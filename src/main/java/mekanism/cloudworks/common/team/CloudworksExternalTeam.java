package mekanism.cloudworks.common.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

/** Main-thread capture of an optional provider's team. No live provider objects escape. */
public final class CloudworksExternalTeam {
    public final String binding, name;
    public final UUID owner;
    public final List<CloudworksTeamMember> members;

    public CloudworksExternalTeam(String binding, String name, UUID owner, List<CloudworksTeamMember> members) {
        if (binding == null || binding.isEmpty() || binding.length() > 128)
            throw new IllegalArgumentException("Invalid external team binding");
        this.binding = binding;
        this.name = CloudworksTeam.checkedName(name);
        this.owner = Objects.requireNonNull(owner);
        this.members = Collections.unmodifiableList(new ArrayList<>(members));
        if (this.members.stream().noneMatch(member -> owner.equals(member.id())))
            throw new IllegalArgumentException("External owner is not a member");
    }
}
