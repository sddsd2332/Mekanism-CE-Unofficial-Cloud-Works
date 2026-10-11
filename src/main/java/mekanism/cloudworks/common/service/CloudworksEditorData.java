package mekanism.cloudworks.common.service;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.*;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.*;

/**
 * Sanitized editor projection; machine inventories and operation requirements never leave the server.
 */
public final class CloudworksEditorData {
    private CloudworksEditorData() {
    }

    public static NBTTagCompound graph(ProductionLineData line) {
        NBTTagCompound result = new NBTTagCompound();
        if (line == null) return result;
        result.setString("id", line.lineId().toString());
        result.setBoolean("enabled", line.enabled());
        result.setBoolean("explicitRouting", line.explicitRouting());
        result.setDouble("energy", line.energy());
        result.setDouble("maxEnergy", line.maxEnergy());
        result.setInteger("speed", line.speedUpgrades());
        result.setInteger("energyUpgrades", line.energyUpgrades());
        NBTTagList nodes = new NBTTagList(), links = new NBTTagList();
        for (MachineNodeData node : line.machines()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("id", node.nodeId().toString());
            tag.setString("kind", "machine");
            tag.setString("name", node.machine().getDisplayName());
            ItemStack icon = CloudworksRegistries.identityKey(node.machine()).displayStack();
            tag.setTag("icon", icon.writeToNBT(new NBTTagCompound()));
            tag.setInteger("x", node.x());
            tag.setInteger("z", node.z());
            tag.setBoolean("active", node.active());
            tag.setString("state", node.state());
            tag.setInteger("count", (int) Math.min(Integer.MAX_VALUE, node.machineCount()));
            tag.setInteger("progress", node.progress());
            tag.setInteger("duration", node.totalTicks());
            tag.setTag("input", summary(node.inputs()));
            tag.setTag("output", summary(node.outputs()));
            NBTTagList ports = new NBTTagList();
            MachineSlotLayout layout = CloudworksRegistries.slots(node.machine());
            if (layout != null) for (MachineSlotLayout.Slot slot : layout.slots()) {
                ports.appendTag(port(slot.id(), slot.codecId().toString(), slot.role() == MachineSlotLayout.PortRole.OUTPUT));
            }
            tag.setTag("ports", ports);
            nodes.appendTag(tag);
        }
        for (ProductionLineLink link : line.links()) {
            NBTTagCompound tag = link.write();
            tag.removeTag("resourceFilters");
            NBTTagList names = new NBTTagList();
            for (QIOResourceDescriptor filter : link.resourceFilters())
                names.appendTag(new net.minecraft.nbt.NBTTagString(resourceName(filter)));
            tag.setTag("filterNames", names);
            links.appendTag(tag);
        }
        result.setTag("nodes", nodes);
        result.setTag("links", links);
        return result;
    }

    private static NBTTagList summary(List<CloudworksResourceStack> resources) {
        NBTTagList tags = new NBTTagList();
        for (CloudworksResourceStack resource : resources) {
            if (tags.tagCount() == 12) break;
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("name", resourceName(resource.descriptor()));
            tag.setLong("amount", resource.amount());
            tags.appendTag(tag);
        }
        return tags;
    }

    public static String resourceName(QIOResourceDescriptor resource) {
        mekanism.common.InfuseStorage infuse = resource.resolve(
                mekanism.cloudworks.common.recipe.CloudworksInfuseResourceCodec.INSTANCE);
        if (infuse != null && infuse.getType() != null) return infuse.getType().getLocalizedName();
        ItemStack item = resource.resolve(mekanism.api.qio.resource.QIOResourceCodecs.ITEM_STACK);
        if (item != null && !item.isEmpty()) return item.getDisplayName();
        mekanism.api.gas.GasStack gas = resource.resolve(mekanism.api.qio.resource.QIOResourceCodecs.GAS_STACK);
        if (gas != null) return gas.getGas().getLocalizedName();
        net.minecraftforge.fluids.FluidStack fluid = resource.resolve(mekanism.api.qio.resource.QIOResourceCodecs.FLUID_STACK);
        if (fluid != null) return fluid.getLocalizedName();
        return resource.getCodecId().toString();
    }

    private static NBTTagCompound port(String id, String codec, boolean output) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id);
        tag.setString("codec", codec);
        tag.setBoolean("output", output);
        return tag;
    }

}
