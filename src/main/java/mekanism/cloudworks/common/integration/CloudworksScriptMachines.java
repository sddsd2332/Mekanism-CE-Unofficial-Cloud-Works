package mekanism.cloudworks.common.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.cloudworks.api.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.CloudworksGenerationPlan;
import mekanism.cloudworks.common.recipe.CloudworksContractRecipeAdapter;
import mekanism.cloudworks.common.recipe.CloudworksFrozenRecipeView;
import mekanism.cloudworks.common.registry.CloudworksMachineAdapters;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineExecutionProfile;
import mekanism.cloudworks.common.registry.MachineIdentityPolicy;
import mekanism.cloudworks.common.registry.MachineKey;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import net.minecraft.item.ItemStack;

/** Core registration operations contain no CraftTweaker classes or script callbacks. */
public final class CloudworksScriptMachines {
    private static final Map<MachineKey, LinkedHashMap<String, CloudworksContractRecipeAdapter.Contract>> RECIPES = new LinkedHashMap<>();
    private static final Map<MachineKey, MachineKey> TEMPLATES = new LinkedHashMap<>();

    private CloudworksScriptMachines() {}

    public static void register(ItemStack machine, MachineExecutionProfile profile, MachineSlotLayout layout) {
        mutable();
        if (CloudworksRegistries.isRegistered(machine)) throw new IllegalArgumentException("Machine already registered");
        CloudworksRegistries.registerMachine(machine, profile, layout, Collections.emptyList(), MachineIdentityPolicy.exactNbt());
    }

    public static void profile(ItemStack machine, MachineExecutionProfile profile) {
        mutable();
        if (!CloudworksRegistries.isRegistered(machine) || !CloudworksRegistries.aliasTarget(machine).isEmpty())
            throw new IllegalArgumentException("Profile requires a registered non-alias machine");
        CloudworksRegistries.updateProfile(machine, profile);
    }

    public static void registerFrom(ItemStack machine, ItemStack target, MachineExecutionProfile profile) {
        mutable();
        if (!CloudworksRegistries.isRegistered(target) || !CloudworksRegistries.aliasTarget(target).isEmpty())
            throw new IllegalArgumentException("Template requires a registered non-alias machine");
        register(machine, profile, CloudworksRegistries.slots(target));
        TEMPLATES.put(CloudworksRegistries.identityKey(machine), CloudworksRegistries.identityKey(target));
    }

    public static void recipe(ItemStack machine, CloudworksContractRecipeAdapter.Contract recipe) {
        mutable();
        if (!CloudworksRegistries.isRegistered(machine) || !CloudworksRegistries.aliasTarget(machine).isEmpty())
            throw new IllegalArgumentException("Recipes require a registered non-alias machine");
        mekanism.cloudworks.api.CloudworksThermalPlan thermal =
                CloudworksRegistries.thermal(CloudworksRegistries.identityKey(machine));
        if (thermal != null && thermal.kind != mekanism.cloudworks.api.CloudworksThermalPlan.Kind.EVAPORATION)
            throw new IllegalArgumentException("This thermal machine owns a fixed resource contract; use an independent script machine");
        if (thermal != null && (recipe.batch.extraEnergyPerTick() != 0 || recipe.batch.energyPerOperation() != 0))
            throw new IllegalArgumentException("Evaporation recipes are driven by heat, not electric processing or fuel generation");
        LinkedHashMap<String, CloudworksContractRecipeAdapter.Contract> entries =
                RECIPES.computeIfAbsent(CloudworksRegistries.identityKey(machine), ignored -> new LinkedHashMap<>());
        if (entries.containsKey(recipe.id)) throw new IllegalArgumentException("Duplicate cloud recipe: " + recipe.id);
        if (recipe.batch.energyPerOperation() > 0 && (recipe.ticks != 1 || recipe.batch.extraEnergyPerTick() != 0))
            throw new IllegalArgumentException("Fuel contracts must have one tick and no processing energy");
        validate(machine, new CloudworksContractRecipeAdapter(Collections.singletonList(recipe)));
        entries.put(recipe.id, recipe);
    }

    public static void removeRecipe(ItemStack machine, String id) {
        mutable();
        if (!CloudworksRegistries.isRegistered(machine) || !CloudworksRegistries.aliasTarget(machine).isEmpty())
            throw new IllegalArgumentException("Recipes require a registered non-alias machine");
        Map<String, CloudworksContractRecipeAdapter.Contract> entries = RECIPES.get(CloudworksRegistries.identityKey(machine));
        if (entries != null) entries.remove(id);
    }

    public static void removeAllRecipes(ItemStack machine) {
        mutable();
        if (!CloudworksRegistries.isRegistered(machine) || !CloudworksRegistries.aliasTarget(machine).isEmpty())
            throw new IllegalArgumentException("Cannot clear an unregistered or alias machine");
        mekanism.cloudworks.api.CloudworksThermalPlan thermal =
                CloudworksRegistries.thermal(CloudworksRegistries.identityKey(machine));
        if (thermal != null && thermal.kind != mekanism.cloudworks.api.CloudworksThermalPlan.Kind.EVAPORATION)
            throw new IllegalArgumentException("Fixed thermal contracts cannot be removed as ordinary recipes");
        CloudworksMachineAdapters.replace(CloudworksRegistries.identityKey(machine), Collections.emptyList());
        RECIPES.remove(CloudworksRegistries.identityKey(machine));
    }

    public static void finish() {
        try {
            RECIPES.forEach((key, recipes) -> applyFinish("recipe registrations for " + key, () -> {
                if (!recipes.isEmpty()) CloudworksMachineAdapters.register(key,
                        new CloudworksContractRecipeAdapter(new ArrayList<>(recipes.values())));
            }));
            // Templates are declared after their source identity; this order also handles template chains.
            TEMPLATES.forEach((key, target) -> applyFinish("template " + key, () -> {
                java.util.List<mekanism.cloudworks.api.CloudworksMachineAdapter> adapters =
                        new ArrayList<>(CloudworksMachineAdapters.getAll(target));
                adapters.addAll(CloudworksMachineAdapters.getAll(key));
                if (adapters.isEmpty() && CloudworksRegistries.special(target) == null
                        && CloudworksRegistries.energyGenerator(target) == null && CloudworksRegistries.thermal(target) == null)
                    throw new IllegalArgumentException("Template has no execution contract: " + target.displayStack());
                CloudworksMachineAdapters.replace(key, adapters);
                if (CloudworksRegistries.special(target) != null)
                    CloudworksRegistries.registerSpecial(key.displayStack(), CloudworksRegistries.special(target));
                if (CloudworksRegistries.energyGenerator(target) != null)
                    CloudworksRegistries.registerEnergyGenerator(key.displayStack(), CloudworksRegistries.energyGenerator(target));
                if (CloudworksRegistries.thermal(target) != null)
                    CloudworksRegistries.registerThermal(key.displayStack(), CloudworksRegistries.thermal(target));
            }));
            java.util.Set<MachineKey> keys = new java.util.LinkedHashSet<>(RECIPES.keySet());
            keys.addAll(TEMPLATES.keySet());
            for (MachineKey key : keys) applyFinish("contract validation for " + key, () -> {
                mekanism.cloudworks.api.CloudworksThermalPlan thermal = CloudworksRegistries.thermal(key);
                Map<String, CloudworksContractRecipeAdapter.Contract> additions = RECIPES.get(key);
                if (thermal != null && additions != null && !additions.isEmpty()) {
                    if (thermal.kind != mekanism.cloudworks.api.CloudworksThermalPlan.Kind.EVAPORATION)
                        throw new IllegalArgumentException("Cannot attach processing recipes to a fixed thermal template");
                    for (CloudworksContractRecipeAdapter.Contract recipe : additions.values())
                        if (recipe.batch.extraEnergyPerTick() != 0 || recipe.batch.energyPerOperation() != 0)
                            throw new IllegalArgumentException("Evaporation recipes cannot contain electric costs or generation");
                }
                boolean fuel = CloudworksRegistries.special(key) != null || CloudworksRegistries.energyGenerator(key) != null;
                boolean processing = false;
                for (mekanism.cloudworks.api.CloudworksMachineAdapter adapter : CloudworksMachineAdapters.getAll(key)) {
                    CloudworksFrozenRecipeView view = mekanism.cloudworks.common.recipe.CloudworksAsyncRecipeAdmission.capture(adapter)
                            .orElseThrow(() -> new IllegalArgumentException("Script machine contract cannot be frozen"));
                    for (CloudworksFrozenRecipeView.Entry entry : view.entries()) {
                        fuel |= entry.energyPerOperation() > 0;
                        processing |= entry.energyPerOperation() == 0;
                    }
                }
                if (fuel && processing)
                    throw new IllegalArgumentException("Mixed processing and generation contracts: " + key.displayStack());
            });
        } finally {
            RECIPES.clear();
            TEMPLATES.clear();
        }
    }

    private static void applyFinish(String scope, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            mekanism.common.Mekanism.logger.error("Cloudworks script finalization failed for " + scope, failure);
        }
    }

    public static void validate(ItemStack machine, mekanism.cloudworks.api.CloudworksMachineAdapter adapter) {
        validate(CloudworksRegistries.slots(machine), adapter);
    }

    public static void validate(MachineSlotLayout layout, mekanism.cloudworks.api.CloudworksMachineAdapter adapter) {
        CloudworksFrozenRecipeView view = mekanism.cloudworks.common.recipe.CloudworksAsyncRecipeAdmission.capture(adapter)
                .orElseThrow(() -> new IllegalArgumentException("Machine contract cannot be frozen"));
        if (layout == null) throw new IllegalArgumentException("Machine layout is unavailable");
        for (CloudworksFrozenRecipeView.Entry entry : view.entries()) {
            for (int index = 0; index < entry.inputCount(); index++) {
                String name = entry.inputPort(index);
                if (!name.isEmpty()) {
                    MachineSlotLayout.Slot port = layout.find(name);
                    if (port == null || port.role() != MachineSlotLayout.PortRole.INPUT
                            || port.capacity() < entry.inputAmount(index)
                            || entry.inputCodec(index) != null && !port.codecId().equals(entry.inputCodec(index)))
                        throw new IllegalArgumentException("Incompatible or undersized input port: " + name);
                } else if (entry.inputCodec(index) != null) {
                    long room = 0;
                    for (MachineSlotLayout.Slot port : layout.slots())
                        if (port.role() == MachineSlotLayout.PortRole.INPUT
                                && port.codecId().equals(entry.inputCodec(index))) room += port.capacity();
                    if (room < entry.inputAmount(index))
                        throw new IllegalArgumentException("Missing or undersized input codec: " + entry.inputCodec(index));
                }
            }
            for (int index = 0; index < entry.configurationCount(); index++) {
                MachineSlotLayout.Slot port = layout.find(entry.configurationPort(index));
                if (port == null || port.role() != MachineSlotLayout.PortRole.CONFIGURATION
                        || entry.configurationCodec(index) != null && !port.codecId().equals(entry.configurationCodec(index)))
                    throw new IllegalArgumentException("Incompatible template port");
            }
            for (int index = 0; index < entry.outputTemplate().size(); index++) {
                mekanism.cloudworks.common.data.CloudworksResourceStack output = entry.outputTemplate().get(index);
                String name = entry.outputPort(index);
                long room = 0;
                for (MachineSlotLayout.Slot port : layout.slots())
                    if (port.role() == MachineSlotLayout.PortRole.OUTPUT
                            && port.codecId().equals(output.descriptor().getCodecId())
                            && (name.isEmpty() || name.equals(port.id()))) room += port.capacity();
                if (room < output.amount()) throw new IllegalArgumentException("Incompatible or undersized output port: " + name);
            }
            Map<net.minecraft.util.ResourceLocation, Long> outputTotals = new LinkedHashMap<>();
            for (mekanism.cloudworks.common.data.CloudworksResourceStack output : entry.outputTemplate())
                outputTotals.merge(output.descriptor().getCodecId(), output.amount(), Math::addExact);
            for (mekanism.cloudworks.common.recipe.CloudworksRecipeBatch.ChanceOutput output : entry.chanceOutputTemplate())
                if (output.chance() > 0) outputTotals.merge(output.descriptor().getCodecId(), output.amount(), Math::addExact);
            outputTotals.forEach((codec, total) -> {
                long room = 0;
                for (MachineSlotLayout.Slot port : layout.slots())
                    if (port.role() == MachineSlotLayout.PortRole.OUTPUT && port.codecId().equals(codec)) room += port.capacity();
                if (room < total) throw new IllegalArgumentException("Combined recipe outputs exceed capacity: " + codec);
            });
        }
    }

    public static void constantGenerator(ItemStack machine, double energy) {
        mutable();
        if (!CloudworksRegistries.isRegistered(machine) || !CloudworksRegistries.aliasTarget(machine).isEmpty())
            throw new IllegalArgumentException("Generation requires a registered non-alias machine");
        if (!CloudworksMachineAdapters.getAll(machine).isEmpty())
            throw new IllegalArgumentException("Constant generation requires an empty recipe machine");
        if (CloudworksRegistries.thermal(CloudworksRegistries.identityKey(machine)) != null)
            throw new IllegalArgumentException("Thermal machines settle generation through their heat system");
        CloudworksGenerationPlan.constant(energy);
        CloudworksRegistries.registerEnergyGenerator(machine, new CloudworksEnergyGenerator() {
            @Override
            public double generationPerTick(mekanism.cloudworks.common.data.MachineNodeData node, int operations) {
                return energy * operations;
            }
            @Override
            public CloudworksGenerationPlan freezeGeneration(mekanism.cloudworks.common.data.MachineNodeData node,
                    int operations, mekanism.cloudworks.api.CloudworksGenerationContext context) {
                return CloudworksGenerationPlan.constant(energy * operations);
            }
        });
    }

    private static void mutable() {
        if (CloudworksRegistries.isFrozen()) throw new IllegalStateException("Cloudworks scripts require a restart");
    }
}
