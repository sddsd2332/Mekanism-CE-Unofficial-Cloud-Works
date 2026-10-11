package mekanism.cloudworks.common.content;

import mekanism.api.IContentsListener;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.common.Upgrade;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.inventory.slot.OutputInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.item.ItemBlockEnergyCube;
import mekanism.common.tier.BaseTier;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;

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
        return new InputInventorySlot(CloudworksTerminalSlots::acceptsMachine,
              mekanism.common.inventory.slot.BasicInventorySlot.alwaysTrue, listener, x, y) {
        };
    }

    /** Energy-item upload input: one item with extractable Mekanism energy. */
    public static InputInventorySlot energyInput(@Nullable IContentsListener listener, int x, int y) {
        return new InputInventorySlot(CloudworksTerminalSlots::acceptsEnergy,
              EnergyInventorySlot::isEnergyContainerItem, listener, x, y) {
            @Override
            public int getLimit(net.minecraft.item.ItemStack stack) {
                return 1;
            } 
        };
    }

    public static boolean acceptsEnergy(ItemStack stack) {
        try {
            return EnergyInventorySlot.isEnergyContainerItem(stack);
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /** Upgrade-upload input: speed/energy upgrade items only. */
    public static InputInventorySlot upgradeInput(@Nullable IContentsListener listener, int x, int y) {
        return new InputInventorySlot(CloudworksTerminalSlots::acceptsUpgrade,
              mekanism.common.inventory.slot.BasicInventorySlot.alwaysTrue, listener, x, y) {
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

    private static boolean acceptsMachine(ItemStack stack) {
        return !stack.isEmpty() && CloudworksRegistries.isRegistered(stack);
    }

    @SuppressWarnings("unchecked")
    public static boolean acceptsUpgrade(ItemStack stack) {
        if (!(stack.getItem() instanceof mekanism.common.base.IUpgradeItem upgradeItem)) return false;
        Upgrade type = upgradeItem.getUpgradeType(stack);
        return type == Upgrade.SPEED || type == Upgrade.ENERGY;
    }
}
