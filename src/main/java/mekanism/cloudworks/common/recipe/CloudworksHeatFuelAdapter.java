package mekanism.cloudworks.common.recipe;

import java.util.Optional;
import mekanism.cloudworks.common.data.MachineNodeData;

/**
 * Marks the dedicated retained-lava Runtime contract. No creative inventory
 * enumeration: the Runtime compiler freezes only known resource symbols.
 */
public final class CloudworksHeatFuelAdapter implements SpecialMachineAdapter,
        CloudworksAsyncRecipeAdmission.FreezableView {
    @Override
    public Optional<CloudworksFrozenRecipeView> captureFrozenView() {
        return CloudworksFrozenRecipeView.capture(java.util.Collections::emptyList);
    }

    @Override public Object preview(MachineNodeData node) { return null; }
    @Override public boolean canStart(MachineNodeData node) { return false; }
    @Override public boolean reserve(MachineNodeData node) { return true; }
    @Override public void advance(MachineNodeData node, int operations) { }
    @Override public boolean complete(MachineNodeData node) { return true; }
    @Override public void cancel(MachineNodeData node) { }
    @Override public void restore(MachineNodeData node) { }
}
