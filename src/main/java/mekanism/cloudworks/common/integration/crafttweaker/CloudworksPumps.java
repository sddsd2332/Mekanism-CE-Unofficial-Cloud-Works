package mekanism.cloudworks.common.integration.crafttweaker;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.liquid.ILiquidStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.recipe.CloudworksPumpRule;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import stanhebben.zenscript.annotations.*;

/** Axis and native biome names normalize into the same immutable pump rule registry. */
@ZenClass("mods.cloudworks.Pumps")
@ZenRegister
public final class CloudworksPumps {
    private CloudworksPumps() {}
    @ZenMethod public static void add(String id, String axis, ILiquidStack output, int ticks, int priority) {
        add(id, axis, output, ticks, priority, "fixed", "any");
    }
    @ZenMethod public static void add(String id, String axis, ILiquidStack output, int ticks, int priority, String scaling, String filter) {
        submit(id, () -> qualified(axis), output, ticks, priority, scaling, filter);
    }
    @ZenMethod public static void biome(String id, String biome, ILiquidStack output, int ticks, int priority) {
        biome(id, biome, output, ticks, priority, "fixed", "any");
    }
    @ZenMethod public static void biome(String id, String biome, ILiquidStack output, int ticks, int priority, String scaling, String filter) {
        ResourceLocation name = qualified(biome);
        submit(id, () -> CloudworksAPI.pumpAxisForBiome(name), output, ticks, priority, scaling, filter);
    }
    private static void submit(String id, Supplier<ResourceLocation> axis, ILiquidStack source, int ticks,
            int priority, String scaling, String filter) {
        ResourceLocation name = qualified(id);
        FluidStack output = Objects.requireNonNull(CraftTweakerMC.getLiquidStack(Objects.requireNonNull(source))).copy();
        CloudworksPumpRule.Scaling tier = CloudworksPumpRule.Scaling.valueOf(scaling.toUpperCase(Locale.ROOT));
        CloudworksPumpRule.Filter equipment = CloudworksPumpRule.Filter.valueOf(filter.toUpperCase(Locale.ROOT));
        CloudworksScriptAction.submit("Cloudworks pump rule " + name, () -> CloudworksAPI.registerPumpRule(
                new CloudworksPumpRule(name, axis.get(), output, ticks, priority, tier, equipment)));
    }
    private static ResourceLocation qualified(String value) {
        if (value == null || value.length() > 128 || !value.contains(":")) throw new IllegalArgumentException("Expected a full namespaced id");
        ResourceLocation id = new ResourceLocation(value);
        if (!value.equals(id.toString())) throw new IllegalArgumentException("Invalid namespaced id: " + value);
        return id;
    }
    @ZenMethod public static void remove(String id) {
        ResourceLocation name = qualified(id);
        CloudworksScriptAction.submit("Cloudworks remove pump rule " + name, () -> CloudworksAPI.removePumpRule(name));
    }
    @ZenMethod public static void removeAll() {
        CloudworksScriptAction.submit("Cloudworks clear pump rules", CloudworksAPI::clearPumpRules);
    }
    @ZenMethod public static String[] getAxes() { return CloudworksAPI.pumpEnvironmentAxes().toArray(new String[0]); }
    @ZenMethod public static String[] getRuleIds() { return CloudworksAPI.pumpRuleIds().toArray(new String[0]); }
    @ZenMethod public static String axisForBiome(String biome) { return CloudworksAPI.pumpAxisForBiome(qualified(biome)).toString(); }
}
