package mekanism.cloudworks.common.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Read-only team data used by GUI projections and client synchronization. */
public final class CloudworksTeamSnapshot {
    private static final CloudworksTeamSnapshot EMPTY = new CloudworksTeamSnapshot(
            null, "", null, Collections.emptyList(), Collections.emptySet(), Collections.emptySet(), "", false,
            CloudworksTeam.JoinMode.DISABLED, 0, Collections.emptyMap());
    private final UUID id;
    private final String name;
    private final UUID owner;
    private final List<CloudworksTeamMember> members;
    private final Set<UUID> memberIds;
    private final Set<UUID> denied;
    public final String ftbBinding;
    public final boolean dissolving;
    public final CloudworksTeam.JoinMode joinMode;
    public final int color;
    private final Map<UUID, CloudworksTeamMember> requests;

    CloudworksTeamSnapshot(UUID id, String name, UUID owner,
            List<CloudworksTeamMember> members, Set<UUID> memberIds,
            Set<UUID> denied, String ftbBinding, boolean dissolving,
            CloudworksTeam.JoinMode joinMode, int color,
            Map<UUID, CloudworksTeamMember> requests) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.owner = owner;
        this.members = Collections.unmodifiableList(new ArrayList<>(members));
        this.memberIds = Collections.unmodifiableSet(new LinkedHashSet<>(memberIds));
        this.denied = Collections.unmodifiableSet(new LinkedHashSet<>(denied));
        this.ftbBinding = ftbBinding;
        this.dissolving = dissolving;
        this.joinMode = joinMode;
        this.color = color;
        this.requests = Collections.unmodifiableMap(new LinkedHashMap<>(requests));
    }

    static CloudworksTeamSnapshot empty() {
        return EMPTY;
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public UUID owner() { return owner; }
    public List<CloudworksTeamMember> members() { return members; }
    public boolean contains(UUID player) { return memberIds.contains(player); }
    public boolean allowed(UUID player) {
        return owner != null && owner.equals(player) || contains(player) && !denied.contains(player);
    }
    public Map<UUID, CloudworksTeamMember> requests() { return requests; }
}
