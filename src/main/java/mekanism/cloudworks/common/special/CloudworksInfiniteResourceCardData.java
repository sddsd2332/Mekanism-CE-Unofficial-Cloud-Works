package mekanism.cloudworks.common.special;

import java.util.Locale;
import mekanism.api.gas.Gas;
import mekanism.api.gas.GasRegistry;
import mekanism.api.gas.GasStack;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.item.ItemInfiniteResourceCard;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** Resolves a physical card on the game thread before it enters the cloud transaction. */
public final class CloudworksInfiniteResourceCardData {
    public final String type, resourceId;
    public final QIOResourceDescriptor descriptor;
    private final NBTTagCompound data;

    private CloudworksInfiniteResourceCardData(String type, String id,
            QIOResourceDescriptor descriptor, NBTTagCompound data) {
        this.type = type;
        resourceId = id;
        this.descriptor = descriptor;
        this.data = data.copy();
    }

    public static CloudworksInfiniteResourceCardData fromEntry(CloudworksSpecialNodeStore.Entry entry) {
        if (entry == null || entry.descriptor == null || !entry.descriptor.isResolved())
            throw corrupt();
        return new CloudworksInfiniteResourceCardData(entry.type, entry.resourceId,
                entry.descriptor, entry.data());
    }

    public NBTTagCompound data() { return data.copy(); }

    /** Rebuilds the physical authorization card with the exact resource descriptor and rules. */
    public ItemStack toItemStack(Item cardItem) {
        if (cardItem == null || !descriptor.isResolved() || resourceId == null || resourceId.isEmpty())
            throw corrupt();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("Resources", "descriptor:" + resourceId);
        tag.setTag("Descriptor", descriptor.write());
        if (!data.isEmpty()) tag.setTag("Data", data.copy());
        ItemStack stack = new ItemStack(cardItem);
        stack.setTagCompound(tag);
        parse(stack);
        return stack;
    }

    public static CloudworksInfiniteResourceCardData parse(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty() || !stack.hasTagCompound()) throw corrupt();
            NBTTagCompound tag = stack.getTagCompound();
            if (tag == null || !tag.hasKey("Resources", 8)) throw corrupt();
            String reference = tag.getString("Resources");
            if (reference.length() > 512 || !reference.equals(reference.trim())) throw corrupt();
            int separator = reference.indexOf(':');
            if (separator < 1 || separator == reference.length() - 1) throw corrupt();
            String type = reference.substring(0, separator).toLowerCase(Locale.ROOT);
            boolean explicitDescriptor = "descriptor".equals(type);
            String id = reference.substring(separator + 1);
            QIOResourceDescriptor descriptor;
            if ("item".equals(type)) {
                int metadata = 0;
                int at = id.lastIndexOf('@');
                String itemId = id;
                if (at >= 0) {
                    itemId = id.substring(0, at);
                    metadata = Integer.parseInt(id.substring(at + 1));
                }
                if (metadata < 0 || metadata >= 32767 || !itemId.contains(":")) throw corrupt();
                ResourceLocation name = new ResourceLocation(itemId);
                if (!Item.REGISTRY.containsKey(name)) throw corrupt();
                Item registered = Item.REGISTRY.getObject(name);
                if (registered == null) throw corrupt();
                ItemStack item = new ItemStack(registered, 1, metadata);
                if (!ItemInfiniteResourceCard.isAllowedResource(item)) throw corrupt();
                descriptor = QIOResourceCodecs.item(item);
                id = name + (metadata == 0 ? "" : "@" + metadata);
            } else if ("fluid".equals(type)) {
                Fluid fluid = FluidRegistry.getFluid(id);
                if (fluid == null) throw corrupt();
                descriptor = QIOResourceCodecs.fluid(new FluidStack(fluid, 1));
                id = fluid.getName();
            } else if ("gas".equals(type)) {
                Gas gas = GasRegistry.getGas(id);
                if (gas == null) throw corrupt();
                descriptor = QIOResourceCodecs.gas(new GasStack(gas, 1));
                id = gas.getName();
            } else if ("descriptor".equals(type) && tag.hasKey("Descriptor", 10)) {
                descriptor = QIOResourceDescriptor.read(tag.getCompoundTag("Descriptor"));
                type = descriptor.getFamily();
                if (!type.matches("[a-z0-9_.:-]+") || id.isEmpty()) throw corrupt();
            } else throw corrupt();
            if (!descriptor.isResolved()) throw corrupt();
            // An optional full descriptor carries native NBT variants, never a permission grant.
            if (tag.hasKey("Descriptor")) {
                if (!tag.hasKey("Descriptor", 10)) throw corrupt();
                QIOResourceDescriptor full = QIOResourceDescriptor.read(tag.getCompoundTag("Descriptor"));
                if (!full.isResolved() || !full.getCodecId().equals(descriptor.getCodecId()))
                    throw corrupt();
                if (!explicitDescriptor) {
                    if (!sameResource(descriptor, full)) throw corrupt();
                }
                descriptor = full;
            }
            if (QIOResourceCodecs.ITEM_STACK_ID.equals(descriptor.getCodecId())) {
                ItemStack item = descriptor.resolve(QIOResourceCodecs.ITEM_STACK);
                if (item == null || !ItemInfiniteResourceCard.isAllowedResource(item)) throw corrupt();
            }
            if (tag.hasKey("Data") && tag.hasKey("data")) throw corrupt();
            String dataKey = tag.hasKey("Data") ? "Data" : "data";
            if (tag.hasKey(dataKey) && !tag.hasKey(dataKey, 10)) throw corrupt();
            NBTTagCompound data = tag.getCompoundTag(dataKey).copy();
            CloudworksInfiniteResourceRules.parse(data);
            return new CloudworksInfiniteResourceCardData(type, id, descriptor, data);
        } catch (RuntimeException invalid) {
            throw corrupt();
        }
    }

    private static boolean sameResource(QIOResourceDescriptor basic, QIOResourceDescriptor full) {
        if (basic.getCodecId().equals(QIOResourceCodecs.ITEM_STACK_ID)) {
            ItemStack a = basic.resolve(QIOResourceCodecs.ITEM_STACK), b = full.resolve(QIOResourceCodecs.ITEM_STACK);
            return a != null && b != null && ItemStack.areItemsEqual(a, b);
        }
        if (basic.getCodecId().equals(QIOResourceCodecs.GAS_STACK_ID)) {
            GasStack a = basic.resolve(QIOResourceCodecs.GAS_STACK), b = full.resolve(QIOResourceCodecs.GAS_STACK);
            return a != null && b != null && a.getGas() == b.getGas();
        }
        FluidStack a = basic.resolve(QIOResourceCodecs.FLUID_STACK), b = full.resolve(QIOResourceCodecs.FLUID_STACK);
        return a != null && b != null && a.getFluid() == b.getFluid();
    }

    public static IllegalArgumentException corrupt() {
        return new IllegalArgumentException("cloudworks.error.card_corrupt");
    }
}
