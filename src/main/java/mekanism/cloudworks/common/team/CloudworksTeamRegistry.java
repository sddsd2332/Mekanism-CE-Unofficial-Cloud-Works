package mekanism.cloudworks.common.team;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import java.util.Objects;

/** Native world index. Writes publish atomically; hot permission reads never wait for disk. */
public final class CloudworksTeamRegistry {
    private final Object writes = new Object();
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private Map<UUID, List<TeamDissolutionNotice>> notices = new LinkedHashMap<>();
    private volatile State state = new State(Collections.emptyMap());
    private volatile Path file;
    private volatile long epoch;

    public void load(Path root) {
        synchronized (writes) {
            epoch++;
            file = null;
            state = new State(Collections.emptyMap());
            notices = new LinkedHashMap<>();
            online.clear();
            Path next = Objects.requireNonNull(root).resolve("teams.dat");
            Map<UUID, CloudworksTeam> teams = new LinkedHashMap<>();
            Map<UUID, List<TeamDissolutionNotice>> loadedNotices = new LinkedHashMap<>();
            if (Files.exists(next)) {
                try {
                    NBTTagCompound tag = CompressedStreamTools.read(next.toFile());
                    if (tag == null || tag.getInteger("schema") < 1 || tag.getInteger("schema") > 1 || !tag.hasKey("teams", 9))
                        throw new IOException("Invalid Cloudworks teams index");
                    NBTTagList stored = (NBTTagList) tag.getTag("teams");
                    if (!stored.isEmpty() && stored.getTagType() != 10)
                        throw new IOException("Invalid Cloudworks team entries");
                    NBTTagList entries = tag.getTagList("teams", 10);
                    for (int i = 0; i < entries.tagCount(); i++) {
                        CloudworksTeam team = CloudworksTeam.read(entries.getCompoundTagAt(i));
                        if (teams.put(team.id(), team) != null) throw new IOException("Duplicate team identity");
                    }
                    if (tag.hasKey("notices", 9)) {
                        NBTTagList storedNotices = tag.getTagList("notices", 10);
                        for (int i = 0; i < storedNotices.tagCount(); i++) {
                            NBTTagCompound notice = storedNotices.getCompoundTagAt(i);
                            UUID player = UUID.fromString(notice.getString("player"));
                            TeamDissolutionNotice value = new TeamDissolutionNotice(player,
                                    UUID.fromString(notice.getString("leader")),
                                    notice.getString("leaderName"), notice.getString("teamName"));
                            loadedNotices.computeIfAbsent(player, ignored -> new ArrayList<>()).add(value);
                        }
                    }
                } catch (IOException | RuntimeException failure) {
                    throw new IllegalStateException("Could not load Cloudworks teams", failure);
                }
            }
            notices = loadedNotices;
            state = new State(teams, notices);
            file = next;
        }
    }

    public void close() {
        synchronized (writes) {
            epoch++;
            file = null;
            state = new State(Collections.emptyMap());
            notices = new LinkedHashMap<>();
            online.clear();
        }
    }

    public long epoch() { return epoch; }
    public boolean loaded() { return file != null; }
    /** True for active and dissolving team records. */
    public boolean hasTeam(UUID teamId) {
        return teamId != null && state.teams.containsKey(teamId);
    }
    public UUID owner(UUID player) { return player == null ? null : state.players.get(player); }
    public boolean isActive(UUID teamId) { return state.active(teamId) != null; }
    public boolean isOwner(UUID player, UUID teamId) {
        CloudworksTeam team = state.active(teamId);
        return team != null && team.owner().equals(player);
    }
    public boolean canAccess(UUID player, UUID teamId) {
        CloudworksTeam team = state.active(teamId);
        return team != null && team.allowed(player);
    }
    public boolean anyMemberOnline(UUID teamId, UUID excluding) {
        CloudworksTeam team = state.active(teamId);
        if (team == null) return false;
        for (UUID member : team.memberIds())
            if (!member.equals(excluding) && online.contains(member)) return true;
        return false;
    }
    public void setOnline(UUID player, boolean present, String ignoredName) {
        if (present) online.add(player);
        else online.remove(player);
    }
    public void updateMemberName(UUID player, String name) {
        mutate(teams -> {
            CloudworksTeam team = memberTeam(teams, player);
            if (team != null) team.addMember(player, name, false);
            return null;
        });
    }

    public UUID create(UUID player, String name, int color, CloudworksTeam.JoinMode mode) {
        return mutate(teams -> {
            if (memberTeam(teams, player) != null) throw error("already_member");
            CloudworksTeam team = new CloudworksTeam(UUID.randomUUID(), name, player);
            team.color(color);
            team.joinMode(mode);
            teams.put(team.id(), team);
            return team.id();
        });
    }

    public boolean join(UUID player, String name, UUID teamId) {
        return mutate(teams -> {
            CloudworksTeam team = active(teams, teamId);
            if (team.joinMode() == CloudworksTeam.JoinMode.DISABLED) throw error("join_disabled");
            if (team.joinMode() != CloudworksTeam.JoinMode.AUTO_JOIN)
                throw error("join_approval");
            if (memberTeam(teams, player) != null) throw error("already_member");
            team.addMember(player, name, false);
            return true;
        });
    }

    public boolean requestJoin(UUID player, String name, UUID teamId) {
        return mutate(teams -> {
            CloudworksTeam team = active(teams, teamId);
            if (memberTeam(teams, player) != null) throw error("already_member");
            if (team.joinMode() == CloudworksTeam.JoinMode.DISABLED) throw error("join_disabled");
            if (team.joinMode() == CloudworksTeam.JoinMode.AUTO_JOIN) {
                team.addMember(player, name, false);
                return true;
            }
            if (!team.request(player, name)) throw error("already_requested");
            return true;
        });
    }

    public boolean approveJoin(UUID actor, UUID teamId, UUID player) {
        return mutate(teams -> {
            CloudworksTeam team = owned(teams, actor);
            if (!team.id().equals(teamId)) throw error("owner_required");
            if (team.joinMode() != CloudworksTeam.JoinMode.APPROVAL) throw error("request_missing");
            CloudworksTeam other = memberTeam(teams, player);
            if (other != null && !other.id().equals(teamId)) throw error("already_member");
            if (!team.approve(player)) throw error("request_missing");
            return true;
        });
    }

    public boolean denyJoin(UUID actor, UUID teamId, UUID player) {
        return mutate(teams -> {
            CloudworksTeam team = owned(teams, actor);
            if (!team.id().equals(teamId)) throw error("owner_required");
            if (!team.denyRequest(player)) throw error("request_missing");
            return true;
        });
    }

    public void setJoinMode(UUID actor, UUID teamId, CloudworksTeam.JoinMode mode) {
        mutate(teams -> {
            CloudworksTeam team = owned(teams, actor);
            if (!team.id().equals(teamId)) throw error("owner_required");
            team.joinMode(mode);
            return null;
        });
    }

    public void setColor(UUID actor, UUID teamId, int color) {
        mutate(teams -> {
            CloudworksTeam team = owned(teams, actor);
            if (!team.id().equals(teamId)) throw error("owner_required");
            team.color(color);
            return null;
        });
    }

    public List<CloudworksTeamSnapshot> joinableTeams(UUID player) {
        if (memberTeam(state.teams, player) != null) return Collections.emptyList();
        List<CloudworksTeamSnapshot> result = new ArrayList<>();
        for (CloudworksTeam team : state.teams.values())
            if (!team.dissolving() && team.joinMode() != CloudworksTeam.JoinMode.DISABLED
                    && !team.contains(player)) result.add(snapshot(team));
        return Collections.unmodifiableList(result);
    }

    public boolean removeMember(UUID actor, UUID teamId, UUID target) {
        return mutate(teams -> {
            CloudworksTeam team = active(teams, teamId);
            if (!team.owner().equals(actor)) throw error("owner_required");
            if (target.equals(team.owner())) throw error("cannot_leave");
            boolean contained = team.contains(target);
            team.removeMember(target);
            return contained;
        });
    }

    public boolean leave(UUID player) {
        return mutate(teams -> {
            CloudworksTeam team = memberTeam(teams, player);
            if (team == null) return false;
            if (team.owner().equals(player)) throw error("cannot_leave");
            team.removeMember(player);
            return true;
        });
    }

    public UUID dissolve(UUID actor) {
        return mutate(teams -> {
            CloudworksTeam team = owned(teams, actor);
            queueDissolutionNotices(team);
            team.dissolve();
            return team.id();
        });
    }

    public void dissolveBinding(String binding) {
        mutate(teams -> {
            for (CloudworksTeam team : teams.values())
                if (!team.dissolving() && binding.equals(team.ftbBinding())) {
                    queueDissolutionNotices(team);
                    team.dissolve();
                }
            return null;
        });
    }

    public void finishRecovery(UUID id) {
        mutate(teams -> {
            CloudworksTeam team = teams.get(id);
            if (team != null && team.dissolving()) teams.remove(id);
            return null;
        });
    }

    public boolean setAllowed(UUID actor, UUID teamId, UUID target, boolean allowed) {
        return mutate(teams -> {
            CloudworksTeam team = active(teams, teamId);
            if (!team.owner().equals(actor)) throw error("owner_required");
            return team.setAllowed(target, allowed);
        });
    }

    public boolean rename(UUID actor, UUID teamId, String name) {
        return mutate(teams -> {
            CloudworksTeam team = active(teams, teamId);
            if (!team.owner().equals(actor)) throw error("owner_required");
            team.rename(name);
            return true;
        });
    }

    public void transferOwner(UUID actor, UUID target) {
        mutate(teams -> {
            CloudworksTeam team = owned(teams, actor);
            if (!team.contains(target)) throw error("member_required");
            team.owner(target);
            return null;
        });
    }

    /** Event-driven FTB deletion unbind; the binding itself is the authority. */
    public void unbindBinding(String binding) {
        mutate(teams -> {
            for (CloudworksTeam team : teams.values())
                if (!team.dissolving() && binding != null && binding.equals(team.ftbBinding())) team.ftbBinding("");
            return null;
        });
    }

    /** Manual GUI import only fills vacancies; it does not rename, rebind or remove members. */
    public void importMembers(UUID actor, CloudworksExternalTeam external) {
        mutate(teams -> {
            CloudworksTeam team = owned(teams, actor);
            for (CloudworksTeamMember member : external.members)
                if (memberTeam(teams, member.id()) == null)
                    team.addMember(member.id(), member.name(), false);
            return null;
        });
    }

    public UUID createFromExternal(CloudworksExternalTeam external) {
        return mutate(teams -> {
            UUID existingId = state.bindings.get(external.binding);
            if (existingId != null) return existingId;
            CloudworksTeam memberTeam = memberTeam(teams, external.owner);
            if (memberTeam != null) return null;
            CloudworksTeam team = new CloudworksTeam(UUID.randomUUID(), external.name, external.owner);
            team.ftbBinding(external.binding);
            for (CloudworksTeamMember member : external.members)
                if (!member.id().equals(external.owner) && memberTeam(teams, member.id()) == null)
                    team.addMember(member.id(), member.name(), false);
            teams.put(team.id(), team);
            return team.id();
        });
    }

    public UUID findByFtbBinding(String binding) { return state.bindings.get(binding); }
    public UUID findByLeader(UUID leader) {
        for (CloudworksTeam team : state.teams.values())
            if (!team.dissolving() && team.owner().equals(leader)) return team.id();
        return null;
    }
    public boolean ftbJoin(UUID leader, UUID player, String name) {
        return mutate(teams -> {
            CloudworksTeam team = null;
            for (CloudworksTeam candidate : teams.values())
                if (!candidate.dissolving() && candidate.owner().equals(leader)) team = candidate;
            if (team == null || memberTeam(teams, player) != null) return false;
            team.addMember(player, name, false);
            return true;
        });
    }
    public boolean ftbLeave(UUID leader, UUID player) {
        return mutate(teams -> {
            CloudworksTeam team = null;
            for (CloudworksTeam candidate : teams.values())
                if (!candidate.dissolving() && candidate.owner().equals(leader)) team = candidate;
            if (team == null || !team.contains(player) || team.owner().equals(player)) return false;
            team.removeMember(player);
            return true;
        });
    }
    public boolean ftbOwnerChanged(UUID oldLeader, UUID newLeader) {
        return ftbOwnerChanged(null, oldLeader, newLeader);
    }

    /** Prefer the exact external binding, then fall back to the old Cloudworks leader. */
    public boolean ftbOwnerChanged(String binding, UUID oldLeader, UUID newLeader) {
        return mutate(teams -> {
            CloudworksTeam team = null;
            if (binding != null && !binding.isEmpty()) {
                for (CloudworksTeam candidate : teams.values())
                    if (!candidate.dissolving() && binding.equals(candidate.ftbBinding())) {
                        team = candidate;
                        break;
                    }
            }
            if (team == null) {
                for (CloudworksTeam candidate : teams.values())
                    if (!candidate.dissolving() && candidate.owner().equals(oldLeader)) {
                        team = candidate;
                        break;
                    }
            }
            if (team == null || !team.contains(newLeader)) return false;
            team.owner(newLeader);
            return true;
        });
    }
    public CloudworksTeamSnapshot snapshot(UUID id) {
        CloudworksTeam team = state.active(id);
        return team == null ? CloudworksTeamSnapshot.empty() : snapshot(team);
    }
    public List<CloudworksTeamSnapshot> snapshots() {
        List<CloudworksTeamSnapshot> result = new ArrayList<>();
        for (CloudworksTeam team : state.teams.values())
            if (!team.dissolving()) result.add(snapshot(team));
        return Collections.unmodifiableList(result);
    }
    public List<CloudworksTeamSnapshot> dissolving() {
        List<CloudworksTeamSnapshot> result = new ArrayList<>();
        for (CloudworksTeam team : state.teams.values())
            if (team.dissolving()) result.add(snapshot(team));
        return result;
    }
    /** Removes and returns durable dissolution notices exactly once. */
    public List<TeamDissolutionNotice> takeNotices(UUID player) {
        synchronized (writes) {
            if (file == null) return Collections.emptyList();
            List<TeamDissolutionNotice> result = state.notices.get(player);
            if (result == null || result.isEmpty()) return Collections.emptyList();
            Map<UUID, List<TeamDissolutionNotice>> remaining = copyNotices(state.notices);
            remaining.remove(player);
            State candidate = new State(state.teams, remaining);
            NBTTagCompound tag = candidate.write();
            if (!tag.equals(state.write())) persist(file, tag);
            notices = remaining;
            state = candidate;
            return Collections.unmodifiableList(new ArrayList<>(result));
        }
    }
    public List<CloudworksTeamMember> members(UUID id) { return snapshot(id).members(); }

    private CloudworksTeamSnapshot snapshot(CloudworksTeam team) {
        List<CloudworksTeamMember> members = new ArrayList<>();
        for (CloudworksTeamMember member : team.members())
            members.add(new CloudworksTeamMember(member.id(), member.name(), online.contains(member.id())));
        return new CloudworksTeamSnapshot(team.id(), team.name(), team.owner(), members,
                team.memberIds(), team.deniedIds(), team.ftbBinding(), team.dissolving(),
                team.joinMode(), team.color(), team.requests());
    }

    private <T> T mutate(Function<Map<UUID, CloudworksTeam>, T> operation) {
        synchronized (writes) {
            if (file == null) throw new IllegalStateException("Cloudworks teams are not loaded");
            Map<UUID, CloudworksTeam> next = new LinkedHashMap<>();
            for (CloudworksTeam team : state.teams.values()) next.put(team.id(), team.copy());
            Map<UUID, List<TeamDissolutionNotice>> previousNotices = notices;
            notices = copyNotices(state.notices);
            boolean committed = false;
            try {
                T result = operation.apply(next);
                State candidate = new State(next, notices);
                NBTTagCompound tag = candidate.write();
                if (!tag.equals(state.write())) persist(file, tag);
                state = candidate;
                committed = true;
                return result;
            } finally {
                if (!committed) notices = previousNotices;
            }
        }
    }

    private static void persist(Path file, NBTTagCompound tag) {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "teams-", ".tmp");
            CompressedStreamTools.write(tag, temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save Cloudworks teams", failure);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException ignored) { }
        }
    }

    private static CloudworksTeam active(Map<UUID, CloudworksTeam> teams, UUID id) {
        CloudworksTeam team = teams.get(id);
        if (team == null || team.dissolving()) throw error("member_required");
        return team;
    }
    private static CloudworksTeam memberTeam(Map<UUID, CloudworksTeam> teams, UUID player) {
        for (CloudworksTeam team : teams.values())
            if (!team.dissolving() && team.contains(player)) return team;
        return null;
    }
    private static CloudworksTeam owned(Map<UUID, CloudworksTeam> teams, UUID player) {
        CloudworksTeam team = memberTeam(teams, player);
        if (team == null || !team.owner().equals(player)) throw error("owner_required");
        return team;
    }
    private static Map<UUID, List<TeamDissolutionNotice>> copyNotices(
            Map<UUID, List<TeamDissolutionNotice>> source) {
        Map<UUID, List<TeamDissolutionNotice>> copy = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<TeamDissolutionNotice>> entry : source.entrySet())
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        return copy;
    }
    private static IllegalArgumentException error(String key) {
        return new IllegalArgumentException("cloudworks.team.error." + key);
    }

    private void queueDissolutionNotices(CloudworksTeam team) {
        CloudworksTeamMember leader = null;
        for (CloudworksTeamMember member : team.members())
            if (member.id().equals(team.owner())) { leader = member; break; }
        String leaderName = leader == null ? team.owner().toString() : leader.name();
        for (CloudworksTeamMember member : team.members()) {
            notices.computeIfAbsent(member.id(), ignored -> new ArrayList<>())
                    .add(new TeamDissolutionNotice(member.id(), team.owner(), leaderName, team.name()));
        }
    }

    public static final class TeamDissolutionNotice {
        public final UUID player, leader;
        public final String leaderName, teamName;

        TeamDissolutionNotice(UUID player, UUID leader, String leaderName, String teamName) {
            this.player = player;
            this.leader = leader;
            this.leaderName = leaderName;
            this.teamName = teamName;
        }
    }

    private static final class State {
        final Map<UUID, CloudworksTeam> teams;
        final Map<UUID, List<TeamDissolutionNotice>> notices;
        final Map<UUID, UUID> players = new LinkedHashMap<>();
        final Map<String, UUID> bindings = new LinkedHashMap<>();
        State(Map<UUID, CloudworksTeam> teams) {
            this(teams, Collections.emptyMap());
        }
        State(Map<UUID, CloudworksTeam> teams, Map<UUID, List<TeamDissolutionNotice>> notices) {
            this.teams = Collections.unmodifiableMap(new LinkedHashMap<>(teams));
            Map<UUID, List<TeamDissolutionNotice>> copied = new LinkedHashMap<>();
            for (Map.Entry<UUID, List<TeamDissolutionNotice>> entry : notices.entrySet())
                copied.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            this.notices = Collections.unmodifiableMap(copied);
            for (CloudworksTeam team : teams.values()) {
                if (team.dissolving()) continue;
                for (UUID player : team.memberIds())
                    if (players.put(player, team.id()) != null)
                        throw new IllegalArgumentException("Player belongs to multiple teams");
                if (!team.ftbBinding().isEmpty() && bindings.put(team.ftbBinding(), team.id()) != null)
                    throw new IllegalArgumentException("Duplicate external team binding");
            }
        }
        CloudworksTeam active(UUID id) {
            CloudworksTeam team = id == null ? null : teams.get(id);
            return team == null || team.dissolving() ? null : team;
        }
        NBTTagCompound write() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("schema", 1);
            NBTTagList list = new NBTTagList();
            for (CloudworksTeam team : teams.values()) list.appendTag(team.write());
            tag.setTag("teams", list);
            NBTTagList noticeList = new NBTTagList();
            for (List<TeamDissolutionNotice> values : notices.values())
                for (TeamDissolutionNotice notice : values) {
                    NBTTagCompound value = new NBTTagCompound();
                    value.setString("player", notice.player.toString());
                    value.setString("leader", notice.leader.toString());
                    value.setString("leaderName", notice.leaderName);
                    value.setString("teamName", notice.teamName);
                    noticeList.appendTag(value);
                }
            tag.setTag("notices", noticeList);
            return tag;
        }
    }
}
