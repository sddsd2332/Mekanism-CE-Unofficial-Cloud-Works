package mekanism.cloudworks.common.recipe;

import java.util.*;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.recipe.CloudworksPumpRule.*;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;
import mekanism.common.MekanismFluids;
import mekanism.common.config.MekanismConfig;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.*;

/** Startup-only declarations shared by Java integrations and CRT. No script callbacks reach a worker. */
public final class CloudworksPumpRecipes {
    private static Map<ResourceLocation, CloudworksPumpRule> rules;
    private static boolean frozen;
    private CloudworksPumpRecipes() {}
    private static void defaults() {
        if (rules != null) return;
        rules = new LinkedHashMap<>();
        seed("pump_water", BiomeAxes.OCEAN.id(), new FluidStack(FluidRegistry.WATER, 1000), Scaling.NATIVE_WATER, Filter.WITHOUT);
        seed("pump_heavy_water", BiomeAxes.OCEAN.id(), new FluidStack(MekanismFluids.HeavyWater, 10), Scaling.PER_PROCESS, Filter.WITH);
        seed("pump_lava", DimensionAxes.NETHER.id(), new FluidStack(FluidRegistry.LAVA, 1000), Scaling.FIXED, Filter.ANY);
    }
    private static void seed(String id, ResourceLocation axis, FluidStack fluid, Scaling scaling, Filter filter) {
        CloudworksPumpRule rule = new CloudworksPumpRule(new ResourceLocation("cloudworks", id), axis, fluid, 20, 100, scaling, filter);
        rules.put(rule.id(), rule);
    }
    private static void mutable() {
        if (frozen || !mekanism.cloudworks.api.CloudworksAPI.registrationOpen() || CloudworksNativeContracts.isWorker())
            throw new IllegalStateException("Pump rules are frozen; restart after changing scripts");
        defaults();
    }
    public static synchronized void add(CloudworksPumpRule rule) {
        mutable(); Objects.requireNonNull(rule);
        if (rules.containsKey(rule.id())) throw new IllegalArgumentException("Duplicate pump rule: " + rule.id());
        validate(rule); CloudworksAsyncRecipeAdmission.invalidatePumpAdapters(); rules.put(rule.id(), rule);
        mekanism.cloudworks.common.registry.CloudworksRegistries.adapterRegistered();
    }
    public static synchronized void remove(ResourceLocation id) {
        mutable(); CloudworksAsyncRecipeAdmission.invalidatePumpAdapters();
        if (rules.remove(Objects.requireNonNull(id)) != null) mekanism.cloudworks.common.registry.CloudworksRegistries.adapterRegistered();
    }
    public static synchronized void clear() {
        mutable(); CloudworksAsyncRecipeAdmission.invalidatePumpAdapters();
        if (!rules.isEmpty()) { rules.clear(); mekanism.cloudworks.common.registry.CloudworksRegistries.adapterRegistered(); }
    }
    private static EnvironmentAxis axis(ResourceLocation id) {
        EnvironmentAxis axis = EnvironmentAxisRegistry.byIdString(id.toString());
        if (axis == null || axis.kind() != EnvironmentAxis.Kind.BIOME && axis.kind() != EnvironmentAxis.Kind.DIMENSION)
            throw new IllegalArgumentException("Pump rule requires a registered biome/dimension axis: " + id);
        return axis;
    }
    private static void validate(CloudworksPumpRule rule) {
        axis(rule.axis());
        FluidStack output = rule.output();
        if (!FluidRegistry.isFluidRegistered(output.getFluid())
                && !(rule.id().equals(new ResourceLocation("cloudworks", "pump_heavy_water")) && output.getFluid() == MekanismFluids.HeavyWater))
            throw new IllegalArgumentException("Unregistered pump fluid: " + output.getFluid().getName());
    }
    public static synchronized void freeze() {
        defaults();
        if (frozen) return;
        for (CloudworksPumpRule rule : rules.values()) validate(rule);
        frozen = true;
    }
    public static synchronized List<CloudworksRecipeSpec> recipes(int processes, boolean filtered) {
        defaults();
        if (processes < 1) throw new IllegalArgumentException("Invalid pump tier processes");
        List<CloudworksPumpRule> ordered = new ArrayList<>(rules.values());
        ordered.sort(Comparator.comparingInt(CloudworksPumpRule::priority).reversed()
                .thenComparingInt(rule -> axis(rule.axis()).kind() == EnvironmentAxis.Kind.BIOME ? 0 : 1)
                .thenComparing(rule -> rule.id().toString()));
        List<CloudworksRecipeSpec> result = new ArrayList<>();
        for (CloudworksPumpRule rule : ordered) {
            if (rule.filter() == Filter.WITH && !filtered || rule.filter() == Filter.WITHOUT && filtered) continue;
            int scale = rule.scaling() == Scaling.PER_PROCESS || rule.scaling() == Scaling.NATIVE_WATER
                    && !MekanismConfig.current().general.pumpWaterSources.val() ? processes : 1;
            FluidStack output = rule.output(); long amount = Math.multiplyExact((long) output.amount, scale); output.amount = 1;
            result.add(CloudworksRecipeSpec.builder(rule.id().toString()).duration(rule.ticks())
                    .environmentAxis(rule.axis()).exclusiveSelection("cloudworks:pump", rule.priority())
                    .output(QIOResourceCodecs.fluid(output), amount, "fluid_output").build());
        }
        return Collections.unmodifiableList(result);
    }
    public static ResourceLocation biome(String fullRegistryName) {
        ResourceLocation name = new ResourceLocation(fullRegistryName);
        EnvironmentAxis axis = BiomeAxes.byBiome(name);
        if (axis == null) throw new IllegalArgumentException("No environment axis for biome: " + name);
        return axis.id();
    }
    public static synchronized List<String> axes() {
        List<String> ids = new ArrayList<>();
        for (EnvironmentAxis axis : EnvironmentAxisRegistry.all())
            if (axis.kind() == EnvironmentAxis.Kind.BIOME || axis.kind() == EnvironmentAxis.Kind.DIMENSION) ids.add(axis.id().toString());
        Collections.sort(ids); return Collections.unmodifiableList(ids);
    }
    public static synchronized List<String> ids() {
        defaults(); List<String> result = new ArrayList<>(); for (ResourceLocation id : rules.keySet()) result.add(id.toString());
        Collections.sort(result); return Collections.unmodifiableList(result);
    }
    static synchronized Object checkpoint() { return new Snapshot(rules == null ? null : new LinkedHashMap<>(rules), frozen); }
    static synchronized void restore(Object value) {
        Snapshot snapshot = (Snapshot) value; rules = snapshot.rules == null ? null : new LinkedHashMap<>(snapshot.rules); frozen = snapshot.frozen;
    }
    static synchronized void resetForTests() { rules = null; frozen = false; }
    private static final class Snapshot {
        final Map<ResourceLocation, CloudworksPumpRule> rules; final boolean frozen;
        Snapshot(Map<ResourceLocation, CloudworksPumpRule> rules, boolean frozen) { this.rules = rules; this.frozen = frozen; }
    }
}
