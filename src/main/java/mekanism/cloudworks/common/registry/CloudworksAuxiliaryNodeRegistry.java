package mekanism.cloudworks.common.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekanism.cloudworks.api.node.CloudworksAuxiliaryNodeType;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/** Lifecycle-frozen registry for the fourth node family. */
public final class CloudworksAuxiliaryNodeRegistry {
    private static final Map<ResourceLocation, CloudworksAuxiliaryNodeType> TYPES = new LinkedHashMap<>();
    private static boolean frozen;

    private CloudworksAuxiliaryNodeRegistry() {}

    public static synchronized void register(CloudworksAuxiliaryNodeType type) {
        if (frozen) throw new IllegalStateException("CloudWorks auxiliary nodes are frozen");
        if (TYPES.containsKey(type.id()))
            throw new IllegalArgumentException("Duplicate auxiliary node: " + type.id());
        if (!type.item().isEmpty())
            for (CloudworksAuxiliaryNodeType existing : TYPES.values())
                if (!existing.item().isEmpty()
                        && ItemStack.areItemsEqual(existing.item(), type.item())
                        && ItemStack.areItemStackTagsEqual(existing.item(), type.item()))
                    throw new IllegalArgumentException("Auxiliary item is already bound: " + type.item());
        TYPES.put(type.id(), type);
    }

    public static synchronized void freeze() {
        frozen = true;
    }

    public static synchronized void resetForTests() {
        TYPES.clear();
        frozen = false;
    }

    public static CloudworksAuxiliaryNodeType get(ResourceLocation id) {
        return TYPES.get(id);
    }

    public static CloudworksAuxiliaryNodeType get(String id) {
        return id == null || id.isEmpty() ? null : get(new ResourceLocation(id));
    }

    public static CloudworksAuxiliaryNodeType byItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        for (CloudworksAuxiliaryNodeType type : TYPES.values())
            if (!type.item().isEmpty() && ItemStack.areItemsEqual(type.item(), stack)
                    && ItemStack.areItemStackTagsEqual(type.item(), stack)) return type;
        return null;
    }

    public static List<CloudworksAuxiliaryNodeType> values() {
        return Collections.unmodifiableList(new ArrayList<>(TYPES.values()));
    }

    public static Map<ResourceLocation, CloudworksAuxiliaryNodeType> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(TYPES));
    }
}
