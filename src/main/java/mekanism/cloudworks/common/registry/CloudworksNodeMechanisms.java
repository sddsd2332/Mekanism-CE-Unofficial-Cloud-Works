package mekanism.cloudworks.common.registry;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import mekanism.cloudworks.api.*;
import mekanism.cloudworks.api.card.*;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.node.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.transfer.*;
import mekanism.cloudworks.api.work.*;
import mekanism.cloudworks.common.data.*;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.recipe.*;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import net.minecraft.util.ResourceLocation;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Lifecycle-frozen fragments; composition never invokes addon callbacks.
 */
public final class CloudworksNodeMechanisms {
    /** Per-node mechanism-branch ceiling (own constant; formerly borrowed from UpgradeSet). */
    public static final int BRANCH_LIMIT = 64;
    public static final int MAX_SHAPE_CHARS = BRANCH_LIMIT * 129;
    private static volatile Map<MachineKey, Map<String, Map<ResourceLocation, Fragment>>> fragments = Collections.emptyMap();
    private static final Map<MachineKey, Map<String, Map<String, Variant>>> composed = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Variant>> installedVariants = new ConcurrentHashMap<>();
    private static volatile long generation;

    private CloudworksNodeMechanisms() {
    }

    public static long version() {
        return generation;
    }

    public static synchronized void freeze() {
        if (CloudworksNativeContracts.isWorker())
            throw new IllegalStateException("Mechanisms cannot freeze on workers");
        Object adapters = CloudworksMachineAdapters.checkpoint();
        Object admission = CloudworksAsyncRecipeAdmission.checkpoint();
        Map<MachineKey, Map<String, Map<ResourceLocation, Fragment>>> next = new LinkedHashMap<>();
        try {
            for (Map.Entry<MachineKey, MachineExecutionProfile> machine : CloudworksRegistries.profileSnapshot().entrySet()) {
                Set<String> modes = new LinkedHashSet<>();
                modes.add("");
                for (MachineRecipeTypeEntry mode : CloudworksRegistries.recipeTypes(machine.getKey()))
                    modes.add(mode.typeId());
                Map<String, Map<ResourceLocation, Fragment>> byMode = new LinkedHashMap<>();
                for (String mode : modes) {
                    Map<ResourceLocation, Fragment> byUpgrade = new LinkedHashMap<>();
                    for (ResourceLocation id : machine.getValue().upgradePolicy().customLimits().keySet()) {
                        MachineModuleType type = CloudworksMachineModuleRegistry.type(id);
                        if (type == null)
                            throw new IllegalArgumentException("Unregistered machine upgrade type: " + id);
                        if (machine.getValue().upgradePolicy().maxCustom(id) == 0) continue;
                        try {
                            Fragment fragment = freeze(machine.getKey(), mode, machine.getValue(), type);
                            if (fragment == null) continue;
                            if (fragment.definition.required() > machine.getValue().upgradePolicy().maxCustom(id))
                                throw new IllegalArgumentException("Module activation exceeds machine acceptance: " + id);
                            compose(machine.getKey(), mode, Collections.singletonList(fragment));
                            byUpgrade.put(id, fragment);
                        } catch (RuntimeException | LinkageError failure) {
                            throw new IllegalArgumentException("Cannot freeze mechanism " + id + " for "
                                    + machine.getKey().displayStack() + " mode " + mode, failure);
                        }
                    }
                    byMode.put(mode, Collections.unmodifiableMap(byUpgrade));
                }
                next.put(machine.getKey(), Collections.unmodifiableMap(byMode));
            }
        } catch (RuntimeException | LinkageError failure) {
            CloudworksMachineAdapters.restore(adapters);
            CloudworksAsyncRecipeAdmission.restore(admission);
            throw failure;
        }
        fragments = Collections.unmodifiableMap(next);
        composed.clear();
        installedVariants.clear();
        generation++;
        CloudworksRegistries.adapterRegistered();
    }

    private static Fragment freeze(MachineKey key, String mode, MachineExecutionProfile profile, MachineModuleType type) {
        return freeze(key, mode, profile, type, 1L, Collections.singletonMap(type.id(), 1L));
    }

    private static Fragment freeze(MachineKey key, String mode, MachineExecutionProfile profile, MachineModuleType type,
                                   long machines, Map<ResourceLocation, Long> installed) {
        if (!type.hasRecipeHook()) return null;
        MachineSlotLayout baseSlots = CloudworksRegistries.slots(key, mode);
        List<CloudworksRecipeSpec> definitions = new ArrayList<>();
        for (CloudworksMachineAdapter adapter : adapters(key, mode)) {
            CloudworksFrozenRecipeView view = CloudworksAsyncRecipeAdmission.capture(adapter)
                    .orElseThrow(() -> new IllegalArgumentException("Base recipes cannot freeze for " + type.id()));
            for (CloudworksFrozenRecipeView.Entry recipe : view.entries()) {
                if (recipe.definition() == null) throw new IllegalArgumentException("Missing public recipe contract");
                definitions.add(recipe.definition());
            }
        }
        MachineExecutionProfile effective = profile;
        CloudworksMachineRegistration registration = new CloudworksMachineRegistration(key.displayStack(), effective,
                CloudworksRegistries.identityPolicy(key.displayStack()), baseSlots, mode);
        RecipeContribution declaration = definition(key, mode, type.id());
        long units = declaration != null && declaration.installation() == RecipeContribution.Installation.PER_NODE
                ? 1 : Math.max(1L, machines);
        int effectiveCount = (int) Math.min(profile.upgradePolicy().maxCustom(type.id()),
                installed.getOrDefault(type.id(), 0L) / units);
        MachineModuleHandle handle = new MachineModuleHandle(type,
                (int) Math.min(Integer.MAX_VALUE, installed.getOrDefault(type.id(), 0L)), effectiveCount, MachineModuleSource.UPGRADE,
                null, null, key.displayStack(), machines, "", installed,
                new CloudworksGenerationContext(0, 1, 0, false));
        RecipeContribution contribution = (RecipeContribution) type.module().onRecipes(handle,
                new RecipeFreezeContext(registration, Collections.unmodifiableList(definitions), baseSlots));
        if (contribution == null) return null;
        MachineSlotLayout slots = merge(baseSlots, contribution.ports());
        if (contribution.thermal() != null) slots = CloudworksThermalPlan.ports(slots).frozenCopy();
        if (contribution.profile() != null) {
            effective = contribution.profile().inheritUpgradePolicies(profile);
            registration = new CloudworksMachineRegistration(key.displayStack(), effective,
                    CloudworksRegistries.identityPolicy(key.displayStack()), slots, mode);
        }
        List<CloudworksRecipeSpec> transformed = contribution.recipes();
        if (transformed == null || transformed.contains(null))
            throw new IllegalArgumentException("Invalid mechanism recipes");
        List<CloudworksRecipeSpec> frozen = Collections.unmodifiableList(new ArrayList<>(transformed));
        CloudworksMachineAdapter derived = ignored -> frozen;
        CloudworksMachineAdapters.registerDerived(derived, registration);
        CloudworksAsyncRecipeAdmission.admitDerived(derived, registration);
        CloudworksApiRegistration.validate(slots, derived);
        return new Fragment(type.id(), contribution, derived, effective);
    }

    private static List<CloudworksMachineAdapter> adapters(MachineKey key, String mode) {
        if (mode.isEmpty()) return CloudworksMachineAdapters.getAutomatic(key);
        MachineRecipeTypeEntry selected = CloudworksRegistries.recipeType(key, mode);
        return selected == null ? Collections.emptyList() : Collections.singletonList(selected.adapter());
    }

    public static Variant resolve(MachineKey key, String mode, long machines, Map<ResourceLocation, Long> installed) {
        if (installed.isEmpty()) return null;
        if (machines == 1L && installed.size() == 1 && installed.values().iterator().next() == 1L)
            return resolveDeclared(key, mode, machines, installed);
        String cacheKey = key.displayStack().writeToNBT(new NBTTagCompound()).toString()
                + "|" + mode + "|" + machines + "|" + new TreeMap<>(installed);
        Optional<Variant> cached = installedVariants.get(cacheKey);
        if (cached != null) return cached.orElse(null);
        if (CloudworksNativeContracts.isWorker())
            throw new IllegalStateException("Machine module recipe variant was not frozen before worker dispatch");
        List<Fragment> selected = new ArrayList<>();
        MachineExecutionProfile profile = CloudworksRegistries.profile(key);
        if (profile == null) return null;
        Set<String> groups = new HashSet<>();
        List<ResourceLocation> ids = new ArrayList<>(installed.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation id : ids) {
            MachineModuleType type = CloudworksMachineModuleRegistry.type(id);
            if (type == null || installed.get(id) <= 0 || profile.upgradePolicy().maxCustom(id) <= 0) continue;
            RecipeContribution declared = definition(key, mode, id);
            if (declared != null) {
                if (!declared.exclusiveGroup().isEmpty() && !groups.add(declared.exclusiveGroup())) throw conflict();
                if (!declared.active(installed.get(id), machines)) continue;
            }
            Fragment fragment = freeze(key, mode, profile, type, machines, installed);
            if (fragment != null && fragment.definition.active(installed.get(id), machines)) selected.add(fragment);
        }
        Variant result = selected.isEmpty() ? null : compose(key, mode, selected);
        installedVariants.put(cacheKey, Optional.ofNullable(result));
        return result;
    }

    private static Variant resolveDeclared(MachineKey key, String mode, long machines, Map<ResourceLocation, Long> installed) {
        List<ResourceLocation> active = new ArrayList<>();
        Map<String, ResourceLocation> exclusive = new HashMap<>();
        MachineExecutionProfile profile = CloudworksRegistries.profile(key);
        for (Map.Entry<ResourceLocation, Long> entry : installed.entrySet()) {
            MachineModuleType type = CloudworksMachineModuleRegistry.type(entry.getKey());
            Fragment fragment = frozenFragment(key, mode, entry.getKey());
            if (entry.getValue() <= 0 || type == null || fragment == null
                    || profile == null || profile.upgradePolicy().maxCustom(type.id()) <= 0) continue;
            RecipeContribution definition = fragment.definition;
            String group = definition.exclusiveGroup();
            if (!group.isEmpty() && exclusive.put(group, type.id()) != null) throw conflict();
            if (definition.active(entry.getValue(), machines)) active.add(type.id());
        }
        active.sort(Comparator.comparing(ResourceLocation::toString));
        return active.isEmpty() ? null : resolve(key, mode, active);
    }

    private static Variant resolve(MachineKey key, String mode, List<ResourceLocation> active) {
        String shape = join(active);
        return composed.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(mode, ignored -> new ConcurrentHashMap<>()).computeIfAbsent(shape, ignored -> {
                    List<Fragment> selected = new ArrayList<>();
                    for (ResourceLocation id : active) {
                        Fragment fragment = fragments.getOrDefault(key, Collections.emptyMap())
                                .getOrDefault(mode, Collections.emptyMap()).get(id);
                        if (fragment == null)
                            throw new IllegalArgumentException("cloudworks.error.node_mechanism_unavailable");
                        selected.add(fragment);
                    }
                    return compose(key, mode, selected);
                });
    }

    private static Fragment frozenFragment(MachineKey key, String mode, ResourceLocation id) {
        return fragments.getOrDefault(key, Collections.emptyMap())
                .getOrDefault(mode, Collections.emptyMap()).get(id);
    }

    public static RecipeContribution definition(MachineKey key, String mode, ResourceLocation id) {
        Fragment fragment = frozenFragment(key, mode, id);
        return fragment == null ? null : fragment.definition;
    }

    /**
     * Frozen recipe/port contribution for projections; null for modules without recipe behavior.
     */
    public static RecipeContribution definition(ResourceLocation id) {
        for (Map<String, Map<ResourceLocation, Fragment>> byMode : fragments.values())
            for (Map<ResourceLocation, Fragment> byId : byMode.values()) {
                Fragment fragment = byId.get(id);
                if (fragment != null) return fragment.definition;
            }
        return null;
    }

    private static Variant compose(MachineKey key, String mode, List<Fragment> selected) {
        MachineSlotLayout slots = CloudworksRegistries.slots(key, mode);
        MachineExecutionProfile profile = CloudworksRegistries.profile(key);
        CloudworksEnergyGenerator generator = mode.isEmpty() ? CloudworksRegistries.energyGenerator(key) : null;
        MachineRecipeTypeEntry selectedMode = mode.isEmpty() ? null : CloudworksRegistries.recipeType(key, mode);
        CloudworksThermalPlan thermal = mode.isEmpty() ? CloudworksRegistries.thermal(key)
                : selectedMode == null ? null : selectedMode.adapter().thermalPlan();
        List<CloudworksMachineAdapter> base = adapters(key, mode), added = new ArrayList<>();
        List<Branch> branches = new ArrayList<>();
        boolean replaced = false, generationChanged = false, thermalChanged = false, profileChanged = false;
        List<ResourceLocation> ids = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        for (Fragment fragment : selected) {
            RecipeContribution definition = fragment.definition;
            if (!definition.exclusiveGroup().isEmpty() && !groups.add(definition.exclusiveGroup())) throw conflict();
            ids.add(fragment.id);
            slots = merge(slots, definition.ports());
            if (definition.mode() == RecipeContribution.RecipeMode.PARALLEL) {
                CloudworksEnergyGenerator branchGenerator = definition.overridesGenerator() ? definition.generator() : null;
                if (!definition.overridesGenerator() && definition.generationTransformer() != null && mode.isEmpty())
                    branchGenerator = CloudworksRegistries.energyGenerator(key);
                branches.add(new Branch(fragment.id.toString(), fragment.profile, Collections.singletonList(fragment.adapter),
                        wrap(branchGenerator, definition.generationTransformer()), definition.thermal()));
                continue;
            }
            if (definition.mode() == RecipeContribution.RecipeMode.REPLACE) {
                if (replaced) throw conflict();
                replaced = true;
                base = Collections.singletonList(fragment.adapter);
            } else added.add(fragment.adapter);
            if (definition.profile() != null) {
                if (profileChanged) throw conflict();
                profile = fragment.profile;
                profileChanged = true;
            }
            if (definition.overridesGenerator() || definition.generationTransformer() != null) {
                if (generationChanged) throw conflict();
                generator = wrap(definition.overridesGenerator() ? definition.generator() : generator,
                        definition.generationTransformer());
                generationChanged = true;
            }
            if (definition.overridesThermal()) {
                if (thermalChanged) throw conflict();
                thermal = definition.thermal();
                thermalChanged = true;
            }
        }
        added.addAll(base);
        int thermalContracts = thermal == null ? 0 : 1;
        for (Branch branch : branches) {
            if (branch.thermal != null) thermalContracts++;
            validateFamily(branch.adapters, branch.generator, branch.thermal);
        }
        if (thermalContracts > 1) throw conflict();
        if (thermalContracts > 0) slots = CloudworksThermalPlan.ports(slots).frozenCopy();
        else if (thermalChanged) slots = withoutThermalPorts(slots);
        validateFamily(added, generator, thermal);
        return new Variant(join(ids), slots, added, profile, generator, thermal, branches);
    }

    private static CloudworksEnergyGenerator wrap(CloudworksEnergyGenerator generator,
                                                  RecipeContribution.GenerationTransformer transformer) {
        if (transformer == null) return generator;
        return (machine, operations, environment) -> Objects.requireNonNull(transformer.transform(
                generator == null ? CloudworksGenerationPlan.constant(0) : generator.freeze(machine, operations, environment),
                machine, operations, environment), "Null transformed generation plan");
    }

    private static void validateFamily(List<CloudworksMachineAdapter> adapters,
                                       CloudworksEnergyGenerator generator, CloudworksThermalPlan thermal) {
        boolean fuel = generator != null, processing = false, decay = false;
        for (CloudworksMachineAdapter adapter : adapters)
            for (CloudworksFrozenRecipeView.Entry recipe : CloudworksAsyncRecipeAdmission.peek(adapter)
                    .orElseThrow(() -> new IllegalArgumentException("Unfrozen mechanism contract")).entries()) {
                fuel |= recipe.energyPerOperation() > 0;
                processing |= recipe.energyPerOperation() == 0 && recipe.decayAmount() < 0;
                decay |= recipe.decayAmount() >= 0;
            }
        if (thermal == null && (fuel && processing || decay && (fuel || processing)))
            throw new IllegalArgumentException("Mixed execution families require independent PARALLEL branches");
    }

    public static MachineSlotLayout slots(CloudworksLineDraft.Machine machine) {
        if (mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.isController(machine.type))
            return mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.slots(machine.fusionInjectionRate);
        MachineKey key = CloudworksRegistries.identityKey(machine.type);
        // Use the same machine/mode/count-specific composition as execution.
        // A type-wide definition is only catalogue metadata, never a node layout.
        if (machine.waiting != null && !machine.waitingMechanism.isEmpty())
            return preview(key, machine.recipeType, machine.waitingMechanism).slots;
        Variant variant = resolve(key, machine.recipeType, machine.count, assignments(machine));
        return variant == null ? CloudworksRegistries.slots(key, machine.recipeType) : variant.slots;
    }

    private static Map<ResourceLocation, Long> assignments(CloudworksLineDraft.Machine machine) {
        Map<ResourceLocation, Long> result = new LinkedHashMap<>();
        machine.assignedCustom.forEach((id, count) -> result.put(new ResourceLocation(id), count));
        return result;
    }

    public static String activeId(CloudworksLineDraft.Machine machine) {
        Variant variant = resolve(CloudworksRegistries.identityKey(machine.type), machine.recipeType,
                machine.count, assignments(machine));
        return variant == null ? "" : variant.id;
    }

    public static Variant preview(MachineKey key, String mode, String id) {
        List<ResourceLocation> active = new ArrayList<>();
        for (String value : split(id)) active.add(new ResourceLocation(value));
        if (active.isEmpty()) throw new IllegalArgumentException("cloudworks.error.node_mechanism_unavailable");
        return resolve(key, mode, active);
    }

    public static List<String> split(String shape) {
        if (shape == null || shape.length() > MAX_SHAPE_CHARS) throw conflict();
        if (shape.isEmpty()) return Collections.emptyList();
        List<String> ids = Arrays.asList(shape.split("\\+", -1));
        if (ids.size() > BRANCH_LIMIT) throw conflict();
        String previous = "";
        for (String id : ids) {
            if (id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || previous.compareTo(id) >= 0)
                throw conflict();
            previous = id;
        }
        return ids;
    }

    private static String join(List<ResourceLocation> ids) {
        List<String> values = new ArrayList<>();
        ids.forEach(id -> values.add(id.toString()));
        return String.join("+", values);
    }

    public static UUID branchId(UUID node, String branch) {
        return UUID.nameUUIDFromBytes((node + "|" + branch).getBytes(StandardCharsets.UTF_8));
    }

    public static MachineSlotLayout merge(MachineSlotLayout base, MachineSlotLayout extra) {
        MachineSlotLayout result = new MachineSlotLayout();
        if (base != null) for (MachineSlotLayout.Slot slot : base.slots()) add(result, slot);
        if (extra != null) for (MachineSlotLayout.Slot slot : extra.slots()) {
            MachineSlotLayout.Slot existing = result.find(slot.id());
            if (existing == null) add(result, slot);
            else if (existing.role() != slot.role() || !existing.codecId().equals(slot.codecId())
                    || existing.multipleConnections() != slot.multipleConnections()
                    || existing.recipeFilteredInput() != slot.recipeFilteredInput())
                throw conflict();
            else if (slot.capacity() > existing.capacity()) {
                result = replaceCapacity(result, existing, slot.capacity());
            }
        }
        return result.frozenCopy();
    }

    private static MachineSlotLayout replaceCapacity(MachineSlotLayout source,
                                                     MachineSlotLayout.Slot replaced, int capacity) {
        MachineSlotLayout result = new MachineSlotLayout();
        for (MachineSlotLayout.Slot slot : source.slots()) {
            if (slot.id().equals(replaced.id())) {
                result.add(slot.id(), slot.codecId(), slot.role(), capacity, slot.multipleConnections(), slot.recipeFilteredInput());
            } else {
                add(result, slot);
            }
        }
        return result;
    }

    private static MachineSlotLayout withoutThermalPorts(MachineSlotLayout slots) {
        MachineSlotLayout result = new MachineSlotLayout();
        for (MachineSlotLayout.Slot slot : slots.slots())
            if (!slot.codecId().equals(CloudworksThermalPlan.HEAT)) add(result, slot);
        return result.frozenCopy();
    }

    public static MachineSlotLayout executionSlots(MachineNodeData node) {
        MachineSlotLayout effective = node.capabilities().slots();
        if (!node.hasOperations()) return effective;
        Map<String, MachineSlotLayout.Slot> ports = new LinkedHashMap<>();
        MachineSlotLayout base = CloudworksRegistries.slots(node.machine());
        if (base != null) for (MachineSlotLayout.Slot port : base.slots()) ports.put(port.id(), port);
        if (effective != null) for (MachineSlotLayout.Slot port : effective.slots()) {
            MachineSlotLayout.Slot existing = ports.get(port.id());
            if (existing != null && (existing.role() != port.role() || !existing.codecId().equals(port.codecId())))
                throw conflict();
            if (existing == null || port.capacity() > existing.capacity()) ports.put(port.id(), port);
        }
        MachineSlotLayout result = new MachineSlotLayout();
        ports.values().forEach(port -> add(result, port));
        return result.frozenCopy();
    }

    public static boolean samePort(MachineSlotLayout.Slot before, MachineSlotLayout after) {
        MachineSlotLayout.Slot retained = after == null ? null : after.find(before.id());
        return retained != null && retained.role() == before.role() && retained.codecId().equals(before.codecId());
    }

    public static void validateChange(MachineNodeData before, CloudworksLineDraft.Machine after) {
        validateChange(before, after, Collections.emptyList());
    }

    public static void validateChange(MachineNodeData before, CloudworksLineDraft.Machine after,
                                      List<CloudworksLineDraft.Wire> wires) {
        MachineSlotLayout next = slots(after);
        if (before == null || before.waitingBlueprint() != null) return;
        String nextId = activeId(after);
        if (!before.capabilities().mechanism().equals(nextId)) {
            if (before.hasOperations()) throw new IllegalArgumentException("cloudworks.error.node_mechanism_busy");
            Variant target = nextId.isEmpty() ? null : preview(CloudworksRegistries.identityKey(after.type), after.recipeType, nextId);
            CloudworksThermalPlan nextThermal = target == null ? after.recipeType.isEmpty()
                    ? CloudworksRegistries.thermal(CloudworksRegistries.identityKey(after.type))
                    : CloudworksRegistries.recipeType(CloudworksRegistries.identityKey(after.type), after.recipeType) == null ? null
                    : CloudworksRegistries.recipeType(CloudworksRegistries.identityKey(after.type), after.recipeType).adapter().thermalPlan() : target.thermal;
            if (before.capabilities().thermal() != nextThermal
                    && hot(before.thermalState(), before.capabilities().thermal(), before.machineCount()))
                throw new IllegalArgumentException("cloudworks.error.node_mechanism_hot");
            for (Branch branch : before.capabilities().branches()) {
                CloudworksExecutionState state = before.executionBranches().get(branch.id);
                boolean retained = target != null && target.branches.stream()
                        .anyMatch(candidate -> candidate.id.equals(branch.id) && candidate.thermal == branch.thermal);
                if (!retained && state != null && hot(state.thermal, branch.thermal, before.machineCount()))
                    throw new IllegalArgumentException("cloudworks.error.node_mechanism_hot");
            }
        }
        MachineSlotLayout previous = before.capabilities().slots();
        if (previous == null) return;
        MachineSlotLayout nativeSlots = CloudworksRegistries.slots(before.machine());
        for (MachineSlotLayout.Slot slot : previous.slots()) {
            if (samePort(slot, next)) continue;
            // Recipe selection can hide native ports without revoking their stored resources or promises.
            if (samePort(slot, nativeSlots)) continue;
            for (CloudworksLineDraft.Wire wire : wires)
                if (before.nodeId().equals(wire.source) && slot.id().equals(wire.sourcePort)
                        || before.nodeId().equals(wire.target) && slot.id().equals(wire.targetPort))
                    throw new IllegalArgumentException("cloudworks.error.node_mechanism_connected");
            List<CloudworksResourceStack> contents = slot.role() == MachineSlotLayout.PortRole.OUTPUT ? before.outputs()
                    : slot.role() == MachineSlotLayout.PortRole.CONFIGURATION ? before.templates() : before.inputs();
            for (CloudworksResourceStack resource : contents)
                if (resource.amount() > 0 && slot.codecId().equals(resource.descriptor().getCodecId()))
                    throw new IllegalArgumentException("cloudworks.error.node_mechanism_buffered");
        }
    }

    private static boolean hot(CloudworksThermalState state, CloudworksThermalPlan plan, long machines) {
        return state != null && (state.heat > 300 * state.capacity + 1E-6 || state.fuelTicks > 0 || state.burning
                || plan != null && state.plasma > 300 * plan.plasmaCapacity * machines + 1E-6);
    }

    private static IllegalArgumentException conflict() {
        return new IllegalArgumentException("cloudworks.error.node_mechanism_conflict");
    }

    private static void add(MachineSlotLayout result, MachineSlotLayout.Slot slot) {
        result.add(slot.id(), slot.codecId(), slot.role(), slot.capacity(), slot.multipleConnections(), slot.recipeFilteredInput());
    }

    public static List<CloudworksMachineAdapter> frozenAdapters() {
        List<CloudworksMachineAdapter> result = new ArrayList<>();
        for (Map<String, Map<ResourceLocation, Fragment>> modes : fragments.values())
            for (Map<ResourceLocation, Fragment> modules : modes.values())
                for (Fragment fragment : modules.values()) result.add(fragment.adapter);
        return Collections.unmodifiableList(result);
    }

    public static void applyAssignments(CloudworksLineDraft.Machine source, MachineNodeData target) {
        target.customUpgrades().setAll(assignments(source));
    }

    public static synchronized void resetForTests() {
        fragments = Collections.emptyMap();
        composed.clear();
        installedVariants.clear();
        generation++;
    }

    private static final class Fragment {
        final ResourceLocation id;
        final RecipeContribution definition;
        final CloudworksMachineAdapter adapter;
        final MachineExecutionProfile profile;

        Fragment(ResourceLocation id, RecipeContribution definition, CloudworksMachineAdapter adapter,
                 MachineExecutionProfile profile) {
            this.id = id;
            this.definition = definition;
            this.adapter = adapter;
            this.profile = profile;
        }
    }

    public static final class Branch {
        public final String id;
        public final MachineExecutionProfile profile;
        public final List<CloudworksMachineAdapter> adapters;
        public final CloudworksEnergyGenerator generator;
        public final CloudworksThermalPlan thermal;

        Branch(String id, MachineExecutionProfile profile, List<CloudworksMachineAdapter> adapters,
               CloudworksEnergyGenerator generator, CloudworksThermalPlan thermal) {
            this.id = id;
            this.profile = profile;
            this.adapters = Collections.unmodifiableList(new ArrayList<>(adapters));
            this.generator = generator;
            this.thermal = thermal;
        }
    }

    public static final class Variant {
        public final String id;
        public final MachineSlotLayout slots;
        public final List<CloudworksMachineAdapter> adapters;
        public final MachineExecutionProfile profile;
        public final CloudworksEnergyGenerator generator;
        public final CloudworksThermalPlan thermal;
        public final List<Branch> branches;

        private Variant(String id, MachineSlotLayout slots, List<CloudworksMachineAdapter> adapters,
                        MachineExecutionProfile profile, CloudworksEnergyGenerator generator, CloudworksThermalPlan thermal,
                        List<Branch> branches) {
            this.id = id;
            this.slots = slots;
            this.adapters = Collections.unmodifiableList(new ArrayList<>(adapters));
            this.profile = profile;
            this.generator = generator;
            this.thermal = thermal;
            this.branches = Collections.unmodifiableList(new ArrayList<>(branches));
        }
    }
}
