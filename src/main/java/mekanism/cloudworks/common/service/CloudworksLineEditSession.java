package mekanism.cloudworks.common.service;

import java.util.UUID;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import net.minecraft.nbt.NBTTagCompound;

/** Server-owned configuration baseline. Runtime inventories never come from the client. */
public final class CloudworksLineEditSession {
    public final UUID token = UUID.randomUUID();
    public final UUID owner, line;
    public final boolean wasRunning;
    public NBTTagCompound baseline;
    public final CloudworksLineDraft initial;
    public CloudworksLineDraft current;
    public mekanism.cloudworks.common.blueprint.CloudworksPendingBlueprint pending;

    public CloudworksLineEditSession(UUID owner, UUID line, boolean wasRunning,
            NBTTagCompound baseline, CloudworksLineDraft initial) {
        this.owner = owner;
        this.line = line;
        this.wasRunning = wasRunning;
        this.baseline = baseline.copy();
        this.initial = mekanism.cloudworks.common.network.CloudworksLineDraftCodec.copy(initial);
        this.current = mekanism.cloudworks.common.network.CloudworksLineDraftCodec.copy(initial);
    }

    public CloudworksLineDraft snapshot() {
        return mekanism.cloudworks.common.network.CloudworksLineDraftCodec.copy(current);
    }
}
