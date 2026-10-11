package mekanism.cloudworks.common.content;

import mekanism.api.Action;
import mekanism.api.IContentsListener;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.gas.GasStack;
import mekanism.api.gas.GasTankInfo;
import mekanism.api.gas.IExtendedGasTank;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One port buffer tank slot that stores EITHER a fluid OR a gas (dynamic-tank style
 * capacities). A single class cannot implement both extended tank interfaces (their
 * {@code getInfo()} return types clash), so this holder owns the state and exposes
 * two live views: {@link #fluidView()} goes into the tile's fluid holder,
 * {@link #gasView()} into its gas holder. Mutators keep the two sides mutually
 * exclusive: setting one side clears the other, and clearing one side never disturbs
 * the other — which also makes the container-block double-list NBT round trip
 * (fluid list read, then gas list read) idempotent.
 */
public final class CloudworksHybridTank {
    /**
     * Default fluid capacity per tank slot (mB).
     */
    public static final int FLUID_CAPACITY = 350_000;
    /**
     * Default gas capacity per tank slot.
     */
    public static final int GAS_CAPACITY = 16_000_000;
    @Nullable
    private final IContentsListener listener;
    private final FluidView fluidView = new FluidView();
    private final GasView gasView = new GasView();
    @Nullable
    private FluidStack fluid;
    @Nullable
    private GasStack gas;

    public CloudworksHybridTank(@Nullable IContentsListener listener) {
        this.listener = listener;
    }

    public IExtendedFluidTank fluidView() {
        return fluidView;
    }

    public IExtendedGasTank gasView() {
        return gasView;
    }

    private boolean hasFluid() {
        return fluid != null && fluid.amount > 0;
    }

    private boolean hasGas() {
        return gas != null && gas.amount > 0;
    }

    private void changed() {
        if (listener != null) listener.onContentsChanged();
    }

    /**
     * Port drain: empties whichever side is set.
     */
    public void drainAll() {
        boolean changed = hasFluid() || hasGas();
        fluid = null;
        gas = null;
        if (changed) changed();
    }

    /**
     * 协议储存箱 commit step: shrinks the fluid side by the planned take.
     */
    void shrinkFluid(int amount) {
        if (hasFluid() && amount > 0) fluidView().shrinkStack(amount, Action.EXECUTE);
    }

    /**
     * 协议储存箱 commit step: shrinks the gas side by the planned take.
     */
    void shrinkGas(int amount) {
        if (hasGas() && amount > 0) gasView().shrinkStack(amount, Action.EXECUTE);
    }

    @Nullable
    public FluidStack getFluid() {
        return hasFluid() ? fluid : null;
    }

    /**
     * Mode-aware fluid setter: a real stack switches the tank to fluid, null only clears the fluid side.
     */
    public void setFluid(@Nullable FluidStack stack) {
        if (stack == null || stack.amount <= 0) {
            if (hasFluid()) {
                fluid = null;
                changed();
            }
            return;
        }
        gas = null;
        FluidStack copy = new FluidStack(stack, Math.min(stack.amount, FLUID_CAPACITY));
        if (!hasFluid() || fluid.amount != copy.amount || !fluid.isFluidEqual(copy)) {
            fluid = copy;
            changed();
        }
    }

    @Nullable
    public GasStack getGas() {
        return hasGas() ? gas : null;
    }

    /**
     * Mode-aware gas setter: a real stack switches the tank to gas, null only clears the gas side.
     */
    public void setGas(@Nullable GasStack stack) {
        if (stack == null || stack.amount <= 0) {
            if (hasGas()) {
                gas = null;
                changed();
            }
            return;
        }
        fluid = null;
        GasStack copy = new GasStack(stack.getGas(), Math.min(stack.amount, GAS_CAPACITY));
        if (!hasGas() || gas.amount != copy.amount || !gas.isGasEqual(copy)) {
            gas = copy;
            changed();
        }
    }

    public int getFluidAmount() {
        return hasFluid() ? fluid.amount : 0;
    }

    public int getGasAmount() {
        return hasGas() ? gas.amount : 0;
    }

    /**
     * Port transfer planning: fluid room this tank offers for the offered type (0 when incompatible).
     */
    public int fluidCapacityFor(@Nullable FluidStack template) {
        if (hasGas()) return 0;
        if (hasFluid())
            return template != null && fluid.isFluidEqual(template) ? Math.max(0, FLUID_CAPACITY - fluid.amount) : 0;
        return FLUID_CAPACITY;
    }

    /**
     * Port transfer planning: gas room this tank offers for the offered type (0 when incompatible).
     */
    public int gasCapacityFor(@Nullable GasStack template) {
        if (hasFluid()) return 0;
        if (hasGas()) return template != null && gas.isGasEqual(template) ? Math.max(0, GAS_CAPACITY - gas.amount) : 0;
        return GAS_CAPACITY;
    }

    /**
     * Port transfer execution: grow the fluid side (only used when a plan already reserved room).
     */
    void growFluid(int amount) {
        if (hasFluid() && amount > 0) fluidView().growStack(amount, Action.EXECUTE);
    }

    /**
     * Port transfer execution: grow the gas side (only used when a plan already reserved room).
     */
    void growGas(int amount) {
        if (hasGas() && amount > 0) gasView().growStack(amount, Action.EXECUTE);
    }

    /**
     * Fluid capability view over the shared state.
     */
    private final class FluidView implements IExtendedFluidTank {
        @Nullable
        @Override
        public FluidStack getFluid() {
            return CloudworksHybridTank.this.getFluid();
        }

        @Override
        public int getCapacity() {
            return FLUID_CAPACITY;
        }

        @Override
        public void setStack(@Nullable FluidStack stack) {
            setFluid(stack);
        }

        @Override
        public void setStackUnchecked(@Nullable FluidStack stack) {
            setFluid(stack);
        }

        @Override
        public boolean isFluidValid(@Nullable FluidStack stack) {
            return !hasGas();
        }

        @Override
        public void onContentsChanged() {
            changed();
        }
    }

    /**
     * Gas capability view over the shared state.
     */
    private final class GasView implements IExtendedGasTank {
        @Nullable
        @Override
        public GasStack getGas() {
            return CloudworksHybridTank.this.getGas();
        }

        @Override
        public int getMaxGas() {
            return GAS_CAPACITY;
        }

        @Override
        public int getGasAmount() {
            return CloudworksHybridTank.this.getGasAmount();
        }

        @Override
        public void setStack(@Nullable GasStack stack) {
            setGas(stack);
        }

        @Override
        public void setStackUnchecked(@Nullable GasStack stack) {
            setGas(stack);
        }

        @Override
        public boolean isValid(@Nullable GasStack stack) {
            return !hasFluid();
        }

        @Override
        public void onContentsChanged() {
            changed();
        }

        @Nonnull
        @Override
        public GasTankInfo getInfo() {
            return this;   // the gas view is its own info source
        }
    }
}
