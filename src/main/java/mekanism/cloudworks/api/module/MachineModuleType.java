package mekanism.cloudworks.api.module;

import java.util.Objects;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import mekanism.cloudworks.api.CloudworksAreaGeometry;
import mekanism.cloudworks.api.environment.EnvironmentAxisRegistry;
import mekanism.cloudworks.api.environment.DimensionAxes;
import mekanism.cloudworks.api.environment.BiomeAxes;
import mekanism.cloudworks.api.environment.WeatherAxes;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.cloudworks.api.recipe.RecipeFreezeContext;

/**
 * Registered module identity, upload binding and lifecycle implementation.
 * Native speed/energy slots retain their Mekanism storage and formula.
 */
public final class MachineModuleType<MODULE extends ICustomMachineModule<MODULE>> {
    public enum Slot { SPEED, ENERGY, CUSTOM }

    public static final ResourceLocation SPEED_ID = new ResourceLocation("mekanism", "speed");
    public static final ResourceLocation ENERGY_ID = new ResourceLocation("mekanism", "energy");
    public static final ResourceLocation LAVA_HEAT_ID = new ResourceLocation("cloudworks", "lava_heat_module");
    public static final ResourceLocation HEIGHT_ID = new ResourceLocation("cloudworks", "height_module");
    /** Built-in Mek node upgrade modules (axis-priority batch 3, numbers doc §4). */
    public static final ResourceLocation MEK_SPEED_MODULE_ID = new ResourceLocation("cloudworks", "mekanism_speed_upgrade");
    public static final ResourceLocation MEK_ENERGY_MODULE_ID = new ResourceLocation("cloudworks", "mekanism_energy_upgrade");

    private static final EmptyModule EMPTY_MODULE = new EmptyModule();

    private final ResourceLocation id;
    private final Slot slot;
    private final int maxPerMachine;
    private final String displayKey;
    private final ItemStack itemTemplate;
    private final MODULE module;
    private final EnumSet<MachineModuleSource> sources;
    private final ModuleEnvironment environment;
    private final ModuleArea area;
    private final List<String> descriptionKeys;
    private final EnumSet<UpgradeAxis> upgradeAxes;
    private final boolean recipeHook;

    private MachineModuleType(ResourceLocation id, Slot slot, int maxPerMachine, String displayKey,
            ItemStack itemTemplate, MODULE module, EnumSet<MachineModuleSource> sources) {
        this(id, slot, maxPerMachine, displayKey, itemTemplate, module, sources, null);
    }
    private MachineModuleType(ResourceLocation id, Slot slot, int maxPerMachine, String displayKey,
            ItemStack itemTemplate, MODULE module, EnumSet<MachineModuleSource> sources, ModuleEnvironment environment) {
        this(id, slot, maxPerMachine, displayKey, itemTemplate, module, sources, environment, null);
    }
    private MachineModuleType(ResourceLocation id, Slot slot, int maxPerMachine, String displayKey,
            ItemStack itemTemplate, MODULE module, EnumSet<MachineModuleSource> sources,
            ModuleEnvironment environment, ModuleArea area) {
        this(id, slot, maxPerMachine, displayKey, itemTemplate, module, sources, environment, area,
                Collections.emptyList());
    }
    private MachineModuleType(ResourceLocation id, Slot slot, int maxPerMachine, String displayKey,
            ItemStack itemTemplate, MODULE module, EnumSet<MachineModuleSource> sources,
            ModuleEnvironment environment, ModuleArea area, List<String> descriptionKeys) {
        this(id, slot, maxPerMachine, displayKey, itemTemplate, module, sources, environment, area,
                descriptionKeys, EnumSet.noneOf(UpgradeAxis.class));
    }
    private MachineModuleType(ResourceLocation id, Slot slot, int maxPerMachine, String displayKey,
            ItemStack itemTemplate, MODULE module, EnumSet<MachineModuleSource> sources,
            ModuleEnvironment environment, ModuleArea area, List<String> descriptionKeys,
            EnumSet<UpgradeAxis> upgradeAxes) {
        this.id = Objects.requireNonNull(id, "id");
        this.slot = Objects.requireNonNull(slot, "slot");
        this.displayKey = Objects.requireNonNull(displayKey, "displayKey");
        if (maxPerMachine < 0) throw new IllegalArgumentException("Negative module limit");
        this.maxPerMachine = maxPerMachine;
        this.itemTemplate = itemTemplate == null ? ItemStack.EMPTY : itemTemplate.copy();
        this.module = Objects.requireNonNull(module, "module");
        this.sources = sources.clone();
        this.environment = environment;
        this.area = area;
        this.descriptionKeys = descriptionKeys == null || descriptionKeys.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(descriptionKeys));
        this.upgradeAxes = upgradeAxes.clone();
        try {
            recipeHook = module.getClass().getMethod("onRecipes", IMachineModule.class, RecipeFreezeContext.class)
                    .getDeclaringClass() != ICustomMachineModule.class;
        } catch (NoSuchMethodException impossible) { throw new IllegalArgumentException("Invalid module implementation", impossible); }
        if (slot != Slot.CUSTOM && maxPerMachine != 0)
            throw new IllegalArgumentException("Native upgrade slots do not use custom module limits");
    }

    public static MachineModuleType<EmptyModule> speed() {
        return nativeType(SPEED_ID, Slot.SPEED, "upgrade.cloudworks.speed");
    }

    public static MachineModuleType<EmptyModule> energy() {
        return nativeType(ENERGY_ID, Slot.ENERGY, "upgrade.cloudworks.energy");
    }

    private static MachineModuleType<EmptyModule> nativeType(ResourceLocation id, Slot slot, String key) {
        return new MachineModuleType<>(id, slot, 0, key, ItemStack.EMPTY, EMPTY_MODULE,
                EnumSet.noneOf(MachineModuleSource.class));
    }

    /** Creates a custom machine module with one immutable lifecycle implementation. */
    public static <M extends ICustomMachineModule<M>> MachineModuleType<M> custom(
            ResourceLocation id, int maxPerMachine, String displayKey, M module) {
        if ("mekanism".equals(id.getNamespace()) || "cloudworks".equals(id.getNamespace()))
            throw new IllegalArgumentException("Reserved module namespace: " + id.getNamespace());
        if (displayKey.isEmpty()) throw new IllegalArgumentException("Empty display key");
        if (id.toString().length() > 128) throw new IllegalArgumentException("Module id too long");
        return new MachineModuleType<>(id, Slot.CUSTOM, maxPerMachine, displayKey,
                ItemStack.EMPTY, module, EnumSet.of(MachineModuleSource.UPGRADE));
    }

    /** Built-in environment module used by the counted upload logistics. */
    public static MachineModuleType<EmptyModule> lavaHeat() {
        return builtInEnvironment(LAVA_HEAT_ID, 7, "upgrade.cloudworks.lava_heat")
                .withEnvironment(new ModuleEnvironment(Collections.emptySet(), 0, 1));
    }

    public static MachineModuleType<EmptyModule> height() {
        return builtInEnvironment(HEIGHT_ID, 256, "upgrade.cloudworks.height")
                .withEnvironment(new ModuleEnvironment(Collections.emptySet(), 1, 0));
    }

    /**
     * Built-in axis-takeover module in the reserved namespace (axis-priority
     * §8.3): no mechanism contribution of its own — the effect flows through
     * the machine's node policy curve using the module's effective count.
     */
    public static MachineModuleType<EmptyModule> builtInAxisModule(ResourceLocation id, int maxPerMachine,
            String displayKey, UpgradeAxis... axes) {
        return new MachineModuleType<>(id, Slot.CUSTOM, maxPerMachine, displayKey, ItemStack.EMPTY,
                EMPTY_MODULE, EnumSet.of(MachineModuleSource.UPGRADE)).withUpgradeAxes(axes);
    }

    /** Core integrations use the same lifecycle contract as addon modules. */
    public static <M extends ICustomMachineModule<M>> MachineModuleType<M> builtInModule(
            ResourceLocation id, int maximum, String key, M behavior) {
        return new MachineModuleType<>(id, Slot.CUSTOM, maximum, key, ItemStack.EMPTY,
                behavior, EnumSet.of(MachineModuleSource.UPGRADE));
    }

    private static MachineModuleType<EmptyModule> builtInEnvironment(ResourceLocation id, int maximum, String key) {
        return new MachineModuleType<>(id, Slot.CUSTOM, maximum, key, ItemStack.EMPTY, EMPTY_MODULE,
                EnumSet.of(MachineModuleSource.UPGRADE, MachineModuleSource.ENVIRONMENT_NODE));
    }

    public MachineModuleType<MODULE> withItem(ItemStack stack) {
        if (slot != Slot.CUSTOM)
            throw new IllegalArgumentException("Native slot items are bound via CloudworksUpgradeItems");
        return new MachineModuleType<>(id, slot, maxPerMachine, displayKey,
                CloudworksUpgradeItems.concrete(stack), module, sources, environment, area, descriptionKeys, upgradeAxes);
    }

    /**
     * Declares which upgrade axes this module takes over from the line effect
     * when legally installed on a node (axis-priority plan §4). Native slot
     * types never take axes over; only custom modules may declare this.
     */
    public MachineModuleType<MODULE> withUpgradeAxes(UpgradeAxis... axes) {
        if (slot != Slot.CUSTOM)
            throw new IllegalArgumentException("Native slot types do not take over upgrade axes");
        EnumSet<UpgradeAxis> copy = EnumSet.noneOf(UpgradeAxis.class);
        if (axes != null) for (UpgradeAxis axis : axes) copy.add(axis);
        return new MachineModuleType<>(id, slot, maxPerMachine, displayKey, itemTemplate, module,
                sources, environment, area, descriptionKeys, copy);
    }

    /** True when this module legally takes over the given axis where installed. */
    public boolean takesOver(UpgradeAxis axis) {
        return slot == Slot.CUSTOM && upgradeAxes.contains(axis);
    }

    /**
     * Attaches client-rendered description lines as translation keys (Mek's
     * module-description pattern): the client localizes each key itself, so no
     * description text ever crosses the network. Untranslated keys are skipped
     * at render time.
     */
    public MachineModuleType<MODULE> withDescription(String... keys) {
        List<String> copy = new ArrayList<>();
        for (String key : keys)
            if (key != null && !key.isEmpty()) copy.add(key);
        return new MachineModuleType<>(id, slot, maxPerMachine, displayKey, itemTemplate, module,
                sources, environment, area, copy, upgradeAxes);
    }

    public MachineModuleType<MODULE> allowedSources(MachineModuleSource... allowed) {
        if (slot != Slot.CUSTOM) throw new IllegalStateException("Native slots do not have module sources");
        EnumSet<MachineModuleSource> copy = EnumSet.noneOf(MachineModuleSource.class);
        if (allowed != null) Collections.addAll(copy, allowed);
        if (copy.isEmpty()) throw new IllegalArgumentException("At least one module source is required");
        return new MachineModuleType<>(id, slot, maxPerMachine, displayKey, itemTemplate, module, copy, environment, area, descriptionKeys, upgradeAxes);
    }
    public MachineModuleType<MODULE> withEnvironment(ModuleEnvironment value) {
        return new MachineModuleType<>(id, slot, maxPerMachine, displayKey, itemTemplate, module, sources,
                Objects.requireNonNull(value), area, descriptionKeys, upgradeAxes);
    }
    public MachineModuleType<MODULE> withArea(ModuleArea value) {
        return new MachineModuleType<>(id, slot, maxPerMachine, displayKey, itemTemplate, module, sources,
                environment, Objects.requireNonNull(value), descriptionKeys, upgradeAxes);
    }
    public ModuleArea area() { return area; }
    /** Client-rendered description translation keys, in display order. */
    public List<String> descriptionKeys() { return descriptionKeys; }
    public ModuleEnvironment environment() { return environment; }
    public static List<MachineModuleType<EmptyModule>> environmentSources() {
        List<MachineModuleType<EmptyModule>> result = new ArrayList<>();
        for (EnvironmentAxis axis : EnvironmentAxisRegistry.all()) {
            ResourceLocation id = axis.id();
            result.add(builtInEnvironment(id, 1, "cloudworks.special." + id.getPath())
                    .allowedSources(MachineModuleSource.ENVIRONMENT_NODE)
                    .withEnvironment(new ModuleEnvironment(Collections.singleton(id), 0, 0))
                    .withArea(new ModuleArea((CloudworksAreaGeometry.NODE_WIDTH + CloudworksAreaGeometry.GRID) * 5 / 2,
                            .5D, ModuleArea.MergeMode.UNION, 0x306E141B, 0xFFE65353)));
        }
        return Collections.unmodifiableList(result);
    }

    public boolean acceptsSource(MachineModuleSource source) {
        return slot != Slot.CUSTOM || sources.contains(source);
    }

    public ResourceLocation id() { return id; }
    public Slot slot() { return slot; }
    public int maxPerMachine() { return maxPerMachine; }
    public String displayKey() { return displayKey; }
    public MODULE module() { return module; }
    public boolean hasRecipeHook() { return recipeHook; }
    public Set<MachineModuleSource> allowedSources() {
        return Collections.unmodifiableSet(sources);
    }
    public ItemStack item() { return itemTemplate.isEmpty() ? ItemStack.EMPTY : itemTemplate.copy(); }

    /** Empty behavior used only for native counted slots and built-in environment logistics. */
    public static final class EmptyModule implements ICustomMachineModule<EmptyModule> {}
}
