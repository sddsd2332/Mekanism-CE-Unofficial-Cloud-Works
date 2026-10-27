package mekanism.cloudworks.common.integration.crafttweaker;

import crafttweaker.annotations.ZenRegister;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import java.util.Locale;

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

    /** Whether a declared input accepts only effective recipe inputs. */
    @ZenMethod
    public CloudworksSlots recipeFilteredInput(String port, boolean enabled) {
        layout.recipeFilteredInput(port, enabled);
        return this;
    }

    @ZenMethod
    public CloudworksSlots template(String id, String codec, int capacity) {
        layout.add(id, new ResourceLocation(codec), MachineSlotLayout.PortRole.CONFIGURATION, capacity);
        return this;
    }

    @ZenMethod
    public CloudworksSlots add(String id, String codec, String role, int capacity, boolean multipleConnections) {
        layout.add(id, new ResourceLocation(codec), MachineSlotLayout.PortRole.valueOf(role.toUpperCase(Locale.ROOT)),
                capacity, multipleConnections);
        return this;
    }

    MachineSlotLayout snapshot() { return layout.frozenCopy(); }
}
