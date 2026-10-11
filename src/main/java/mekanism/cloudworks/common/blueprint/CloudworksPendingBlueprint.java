package mekanism.cloudworks.common.blueprint;

import java.util.*;
import mekanism.cloudworks.common.network.CloudworksLineDraftCodec;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import net.minecraft.nbt.NBTTagCompound;

/** Full independent payload plus the remapped IDs; library deletion cannot affect it. */
public final class CloudworksPendingBlueprint {
    public final UUID id, line;
    public final int anchorX, anchorY;
    public final CloudworksBlueprint blueprint;
    private final CloudworksLineDraft placed;

    public CloudworksPendingBlueprint(UUID id, UUID line, int x, int y,
            CloudworksBlueprint blueprint, CloudworksLineDraft placed) {
        this.id = id; this.line = line; anchorX = x; anchorY = y;
        this.blueprint = blueprint;
        this.placed = CloudworksLineDraftCodec.copy(placed);
    }

    public CloudworksLineDraft placed() { return CloudworksLineDraftCodec.copy(placed); }

    public Set<UUID> nodes() {
        Set<UUID> nodes = new HashSet<>();
        placed.machines.forEach(m -> nodes.add(m.id));
        placed.channels.forEach(c -> nodes.add(c.id));
        return nodes;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("present", true);
        tag.setString("id", id.toString());
        tag.setString("lineId", line.toString());
        tag.setInteger("anchorX", anchorX); tag.setInteger("anchorY", anchorY);
        tag.setTag("blueprint", blueprint.write());
        tag.setByteArray("placed", CloudworksLineDraftCodec.encode(placed));
        return tag;
    }

    public static NBTTagCompound empty() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("present", false);
        return tag;
    }

    public static CloudworksPendingBlueprint read(NBTTagCompound tag) {
        if (!tag.hasKey("present", 1)) throw CloudworksBlueprint.invalid();
        if (!tag.getBoolean("present")) return null;
        if (!tag.hasKey("id", 8) || !tag.hasKey("lineId", 8) || !tag.hasKey("anchorX", 3)
                || !tag.hasKey("anchorY", 3) || !tag.hasKey("blueprint", 10) || !tag.hasKey("placed", 7))
            throw CloudworksBlueprint.invalid();
        CloudworksBlueprint blueprint = CloudworksBlueprint.read(tag.getCompoundTag("blueprint"));
        CloudworksLineDraft placed = CloudworksLineDraftCodec.decode(tag.getByteArray("placed"));
        UUID line = UUID.fromString(tag.getString("lineId"));
        if (!line.equals(placed.line) || placed.machines.size() != blueprint.draft().machines.size()
                || placed.channels.size() != blueprint.draft().channels.size()) throw CloudworksBlueprint.invalid();
        new CloudworksBlueprint(blueprint.exporterName, blueprint.exporterUUID, blueprint.exportTime, placed).validate();
        CloudworksLineDraft original = blueprint.draft();
        for (int index = 0; index < placed.machines.size(); index++) {
            CloudworksLineDraft.Machine a = original.machines.get(index), b = placed.machines.get(index);
            if (!mekanism.cloudworks.common.registry.CloudworksRegistries.identityKey(a.type).equals(
                    mekanism.cloudworks.common.registry.CloudworksRegistries.identityKey(b.type))
                    || a.count != b.count || a.dimension != b.dimension || b.waiting != null)
                throw CloudworksBlueprint.invalid();
        }
        for (int index = 0; index < placed.channels.size(); index++) {
            CloudworksLineDraft.Channel a = original.channels.get(index), b = placed.channels.get(index);
            if (a.mode != b.mode || a.active != b.active || b.waiting != null
                    || a.resources.size() != b.resources.size()) throw CloudworksBlueprint.invalid();
            for (int resource = 0; resource < a.resources.size(); resource++) {
                CloudworksLineDraft.Resource source = a.resources.get(resource), target = b.resources.get(resource);
                if (!source.descriptor.equals(target.descriptor) || source.amount != target.amount)
                    throw CloudworksBlueprint.invalid();
            }
        }
        return new CloudworksPendingBlueprint(UUID.fromString(tag.getString("id")), line,
                tag.getInteger("anchorX"), tag.getInteger("anchorY"), blueprint, placed);
    }
}
