package mekanism.cloudworks.common.service;

import java.util.*;
import java.util.concurrent.Callable;

import mekanism.api.infuse.InfuseObject;
import mekanism.api.infuse.InfuseRegistry;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.*;
import mekanism.cloudworks.api.card.*;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.node.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.transfer.*;
import mekanism.cloudworks.api.work.*;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.recipe.*;
import mekanism.cloudworks.common.registry.*;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import mekanism.common.InfuseStorage;
import mekanism.common.recipe.RecipeHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.CloudworksInventory;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects;
import mekanism.common.Mekanism;

/**
 * Native callbacks freeze on the server thread once per registry generation.
 */
public final class CloudworksNativeContracts {
    private static final ThreadLocal<Boolean> WORKER = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Snapshot> PINNED = new ThreadLocal<>();
    private static volatile Snapshot current;
    private static final Set<String> refusedGenerators = ConcurrentHashMap.newKeySet();

    private CloudworksNativeContracts() {
    }

    public static synchronized void refresh() {
        if (WORKER.get()) throw new IllegalStateException("Native capture requested by Runtime worker");
        long registry = CloudworksRegistries.version();
        int recipes = RecipeHandler.getGlobalRecipeVersion();
        // InfuseRegistry is populated during startup and exposes no mutation
        // counter. Use the map identity and size as a cheap generation marker;
        // hashing every entry on every server tick made refresh O(n) even when
        // the native registry was unchanged.
        Map<ItemStack, InfuseObject> infuseMap = InfuseRegistry.getObjectMap();
        int infuseVersion = 31 * System.identityHashCode(infuseMap) + infuseMap.size();
        int[] upgrades = CloudworksUpgradeEffects.settings();
        if (current != null && current.registry == registry && current.recipes == recipes
                && current.infuseVersion == infuseVersion && Arrays.equals(upgrades, current.upgradeSettings)) return;
        Map<Object, Optional<CloudworksFrozenRecipeView>> views = new IdentityHashMap<>();
        Map<String, List<CloudworksFrozenRecipeView.Entry>> byMachine = new HashMap<>();
        CloudworksMachineAdapters.snapshot().forEach((machine, adapters) -> {
            List<CloudworksFrozenRecipeView.Entry> entries = byMachine.computeIfAbsent(itemKey(
                    machine.displayStack().writeToNBT(new NBTTagCompound())), ignored -> new ArrayList<>());
            for (CloudworksMachineAdapter adapter : adapters) {
                Optional<CloudworksFrozenRecipeView> view = views.computeIfAbsent(adapter,
                        ignored -> CloudworksAsyncRecipeAdmission.capture(adapter));
                view.ifPresent(frozen -> entries.addAll(frozen.entries()));
            }
        });
        for (CloudworksMachineAdapter adapter : CloudworksNodeMechanisms.frozenAdapters()) {
            CloudworksMachineRegistration registration = CloudworksMachineAdapters.registration(adapter);
            Optional<CloudworksFrozenRecipeView> view = CloudworksAsyncRecipeAdmission.peek(adapter);
            views.put(adapter, view);
            view.ifPresent(frozen -> byMachine.computeIfAbsent(
                            itemKey(registration.machine().writeToNBT(new NBTTagCompound())), ignored -> new ArrayList<>())
                    .addAll(frozen.entries()));
        }
        List<Infusion> infusions = new ArrayList<>();
        Map<String, List<NativeMachine>> machines = new HashMap<>();
        for (MachineKey key : CloudworksRegistries.profileSnapshot().keySet()) {
            ItemStack stack = key.displayStack();
            MachineNodeData template = new MachineNodeData(new UUID(0, 0), stack, 0, 0);
            template.capabilities();
            machines.computeIfAbsent(itemKey(stack.writeToNBT(new NBTTagCompound())), ignored -> new ArrayList<>())
                    .add(new NativeMachine(template, key, CloudworksRegistries.identityPolicy(stack)));
        }
        for (Map.Entry<ItemStack, InfuseObject> entry : InfuseRegistry.getObjectMap().entrySet()) {
            InfuseObject object = entry.getValue();
            if (object == null || object.type == null || object.stored <= 0) continue;
            infusions.add(new Infusion(entry.getKey(), object));
        }
        if (registry != CloudworksRegistries.version() || recipes != RecipeHandler.getGlobalRecipeVersion())
            throw new IllegalStateException("Native registry changed during capture");
        current = new Snapshot(registry, recipes, infuseVersion, views, infusions, byMachine, upgrades, machines);
    }

    public static synchronized void clear() {
        current = null;
        refusedGenerators.clear();
    }

    private static Snapshot snapshot() {
        Snapshot snapshot = WORKER.get() ? PINNED.get() : current;
        if (!WORKER.get()) {
            refresh();
            snapshot = current;
        }
        if (snapshot == null) throw new IllegalStateException("Native contracts have not been captured");
        return snapshot;
    }

    public static Optional<CloudworksFrozenRecipeView> view(CloudworksMachineAdapter adapter) {
        Optional<CloudworksFrozenRecipeView> frozen = snapshot().views.get(adapter);
        return frozen != null ? frozen : CloudworksAsyncRecipeAdmission.peek(adapter);
    }

    public static Infusion infusion(QIOResourceDescriptor resource) {
        if (!QIOResourceCodecs.ITEM_STACK_ID.equals(resource.getCodecId())) return null;
        for (Infusion infusion : snapshot().infusions) if (infusion.matches(resource)) return infusion;
        return null;
    }

    public static void prime(CloudworksResourceSymbols symbols, CloudworksPlayerDataManager.VirtualSnapshot origin) {
        prime(symbols, origin, origin.lines());
    }

    /** Capture-scoped decoded lines, owned by the caller until compilation completes. */
    public static void prime(CloudworksResourceSymbols symbols, CloudworksPlayerDataManager.VirtualSnapshot origin,
                             List<ProductionLineData> decodedLines) {
        Snapshot snapshot = snapshot();
        Set<CloudworksFrozenRecipeView> primed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MachineModuleType<?> type : CloudworksMachineModuleRegistry.snapshot().values())
            for (QIOResourceDescriptor output : type.module().outputResources()) remember(symbols, output);
        for (ProductionLineData line : decodedLines)
            for (MachineNodeData node : line.machines())
                for (mekanism.cloudworks.common.data.CloudworksResourceCredit credit : node.processCredits().values()) remember(symbols, credit.resource);
        for (ProductionLineData line : decodedLines)
            for (MachineNodeData node : line.machines())
                for (CloudworksMachineAdapter adapter : node.capabilities().allAdapters())
                    CloudworksAsyncRecipeAdmission.peek(adapter).ifPresent(view -> {
                        if (primed.add(view))
                            for (QIOResourceDescriptor resource : snapshot.primeResources.computeIfAbsent(view,
                                    CloudworksNativeContracts::primeResources)) remember(symbols, resource);
                    });
        CloudworksData cloud = origin.cloud();
        for (CloudworksInventory inventory : Arrays.asList(
                cloud.receiving(), cloud.transfer(), cloud.sending()))
            inventory.snapshot().keySet().forEach(symbols::id);
        origin.visitDescriptorTags(tag -> remember(symbols, QIOResourceDescriptor.read(tag)));
        Set<String> selected = new HashSet<>();
        origin.visitNodeTags(tag -> {
            selected.add(itemKey(tag.getCompoundTag("machine")));
            for (String name : new String[]{"inputs", "outputs", "templates"}) {
                NBTTagList resources = tag.getTagList(name, 10);
                for (int index = 0; index < resources.tagCount(); index++)
                    symbols.id(QIOResourceDescriptor.read(resources.getCompoundTagAt(index).getCompoundTag("descriptor")));
            }
        });
        for (String machine : selected)
            for (CloudworksFrozenRecipeView.Entry entry : snapshot.byMachine.getOrDefault(machine, Collections.emptyList())) {
                entry.outputTemplate().forEach(output -> remember(symbols, output.descriptor()));
                entry.chanceOutputTemplate().forEach(output -> remember(symbols, output.descriptor()));
                entry.choiceOutputTemplate().forEach(output -> remember(symbols, output.descriptor()));
            }
        for (Infusion infusion : snapshot.infusions) remember(symbols, infusion.output);
        for (QIOResourceDescriptor resource : symbols.descriptors()) remember(symbols, resource);
    }

    private static void remember(CloudworksResourceSymbols symbols, QIOResourceDescriptor resource) {
        symbols.id(resource);
        Snapshot snapshot = snapshot();
        // Descriptor.toString() is its constructor-frozen serialization identity;
        // do not use codec semantic equality, which can ignore persistent metadata.
        snapshot.resourceKeys.computeIfAbsent(resource.toString(), ignored -> {
            String serialized = resource.write().toString();
            snapshot.resources.put(serialized, resource);
            return serialized;
        });
    }

    private static List<QIOResourceDescriptor> primeResources(CloudworksFrozenRecipeView view) {
        Map<String, QIOResourceDescriptor> resources = new LinkedHashMap<>();
        for (CloudworksFrozenRecipeView.Entry entry : view.entries()) {
            if (entry.definition() != null && entry.definition().creditResource() != null) {
                QIOResourceDescriptor resource = entry.definition().creditResource();
                resources.put(resource.toString(), resource);
            }
            entry.outputTemplate().forEach(output -> resources.put(output.descriptor().toString(), output.descriptor()));
            entry.chanceOutputTemplate().forEach(output -> resources.put(output.descriptor().toString(), output.descriptor()));
            entry.choiceOutputTemplate().forEach(output -> resources.put(output.descriptor().toString(), output.descriptor()));
        }
        return Collections.unmodifiableList(new ArrayList<>(resources.values()));
    }

    public static QIOResourceDescriptor resource(NBTTagCompound tag) {
        QIOResourceDescriptor resource = snapshot().resources.get(tag.toString());
        if (resource == null) throw new IllegalArgumentException("Resource was not pre-frozen");
        return resource;
    }

    private static String itemKey(NBTTagCompound item) {
        return item.getString("id") + ":" + item.getShort("Damage");
    }

    public static <T> T worker(Callable<T> work) throws Exception {
        boolean previous = WORKER.get();
        Snapshot previousSnapshot = PINNED.get();
        PINNED.set(current);
        WORKER.set(true);
        try {
            return work.call();
        } finally {
            WORKER.set(previous);
            if (previousSnapshot == null) PINNED.remove();
            else PINNED.set(previousSnapshot);
        }
    }

    public static Map<UUID, CloudworksGenerationPlan> generationPlans(CloudworksPlayerDataManager.VirtualSnapshot origin,
                                                                      CloudworksGenerationContext context) {
        return generationPlans(origin, context, origin.lines());
    }

    public static Map<UUID, CloudworksGenerationPlan> generationPlans(CloudworksPlayerDataManager.VirtualSnapshot origin,
            CloudworksGenerationContext context, List<ProductionLineData> decodedLines) {
        if (WORKER.get()) throw new IllegalStateException("Native generator called by Runtime worker");
        Map<UUID, CloudworksGenerationPlan> plans = new HashMap<>();
        int workRate = Math.min(origin.cloud().workRate(),
                CloudworksConfig.settings().maxWorkRate);
        for (ProductionLineData line : decodedLines) for (MachineNodeData node : line.machines()) {
            if (!node.active()) continue;
            CloudworksEnergyGenerator generator = node.capabilities().generator();
            if (generator == null && node.capabilities().branches().stream().noneMatch(branch -> branch.generator != null))
                continue;
            CloudworksGenerationContext environment = CloudworksNodeEnvironment.generation(line, node, context);
            if (generator != null && node.active()) {
                plans.put(node.nodeId(), freezeGeneration(node, generator, node.capabilities().profile(), workRate, environment));
            }
            for (CloudworksNodeMechanisms.Branch branch : node.capabilities().branches())
                if (branch.generator != null && node.active())
                    plans.put(CloudworksNodeMechanisms.branchId(node.nodeId(), branch.id),
                            freezeGeneration(node, branch.generator, branch.profile, workRate, environment));
        }
        return Collections.unmodifiableMap(plans);
    }

    private static CloudworksGenerationPlan freezeGeneration(MachineNodeData node, CloudworksEnergyGenerator generator,
                                                             MachineExecutionProfile profile, int workRate, CloudworksGenerationContext context) {
        try {
            long units = CloudworksQuantities.multiplyLimit(
                    CloudworksQuantities.multiplyLimit(node.machineCount(), profile == null ? 1 : profile.threads()), workRate);
            if (units > Integer.MAX_VALUE) throw new IllegalArgumentException("Generator unit overflow");
            CloudworksGenerationPlan plan = Objects.requireNonNull(generator.freeze(new CloudworksMachineContext(
                            node.nodeId(), node.machine(), node.machineCount(), node.active(),
                            node.recipeType(), amounts(node.inputs()), amounts(node.outputs()), amounts(node.templates())), (int) units,
                    context));
            for (CloudworksGenerationPlan.ResourceOutput output : plan.resourceOutputs()) {
                boolean port = false;
                for (MachineSlotLayout.Slot slot : node.capabilities().slots().slots())
                    port |= slot.role() == MachineSlotLayout.PortRole.OUTPUT
                            && slot.codecId().equals(output.resource().getCodecId())
                            && (output.port().isEmpty() || slot.id().equals(output.port()));
                if (!port) throw new IllegalArgumentException("Generation resource lacks an output port");
            }
            return plan;
        } catch (RuntimeException | LinkageError failure) {
            String key = generator.getClass().getName() + "|" + failure.getClass().getName() + "|" + failure.getMessage();
            if (refusedGenerators.add(key))
                Mekanism.logger.error("Cloudworks generation contract refused for node " + node.nodeId(), failure);
            return CloudworksGenerationPlan.unsupported();
        }
    }

    public static void primeGeneration(CloudworksResourceSymbols symbols, Map<UUID, CloudworksGenerationPlan> plans) {
        if (WORKER.get()) throw new IllegalStateException("Generation resources must prime before worker dispatch");
        for (CloudworksGenerationPlan plan : plans.values())
            for (CloudworksGenerationPlan.ResourceOutput output : plan.resourceOutputs())
                remember(symbols, output.resource());
    }

    public static boolean isWorker() {
        return WORKER.get();
    }

    private static Map<QIOResourceDescriptor, Long> amounts(List<CloudworksResourceStack> values) {
        Map<QIOResourceDescriptor, Long> result = new LinkedHashMap<>();
        for (CloudworksResourceStack value : values)
            result.merge(value.descriptor(), value.amount(), Math::addExact);
        return result;
    }

    public static int[] upgradeSettings() {
        return snapshot().upgradeSettings.clone();
    }

    public static MachineNodeData machine(NBTTagCompound item) {
        for (NativeMachine template : snapshot().machines.getOrDefault(itemKey(item), Collections.emptyList())) {
            if (template.key.equals(MachineKey.serialized(template.key, item.getCompoundTag("tag"), template.policy)))
                return template.node;
        }
        throw new IllegalArgumentException("Unknown frozen Runtime machine");
    }

    public static Predicate<NBTTagCompound> thermalFuelMatcher() {
        Snapshot frozen = snapshot();
        return item -> {
            for (NativeMachine template : frozen.machines.getOrDefault(itemKey(item), Collections.emptyList())) {
                if (template.thermalFuel && template.key.equals(
                        MachineKey.serialized(template.key, item.getCompoundTag("tag"), template.policy))) return true;
            }
            return false;
        };
    }

    public static boolean moduleHeatFuel(NBTTagCompound node) {
        MachineNodeData decoded = MachineNodeData.read(node);
        if (heatFuel(decoded.capabilities().thermal())) return true;
        for (CloudworksNodeMechanisms.Branch branch :
                decoded.capabilities().branches())
            if (heatFuel(branch.thermal)) return true;
        return false;
    }

    private static boolean moduleGenerator(NBTTagCompound node) {
        MachineNodeData decoded = MachineNodeData.read(node);
        if (decoded.capabilities().generator() != null) return true;
        for (CloudworksNodeMechanisms.Branch branch :
                decoded.capabilities().branches())
            if (branch.generator != null) return true;
        return false;
    }

    private static boolean heatFuel(CloudworksThermalPlan plan) {
        return plan != null && (plan.kind == CloudworksThermalPlan.Kind.FUELWOOD
                || plan.kind == CloudworksThermalPlan.Kind.HEAT_GENERATOR);
    }

    public static final class Infusion {
        private final NBTTagCompound item, itemTag;
        private final boolean subtype, hasTag;
        public final QIOResourceDescriptor output;
        public final int amount;

        Infusion(ItemStack stack, InfuseObject object) {
            item = stack.writeToNBT(new NBTTagCompound());
            subtype = stack.getHasSubtypes();
            hasTag = stack.hasTagCompound();
            itemTag = hasTag ? stack.getTagCompound().copy() : null;
            output = CloudworksInfuseResourceCodec.descriptor(new InfuseStorage(object.type, 1));
            amount = object.stored;
        }

        boolean matches(QIOResourceDescriptor descriptor) {
            NBTTagCompound candidate = descriptor.getPayload();
            return item.getString("id").equals(candidate.getString("id"))
                    && (!subtype || item.getShort("Damage") == candidate.getShort("Damage"))
                    && hasTag == candidate.hasKey("tag", 10)
                    && (!hasTag || itemTag.equals(candidate.getCompoundTag("tag")));
        }
    }

    private static final class Snapshot {
        final long registry;
        final int recipes, infuseVersion;
        final Map<Object, Optional<CloudworksFrozenRecipeView>> views;
        final List<Infusion> infusions;
        final int[] upgradeSettings;
        final Map<String, List<CloudworksFrozenRecipeView.Entry>> byMachine;
        final Map<String, List<NativeMachine>> machines;
        final ConcurrentMap<String, QIOResourceDescriptor> resources =
                new ConcurrentHashMap<>();
        final ConcurrentMap<String, String> resourceKeys = new ConcurrentHashMap<>();
        final ConcurrentMap<CloudworksFrozenRecipeView, List<QIOResourceDescriptor>> primeResources = new ConcurrentHashMap<>();

        Snapshot(long registry, int recipes, int infuseVersion,
                 Map<Object, Optional<CloudworksFrozenRecipeView>> views, List<Infusion> infusions,
                 Map<String, List<CloudworksFrozenRecipeView.Entry>> byMachine, int[] upgradeSettings,
                 Map<String, List<NativeMachine>> machines) {
            this.registry = registry;
            this.recipes = recipes;
            this.infuseVersion = infuseVersion;
            this.views = Collections.unmodifiableMap(views);
            this.infusions = Collections.unmodifiableList(infusions);
            this.upgradeSettings = upgradeSettings;
            Map<String, List<CloudworksFrozenRecipeView.Entry>> copy = new HashMap<>();
            byMachine.forEach((key, entries) -> copy.put(key, Collections.unmodifiableList(new ArrayList<>(entries))));
            this.byMachine = Collections.unmodifiableMap(copy);
            Map<String, List<NativeMachine>> machineCopy = new HashMap<>();
            machines.forEach((key, entries) -> machineCopy.put(key, Collections.unmodifiableList(entries)));
            this.machines = Collections.unmodifiableMap(machineCopy);
        }
    }

    private static final class NativeMachine {
        final MachineNodeData node;
        final MachineKey key;
        final MachineIdentityPolicy policy;
        final boolean thermalFuel;

        NativeMachine(MachineNodeData node, MachineKey key, MachineIdentityPolicy policy) {
            this.node = node;
            this.key = key;
            this.policy = policy;
            CloudworksThermalPlan plan = node.capabilities().thermal();
            thermalFuel = plan != null && (plan.kind == CloudworksThermalPlan.Kind.FUELWOOD
                    || plan.kind == CloudworksThermalPlan.Kind.HEAT_GENERATOR);
        }
    }
}
