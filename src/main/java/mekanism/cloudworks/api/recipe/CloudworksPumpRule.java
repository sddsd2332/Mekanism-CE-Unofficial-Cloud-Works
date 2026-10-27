package mekanism.cloudworks.api.recipe;

import java.util.Objects;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;

/** Immutable lifecycle declaration for one environment-dependent pump recipe. */
public final class CloudworksPumpRule {
    public enum Scaling { FIXED, PER_PROCESS, NATIVE_WATER }
    public enum Filter { ANY, WITHOUT, WITH }
    private final ResourceLocation id, axis;
    private final FluidStack output;
    private final int ticks, priority;
    private final Scaling scaling;
    private final Filter filter;
    public CloudworksPumpRule(ResourceLocation id, ResourceLocation axis, FluidStack output,
            int ticks, int priority, Scaling scaling, Filter filter) {
        this.id = Objects.requireNonNull(id); this.axis = Objects.requireNonNull(axis);
        if (id.toString().length() > 128 || axis.toString().length() > 128 || output == null
                || output.getFluid() == null || output.amount <= 0 || ticks < 1)
            throw new IllegalArgumentException("Invalid pump environment rule");
        this.output = output.copy(); this.ticks = ticks; this.priority = priority;
        this.scaling = Objects.requireNonNull(scaling); this.filter = Objects.requireNonNull(filter);
    }
    public ResourceLocation id() { return id; }
    public ResourceLocation axis() { return axis; }
    public FluidStack output() { return output.copy(); }
    public int ticks() { return ticks; }
    public int priority() { return priority; }
    public Scaling scaling() { return scaling; }
    public Filter filter() { return filter; }
}
