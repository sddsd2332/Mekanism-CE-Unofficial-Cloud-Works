package mekanism.cloudworks.common.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import java.util.Objects;
import net.minecraft.nbt.NBTTagString;

/** Registry-owned team record; published instances are never mutated. */
public final class CloudworksTeam {
    private static final int SCHEMA = 2;
    public enum JoinMode { AUTO_JOIN, APPROVAL, DISABLED }
    private final UUID id;
    private String name;
    private UUID owner;
    private final Map<UUID, CloudworksTeamMember> members = new LinkedHashMap<>();
    private final Set<UUID> denied = new LinkedHashSet<>();
    private String ftbBinding = "";
    private boolean dissolving;
    private JoinMode joinMode = JoinMode.APPROVAL;
    private int color = 0xFF4F7FFF;
    private final Map<UUID, CloudworksTeamMember> requests = new LinkedHashMap<>();

    CloudworksTeam(UUID id, String name, UUID owner) {
        this.id = Objects.requireNonNull(id);
        this.name = checkedName(name);
        this.owner = Objects.requireNonNull(owner);
        addMember(owner, owner.toString(), false);
    }

    static CloudworksTeam read(NBTTagCompound tag) {
        for (String key : new String[]{"id", "owner", "name", "ftbBinding"})
            if (!tag.hasKey(key, Constants.NBT.TAG_STRING)) throw new IllegalArgumentException("Invalid team field " + key);
        if (!tag.hasKey("schema", Constants.NBT.TAG_INT) || !tag.hasKey("dissolving", Constants.NBT.TAG_BYTE))
            throw new IllegalArgumentException("Invalid team header");
        requireList(tag, "members", Constants.NBT.TAG_COMPOUND);
        requireList(tag, "denied", Constants.NBT.TAG_STRING);
        int schema = tag.getInteger("schema");
        if (schema < 1 || schema > SCHEMA) throw new IllegalArgumentException("Invalid Cloudworks team schema");
        UUID id = UUID.fromString(tag.getString("id"));
        UUID owner = UUID.fromString(tag.getString("owner"));
        CloudworksTeam team = new CloudworksTeam(id, tag.getString("name"), owner);
        team.members.clear();
        NBTTagList members = tag.getTagList("members", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < members.tagCount(); i++) {
            NBTTagCompound member = members.getCompoundTagAt(i);
            if (!member.hasKey("id", Constants.NBT.TAG_STRING) || !member.hasKey("name", Constants.NBT.TAG_STRING))
                throw new IllegalArgumentException("Invalid team member");
            UUID memberId = UUID.fromString(member.getString("id"));
            if (team.contains(memberId)) throw new IllegalArgumentException("Duplicate team member");
            team.addMember(memberId, member.getString("name"), false);
        }
        if (!team.members.containsKey(owner)) throw new IllegalArgumentException("Team owner is not a member");
        NBTTagList denied = tag.getTagList("denied", Constants.NBT.TAG_STRING);
        for (int i = 0; i < denied.tagCount(); i++) {
            UUID player = UUID.fromString(denied.getStringTagAt(i));
            if (!team.contains(player) || owner.equals(player))
                throw new IllegalArgumentException("Invalid team permission");
            team.denied.add(player);
        }
        team.ftbBinding = tag.getString("ftbBinding");
        team.dissolving = tag.getBoolean("dissolving");
        if (schema >= 2) {
            if (tag.hasKey("joinMode", Constants.NBT.TAG_STRING))
                team.joinMode = JoinMode.valueOf(tag.getString("joinMode"));
            if (tag.hasKey("color", Constants.NBT.TAG_INT)) team.color = tag.getInteger("color");
            if (tag.hasKey("requests", Constants.NBT.TAG_LIST)) {
                NBTTagList requestList = tag.getTagList("requests", Constants.NBT.TAG_COMPOUND);
                for (int i = 0; i < requestList.tagCount(); i++) {
                    NBTTagCompound request = requestList.getCompoundTagAt(i);
                    UUID requestId = UUID.fromString(request.getString("id"));
                    team.requests.put(requestId, new CloudworksTeamMember(requestId, request.getString("name"), false));
                }
            }
        }
        return team;
    }

    NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", SCHEMA);
        tag.setString("id", id.toString());
        tag.setString("name", name);
        tag.setString("owner", owner.toString());
        tag.setString("ftbBinding", ftbBinding == null ? "" : ftbBinding);
        tag.setBoolean("dissolving", dissolving);
        tag.setString("joinMode", joinMode.name());
        tag.setInteger("color", color);
        NBTTagList memberList = new NBTTagList();
        for (CloudworksTeamMember member : members.values()) {
            NBTTagCompound value = new NBTTagCompound();
            value.setString("id", member.id().toString());
            value.setString("name", member.name());
            memberList.appendTag(value);
        }
        tag.setTag("members", memberList);
        NBTTagList deniedList = new NBTTagList();
        for (UUID player : denied) deniedList.appendTag(new NBTTagString(player.toString()));
        tag.setTag("denied", deniedList);
        NBTTagList requestList = new NBTTagList();
        for (CloudworksTeamMember request : requests.values()) {
            NBTTagCompound value = new NBTTagCompound();
            value.setString("id", request.id().toString());
            value.setString("name", request.name());
            requestList.appendTag(value);
        }
        tag.setTag("requests", requestList);
        return tag;
    }

    UUID id() {
        return id;
    }

    String name() {
        return name;
    }

    void rename(String value) {
        name = checkedName(value);
    }

    UUID owner() {
        return owner;
    }

    void owner(UUID value) {
        owner = Objects.requireNonNull(value);
        if (!members.containsKey(value)) addMember(value, value.toString(), false);
        denied.remove(value);
    }

    String ftbBinding() {
        return ftbBinding;
    }

    void ftbBinding(String value) {
        ftbBinding = value == null ? "" : value;
    }

    void addMember(UUID player, String name, boolean online) {
        CloudworksTeamMember previous = members.get(player);
        String display = name == null || name.isEmpty()
                ? previous == null ? player.toString() : previous.name() : name;
        members.put(player, new CloudworksTeamMember(player, display, online));
        if (owner.equals(player)) denied.remove(player);
    }

    void setOnline(UUID player, boolean online, String name) {
        CloudworksTeamMember previous = members.get(player);
        if (previous == null) return;
        members.put(player, new CloudworksTeamMember(player,
                name == null || name.isEmpty() ? previous.name() : name, online));
    }

    void removeMember(UUID player) {
        if (player == null || owner.equals(player)) return;
        members.remove(player);
        denied.remove(player);
    }

    boolean contains(UUID player) {
        return members.containsKey(player);
    }

    boolean allowed(UUID player) {
        return contains(player) && (owner.equals(player) || !denied.contains(player));
    }

    boolean setAllowed(UUID player, boolean allowed) {
        if (!contains(player) || owner.equals(player)) return false;
        return allowed ? denied.remove(player) : denied.add(player);
    }

    boolean anyOnline(UUID excluding) {
        for (CloudworksTeamMember member : members.values()) {
            if (excluding != null && excluding.equals(member.id())) continue;
            if (member.online()) return true;
        }
        return false;
    }

    List<CloudworksTeamMember> members() {
        return Collections.unmodifiableList(new ArrayList<>(members.values()));
    }

    Set<UUID> memberIds() {
        return new LinkedHashSet<>(members.keySet());
    }

    Set<UUID> deniedIds() {
        return new LinkedHashSet<>(denied);
    }

    CloudworksTeam copy() { return read(write()); }
    boolean dissolving() { return dissolving; }
    void dissolve() { dissolving = true; requests.clear(); }
    JoinMode joinMode() { return joinMode; }
    void joinMode(JoinMode mode) { joinMode = Objects.requireNonNull(mode); }
    int color() { return color; }
    void color(int value) { color = value; }
    boolean request(UUID player, String name) {
        if (contains(player) || requests.containsKey(player)) return false;
        requests.put(player, new CloudworksTeamMember(player, name, false));
        return true;
    }
    boolean approve(UUID player) {
        CloudworksTeamMember request = requests.remove(player);
        if (request == null) return false;
        denied.remove(player);
        addMember(player, request.name(), false);
        return true;
    }
    boolean denyRequest(UUID player) { return requests.remove(player) != null; }
    Map<UUID, CloudworksTeamMember> requests() { return Collections.unmodifiableMap(requests); }

    static String checkedName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 64
                || name.chars().anyMatch(c -> c < 32 || c == 127 || c == 167))
            throw new IllegalArgumentException("cloudworks.error.name");
        return name.trim();
    }

    private static void requireList(NBTTagCompound tag, String key, int elementType) {
        if (!tag.hasKey(key, Constants.NBT.TAG_LIST)) throw new IllegalArgumentException("Invalid team list " + key);
        NBTTagList list = (NBTTagList) tag.getTag(key);
        if (!list.isEmpty() && list.getTagType() != elementType)
            throw new IllegalArgumentException("Invalid team list entries " + key);
    }
}
