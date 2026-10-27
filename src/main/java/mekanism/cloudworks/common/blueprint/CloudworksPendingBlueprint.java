package mekanism.cloudworks.common.blueprint;

import java.util.*;
import mekanism.cloudworks.common.network.CloudworksLineDraftCodec;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import net.minecraft.nbt.NBTTagCompound;
import mekanism.cloudworks.common.registry.CloudworksRegistries;

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

    /** Restores demands only in the exported copy; pending nodes never own these assets. */
    public CloudworksLineDraft exportDraft(CloudworksLineDraft current) {
        CloudworksLineDraft result = CloudworksLineDraftCodec.copy(current);
        Map<UUID, CloudworksLineDraft.Machine> desired = new HashMap<>();
        placed.machines.forEach(machine -> desired.put(machine.id, machine));
        for (CloudworksLineDraft.Machine machine : result.machines) {
            if (!id.equals(machine.waiting)) continue;
            CloudworksLineDraft.Machine original = desired.get(machine.id);
            if (original == null) throw CloudworksBlueprint.invalid();
            machine.count = original.count;
            machine.active = original.active;
            machine.recipeType = original.recipeType;
            machine.oreRules.addAll(original.oreRules);
            machine.fusionInjectionRate = original.fusionInjectionRate;
            machine.assignedCustom.clear();
            machine.assignedCustom.putAll(original.assignedCustom);
            machine.waiting = null;
            machine.waitingMechanism = "";
            machine.required = machine.missing = 0;
        }
        return result;
    }

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
        tag.setInteger("placedVersion", CloudworksLineDraftCodec.SCHEMA_VERSION);
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
        if (tag.hasKey("placedVersion") && !tag.hasKey("placedVersion", 3)) throw CloudworksBlueprint.invalid();
        CloudworksLineDraft placed = CloudworksLineDraftCodec.decodeBlueprint(tag.getByteArray("placed"),
                tag.hasKey("placedVersion", 3) ? tag.getInteger("placedVersion") : 1);
        UUID line = UUID.fromString(tag.getString("lineId"));
        if (!line.equals(placed.line) || placed.machines.size() != blueprint.draft().machines.size()
                || placed.channels.size() != blueprint.draft().channels.size()) throw CloudworksBlueprint.invalid();
        new CloudworksBlueprint(blueprint.exporterName, blueprint.exporterUUID, blueprint.exportTime, placed).validate();
        CloudworksLineDraft original = blueprint.draft();
        for (int index = 0; index < placed.machines.size(); index++) {
            CloudworksLineDraft.Machine a = original.machines.get(index), b = placed.machines.get(index);
            if (!CloudworksRegistries.identityKey(a.type).equals(
                    CloudworksRegistries.identityKey(b.type))
                    || a.count != b.count || !a.recipeType.equals(b.recipeType) || !a.oreRules.equals(b.oreRules)
                    || a.fusionInjectionRate != b.fusionInjectionRate
                    || !a.assignedCustom.equals(b.assignedCustom)
                    || b.waiting != null)
                throw CloudworksBlueprint.invalid();
        }
        for (int index = 0; index < placed.channels.size(); index++) {
            CloudworksLineDraft.Channel a = original.channels.get(index), b = placed.channels.get(index);
            if (a.mode != b.mode || !Objects.equals(a.archetypeId, b.archetypeId)
                    || a.active != b.active || b.waiting != null
                    || !a.config.equals(b.config)
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
