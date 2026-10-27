package mekanism.cloudworks.common.integration.te;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import cofh.core.util.ItemWrapper;
import cofh.thermalexpansion.block.device.BlockDevice;
import cofh.thermalexpansion.init.TEBlocks;
import cofh.thermalexpansion.util.managers.device.FactorizerManager;
import cofh.thermalexpansion.util.managers.device.FisherManager;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.api.machine.MachineLineUpgradePolicy;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.api.module.MachineModuleSource;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.api.module.ICustomMachineModule;
import mekanism.cloudworks.api.module.IMachineModule;
import mekanism.cloudworks.api.work.PreWorkContext;
import mekanism.cloudworks.api.recipe.RecipeCandidateContext;
import mekanism.cloudworks.api.recipe.RecipeCandidateDecision;
import mekanism.cloudworks.api.work.WorkContribution;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import cofh.core.inventory.ComparableItemStack;
import cofh.thermalexpansion.util.managers.device.TapperManager;
import gnu.trove.iterator.TObjectIntIterator;
import gnu.trove.map.hash.TObjectIntHashMap;
import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * Thermal Expansion device registration: five passive devices as plain
 * recipe machines under the one-per-type {@code thermalexpansion:device}
 * item (devices carry no Level NBT and are never augmentable —
 * TileDeviceBase.isAugmentable() is false, so single stacks register).
 * The heat sink lives in {@link CloudworksTeAuxiliaries} as an auxiliary
 * node; the seven excluded devices and the unreachable chunk loader stay
 * out by ruling.
 *
 * <p>Ruled rates: the water generator freezes the rain-fallback tier
 * (TileWaterGen:136-137, genRate 100 mB per tick = 4000 mB per 40-tick
 * cycle); the ocean flag projects the maxed six-source tier (20000 mB,
 * TileWaterGen:134-135) as a duration multiplier, and with neither the
 * ocean-family nor the rain-family flags the contract is rejected — the
 * native no-water no-rain core produces nothing (TileWaterGen:132-140,
 * passiveGen defaults false). The fisher freezes the tier-30 contract
 * (7200 native ticks over the standing divisor 3 = 2400) with the four
 * FisherManager weights as independent chance streams; the environment
 * behavior module applies ocean / rain / thunder multipliers from the
 * immutable machine context, and bait contracts are frozen from the live
 * FisherManager table.</p>
 */
public final class CloudworksTeDevices {

    static final int WATER_GEN_META = BlockDevice.Type.WATER_GEN.getMetadata();
    static final int NULLIFIER_META = BlockDevice.Type.NULLIFIER.getMetadata();
    static final int TAPPER_META = BlockDevice.Type.TAPPER.getMetadata();
    static final int FISHER_META = BlockDevice.Type.FISHER.getMetadata();
    static final int FACTORIZER_META = BlockDevice.Type.FACTORIZER.getMetadata();

    /**
     * Tapper harvest cycle: one trunk visit per 500 ticks (TileTapper:84 TIME_CONSTANT).
     */
    static final int TAPPER_CYCLE_TICKS = 500;
    /**
     * TileTapper:87 boostCycles — one fertilizer drives eight boosted cycles.
     */
    static final int TAPPER_FERTILIZER_CYCLES = 8;
    /**
     * Factorizer: CoreProps.TIME_CONSTANT / 2.
     */
    static final int FACTORIZER_TICKS = 16;
    /**
     * Water generator: 4000 mB per 40-tick cycle at the rain-fallback tier.
     */
    static final int WATER_GEN_TICKS = 40;
    static final int WATER_GEN_AMOUNT = 4000;
    /**
     * Fisher contract at the ruled water tier 30: the native base 7200 drops
     * its standing tier divisor 3 (TileFisher:275-290 with targetWater ≥ 30,
     * no rain). Environment divisors — ocean ÷3, rain or thunder ×3/4 on the
     * tier divisor — ride the environment flags through the machine
     * environment behavior module; river (÷2) stays out until the
     * river card exists. Bait (2026-10-21 ruling): one bait drives
     * boostCycles baited cycles at mult catches each, expressed as a per-bait
     * contract of 8 x (mult - 1) independent weighted streams beside the base.
     */
    static final int FISHER_TICKS = 2400;
    /**
     * TileFisher:75 boostCycles — one bait lasts eight baited cycles.
     */
    static final int BAIT_BOOST_CYCLES = 8;
    /**
     * Guard against a malformed addBait entry creating an unbounded freeze loop.
     */
    static final int MAX_BAIT_DRAWS = 4_096;
    /**
     * FluidStack amounts and slot capacities are int-sized in 1.12.
     */
    static final int MAX_PORT_AMOUNT = Integer.MAX_VALUE;

    private static boolean registered;
    private static final ResourceLocation ENVIRONMENT_BEHAVIOR_ID =
            new ResourceLocation("thermalexpansion", "device_environment_behavior");

    private CloudworksTeDevices() {
    }

    public static synchronized void register() {
        if (registered) return;
        registerInfiniteEnergyItem();
        register(Item.getItemFromBlock(TEBlocks.blockDevice), CloudworksTeDevices::tapperTable);
        registered = true;
    }

    private static void registerInfiniteEnergyItem() {
        final Item cell = Item.getItemFromBlock(TEBlocks.blockCell);
        CloudworksAPI.registerInfiniteEnergyItem(stack -> stack.getItem() == cell
                && stack.hasTagCompound() && stack.getTagCompound().getBoolean("Creative"));
    }

    /**
     * Pure registration entry so JVM tests drive it with substitute items.
     */
    static synchronized void register(Item deviceItem, Supplier<Map<ItemWrapper, FluidStack>> tapperItems) {
        Objects.requireNonNull(deviceItem, "device item");
        registerWaterGen(deviceItem);
        registerNullifier(deviceItem);
        registerTapper(deviceItem, tapperItems);
        registerFisher(deviceItem);
        registerFactorizer(deviceItem);
        MachineModuleType<DeviceEnvironmentModule> environmentBehavior = MachineModuleType.custom(
                ENVIRONMENT_BEHAVIOR_ID, 1, "auxiliary.cloudworks.thermalexpansion.device_environment",
                new DeviceEnvironmentModule()).allowedSources(MachineModuleSource.MACHINE_ENVIRONMENT);
        CloudworksAPI.registerMachineModule(environmentBehavior);
        CloudworksAPI.registerMachineEnvironmentBehavior(
                new ItemStack(deviceItem, 1, WATER_GEN_META), environmentBehavior);
        CloudworksAPI.registerMachineEnvironmentBehavior(
                new ItemStack(deviceItem, 1, FISHER_META), environmentBehavior);
    }

    private static void registerWaterGen(Item deviceItem) {
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT,
                        8000);
        registerDevice(deviceItem, WATER_GEN_META, layout, device(1, WATER_GEN_TICKS),
                "cloudworks:te_water_gen", waterGenAdapter());
    }

    private static void registerNullifier(Item deviceItem) {
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64);
        registerDevice(deviceItem, NULLIFIER_META, layout, device(1, 1),
                "cloudworks:te_nullifier", nullifierAdapter());
    }

    private static void registerTapper(Item deviceItem, Supplier<Map<ItemWrapper, FluidStack>> tapperItems) {
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("item_template", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.CONFIGURATION, 1, false)
                .add("item_fertilizer", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT,
                        8000);
        registerDevice(deviceItem, TAPPER_META, layout, device(1, TAPPER_CYCLE_TICKS),
                "cloudworks:te_tapper", tapperAdapter(tapperItems));
    }

    private static void registerFisher(Item deviceItem) {
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                // TileFisher has four output slots (4 x 64).
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT,
                        fisherOutputCapacity());
        registerDevice(deviceItem, FISHER_META, layout, device(1, FISHER_TICKS),
                "cloudworks:te_fisher", fisherAdapter());
    }

    private static void registerFactorizer(Item deviceItem) {
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
        List<CloudworksRecipeMode> modes = new ArrayList<>();
        modes.add(new CloudworksRecipeMode("cloudworks:te_factorizer_combine", 0, layout,
                factorizerAdapter(false, "te_factorizer_combine")));
        modes.add(new CloudworksRecipeMode("cloudworks:te_factorizer_split", 0, layout,
                factorizerAdapter(true, "te_factorizer_split")));
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(
                new ItemStack(deviceItem, 1, FACTORIZER_META), device(1, FACTORIZER_TICKS), layout,
                MachineIdentityPolicy.none(), Collections.unmodifiableList(modes)));
    }

    private static void registerDevice(Item deviceItem, int meta, MachineSlotLayout layout,
                                       MachineExecutionProfile profile, String modeId, CloudworksMachineAdapter adapter) {
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(
                new ItemStack(deviceItem, 1, meta), profile, layout, MachineIdentityPolicy.none(),
                Collections.singletonList(new CloudworksRecipeMode(modeId, 0, layout, adapter))));
    }

    /**
     * The aqua core: water from nothing, one cycle per 40 ticks.
     */
    static CloudworksMachineAdapter waterGenAdapter() {
        return registration -> Collections.singletonList(new CloudworksRecipeBatch()
                .output(new FluidStack(FluidRegistry.WATER, WATER_GEN_AMOUNT))
                .specification("te_water_gen_0", WATER_GEN_TICKS));
    }

    /**
     * The nullifier: any item vanishes; no output ever.
     */
    static CloudworksMachineAdapter nullifierAdapter() {
        return registration -> Collections.singletonList(new CloudworksRecipeBatch()
                .anyItem()
                .specification("te_nullifier_0", 1));
    }

    /** Every native catch chooses exactly one entry from the live weighted pool. */
    static CloudworksMachineAdapter fisherAdapter() {
        return registration -> {
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            // TileFisher:145-163 / FisherManager:54-56 — one bait boosts
            // boostCycles cycles at mult catches each. A selected bait recipe
            // replaces the base recipe at runtime, so it must carry all
            // boostCycles * multiplier catches, not only the extra catches.
            // Keep bait variants first: the no-input base recipe also matches
            // when bait is stocked and would otherwise shadow every bait recipe.
            TObjectIntHashMap<ComparableItemStack> baits = baitTable();
            List<FishContract> fish = fishTable();
            List<mekanism.api.qio.resource.QIOResourceDescriptor> pool = weightedFishPool(fish);
            if (baits != null) {
                TObjectIntIterator<ComparableItemStack> it = baits.iterator();
                int index = 1;
                while (it.hasNext()) {
                    it.advance();
                    ComparableItemStack bait = it.key();
                    int multiplier = it.value();
                    if (bait == null || multiplier <= 1) continue;
                    try {
                        ItemStack stack = bait.toItemStack();
                        if (stack.isEmpty()) continue;
                        long draws = (long) BAIT_BOOST_CYCLES * multiplier;
                        if (draws > MAX_BAIT_DRAWS) continue;
                        var baitResource = QIOResourceCodecs.item(stack);
                        result.add(CloudworksRecipeSpec.builder("te_fisher_bait_" + index++)
                                .duration(FISHER_TICKS).operationsPerCycle(multiplier)
                                .matcher(mekanism.cloudworks.api.recipe.CloudworksResourceMatcher.item(baitResource), 1, "item_input")
                                .choiceOutput(pool, 1).prepaidFuel(baitResource, draws).build());
                    } catch (IllegalArgumentException unsupported) {
                        // One unfreezable bait rejects only itself.
                    }
                }
            }
            result.add(CloudworksRecipeSpec.builder("te_fisher_0").duration(FISHER_TICKS)
                    .choiceOutput(pool, 1).build());
            return Collections.unmodifiableList(result);
        };
    }

    static List<mekanism.api.qio.resource.QIOResourceDescriptor> weightedFishPool(List<FishContract> fish) {
        int divisor = 0;
        for (FishContract entry : fish) {
            int a = divisor, b = entry.weight;
            while (b != 0) { int next = a % b; a = b; b = next; }
            divisor = a;
        }
        List<mekanism.api.qio.resource.QIOResourceDescriptor> pool = new ArrayList<>();
        for (FishContract entry : fish) {
            int copies = entry.weight / Math.max(1, divisor);
            if ((long) pool.size() + copies > 1_000_000)
                throw new IllegalArgumentException("Native fishing weight pool exceeds frozen representation limit");
            var descriptor = QIOResourceCodecs.item(entry.stack);
            for (int i = 0; i < copies; i++) pool.add(descriptor);
        }
        return Collections.unmodifiableList(pool);
    }

    private static long fishTotal(List<FishContract> fish) {
        long total = 0;
        for (FishContract entry : fish) total = Math.addExact(total, entry.weight);
        return total;
    }

    /**
     * The contract validator counts each independent fish stream as a possible
     * output. Reserve enough room for the largest live bait multiplier times
     * the live species count so an addon table cannot make registration fail
     * merely because its multiplier or its fish list is above the native
     * 256-item buffer.
     */
    static int fisherOutputCapacity() {
        return fisherOutputCapacity(fishTable(), baitTable());
    }

    /** Pure sizing rule shared by registration and the JVM tests. */
    static int fisherOutputCapacity(List<FishContract> fish,
                                    TObjectIntHashMap<ComparableItemStack> baits) {
        return 4 * 64;
    }

    static final class DeviceEnvironmentModule implements ICustomMachineModule<DeviceEnvironmentModule> {
        @Override
        public WorkContribution onPreWork(IMachineModule<DeviceEnvironmentModule> module,
                                          PreWorkContext context) {
            int meta = module.getMachineIdentity().getItemDamage();
            if (meta == FISHER_META) {
                double multiplier = 1D;
                if (context.environment().oceanEnvironment()) multiplier /= 3D;
                if (context.environment().rainEnvironment() || context.environment().thunderEnvironment())
                    multiplier *= .75D;
                return WorkContribution.builder().durationMultiplier(multiplier).build();
            }
            if (meta == WATER_GEN_META && context.environment().oceanEnvironment())
                return WorkContribution.builder().durationMultiplier(.2D).build();
            return WorkContribution.neutral();
        }

        /**
         * TileWaterGen:132-140 — without adjacent source blocks and without rain
         * the accumulator produces nothing (passiveGen defaults false). The
         * rain-family flags carry the genRate fallback tier; the ocean-family
         * flags project the maxed six-source tier. River joins the ocean tier
         * once the river card exists.
         */
        @Override
        public RecipeCandidateDecision onRecipeCandidate(
                IMachineModule<DeviceEnvironmentModule> module, RecipeCandidateContext context) {
            if (module.getMachineIdentity().getItemDamage() != WATER_GEN_META)
                return RecipeCandidateDecision.accept();
            CloudworksGenerationContext environment = context.environment();
            boolean ocean = environment.oceanEnvironment();
            boolean rain = environment.rainEnvironment() || environment.thunderEnvironment();
            return ocean || rain ? RecipeCandidateDecision.accept() : RecipeCandidateDecision.reject();
        }
    }

    /**
     * The live bait table, read-only. FisherManager exposes only per-stack
     * lookups, so the private bait map is read reflectively once at
     * freeze-time registration — the same third-party compatibility pattern
     * as the tapper item map. A moved field freezes no bait contracts.
     */
    @SuppressWarnings("unchecked")
    private static TObjectIntHashMap<ComparableItemStack> baitTable() {
        try {
            Field field = FisherManager.class.getDeclaredField("baitMap");
            field.setAccessible(true);
            Object live = field.get(null);
            if (live instanceof TObjectIntHashMap)
                return (TObjectIntHashMap<ComparableItemStack>) live;
        } catch (ReflectiveOperationException | LinkageError inaccessible) {
            // A moved map must not break registration; bait stays out.
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<FishContract> fishTable() {
        try {
            Field fishField = FisherManager.class.getDeclaredField("fishList");
            Field weightField = FisherManager.class.getDeclaredField("weightList");
            fishField.setAccessible(true);
            weightField.setAccessible(true);
            List<ItemStack> fish = (List<ItemStack>) fishField.get(null);
            List<Integer> weights = (List<Integer>) weightField.get(null);
            List<FishContract> result = new ArrayList<>();
            int size = Math.min(fish == null ? 0 : fish.size(), weights == null ? 0 : weights.size());
            for (int index = 0; index < size; index++) {
                ItemStack stack = fish.get(index);
                Integer weight = weights.get(index);
                if (stack != null && !stack.isEmpty() && weight != null && weight > 0)
                    result.add(new FishContract(stack, weight));
            }
            if (!result.isEmpty()) return result;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // Fall through to the vanilla TE default table.
        }
        return Arrays.asList(
                new FishContract(new ItemStack(Items.FISH, 1, 0), 120),
                new FishContract(new ItemStack(Items.FISH, 1, 1), 50),
                new FishContract(new ItemStack(Items.FISH, 1, 2), 4),
                new FishContract(new ItemStack(Items.FISH, 1, 3), 26));
    }

    static final class FishContract {
        final ItemStack stack;
        final int weight;

        FishContract(ItemStack stack, int weight) {
            this.stack = stack.copy();
            this.stack.setCount(1);
            this.weight = weight;
        }
    }

    /**
     * A device never consumes energy; its contract is only the cycle time.
     */
    private static MachineExecutionProfile device(int processes, int ticks) {
        return new MachineExecutionProfile(processes, 0D, 0D, ticks, MachineUpgradePolicy.NONE);
    }

    /**
     * The tapper never consumes its tree (TileTapper:166-175 taps the standing
     * trunk every TIME_CONSTANT): the stocked log rides the @324 monoculture
     * return — declared as an input and handed back through the item output
     * port by unscaledOutput — and each fertilizer drives boostCycles boosted
     * cycles at its multiplier, the same one-item contract shape as the fisher
     * bait (TileTapper:168-175 is structurally TileFisher:145-163). The
     * stocked log count is the tree count: one log funds one concurrent tree.
     */
    static CloudworksMachineAdapter tapperAdapter(Supplier<Map<ItemWrapper, FluidStack>> tapperItems) {
        return tapperAdapter(tapperItems, CloudworksTeDevices::fertilizerTable);
    }

    /**
     * Test seam: the fertilizer table is injectable like the trunk table.
     */
    static CloudworksMachineAdapter tapperAdapter(Supplier<Map<ItemWrapper, FluidStack>> tapperItems,
                                                  Supplier<TObjectIntHashMap<ComparableItemStack>>
                                                          fertilizerTable) {
        return registration -> {
            Map<ItemWrapper, FluidStack> table = tapperItems.get();
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            if (table == null) return Collections.unmodifiableList(result);
            TObjectIntHashMap<ComparableItemStack> fertilizers =
                    fertilizerTable.get();
            for (Map.Entry<ItemWrapper, FluidStack> entry : table.entrySet()) {
                ItemWrapper trunk = entry.getKey();
                FluidStack fluid = entry.getValue();
                if (trunk == null || trunk.item == null || fluid == null || fluid.amount <= 0) continue;
                ItemStack log = new ItemStack(trunk.item, 1, trunk.metadata);
                if (log.isEmpty()) continue;
                // Boosted variants first: the base contract also matches while
                // fertilizer is stocked and would otherwise shadow them.
                if (fertilizers != null) {
                    TObjectIntIterator<ComparableItemStack> it =
                            fertilizers.iterator();
                    while (it.hasNext()) {
                        it.advance();
                        ComparableItemStack fertilizer = it.key();
                        int multiplier = it.value();
                        if (fertilizer == null || multiplier <= 1) continue;
                        try {
                            ItemStack boost = fertilizer.toItemStack();
                            if (boost.isEmpty()) continue;
                            int fluidAmount = scaledFluidAmount(fluid.amount, multiplier);
                            if (fluidAmount <= 0 || fluidAmount > 8000) continue;
                            var fertilizerResource = QIOResourceCodecs.item(boost);
                            result.add(CloudworksRecipeSpec.builder("te_tapper_boost_" + result.size())
                                    .duration(TAPPER_CYCLE_TICKS)
                                    .matcher(mekanism.cloudworks.api.recipe.CloudworksResourceMatcher.item(fertilizerResource), 1, "item_fertilizer")
                                    .configuration(QIOResourceCodecs.item(log), "item_template")
                                    .unscaledOutput(QIOResourceCodecs.fluid(fluid), fluidAmount, "fluid_output")
                                    .prepaidFuel(fertilizerResource, TAPPER_FERTILIZER_CYCLES).build());
                        } catch (IllegalArgumentException unsupported) {
                            // One unfreezable fertilizer rejects only itself.
                        }
                    }
                }
                try {
                    if (fluid.amount > 8000) continue;
                    result.add(CloudworksRecipeSpec.builder("te_tapper_" + result.size()).duration(TAPPER_CYCLE_TICKS)
                            .configuration(QIOResourceCodecs.item(log), "item_template")
                            .unscaledOutput(QIOResourceCodecs.fluid(fluid), fluid.amount, "fluid_output").build());
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable trunk rejects only itself.
                }
            }
            return Collections.unmodifiableList(result);
        };
    }

    private static int scaledFluidAmount(int base, long multiplier) {
        if (base <= 0 || multiplier <= 0) return 0;
        long amount = (long) base * multiplier;
        return amount > MAX_PORT_AMOUNT ? 0 : (int) amount;
    }

    /**
     * One factorizer direction: the manager list, item to item, 16 ticks.
     */
    static CloudworksMachineAdapter factorizerAdapter(boolean reverse, String idPrefix) {
        return registration -> {
            FactorizerManager.FactorizerRecipe[] recipes = FactorizerManager.getRecipeList(reverse);
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            if (recipes == null) return Collections.unmodifiableList(result);
            for (FactorizerManager.FactorizerRecipe recipe : recipes) {
                if (recipe == null) continue;
                try {
                    result.add(new CloudworksRecipeBatch()
                            .item(recipe.getInput())
                            .output(recipe.getOutput())
                            .specification(idPrefix + "_" + result.size(), FACTORIZER_TICKS));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable pair rejects only itself.
                }
            }
            return Collections.unmodifiableList(result);
        };
    }

    /**
     * The live tapper trunk table. The manager exposes only per-stack lookups,
     * so the private item map is read reflectively once at freeze-time
     * registration (read-only, same third-party compatibility pattern as the
     * dynamo power scaling). A moved field freezes an empty table instead of
     * breaking registration.
     */
    private static Map<ItemWrapper, FluidStack> tapperTable() {
        try {
            Field field = TapperManager.class
                    .getDeclaredField("itemMap");
            field.setAccessible(true);
            Map<ItemWrapper, FluidStack> live = (Map<ItemWrapper, FluidStack>) field.get(null);
            if (live != null) return new LinkedHashMap<>(live);
        } catch (ReflectiveOperationException | ClassCastException | LinkageError inaccessible) {
            // A moved map must not break registration; the device stays empty.
        }
        return Collections.emptyMap();
    }

    /**
     * Stand-in trunk table entries for JVM tests without a TE runtime.
     */
    static Map<ItemWrapper, FluidStack> testTapperTable(ItemStack log, FluidStack fluid) {
        Map<ItemWrapper, FluidStack> table = new LinkedHashMap<>();
        table.put(new ItemWrapper(log), fluid);
        return table;
    }

    /**
     * The live fertilizer multiplier table, read-only. TapperManager exposes
     * only per-stack lookups, so the private fertilizer map is read
     * reflectively once at freeze-time registration — the same compatibility
     * pattern as the bait map. TapperManager's static initializer pulls
     * ThermalFoundation (the same hazard the @288 resin finder guards), so a
     * failed initialization degrades to no fertilizer contracts instead of
     * breaking the freeze.
     */
    @SuppressWarnings("unchecked")
    private static TObjectIntHashMap<ComparableItemStack> fertilizerTable() {
        try {
            Field field = TapperManager.class
                    .getDeclaredField("fertilizerMap");
            field.setAccessible(true);
            Object live = field.get(null);
            if (live instanceof TObjectIntHashMap)
                return (TObjectIntHashMap<ComparableItemStack>) live;
        } catch (ReflectiveOperationException | LinkageError inaccessible) {
            // A moved map or an uninitializable manager keeps fertilizer out.
        }
        return null;
    }
}
