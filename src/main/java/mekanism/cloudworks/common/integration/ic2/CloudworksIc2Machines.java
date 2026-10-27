package mekanism.cloudworks.common.integration.ic2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import ic2.api.recipe.IBasicMachineRecipeManager;
import ic2.api.recipe.ICannerBottleRecipeManager;
import ic2.api.recipe.ICannerEnrichRecipeManager;
import ic2.api.recipe.IListRecipeManager;
import ic2.api.recipe.IRecipeInput;
import ic2.api.recipe.MachineRecipe;
import ic2.api.recipe.Recipes;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.common.integration.CloudworksUpgradeCurves;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import ic2.api.recipe.IElectrolyzerRecipeManager;
import ic2.api.recipe.IMachineRecipeManager;
import ic2.core.init.MainConfig;
import ic2.core.item.type.CraftingItemType;
import ic2.core.ref.FluidName;
import ic2.core.ref.ItemName;
import ic2.core.util.ConfigUtil;
import java.util.Collection;
import java.util.Locale;
import mekanism.common.config.MekanismConfig;
import mekanism.common.Mekanism;
import net.minecraft.block.Block;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.common.Loader;

/**
 * IndustrialCraft 2 machine adapters. IC2 types appear only inside this
 * package and only behind {@link mekanism.cloudworks.common.integration.CloudworksHooks}
 * optional-mod boundary; deleting this directory removes the integration.
 *
 * <p>Fourteen processing machines register under the single {@code ic2:te}
 * item, one identity per TeBlock meta (the numbers below are verified against
 * the IC2 2.8.222 TeBlock source). Standard machines carry a fixed
 * EU/t-and-ticks contract from their tiles; the blast furnace is heat-driven
 * (duration and compressed-air consumption ride the recipe metadata NBT) and
 * flattens to that rated contract. TeBlock itself is deliberately never
 * referenced: its enum initializer pulls live tile classes. The matter
 * generator, electrolyzer, and overclocker/energy-storage curves are all
 * frozen through the same public contract path.</p>
 */
public final class CloudworksIc2Machines {

    /**
     * IC2 machine items are plain meta variants; no NBT rides the identity.
     */
    static final MachineIdentityPolicy IDENTITY = MachineIdentityPolicy.none();

    static final int CANNER_META = 42;
    static final int COMPRESSOR_META = 43;
    static final int ELECTRIC_FURNACE_META = 44;
    static final int EXTRACTOR_META = 45;
    static final int MACERATOR_META = 47;
    static final int RECYCLER_META = 48;
    static final int SOLID_CANNER_META = 49;
    static final int BLAST_FURNACE_META = 50;
    static final int BLOCK_CUTTER_META = 51;
    static final int CENTRIFUGE_META = 52;
    static final int METAL_FORMER_META = 55;
    static final int ORE_WASHING_META = 56;
    static final int MATTER_GENERATOR_META = 61;
    static final int ELECTROLYZER_META = 76;

    /**
     * Modeled average EU feed rate used to schedule one whole-mB UU contract.
     */
    static final double MATTER_FEED_RATE_EU = 512D;
    /**
     * TileEntityMatter retains each incoming EU and force-adds five more per
     * amplifier point (TileEntityMatter:157-164), for six stored EU per point.
     */
    static final double MATTER_AMPLIFICATION = 6D;

    static final int FLUID_PORT_CAPACITY = 10_000;
    /** Native amplifier slot; the other inventory slots handle UU containers. */
    static final int MATTER_ITEM_PORT_CAPACITY = 64;
    /**
     * Blast furnace consumes the compressed-air metadata amount every tick of the recipe.
     */
    static final int BLAST_FURNACE_AIR_PORT_CAPACITY = 8000;

    private static boolean registered;

    private CloudworksIc2Machines() {
    }

    public static synchronized void register() {
        if (registered) return;
        Block block = Block.getBlockFromName("ic2:te");
        if (block == null)
            throw new IllegalStateException("IndustrialCraft's ic2:te machine block is unavailable");
        Item machineItem = Item.getItemFromBlock(block);
        CloudworksAPI.registerInfiniteEnergyItem(stack -> stack.getItem() == machineItem
                && stack.getItemDamage() == 86);
        CloudworksUpgradeCurves.Snapshot curves = mekanism.cloudworks.common.integration
                .CloudworksUpgradeCurves.capture(Loader.isModLoaded(
                        CloudworksUpgradeCurves.RANDOM_COMPLEMENT_MOD_ID));
        register(machineItem, MekanismConfigAccess.euToJoules(),
                () -> Recipes.oreWashing, () -> Recipes.cannerBottle, () -> Recipes.cannerEnrich,
                () -> Recipes.blastfurnace,
                () -> Recipes.recyclerBlacklist, () -> Recipes.recyclerWhitelist,
                () -> FluidName.air.getInstance(),
                () -> ItemName.crafting.getItemStack(
                        CraftingItemType.scrap),
                () -> Recipes.electrolyzer,
                () -> Recipes.matterAmplifier,
                () -> FluidName.uu_matter.getInstance(),
                ConfigUtil.getFloat(MainConfig.get(), "balance/uuEnergyFactor"),
                curves.ic2OverclockerTime(), curves.ic2OverclockerEnergy());
        registered = true;
    }

    /**
     * Pure registration entry so JVM tests drive it with substitutes and no IC2 runtime.
     */
    static synchronized void register(Item machineItem, double euToJoules,
                                      Supplier<IBasicMachineRecipeManager> oreWashing,
                                      Supplier<ICannerBottleRecipeManager> cannerBottle,
                                      Supplier<ICannerEnrichRecipeManager> cannerEnrich,
                                      Supplier<IBasicMachineRecipeManager> blastFurnace,
                                      Supplier<IListRecipeManager> recyclerBlacklist,
                                      Supplier<IListRecipeManager> recyclerWhitelist,
                                      Supplier<Fluid> blastFurnaceAir,
                                      Supplier<ItemStack> scrapStack,
                                      Supplier<IElectrolyzerRecipeManager> electrolyzer,
                                      Supplier<IMachineRecipeManager<IRecipeInput, Integer, ItemStack>> matterAmplifier,
                                      Supplier<Fluid> uuMatter,
                                      float uuEnergyFactor,
                                      double overclockerTime,
                                      double overclockerEnergy) {
        Objects.requireNonNull(machineItem, "machine item");
        if (!Double.isFinite(euToJoules) || euToJoules <= 0D)
            throw new IllegalArgumentException("Invalid IndustrialCraft registration inputs");
        double factor = euToJoules;
        CloudworksIc2UpgradeModules.register(factor);
        // RandomComplement overclocker curve, exact per card on the shared
        // fraction axis: the limit folds into the bases, so a fraction f of
        // the four slots evaluates to overclockerTime^(4f) / overclockerEnergy^(4f).
        // Native item modules take over SPEED independently. Storage cards add
        // absolute capacity and never claim the line's ENERGY efficiency axis.
        int overclockerSlots = 4;
        MachineUpgradePolicy processing = MachineUpgradePolicy.fixed(overclockerSlots, 0,
                Math.pow(1.0D / overclockerTime, overclockerSlots),
                Math.pow(overclockerEnergy, overclockerSlots), 1.0D, 1.0D, false);

        // Simple item -> item(s) machines with a fixed EU contract.
        standard(machineItem, COMPRESSOR_META, "compressor", () -> Recipes.compressor, 2, 300, factor, processing);
        standard(machineItem, EXTRACTOR_META, "extractor", () -> Recipes.extractor, 2, 300, factor, processing);
        standard(machineItem, MACERATOR_META, "macerator", () -> Recipes.macerator, 2, 300, factor, processing);
        standard(machineItem, BLOCK_CUTTER_META, "block_cutter", () -> Recipes.blockcutter, 4, 450, factor, processing);
        standard(machineItem, CENTRIFUGE_META, "centrifuge", () -> Recipes.centrifuge, 48, 500, factor, processing, 3);

        // Electric furnace: the IC2 manager is a live vanilla bridge that
        // cannot be enumerated, so freeze the vanilla smelting list directly.
        registerMachine(machineItem, ELECTRIC_FURNACE_META,
                new MachineExecutionProfile(1, 3 * factor, 600 * factor, 100).withUpgradePolicy(processing),
                itemLayout(), "cloudworks:ic2_electric_furnace",
                new ElectricFurnaceAdapter(100));

        // Metal former: one machine, three recipe modes.
        registerModes(machineItem, METAL_FORMER_META,
                new MachineExecutionProfile(1, 10 * factor, 4000 * factor, 200).withUpgradePolicy(processing),
                itemLayout(),
                new BasicAdapter("ic2_metalformer_cutting", () -> Recipes.metalformerCutting, 200, null),
                new BasicAdapter("ic2_metalformer_extruding", () -> Recipes.metalformerExtruding, 200, null),
                new BasicAdapter("ic2_metalformer_rolling", () -> Recipes.metalformerRolling, 200, null));

        // Ore washing: item + water (per-recipe metadata "amount") -> items.
        registerMachine(machineItem, ORE_WASHING_META,
                new MachineExecutionProfile(1, 16 * factor, 16000 * factor, 500).withUpgradePolicy(processing),
                new MachineSlotLayout()
                        .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                        .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 8000)
                        .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 3 * 64),
                "cloudworks:ic2_ore_washing",
                new BasicAdapter("ic2_ore_washing", oreWashing, 500, FluidRegistry.WATER));

        // Blast furnace: heat-driven; duration and compressed air ride the metadata.
        registerMachine(machineItem, BLAST_FURNACE_META,
                new MachineExecutionProfile(1, 0D, 200).withUpgradePolicy(MachineUpgradePolicy.NONE),
                blastFurnaceLayout(),
                "cloudworks:ic2_blast_furnace",
                new BlastFurnaceAdapter(blastFurnace, blastFurnaceAir));

        // Canner (bottle + enrich) and the solid canner (bottle subset).
        registerModes(machineItem, CANNER_META,
                new MachineExecutionProfile(1, 4 * factor, 1600 * factor, 200).withUpgradePolicy(processing),
                cannerLayout(),
                new CannerBottleAdapter("ic2_canner_bottle", cannerBottle, 200),
                new CannerEnrichAdapter("ic2_canner_enrich", cannerEnrich, 200));
        registerMachine(machineItem, SOLID_CANNER_META,
                new MachineExecutionProfile(1, 2 * factor, 800 * factor, 200).withUpgradePolicy(processing),
                new MachineSlotLayout()
                        .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                        .add("item_additive", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                        .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64),
                "cloudworks:ic2_solid_canner",
                new CannerBottleAdapter("ic2_solid_canner", cannerBottle, 200));

        // Recycler: blacklist/whitelist table contract; whitelist entries freeze
        // as explicit recipes, blacklist keeps one any-item-except fallback.
        registerMachine(machineItem, RECYCLER_META,
                new MachineExecutionProfile(1, factor, 90 * factor, 45).withUpgradePolicy(processing),
                itemLayout(), "cloudworks:ic2_recycler",
                new RecyclerAdapter(recyclerTable(recyclerBlacklist, recyclerWhitelist, scrapStack),
                        1D / 8D, 45));

        // Electrolyzer: every recipe carries its own EU/t and tick contract.
        registerMachine(machineItem, ELECTROLYZER_META,
                new MachineExecutionProfile(1, 0D, 32_000 * factor, 1).withUpgradePolicy(MachineUpgradePolicy.NONE),
                electrolyzerLayout(),
                "cloudworks:ic2_electrolyzer",
                new ElectrolyzerAdapter(electrolyzer, factor));

        // Matter generator: amplifier items plus EU feed become UU-matter fluid.
        double uuCapacity = Math.round(1_000_000D * uuEnergyFactor);
        registerMachine(machineItem, MATTER_GENERATOR_META,
                new MachineExecutionProfile(1, 0D, uuCapacity * factor, 1).withUpgradePolicy(MachineUpgradePolicy.NONE),
                new MachineSlotLayout()
                        .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, MATTER_ITEM_PORT_CAPACITY)
                        .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT,
                                8000),
                "cloudworks:ic2_matter_generator",
                new MatterAdapter(matterAmplifier, uuMatter, uuEnergyFactor, factor));
        for (int meta : new int[]{COMPRESSOR_META, EXTRACTOR_META, MACERATOR_META, BLOCK_CUTTER_META,
                CENTRIFUGE_META, ELECTRIC_FURNACE_META, METAL_FORMER_META, ORE_WASHING_META,
                CANNER_META, SOLID_CANNER_META, RECYCLER_META})
            CloudworksIc2UpgradeModules.accept(machineStack(machineItem, meta), factor);
    }

    private static void standard(Item machineItem, int meta, String name,
                                 Supplier<IBasicMachineRecipeManager> manager, int energyPerTick, int ticks, double factor,
                                 MachineUpgradePolicy policy) {
        standard(machineItem, meta, name, manager, energyPerTick, ticks, factor, policy, 1);
    }

    private static void standard(Item machineItem, int meta, String name,
                                 Supplier<IBasicMachineRecipeManager> manager, int energyPerTick, int ticks, double factor,
                                 MachineUpgradePolicy policy, int outputSlots) {
        registerMachine(machineItem, meta,
                new MachineExecutionProfile(1, energyPerTick * factor, 2D * energyPerTick * factor * ticks, ticks).withUpgradePolicy(policy),
                itemLayout(outputSlots), "cloudworks:ic2_" + name,
                new BasicAdapter("ic2_" + name, manager, ticks, null));
    }

    private static MachineSlotLayout itemLayout() {
        return itemLayout(1);
    }

    private static MachineSlotLayout itemLayout(int outputSlots) {
        return new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT,
                        Math.multiplyExact(64, outputSlots));
    }

    private static MachineSlotLayout cannerLayout() {
        return new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("item_additive", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, FLUID_PORT_CAPACITY)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64)
                .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, FLUID_PORT_CAPACITY);
    }

    private static MachineSlotLayout blastFurnaceLayout() {
        return new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT,
                        BLAST_FURNACE_AIR_PORT_CAPACITY)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 2 * 64);
    }

    private static MachineSlotLayout electrolyzerLayout() {
        MachineSlotLayout layout = new MachineSlotLayout()
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 8000);
        for (EnumFacing facing : EnumFacing.values())
            layout.add(electrolyzerOutputPort(facing), QIOResourceCodecs.FLUID_STACK_ID,
                    MachineSlotLayout.PortRole.OUTPUT, Integer.MAX_VALUE);
        return layout;
    }

    private static String electrolyzerOutputPort(EnumFacing facing) {
        return "fluid_output_" + facing.name().toLowerCase(Locale.ROOT);
    }

    private static void registerMachine(Item machineItem, int meta, MachineExecutionProfile profile,
                                        MachineSlotLayout layout, String modeId, CloudworksMachineAdapter adapter) {
        profile = lineParticipation(profile);
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(
                machineStack(machineItem, meta), profile, layout, IDENTITY,
                Collections.singletonList(new CloudworksRecipeMode(modeId, 0, layout, adapter))));
    }

    private static void registerModes(Item machineItem, int meta, MachineExecutionProfile profile,
                                      MachineSlotLayout layout, CloudworksMachineAdapter... adapters) {
        profile = lineParticipation(profile);
        List<CloudworksRecipeMode> modes = new ArrayList<>();
        for (CloudworksMachineAdapter adapter : adapters)
            modes.add(new CloudworksRecipeMode("cloudworks:" + ((NamedAdapter) adapter).idPrefix(), 0, layout, adapter));
        CloudworksAPI.registerMachine(new CloudworksMachineDefinition(
                machineStack(machineItem, meta), profile, layout, IDENTITY,
                Collections.unmodifiableList(modes)));
    }

    private static MachineExecutionProfile lineParticipation(MachineExecutionProfile profile) {
        return profile.upgradePolicy().accepts(mekanism.common.Upgrade.SPEED)
                ? profile.withLineUpgradePolicy(mekanism.cloudworks.api.machine.MachineLineUpgradePolicy.PROCESSING) : profile;
    }

    static ItemStack machineStack(Item machineItem, int meta) {
        return new ItemStack(machineItem, 1, meta);
    }

    // -----------------------------------------------------------------------------------------
    // Shared adapter plumbing
    // -----------------------------------------------------------------------------------------

    private interface NamedAdapter extends CloudworksMachineAdapter {
        String idPrefix();
    }

    /**
     * Freezes item-variant inputs from an IRecipeInput into one alternatives ingredient.
     */
    private static boolean freezeInput(CloudworksRecipeBatch batch, IRecipeInput input) {
        try {
            if (input == null) return false;
            List<ItemStack> variants = input.getInputs();
            if (variants == null || variants.isEmpty()) return false;
            List<QIOResourceDescriptor> descriptors = new ArrayList<>();
            for (ItemStack variant : variants) {
                if (variant == null || variant.isEmpty()) continue;
                descriptors.add(QIOResourceCodecs.item(variant));
            }
            if (descriptors.isEmpty()) return false;
            batch.resources(descriptors, Math.max(1, input.getAmount()), "");
            return true;
        } catch (RuntimeException | LinkageError unavailable) {
            // IRecipeInput is supplied by IC2 add-ons/CRT and may execute
            // third-party code. Reject only this entry during the freeze.
            return false;
        }
    }

    private static void freezeOutputs(CloudworksRecipeBatch batch, Iterable<ItemStack> outputs) {
        for (ItemStack output : outputs)
            if (output != null && !output.isEmpty()) batch.output(output);
    }

    /**
     * Standard IC2 basic manager: item variants in, item collection out, an
     * optional metadata-driven water ingredient and a fixed duration.
     */
    static final class BasicAdapter implements NamedAdapter {
        private final String idPrefix;
        private final Supplier<? extends IBasicMachineRecipeManager> manager;
        private final int duration;
        private final Fluid ingredientFluid;

        BasicAdapter(String idPrefix, Supplier<? extends IBasicMachineRecipeManager> manager,
                     int duration, Fluid ingredientFluid) {
            this.idPrefix = idPrefix;
            this.manager = manager;
            this.duration = duration;
            this.ingredientFluid = ingredientFluid;
        }

        @Override
        public String idPrefix() {
            return idPrefix;
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            IBasicMachineRecipeManager recipes = manager.get();
            if (recipes == null) return Collections.emptyList();
            for (MachineRecipe<IRecipeInput, Collection<ItemStack>> recipe : recipes.getRecipes()) {
                try {
                    CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
                    if (!freezeInput(batch, recipe.getInput())) continue;
                    if (ingredientFluid != null && recipe.getMetaData() != null) {
                        int amount = recipe.getMetaData().getInteger("amount");
                        if (amount > 0) batch.fluid(new FluidStack(ingredientFluid, amount));
                    }
                    freezeOutputs(batch, recipe.getOutput());
                    result.add(batch.specification(idPrefix + "_" + result.size(), duration));
                } catch (RuntimeException | LinkageError unsupported) {
                    // One unfreezable recipe never blocks the manager.
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * Blast furnace: duration and compressed-air consumption ride the metadata NBT.
     */
    static final class BlastFurnaceAdapter implements NamedAdapter {
        private final Supplier<? extends IBasicMachineRecipeManager> manager;
        private final Supplier<Fluid> air;

        BlastFurnaceAdapter(Supplier<? extends IBasicMachineRecipeManager> manager, Supplier<Fluid> air) {
            this.manager = manager;
            this.air = air;
        }

        @Override
        public String idPrefix() {
            return "ic2_blast_furnace";
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
            Fluid airFluid = air.get();
            IBasicMachineRecipeManager recipes = manager.get();
            if (airFluid == null || recipes == null) return Collections.emptyList();
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            for (MachineRecipe<IRecipeInput, Collection<ItemStack>> recipe : recipes.getRecipes()) {
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
                if (!freezeInput(batch, recipe.getInput())) continue;
                NBTTagCompound metadata = recipe.getMetaData();
                int duration = metadata == null ? 0 : metadata.getInteger("duration");
                int airAmount = metadata == null ? 0 : metadata.getInteger("fluid");
                if (duration <= 0 || airAmount <= 0) continue;
                // TileEntityBlastFurnace drains metadata["fluid"] every
                // progress tick, not once at operation start.
                long totalAir = (long) duration * airAmount;
                if (totalAir > Integer.MAX_VALUE) continue;
                batch.fluid(new FluidStack(airFluid, (int) totalAir));
                freezeOutputs(batch, recipe.getOutput());
                try {
                    result.add(batch.specification(idPrefix() + "_" + result.size(), duration));
                } catch (IllegalArgumentException unsupported) {
                    // Same single-recipe skip rule.
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * Canner bottle mode (and the solid canner subset): container + fill items in, item out.
     */
    static final class CannerBottleAdapter implements NamedAdapter {
        private final String idPrefix;
        private final Supplier<? extends ICannerBottleRecipeManager> manager;
        private final int duration;

        CannerBottleAdapter(String idPrefix, Supplier<? extends ICannerBottleRecipeManager> manager, int duration) {
            this.idPrefix = idPrefix;
            this.manager = manager;
            this.duration = duration;
        }

        @Override
        public String idPrefix() {
            return idPrefix;
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            ICannerBottleRecipeManager recipes = manager.get();
            if (recipes == null) return Collections.emptyList();
            for (MachineRecipe<ICannerBottleRecipeManager.Input, ItemStack> recipe : recipes.getRecipes()) {
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
                ICannerBottleRecipeManager.Input input = recipe.getInput();
                if (!freezeInput(batch, input.container) || !freezeInput(batch, input.fill)) continue;
                ItemStack output = recipe.getOutput();
                if (output == null || output.isEmpty()) continue;
                batch.output(output);
                try {
                    result.add(batch.specification(idPrefix + "_" + result.size(), duration));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable pair never blocks the manager.
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * Canner enrich mode: fluid + additive in, fluid out.
     */
    static final class CannerEnrichAdapter implements NamedAdapter {
        private final String idPrefix;
        private final Supplier<? extends ICannerEnrichRecipeManager> manager;
        private final int duration;

        CannerEnrichAdapter(String idPrefix, Supplier<? extends ICannerEnrichRecipeManager> manager, int duration) {
            this.idPrefix = idPrefix;
            this.manager = manager;
            this.duration = duration;
        }

        @Override
        public String idPrefix() {
            return idPrefix;
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            ICannerEnrichRecipeManager recipes = manager.get();
            if (recipes == null) return Collections.emptyList();
            for (MachineRecipe<ICannerEnrichRecipeManager.Input, FluidStack> recipe : recipes.getRecipes()) {
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
                ICannerEnrichRecipeManager.Input input = recipe.getInput();
                if (input.fluid == null || input.fluid.amount <= 0) continue;
                if (!freezeInput(batch, input.additive)) continue;
                FluidStack output = recipe.getOutput();
                if (output == null || output.amount <= 0) continue;
                batch.fluid(input.fluid);
                batch.output(output);
                try {
                    result.add(batch.specification(idPrefix + "_" + result.size(), duration));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable pair never blocks the manager.
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * Electric furnace: the IC2 manager is a live bridge; freeze the vanilla smelting list.
     */
    static final class ElectricFurnaceAdapter implements NamedAdapter {
        private final int duration;

        ElectricFurnaceAdapter(int duration) {
            this.duration = duration;
        }

        @Override
        public String idPrefix() {
            return "ic2_electric_furnace";
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            for (Map.Entry<ItemStack, ItemStack> entry
                    : FurnaceRecipes.instance().getSmeltingList().entrySet()) {
                ItemStack input = entry.getKey();
                ItemStack output = entry.getValue();
                if (input.isEmpty() || output.isEmpty()) continue;
                try {
                    result.add(new CloudworksRecipeBatch()
                            .item(input)
                            .output(output)
                            .specification(idPrefix() + "_" + result.size(), duration));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable smelting entry never blocks the bridge.
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * Recycler: one lookup table plus a mode flag, mirroring the Mekanism CEU
     * recycler's recipe-map shape. In blacklist mode a table hit refuses work
     * (one any-item-except fallback carries everything else); in whitelist
     * mode a table hit continues work (each entry freezes as its own recipe).
     */
    static final class RecyclerAdapter implements NamedAdapter {
        private final Supplier<RecyclerTable> table;
        private final double chance;
        private final int duration;

        /**
         * map&lt;input, output&gt; plus whether the table is a blacklist.
         */
        static final class RecyclerTable {
            final Map<IRecipeInput, ItemStack> entries;
            final boolean blacklist;
            final ItemStack fallbackOutput;

            RecyclerTable(Map<IRecipeInput, ItemStack> entries, boolean blacklist, ItemStack fallbackOutput) {
                this.entries = Objects.requireNonNull(entries);
                this.blacklist = blacklist;
                this.fallbackOutput = fallbackOutput;
            }
        }

        RecyclerAdapter(Supplier<RecyclerTable> table, double chance, int duration) {
            this.table = table;
            this.chance = chance;
            this.duration = duration;
        }

        @Override
        public String idPrefix() {
            return "ic2_recycler";
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
            RecyclerTable contract = table.get();
            if (contract == null) return Collections.emptyList();
            if (contract.blacklist) {
                // Blacklist mode: a table hit refuses work; one fallback
                // contract excludes every key and recycles all the rest.
                if (contract.fallbackOutput == null || contract.fallbackOutput.isEmpty())
                    return Collections.emptyList();
                List<QIOResourceDescriptor> excluded = new ArrayList<>();
                for (IRecipeInput input : contract.entries.keySet()) {
                    List<ItemStack> variants = safeInputs(input);
                    if (variants == null) continue;
                    for (ItemStack variant : variants)
                        if (variant != null && !variant.isEmpty()) excluded.add(QIOResourceCodecs.item(variant));
                }
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch().anyItemExcept(excluded);
                batch.chanceOutput(contract.fallbackOutput, chance);
                try {
                    return Collections.singletonList(
                            batch.specification(idPrefix() + "_fallback", duration));
                } catch (IllegalArgumentException unsupported) {
                    return Collections.emptyList();
                }
            }
            // Whitelist mode: a table hit continues work; each entry freezes
            // as its own recipe exactly like the Mekanism CEU recycler map.
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            for (Map.Entry<IRecipeInput, ItemStack> entry : contract.entries.entrySet()) {
                ItemStack output = entry.getValue();
                if (output == null || output.isEmpty()) continue;
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
                if (!freezeInput(batch, entry.getKey())) continue;
                batch.chanceOutput(output, chance);
                try {
                    result.add(batch.specification(idPrefix() + "_" + result.size(), duration));
                } catch (IllegalArgumentException unsupported) {
                    // One unfreezable whitelist entry rejects only itself.
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * Resolves the live IC2 blacklist/whitelist managers into one table plus its mode flag.
     */
    static Supplier<RecyclerAdapter.RecyclerTable> recyclerTable(Supplier<IListRecipeManager> blacklist,
                                                                 Supplier<IListRecipeManager> whitelist, Supplier<ItemStack> scrap) {
        return () -> {
            IListRecipeManager allow = whitelist.get();
            boolean whitelistMode = allow != null && !allow.isEmpty();
            IListRecipeManager active = whitelistMode ? allow : blacklist.get();
            Map<IRecipeInput, ItemStack> entries = new LinkedHashMap<>();
            if (active != null && active.getInputs() != null)
                for (IRecipeInput input : active.getInputs())
                    if (input != null) entries.put(input, scrap.get());
            return new RecyclerAdapter.RecyclerTable(entries, !whitelistMode, scrap.get());
        };
    }

    /**
     * Mekanism's IC2 energy conversion, isolated for test substitutes.
     */
    static final class MekanismConfigAccess {
        static double euToJoules() {
            return MekanismConfig.current().general.FROM_IC2.val();
        }

        private MekanismConfigAccess() {
        }
    }

    /**
     * Electrolyzer: one recipe per input fluid, each carrying its own EU/t and
     * tick contract; outputs are named fluids on both tank directions.
     */
    static final class ElectrolyzerAdapter implements NamedAdapter {
        private final Supplier<IElectrolyzerRecipeManager> manager;
        private final double factor;

        ElectrolyzerAdapter(Supplier<IElectrolyzerRecipeManager> manager, double factor) {
            this.manager = manager;
            this.factor = factor;
        }

        @Override
        public String idPrefix() {
            return "ic2_electrolyzer";
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
            IElectrolyzerRecipeManager recipes = manager.get();
            if (recipes == null) return Collections.emptyList();
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            for (Map.Entry<String, IElectrolyzerRecipeManager.ElectrolyzerRecipe> entry
                    : recipes.getRecipeMap().entrySet()) {
                Fluid input = FluidRegistry.getFluid(entry.getKey());
                IElectrolyzerRecipeManager.ElectrolyzerRecipe recipe = entry.getValue();
                if (input == null || recipe == null || recipe.inputAmount <= 0 || recipe.ticksNeeded <= 0) continue;
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
                batch.fluid(new FluidStack(input, recipe.inputAmount));
                boolean anyOutput = false;
                boolean invalidOutput = false;
                for (IElectrolyzerRecipeManager.ElectrolyzerOutput output : recipe.outputs) {
                    if (output == null || output.fluidAmount <= 0 || output.tankDirection == null) {
                        invalidOutput = true;
                        break;
                    }
                    Fluid fluid = FluidRegistry.getFluid(output.fluidName);
                    if (fluid == null) {
                        invalidOutput = true;
                        break;
                    }
                    batch.output(new FluidStack(fluid, output.fluidAmount),
                            electrolyzerOutputPort(output.tankDirection));
                    anyOutput = true;
                }
                if (!anyOutput || invalidOutput) continue;
                batch.extraEnergy(recipe.EUaTick * factor);
                try {
                    result.add(batch.specification(idPrefix() + "_" + result.size(), recipe.ticksNeeded));
                } catch (IllegalArgumentException unsupported) {
                    // One unresolved fluid never blocks the manager.
                }
            }
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * Matter generator: each recipe operation consumes its declared item amount
     * and adds the output points to the scrap buffer. Each point represents one
     * EU of incoming feed plus five EU force-added by the machine; one mB costs
     * {@code 1,000,000 * uuEnergyFactor} stored EU. The frozen contract groups
     * whole amplifier operations until their total stored charge divides evenly
     * into whole mB outputs, preserving the native carry-over instead of dropping
     * excess scrap energy at every synthetic operation.
     */
    static final class MatterAdapter implements NamedAdapter {
        private final Supplier<IMachineRecipeManager<IRecipeInput, Integer, ItemStack>> manager;
        private final Supplier<Fluid> uuMatter;
        private final double uuEnergyFactor;
        private final double factor;

        MatterAdapter(Supplier<IMachineRecipeManager<IRecipeInput, Integer, ItemStack>> manager,
                      Supplier<Fluid> uuMatter, double uuEnergyFactor, double factor) {
            this.manager = manager;
            this.uuMatter = uuMatter;
            this.uuEnergyFactor = uuEnergyFactor;
            this.factor = factor;
        }

        @Override
        public String idPrefix() {
            return "ic2_matter_generator";
        }

        @Override
        public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
            Fluid uu = uuMatter.get();
            IMachineRecipeManager<IRecipeInput, Integer, ItemStack> recipes = manager.get();
            if (uu == null || recipes == null) return Collections.emptyList();
            long uuCost = Math.round(1_000_000D * uuEnergyFactor);
            if (uuCost <= 0L) return Collections.emptyList();
            List<CloudworksRecipeSpec> result = new ArrayList<>();
            int oversized = 0;
            for (MachineRecipe<IRecipeInput, Integer> recipe : recipes.getRecipes()) {
                try {
                    if (recipe == null || recipe.getOutput() == null || recipe.getOutput() <= 0) continue;
                    IRecipeInput input = recipe.getInput();
                    List<ItemStack> variants = safeInputs(input);
                    int inputAmount = safeAmount(input);
                    if (variants == null || variants.isEmpty() || inputAmount <= 0) continue;
                    long pointsPerOperation = recipe.getOutput().longValue();
                    long storedChargePerOperation;
                    storedChargePerOperation = Math.multiplyExact(pointsPerOperation,
                            (long) MATTER_AMPLIFICATION);
                    long divisor = gcd(uuCost, storedChargePerOperation);
                    long operationsPerBatch = uuCost / divisor;
                    long outputMillibuckets = storedChargePerOperation / divisor;
                    if (operationsPerBatch <= 0 || outputMillibuckets <= 0
                            || outputMillibuckets > Integer.MAX_VALUE) continue;
                    if (inputAmount > MATTER_ITEM_PORT_CAPACITY || outputMillibuckets > 8000) {
                        oversized++;
                        continue;
                    }
                    for (ItemStack variant : variants)
                        if (variant != null && !variant.isEmpty())
                            result.add(CloudworksRecipeSpec.builder(idPrefix() + "_" + result.size()).duration(1)
                                    .input(QIOResourceCodecs.item(variant), inputAmount, "item_input")
                                    .unscaledOutput(QIOResourceCodecs.fluid(new FluidStack(uu, 1)), outputMillibuckets, "fluid_output")
                                    .powerDriven(pointsPerOperation * factor, operationsPerBatch)
                                    .extraEnergyPerTick(MATTER_FEED_RATE_EU * factor).build());
                } catch (RuntimeException | LinkageError unsupported) {
                    // One unusable amplifier never blocks the manager.
                }
            }
            if (oversized > 0 && Mekanism.logger != null)
                Mekanism.logger.warn(
                        "Cloudworks skipped {} IndustrialCraft matter amplifier contracts needing more than {} input items",
                        oversized, MATTER_ITEM_PORT_CAPACITY);
            return Collections.unmodifiableList(result);
        }

        private static long gcd(long first, long second) {
            while (second != 0L) {
                long remainder = first % second;
                first = second;
                second = remainder;
            }
            return first;
        }
    }

    /**
     * Reads an IC2 recipe input supplied by a third-party manager or script.
     * Both accessors can execute arbitrary addon code, so callers must treat a
     * failure as an invalid entry rather than aborting the whole freeze.
     */
    private static List<ItemStack> safeInputs(IRecipeInput input) {
        try {
            if (input == null) return null;
            List<ItemStack> inputs = input.getInputs();
            return inputs == null ? null : new ArrayList<>(inputs);
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static int safeAmount(IRecipeInput input) {
        try {
            return input == null ? -1 : input.getAmount();
        } catch (RuntimeException | LinkageError unavailable) {
            return -1;
        }
    }
}
