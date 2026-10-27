package mekanism.cloudworks.common.content;

import mekanism.api.IContentsListener;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;
import mekanism.cloudworks.api.module.CloudworksUpgradeItems;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.common.inventory.slot.BasicInventorySlot;

/**
 * Five host-owned slots: machine input/output, energy input, upgrade input/output.
 * Cloud transfers journal these slots together with the cloud/cache projection;
 * the container locks them until the durable projection is acknowledged.
 */
public final class CloudworksTerminalSlots {

    private CloudworksTerminalSlots() {
    }

    /** Machine-upload input: accepts registered cloud machines only. */
    public static InputInventorySlot machineInput(@Nullable IContentsListener listener, int x, int y) {
        return new InputInventorySlot(stack -> uploadsEnabled() && acceptsMachine(stack),
              BasicInventorySlot.alwaysTrue, listener, x, y) {
        };
    }

    /** Energy-item upload input: one item with extractable Mekanism energy. */
    public static InputInventorySlot energyInput(@Nullable IContentsListener listener, int x, int y) {
        return new InputInventorySlot(stack -> uploadsEnabled() && acceptsEnergy(stack),
              BasicInventorySlot.alwaysTrue, listener, x, y) {
            @Override
            public int getLimit(ItemStack stack) {
                return 1;
            } 
        };
    }

    public static boolean acceptsEnergy(ItemStack stack) {
        try {
            return CloudworksAPI.isInfiniteEnergyItem(stack)
                    || EnergyInventorySlot.isEnergyContainerItem(stack);
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /** Upgrade-upload input: the registered cloud speed/energy items only. */
    public static InputInventorySlot upgradeInput(@Nullable IContentsListener listener, int x, int y) {
        return new InputInventorySlot(stack -> uploadsEnabled() && acceptsUpgrade(stack),
              BasicInventorySlot.alwaysTrue, listener, x, y) {
        };
    }

    /** Machine-download output: filled by the cache-window download action. */
    public static OutputInventorySlot machineOutput(@Nullable IContentsListener listener, int x, int y) {
        return OutputInventorySlot.at(listener, x, y);
    }

    /** Upgrade-download output: filled by the upgrade-window download action. */
    public static OutputInventorySlot upgradeOutput(@Nullable IContentsListener listener, int x, int y) {
        return OutputInventorySlot.at(listener, x, y);
    }

    /** The canonical identity predicate shared by the terminal slot and upload catalogue. */
    public static boolean acceptsMachine(ItemStack stack) {
        return !stack.isEmpty() && CloudworksRegistries.isRegistered(stack);
    }

    public static boolean uploadsEnabled() {
        return CloudworksConfig.currentSettings().terminalItemUploadsEnabled;
    }

    public static boolean acceptsUpgrade(ItemStack stack) {
        // Native speed/energy items plus any registered custom-type item (8.5-B).
        return CloudworksUpgradeItems.accepts(stack)
                || CloudworksMachineModuleRegistry.typeForItem(stack) != null;
    }
}
