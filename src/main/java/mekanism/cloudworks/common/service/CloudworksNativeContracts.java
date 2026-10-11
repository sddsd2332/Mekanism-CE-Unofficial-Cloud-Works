package mekanism.cloudworks.common.service;

import java.util.*;
import java.util.concurrent.Callable;
import mekanism.api.infuse.InfuseObject;
import mekanism.api.infuse.InfuseRegistry;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.CloudworksGenerationContext;
import mekanism.cloudworks.api.CloudworksGenerationPlan;
import mekanism.cloudworks.api.CloudworksMachineAdapter;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.recipe.*;
import mekanism.cloudworks.common.registry.*;
import mekanism.common.InfuseStorage;
import mekanism.common.recipe.RecipeHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Native callbacks freeze on the server thread once per registry generation. */
public final class CloudworksNativeContracts {
    private static final ThreadLocal<Boolean> WORKER = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Snapshot> PINNED = new ThreadLocal<>();
    private static volatile Snapshot current;

    private CloudworksNativeContracts() {}

    public static synchronized void refresh() {
        if (WORKER.get()) throw new IllegalStateException("Native capture requested by Runtime worker");
        long registry = CloudworksRegistries.version();
        int recipes = RecipeHandler.getGlobalRecipeVersion();
        int infuseVersion = InfuseRegistry.getObjectMap().hashCode();
        int[] upgrades = mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects.settings();
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
        CloudworksRegistries.specialSnapshot().forEach((machine, adapter) -> {
            Optional<CloudworksFrozenRecipeView> view = views.computeIfAbsent(adapter,
                    ignored -> CloudworksAsyncRecipeAdmission.captureSpecial(adapter));
            view.ifPresent(frozen -> byMachine.computeIfAbsent(itemKey(
                    machine.displayStack().writeToNBT(new NBTTagCompound())), ignored -> new ArrayList<>())
                    .addAll(frozen.entries()));
        });
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

    public static synchronized void clear() { current = null; }

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
        return snapshot().views.getOrDefault(adapter, Optional.empty());
    }

    public static Optional<CloudworksFrozenRecipeView> special(SpecialMachineAdapter adapter) {
        return snapshot().views.getOrDefault(adapter, Optional.empty());
    }

    public static Infusion infusion(QIOResourceDescriptor resource) {
        if (!QIOResourceCodecs.ITEM_STACK_ID.equals(resource.getCodecId())) return null;
        for (Infusion infusion : snapshot().infusions) if (infusion.matches(resource)) return infusion;
        return null;
    }

    public static void prime(CloudworksResourceSymbols symbols, CloudworksPlayerDataManager.VirtualSnapshot origin) {
        Snapshot snapshot = snapshot();
        mekanism.cloudworks.common.data.CloudworksData cloud = origin.cloud();
        for (mekanism.cloudworks.common.data.CloudworksInventory inventory : Arrays.asList(
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
        snapshot().resources.put(resource.write().toString(), resource);
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
        try { return work.call(); }
        finally {
            WORKER.set(previous);
            if (previousSnapshot == null) PINNED.remove(); else PINNED.set(previousSnapshot);
        }
    }

    public static Map<UUID, CloudworksGenerationPlan> generationPlans(CloudworksPlayerDataManager.VirtualSnapshot origin,
            CloudworksGenerationContext context) {
        if (WORKER.get()) throw new IllegalStateException("Native generator called by Runtime worker");
        Map<UUID, CloudworksGenerationPlan> plans = new HashMap<>();
        int workRate = Math.min(origin.cloud().workRate(),
                mekanism.cloudworks.common.config.CloudworksConfig.settings().maxWorkRate);
        Map<String, Set<Integer>> supported = new HashMap<>();
        for (MachineKey key : CloudworksRegistries.generatorSnapshot().keySet()) {
            NBTTagCompound encoded = key.displayStack().writeToNBT(new NBTTagCompound());
            supported.computeIfAbsent(encoded.getString("id"), ignored -> new HashSet<>())
                    .add((int) encoded.getShort("Damage"));
        }
        origin.visitNodeTags(tag -> {
            NBTTagCompound machine = tag.getCompoundTag("machine");
            if (!tag.getBoolean("active") || !supported.getOrDefault(machine.getString("id"), Collections.emptySet())
                    .contains((int) machine.getShort("Damage"))) return;
            MachineNodeData node = MachineNodeData.read(tag);
            CloudworksEnergyGenerator generator = node.capabilities().generator();
            if (generator != null && generator.enabled(node)) {
                MachineExecutionProfile profile = node.capabilities().profile();
                long units = CloudworksQuantities.multiplyLimit(
                        CloudworksQuantities.multiplyLimit(node.machineCount(), profile == null ? 1 : profile.threads()),
                        workRate);
                if (units > Integer.MAX_VALUE) throw new IllegalArgumentException("Generator unit overflow");
                plans.put(node.nodeId(), generator.freezeGeneration(node, (int) units, context));
            }
        });
        return Collections.unmodifiableMap(plans);
    }

    public static boolean isWorker() { return WORKER.get(); }
    public static int[] upgradeSettings() { return snapshot().upgradeSettings.clone(); }

    public static MachineNodeData machine(NBTTagCompound item) {
        for (NativeMachine template : snapshot().machines.getOrDefault(itemKey(item), Collections.emptyList())) {
            if (template.key.equals(MachineKey.serialized(template.key, item.getCompoundTag("tag"), template.policy)))
                return template.node;
        }
        throw new IllegalArgumentException("Unknown frozen Runtime machine");
    }

    public static java.util.function.Predicate<NBTTagCompound> thermalFuelMatcher() {
        Snapshot frozen = snapshot();
        return item -> {
            for (NativeMachine template : frozen.machines.getOrDefault(itemKey(item), Collections.emptyList())) {
                if (template.thermalFuel && template.key.equals(
                        MachineKey.serialized(template.key, item.getCompoundTag("tag"), template.policy))) return true;
            }
            return false;
        };
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
        final java.util.concurrent.ConcurrentMap<String, QIOResourceDescriptor> resources =
                new java.util.concurrent.ConcurrentHashMap<>();
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
            mekanism.cloudworks.api.CloudworksThermalPlan plan = node.capabilities().thermal();
            thermalFuel = plan != null && (plan.kind == mekanism.cloudworks.api.CloudworksThermalPlan.Kind.FUELWOOD
                    || plan.kind == mekanism.cloudworks.api.CloudworksThermalPlan.Kind.HEAT_GENERATOR)
                    || node.capabilities().special() instanceof mekanism.cloudworks.common.recipe.CloudworksHeatFuelAdapter;
        }
    }
}
