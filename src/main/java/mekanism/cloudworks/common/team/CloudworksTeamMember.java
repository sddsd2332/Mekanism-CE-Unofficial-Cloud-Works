package mekanism.cloudworks.common.team;

import java.util.UUID;
import java.util.Objects;

/** Immutable member projection used by the native Cloudworks team store. */
public final class CloudworksTeamMember {
    private final UUID id;
    private final String name;
    private final boolean online;

    public CloudworksTeamMember(UUID id, String name, boolean online) {
        this.id = Objects.requireNonNull(id);
        this.name = name == null || name.isEmpty() ? id.toString() : name;
        this.online = online;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean online() {
        return online;
    }
}
