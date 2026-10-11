package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenClass("mods.cloudworks.Slots")
@ZenRegister
public final class CloudworksSlots {
    private final MachineSlotLayout layout = new MachineSlotLayout();

    @ZenMethod
    public static CloudworksSlots create() { return new CloudworksSlots(); }

    @ZenMethod
    public CloudworksSlots input(String id, String codec, int capacity) {
        layout.add(id, new ResourceLocation(codec), MachineSlotLayout.PortRole.INPUT, capacity);
        return this;
    }

    @ZenMethod
    public CloudworksSlots output(String id, String codec, int capacity) {
        layout.add(id, new ResourceLocation(codec), MachineSlotLayout.PortRole.OUTPUT, capacity);
        return this;
    }

    @ZenMethod
    public CloudworksSlots template(String id, String codec, int capacity) {
        layout.add(id, new ResourceLocation(codec), MachineSlotLayout.PortRole.CONFIGURATION, capacity);
        return this;
    }

    @ZenMethod
    public CloudworksSlots add(String id, String codec, String role, int capacity, boolean multipleConnections) {
        layout.add(id, new ResourceLocation(codec), MachineSlotLayout.PortRole.valueOf(role.toUpperCase(java.util.Locale.ROOT)),
                capacity, multipleConnections);
        return this;
    }

    MachineSlotLayout snapshot() { return layout.frozenCopy(); }
}
