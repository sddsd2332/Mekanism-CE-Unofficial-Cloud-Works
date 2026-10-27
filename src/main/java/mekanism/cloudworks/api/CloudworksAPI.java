package mekanism.cloudworks.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.UUID;
import mekanism.cloudworks.common.service.CloudworksModuleEvents;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import mekanism.cloudworks.api.node.CloudworksAuxiliaryNodeType;
import mekanism.cloudworks.api.card.CloudworksCardResult;
import mekanism.cloudworks.api.card.CloudworksCardType;
import mekanism.cloudworks.api.transfer.CloudworksDepositRequest;
import mekanism.cloudworks.api.transfer.CloudworksDepositResult;
import mekanism.cloudworks.api.machine.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineCategory;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.machine.CloudworksMachineInfo;
import mekanism.cloudworks.api.machine.CloudworksMachineMutation;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;
import mekanism.cloudworks.api.node.CloudworksSpecialNodeType;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.api.transfer.CloudworksTransferRequest;
import mekanism.cloudworks.api.transfer.CloudworksTransferResult;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.api.module.MachineModuleSource;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.api.environment.CloudworksLootInjection;

/**
 * Stable addon entry point for declarations collected before load-complete freeze.
 *
 * <p>Declaration window: from mod construction until CloudWorks' load-complete
 * lifecycle step drains the queues. Registrations after that point throw.
 */
public final class CloudworksAPI {
    /** Startup-only pump environment declarations shared with the CRT frontend. */
    public static void registerPumpRule(mekanism.cloudworks.api.recipe.CloudworksPumpRule rule) {
        mekanism.cloudworks.common.recipe.CloudworksPumpRecipes.add(rule);
    }
    public static void removePumpRule(ResourceLocation id) { mekanism.cloudworks.common.recipe.CloudworksPumpRecipes.remove(id); }
    public static void clearPumpRules() { mekanism.cloudworks.common.recipe.CloudworksPumpRecipes.clear(); }
    public static List<String> pumpEnvironmentAxes() { return mekanism.cloudworks.common.recipe.CloudworksPumpRecipes.axes(); }
    public static List<String> pumpRuleIds() { return mekanism.cloudworks.common.recipe.CloudworksPumpRecipes.ids(); }
    public static ResourceLocation pumpAxisForBiome(ResourceLocation biome) {
        return mekanism.cloudworks.common.recipe.CloudworksPumpRecipes.biome(Objects.requireNonNull(biome).toString());
    }
    private static final List<CloudworksMachineDefinition> MACHINES = new ArrayList<>();
    private static final List<CloudworksExtensionType<?>> EXTENSIONS = new ArrayList<>();
    private static final List<MachineModuleType> UPGRADES = new ArrayList<>();
    private static final List<EnvironmentBehaviorRegistration> ENVIRONMENT_BEHAVIORS = new ArrayList<>();
    private static final List<Predicate<ItemStack>> INFINITE_ENERGY_ITEMS = new ArrayList<>();
    private static final List<CloudworksSpecialNodeType> SPECIAL_NODES = new ArrayList<>();
    private static final List<CloudworksAuxiliaryNodeType> AUXILIARY_NODES = new ArrayList<>();
    private static final List<CloudworksCardType> CARDS = new ArrayList<>();
    private static final List<CloudworksMachineMutation> MACHINE_MUTATIONS = new ArrayList<>();
    private static final List<CloudworksLootInjection> LOOT_INJECTIONS = new ArrayList<>();
    private static boolean extensionsClosed;
    private static boolean lootInjectionsClosed;
    private static boolean upgradesClosed;
    private static boolean specialNodesClosed;
    private static boolean auxiliaryNodesClosed;
    private static volatile Services services;
    private static volatile MachineServices machineServices;
    private static boolean frozen;

    private CloudworksAPI() {
    }

    public static synchronized void registerMachine(CloudworksMachineDefinition definition) {
        if (frozen) throw new IllegalStateException("CloudWorks API registrations are frozen");
        MACHINES.add(Objects.requireNonNull(definition));
    }

    public static void registerMachine(ItemStack machine, MachineExecutionProfile profile,
                                       MachineSlotLayout slots, CloudworksMachineAdapter adapter, MachineIdentityPolicy identity) {
        registerMachine(new CloudworksMachineDefinition(machine, profile, slots, identity,
                Collections.singletonList(new CloudworksRecipeMode("cloudworks:processing", 0, null, adapter))));
    }

    public static synchronized boolean registrationOpen() {
        return !frozen;
    }

    public static synchronized void registerMachineAlias(ItemStack alias,
                                                         ItemStack target) {
        addMutation(new CloudworksMachineMutation(CloudworksMachineMutation.Kind.ALIAS,
                alias, target, null, null, null, null));
    }

    public static synchronized void updateMachineProfile(ItemStack machine,
                                                         MachineExecutionProfile profile) {
        addMutation(new CloudworksMachineMutation(CloudworksMachineMutation.Kind.PROFILE,
                machine, null, Objects.requireNonNull(profile), null, null, null));
    }

    public static synchronized void registerMachineFrom(ItemStack machine,
                                                        ItemStack target, MachineExecutionProfile profile) {
        addMutation(new CloudworksMachineMutation(CloudworksMachineMutation.Kind.TEMPLATE,
                machine, target, Objects.requireNonNull(profile), null, null, null));
    }

    public static synchronized void registerMachineAdapter(ItemStack machine,
                                                           CloudworksMachineAdapter adapter) {
        addMutation(new CloudworksMachineMutation(CloudworksMachineMutation.Kind.ADD_ADAPTER,
                machine, null, null, Objects.requireNonNull(adapter), null, null));
    }

    /**
     * Extends a registered machine's upgrade acceptance without changing its native policy or identity.
     */
    public static synchronized void acceptMachineUpgrade(ItemStack machine,
                                                         MachineModuleType upgrade, int limit) {
        Objects.requireNonNull(upgrade);
        if (upgrade.slot() != MachineModuleType.Slot.CUSTOM || !upgrade.acceptsSource(MachineModuleSource.UPGRADE))
            throw new IllegalArgumentException("Only custom upgrade acceptance can be appended");
        addMutation(new CloudworksMachineMutation(CloudworksMachineMutation.Kind.ACCEPT_UPGRADE,
                machine, null, null, null, null, null, upgrade, limit));
    }

    public static synchronized void clearMachineAdapters(ItemStack machine) {
        addMutation(new CloudworksMachineMutation(CloudworksMachineMutation.Kind.CLEAR_ADAPTERS,
                machine, null, null, null, null, null));
    }

    public static synchronized void registerEnergyGenerator(ItemStack machine,
                                                            CloudworksEnergyGenerator generator) {
        addMutation(new CloudworksMachineMutation(CloudworksMachineMutation.Kind.GENERATOR,
                machine, null, null, null, Objects.requireNonNull(generator), null));
    }

    public static synchronized void registerThermalPlan(ItemStack machine,
                                                        CloudworksThermalPlan thermal) {
        addMutation(new CloudworksMachineMutation(CloudworksMachineMutation.Kind.THERMAL,
                machine, null, null, null, null, Objects.requireNonNull(thermal)));
    }

    private static void addMutation(CloudworksMachineMutation mutation) {
        if (frozen) throw new IllegalStateException("CloudWorks API registrations are frozen");
        MachineServices platform = machineServices;
        if (platform != null) platform.validateMachineMutation(mutation, machineInfo(mutation.machine()));
        MACHINE_MUTATIONS.add(mutation);
    }

    public static CloudworksMachineInfo machineInfo(ItemStack machine) {
        Objects.requireNonNull(machine);
        return machineInfo(machine, new ArrayList<>());
    }

    private static CloudworksMachineInfo machineInfo(ItemStack machine,
                                                     List<ItemStack> resolving) {
        Objects.requireNonNull(machine);
        for (ItemStack parent : resolving) {
            if (sameMachine(parent, machine)) {
                throw new IllegalStateException("Cyclic CloudWorks machine alias/template resolution: " + machine);
            }
        }
        resolving.add(machine.copy());
        CloudworksMachineInfo result = null;
        try {
            synchronized (CloudworksAPI.class) {
                for (CloudworksMachineDefinition definition : MACHINES)
                    if (sameMachine(definition.machine(), machine)) result = info(definition);
                for (CloudworksMachineMutation mutation : MACHINE_MUTATIONS) {
                    if (!sameMachine(mutation.machine(), machine)) continue;
                    result = applyInfoMutation(result == null ? services().machineInfo(machine) : result,
                            mutation, resolving);
                }
            }
            return result == null ? services().machineInfo(machine) : result;
        } finally {
            resolving.remove(resolving.size() - 1);
        }
    }

    public static List<CloudworksMachineInfo> machineInfos() {
        MachineServices platform = services();
        List<CloudworksMachineInfo> result = new ArrayList<>(platform.machineInfos());
        synchronized (CloudworksAPI.class) {
            for (CloudworksMachineDefinition definition : MACHINES) {
                CloudworksMachineInfo info = info(definition);
                int existing = -1;
                for (int at = 0; at < result.size(); at++) {
                    if (sameMachine(result.get(at).machine(), info.machine())) {
                        existing = at;
                        break;
                    }
                }
                if (existing < 0) result.add(info);
                else result.set(existing, info);
            }
            for (CloudworksMachineMutation mutation : MACHINE_MUTATIONS) {
                int index = -1;
                for (int at = 0; at < result.size(); at++)
                    if (sameMachine(result.get(at).machine(), mutation.machine())) {
                        index = at;
                        break;
                    }
                CloudworksMachineInfo info = index < 0 ? platform.machineInfo(mutation.machine()) : result.get(index);
                List<ItemStack> resolving = new ArrayList<>();
                resolving.add(mutation.machine().copy());
                CloudworksMachineInfo updated = applyInfoMutation(info, mutation, resolving);
                if (index < 0) result.add(updated);
                else result.set(index, updated);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public static void validateMachineAdapter(ItemStack machine, CloudworksMachineAdapter adapter) {
        CloudworksMachineInfo info = machineInfo(machine);
        MachineSlotLayout pending = info.registered() ? info.slots() : null;
        services().validateMachineAdapter(machine, adapter, pending);
    }

    public static void validateMachineAdapter(MachineSlotLayout slots, CloudworksMachineAdapter adapter) {
        services().validateMachineAdapter(ItemStack.EMPTY, adapter, Objects.requireNonNull(slots));
    }

    public static List<MachineModuleType> machineModuleTypes() {
        List<MachineModuleType> result = new ArrayList<>(services().upgradeTypes());
        synchronized (CloudworksAPI.class) {
            result.addAll(UPGRADES);
        }
        return Collections.unmodifiableList(result);
    }

    private static MachineServices services() {
        MachineServices platform = machineServices;
        if (platform == null) throw new IllegalStateException("CloudWorks machine API is not initialized");
        return platform;
    }

    private static CloudworksMachineInfo info(CloudworksMachineDefinition definition) {
        List<String> modes = new ArrayList<>();
        for (CloudworksRecipeMode mode : definition.modes()) modes.add(mode.id());
        return new CloudworksMachineInfo(definition.machine(), ItemStack.EMPTY,
                definition.profile(), definition.slots(), modes, modes.size(), true,
                definition.generator() != null, definition.thermal(), definition.resolvedCategory());
    }

    private static CloudworksMachineInfo applyInfoMutation(CloudworksMachineInfo old,
                                                           CloudworksMachineMutation mutation, List<ItemStack> resolving) {
        ItemStack machine = mutation.machine();
        ItemStack alias = old.aliasTarget();
        MachineExecutionProfile profile = old.profile();
        MachineSlotLayout slots = old.slots();
        List<String> recipeTypes = new ArrayList<>(old.recipeTypes());
        int adapters = old.adapterCount();
        boolean registered = old.registered(), generator = old.hasEnergyGenerator();
        CloudworksThermalPlan thermal = old.thermal();
        CloudworksMachineCategory category = old.category();
        switch (mutation.kind) {
            case ALIAS: {
                alias = mutation.target();
                category = machineInfo(mutation.target(), resolving).category();
                registered = true;
                break;
            }
            case PROFILE:
                profile = mutation.profile().inheritUpgradePolicies(profile);
                break;
            case ACCEPT_UPGRADE:
                profile = profile.withUpgradePolicy(profile.upgradePolicy()
                        .withCustomType(mutation.upgrade(), mutation.upgradeLimit()));
                break;
            case TEMPLATE: {
                CloudworksMachineInfo target = machineInfo(mutation.target(), resolving);
                profile = mutation.profile().inheritUpgradePolicies(target.profile());
                slots = target.slots();
                alias = ItemStack.EMPTY;
                recipeTypes = new ArrayList<>(target.recipeTypes());
                adapters = target.adapterCount();
                generator = target.hasEnergyGenerator();
                thermal = target.thermal();
                category = target.category();
                registered = true;
                break;
            }
            case ADD_ADAPTER:
                adapters++;
                break;
            case CLEAR_ADAPTERS:
                adapters = 0;
                recipeTypes.clear();
                break;
            case GENERATOR:
                generator = true;
                if (category == null || category.id().startsWith("cloudworks:"))
                    category = CloudworksMachineCategory.GENERATION;
                break;
            case THERMAL:
                thermal = mutation.thermal();
                if (category == null || category.id().startsWith("cloudworks:"))
                    category = CloudworksMachineCategory.OTHER;
                break;
        }
        return new CloudworksMachineInfo(machine, alias, profile, slots, recipeTypes, adapters,
                registered, generator, thermal, category);
    }

    private static boolean sameMachine(ItemStack first, ItemStack second) {
        return first != null && second != null && !first.isEmpty() && !second.isEmpty()
                && ItemStack.areItemsEqual(first, second)
                && ItemStack.areItemStackTagsEqual(first, second);
    }

    /**
     * Internal startup registration of the immutable registry view and lifecycle mutator.
     */
    public static void installMachineServices(MachineServices platform) {
        requireInternalLifecycleCaller();
        machineServices = Objects.requireNonNull(platform);
    }

    public interface MachineServices {
        CloudworksMachineInfo machineInfo(ItemStack machine);

        List<CloudworksMachineInfo> machineInfos();

        void applyMachineMutation(CloudworksMachineMutation mutation);

        void validateMachineMutation(CloudworksMachineMutation mutation, CloudworksMachineInfo current);

        void validateMachineAdapter(ItemStack machine, CloudworksMachineAdapter adapter,
                                    MachineSlotLayout pendingSlots);

        List<MachineModuleType> upgradeTypes();
    }

    /**
     * Internal lifecycle stage: materializes declarations before script customization without freezing.
     */
    public static synchronized void applyPendingMachines(Consumer<CloudworksMachineDefinition> registrar) {
        requireInternalLifecycleCaller();
        applyPendingMachines0(registrar, ignored -> {
        });
    }

    public static synchronized void applyPendingMachines(Consumer<CloudworksMachineDefinition> registrar,
                                                         Consumer<CloudworksMachineMutation> mutationRegistrar) {
        requireInternalLifecycleCaller();
        applyPendingMachines0(registrar, mutationRegistrar);
    }

    private static void applyPendingMachines0(Consumer<CloudworksMachineDefinition> registrar,
                                              Consumer<CloudworksMachineMutation> mutationRegistrar) {
        if (frozen) return;
        List<CloudworksMachineDefinition> pending = new ArrayList<>(MACHINES);
        List<CloudworksMachineMutation> mutations = new ArrayList<>(MACHINE_MUTATIONS);
        MACHINES.clear();
        MACHINE_MUTATIONS.clear();
        try {
            for (CloudworksMachineDefinition declaration : pending) registrar.accept(declaration);
            for (CloudworksMachineMutation mutation : mutations) mutationRegistrar.accept(mutation);
        } catch (RuntimeException failure) {
            MACHINES.addAll(0, pending);
            MACHINE_MUTATIONS.addAll(0, mutations);
            throw failure;
        }
    }

    public static synchronized void registerExtension(CloudworksExtensionType<?> type) {
        if (frozen || extensionsClosed)
            throw new IllegalStateException("CloudWorks extension registrations are closed");
        EXTENSIONS.add(Objects.requireNonNull(type));
    }

    /**
     * Declares one manual probability-item drop layered on the frozen mob and
     * loot pipeline (per entity kill or per axis event). The frozen pipeline
     * is never modified; injections roll on top of its results.
     */
    public static synchronized void registerLootInjection(CloudworksLootInjection injection) {
        if (frozen || lootInjectionsClosed)
            throw new IllegalStateException("CloudWorks loot-injection registrations are closed");
        LOOT_INJECTIONS.add(Objects.requireNonNull(injection));
    }

    public static synchronized void registerMachineModule(MachineModuleType type) {
        if (frozen || upgradesClosed) throw new IllegalStateException("CloudWorks upgrade registrations are closed");
        UPGRADES.add(Objects.requireNonNull(type));
    }

    /**
     * Registers a machine-owned behavior that is evaluated against the frozen
     * environment/topology snapshot. It has no upload item and is not an
     * installed upgrade.
     */
    public static synchronized void registerMachineEnvironmentBehavior(
            ItemStack machine, MachineModuleType<?> behavior) {
        if (frozen || upgradesClosed) throw new IllegalStateException("CloudWorks API registrations are frozen");
        Objects.requireNonNull(machine);
        Objects.requireNonNull(behavior);
        if (!behavior.acceptsSource(MachineModuleSource.MACHINE_ENVIRONMENT))
            throw new IllegalArgumentException("Behavior does not accept machine environment source");
        ENVIRONMENT_BEHAVIORS.add(new EnvironmentBehaviorRegistration(machine, behavior));
    }

    public static synchronized MachineModuleType<?> machineEnvironmentBehavior(
            ItemStack machine) {
        for (EnvironmentBehaviorRegistration registration : ENVIRONMENT_BEHAVIORS)
            if (ItemStack.areItemsEqual(registration.machine, machine)
                    && ItemStack.areItemStackTagsEqual(registration.machine, machine))
                return registration.behavior;
        return null;
    }

    /**
     * Registers a capability-independent infinite energy item matcher.
     */
    public static synchronized void registerInfiniteEnergyItem(Predicate<ItemStack> matcher) {
        if (frozen || upgradesClosed)
            throw new IllegalStateException("CloudWorks API registrations are frozen");
        INFINITE_ENERGY_ITEMS.add(Objects.requireNonNull(matcher));
    }

    /**
     * Returns true when a registered matcher identifies an infinite energy item.
     */
    public static synchronized boolean isInfiniteEnergyItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (Predicate<ItemStack> matcher : INFINITE_ENERGY_ITEMS) {
            try {
                if (matcher.test(stack)) return true;
            } catch (RuntimeException | LinkageError ignored) {
                // Optional integration matchers must not break the terminal path.
            }
        }
        return false;
    }

    public static synchronized void registerSpecialNodeType(CloudworksSpecialNodeType type) {
        if (frozen || specialNodesClosed)
            throw new IllegalStateException("CloudWorks special-node registrations are closed");
        SPECIAL_NODES.add(Objects.requireNonNull(type));
    }

    /**
     * Declares one fourth-family auxiliary node before the lifecycle freeze.
     */
    public static synchronized void registerAuxiliaryNodeType(CloudworksAuxiliaryNodeType type) {
        if (frozen || auxiliaryNodesClosed)
            throw new IllegalStateException("CloudWorks auxiliary-node registrations are closed");
        AUXILIARY_NODES.add(Objects.requireNonNull(type));
    }

    public static synchronized void registerCardType(CloudworksCardType type) {
        if (frozen) throw new IllegalStateException("CloudWorks API registrations are frozen");
        CARDS.add(Objects.requireNonNull(type));
    }

    /**
     * Trusted server addon entry. Actor must be the authenticated player, not client-supplied data.
     */
    public static CompletionStage<CloudworksSnapshot> query(
            UUID actor, UUID owner, CloudworksQuery query) {
        Services platform = services;
        if (platform == null) {
            CompletableFuture<CloudworksSnapshot> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("CloudWorks server is not running"));
            return failed;
        }
        return platform.query(Objects.requireNonNull(actor), Objects.requireNonNull(owner), Objects.requireNonNull(query));
    }

    /**
     * Trusted server-side reward entry. Does not debit a player inventory or bypass cloud activation/team access.
     * Independent of terminalItemUploadsEnabled; never join the result on the server thread.
     */
    public static CompletionStage<CloudworksDepositResult> deposit(
            UUID actor, UUID owner, CloudworksDepositRequest request) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(request);
        Services platform = services;
        if (platform == null) return CompletableFuture.completedFuture(
                CloudworksDepositResult.refused(CloudworksDepositResult.Status.UNAVAILABLE, "cloudworks.error.storage"));
        return platform.deposit(actor, owner, request);
    }

    /**
     * Cloud-side half of a trusted CRT/MMCE transfer; external inventory is never touched here.
     */
    public static CompletionStage<CloudworksTransferResult> transfer(
            UUID actor, UUID owner, CloudworksTransferRequest request) {
        Objects.requireNonNull(actor);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(request);
        Services platform = services;
        if (platform == null) return CompletableFuture.completedFuture(
                CloudworksTransferResult.refused(CloudworksTransferResult.Status.UNAVAILABLE, "cloudworks.error.storage"));
        return platform.transfer(actor, owner, request);
    }

    /**
     * Batches dispatch on the server thread after commits and outside all owner locks.
     */
    public static AutoCloseable subscribe(UUID actor, UUID owner,
                                          Consumer<List<CloudworksEvent>> listener) {
        Services platform = services;
        if (platform == null) throw new IllegalStateException("CloudWorks server is not running");
        return platform.subscribe(Objects.requireNonNull(actor), Objects.requireNonNull(owner), Objects.requireNonNull(listener));
    }

    public static CloudworksCardResult useCard(EntityPlayer player,
                                               EnumHand hand, ResourceLocation card) {
        Services platform = services;
        if (platform == null) return new CloudworksCardResult(CloudworksCardResult.Status.UNAVAILABLE,
                0, "cloudworks.error.storage", new Object[0]);
        return platform.useCard(Objects.requireNonNull(player), Objects.requireNonNull(hand), Objects.requireNonNull(card));
    }

    /**
     * Internal server lifecycle binding; addon implementations must not install a platform.
     */
    public static void installServices(Services platform) {
        requireInternalLifecycleCaller();
        services = platform;
    }

    public static void emit(CloudworksEvent event) {
        Services platform = services;
        if (platform != null) platform.emit(event);
    }

    public static void flushEvents() {
        CloudworksModuleEvents.flush();
        Services platform = services;
        if (platform != null) platform.flushEvents();
    }

    public static void shutdownServices() {
        CloudworksModuleEvents.clear();
        Services platform = services;
        services = null;
        if (platform != null) platform.close();
    }

    public interface Services {
        CompletionStage<CloudworksSnapshot> query(UUID actor, UUID owner,
                                                                       CloudworksQuery query);

        AutoCloseable subscribe(UUID actor, UUID owner,
                                Consumer<List<CloudworksEvent>> listener);

        CloudworksCardResult useCard(EntityPlayer player, EnumHand hand,
                                     ResourceLocation card);

        default CompletionStage<CloudworksDepositResult> deposit(UUID actor, UUID owner,
                                                                                      CloudworksDepositRequest request) {
            return CompletableFuture.completedFuture(CloudworksDepositResult.refused(
                    CloudworksDepositResult.Status.UNAVAILABLE, "cloudworks.error.storage"));
        }

        default CompletionStage<CloudworksTransferResult> transfer(UUID actor, UUID owner,
                                                                                        CloudworksTransferRequest request) {
            return CompletableFuture.completedFuture(CloudworksTransferResult.refused(
                    CloudworksTransferResult.Status.UNAVAILABLE, "cloudworks.error.storage"));
        }

        default void emit(CloudworksEvent event) {
        }

        default void flushEvents() {
        }

        default void close() {
        }
    }

    public static synchronized void drainCards(Consumer<CloudworksCardType> registrar) {
        requireInternalLifecycleCaller();
        if (frozen) throw new IllegalStateException("Card registrations are frozen");
        List<CloudworksCardType> pending = new ArrayList<>(CARDS);
        try {
            for (CloudworksCardType card : pending) registrar.accept(card);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Card registration failed", failure);
        }
    }

    /**
     * Rebinds all card declarations to a fresh server-world service.
     */
    public static synchronized void rebindCards(Consumer<CloudworksCardType> registrar) {
        requireInternalLifecycleCaller();
        Objects.requireNonNull(registrar);
        try {
            for (CloudworksCardType card : CARDS) registrar.accept(card);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Card service binding failed", failure);
        }
    }

    /**
     * CloudWorks lifecycle hook. Drains the declaration queue atomically before
     * internal registries freeze. Addon code must not call this: the drain is
     * guarded to the mod's own lifecycle package and any registrar failure
     * aborts loading with the offending declaration identified instead of
     * leaving a half-registered state that looks complete.
     */
    public static synchronized void freezeAndDrain(Consumer<CloudworksMachineDefinition> registrar) {
        requireInternalLifecycleCaller();
        freezeAndDrain0(registrar, ignored -> {
        }, () -> {
        });
    }

    public static synchronized void freezeAndDrain(Consumer<CloudworksMachineDefinition> registrar,
                                                   Consumer<CloudworksMachineMutation> mutationRegistrar) {
        requireInternalLifecycleCaller();
        freezeAndDrain0(registrar, mutationRegistrar, () -> {
        });
    }

    public static synchronized void freezeAndDrain(Consumer<CloudworksMachineDefinition> registrar,
                                                   Consumer<CloudworksMachineMutation> mutationRegistrar, Runnable validateBeforeFreeze) {
        requireInternalLifecycleCaller();
        freezeAndDrain0(registrar, mutationRegistrar, validateBeforeFreeze);
    }

    private static void freezeAndDrain0(Consumer<CloudworksMachineDefinition> registrar,
                                        Consumer<CloudworksMachineMutation> mutationRegistrar, Runnable validateBeforeFreeze) {
        Objects.requireNonNull(registrar);
        Objects.requireNonNull(mutationRegistrar);
        Objects.requireNonNull(validateBeforeFreeze);
        if (frozen) return;
        if (!EXTENSIONS.isEmpty() || !UPGRADES.isEmpty() || !SPECIAL_NODES.isEmpty()
                || !AUXILIARY_NODES.isEmpty() || !LOOT_INJECTIONS.isEmpty())
            throw new IllegalStateException("CloudWorks auxiliary API declarations were not drained before machine freeze");
        List<CloudworksMachineDefinition> declarations = new ArrayList<>(MACHINES);
        List<CloudworksMachineMutation> mutations = new ArrayList<>(MACHINE_MUTATIONS);
        MACHINES.clear();
        MACHINE_MUTATIONS.clear();
        try {
            for (CloudworksMachineDefinition definition : declarations) {
                try {
                    registrar.accept(definition);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("CloudWorks API machine registration failed for "
                            + describe(definition), failure);
                }
            }
            for (CloudworksMachineMutation mutation : mutations) {
                try {
                    mutationRegistrar.accept(mutation);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("CloudWorks API machine customization failed for "
                            + mutation.machine(), failure);
                }
            }
            validateBeforeFreeze.run();
        } catch (RuntimeException failure) {
            // Transactional drain (audit fix): a failed drain restores the queue so
            // the declaration window stays coherent; internal registries roll back
            // their own snapshots before rethrowing.
            MACHINES.addAll(declarations);
            MACHINE_MUTATIONS.addAll(mutations);
            throw failure;
        }
        frozen = true;
    }

    /**
     * CloudWorks lifecycle hook. Drains extension type declarations into the
     * internal extension registry; same caller guard and fail-fast semantics
     * as {@link #freezeAndDrain(Consumer)}. Must run before machine declarations
     * so payloads referenced by machines resolve in the same composition.
     */
    public static synchronized void freezeAndDrainExtensions(Consumer<CloudworksExtensionType<?>> registrar) {
        Objects.requireNonNull(registrar);
        if (frozen || extensionsClosed) return;
        requireInternalLifecycleCaller();
        List<CloudworksExtensionType<?>> declarations = new ArrayList<>(EXTENSIONS);
        EXTENSIONS.clear();
        try {
            for (CloudworksExtensionType<?> type : declarations) {
                try {
                    registrar.accept(type);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("CloudWorks extension registration failed for "
                            + type.id(), failure);
                }
            }
        } catch (RuntimeException failure) {
            EXTENSIONS.addAll(declarations);
            throw failure;
        }
        extensionsClosed = true;
    }

    /**
     * CloudWorks lifecycle hook. Drains manual loot-injection declarations
     * into the internal registry; same caller guard and fail-fast semantics
     * as the extension drain. Must run before the machine drain closes the
     * shared frozen boundary.
     */
    public static synchronized void freezeAndDrainLootInjections(Consumer<CloudworksLootInjection> registrar) {
        Objects.requireNonNull(registrar);
        if (frozen || lootInjectionsClosed) return;
        requireInternalLifecycleCaller();
        List<CloudworksLootInjection> declarations = new ArrayList<>(LOOT_INJECTIONS);
        LOOT_INJECTIONS.clear();
        try {
            for (CloudworksLootInjection injection : declarations) {
                try {
                    registrar.accept(injection);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("CloudWorks loot injection registration failed for "
                            + injection.describeTarget(), failure);
                }
            }
        } catch (RuntimeException failure) {
            LOOT_INJECTIONS.addAll(declarations);
            throw failure;
        }
        lootInjectionsClosed = true;
    }

    /**
     * CloudWorks lifecycle hook. Drains upgrade type declarations into the
     * internal registry; same guard and fail-fast semantics as the machine
     * drain. Must run before machine declarations so policies referencing
     * custom types resolve in the same composition.
     */
    public static synchronized void freezeAndDrainMachineModules(Consumer<MachineModuleType> registrar) {
        Objects.requireNonNull(registrar);
        if (frozen || upgradesClosed) return;
        requireInternalLifecycleCaller();
        List<MachineModuleType> declarations = new ArrayList<>(UPGRADES);
        UPGRADES.clear();
        try {
            for (MachineModuleType type : declarations) {
                try {
                    registrar.accept(type);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("CloudWorks upgrade type registration failed for "
                            + type.id(), failure);
                }
            }
        } catch (RuntimeException failure) {
            UPGRADES.addAll(declarations);
            throw failure;
        }
        upgradesClosed = true;
    }

    /**
     * CloudWorks lifecycle hook. Drains special-node archetype declarations
     * into the internal registry; same guard and fail-fast semantics as the
     * machine drain. Must run before the machine drain because the shared
     * frozen boundary closes with it.
     */
    public static synchronized void freezeAndDrainSpecialNodes(Consumer<CloudworksSpecialNodeType> registrar) {
        Objects.requireNonNull(registrar);
        if (frozen || specialNodesClosed) return;
        requireInternalLifecycleCaller();
        List<CloudworksSpecialNodeType> declarations = new ArrayList<>(SPECIAL_NODES);
        SPECIAL_NODES.clear();
        try {
            for (CloudworksSpecialNodeType type : declarations) {
                try {
                    registrar.accept(type);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("CloudWorks special node registration failed for "
                            + type.id(), failure);
                }
            }
        } catch (RuntimeException failure) {
            SPECIAL_NODES.addAll(declarations);
            throw failure;
        }
        specialNodesClosed = true;
    }

    /**
     * Lifecycle hook for the fourth node family.
     */
    public static synchronized void freezeAndDrainAuxiliaryNodes(
            Consumer<CloudworksAuxiliaryNodeType> registrar) {
        Objects.requireNonNull(registrar);
        if (frozen || auxiliaryNodesClosed) return;
        requireInternalLifecycleCaller();
        List<CloudworksAuxiliaryNodeType> declarations = new ArrayList<>(AUXILIARY_NODES);
        AUXILIARY_NODES.clear();
        try {
            for (CloudworksAuxiliaryNodeType type : declarations) {
                try {
                    registrar.accept(type);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException("CloudWorks auxiliary-node registration failed for "
                            + type.id(), failure);
                }
            }
        } catch (RuntimeException failure) {
            AUXILIARY_NODES.addAll(declarations);
            throw failure;
        }
        auxiliaryNodesClosed = true;
    }

    private static void requireInternalLifecycleCaller() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        // [0] this frame, [1] the draining method, [2] the direct caller under test.
        String caller = stack.length > 2 ? stack[2].getClassName() : "";
        if (!caller.startsWith("mekanism.cloudworks.common."))
            throw new IllegalStateException(
                    "CloudWorks API declarations are drained only by the CloudWorks lifecycle, caller: " + caller);
    }

    /**
     * Test fixture hook. Kept package-private so addons cannot clear the live
     * declaration window or make the public registry diverge at runtime.
     */
    static synchronized void resetForTests() {
        MACHINES.clear();
        MACHINE_MUTATIONS.clear();
        EXTENSIONS.clear();
        UPGRADES.clear();
        SPECIAL_NODES.clear();
        AUXILIARY_NODES.clear();
        CARDS.clear();
        ENVIRONMENT_BEHAVIORS.clear();
        INFINITE_ENERGY_ITEMS.clear();
        LOOT_INJECTIONS.clear();
        extensionsClosed = false;
        lootInjectionsClosed = false;
        upgradesClosed = false;
        specialNodesClosed = false;
        auxiliaryNodesClosed = false;
        frozen = false;
    }

    private static final class EnvironmentBehaviorRegistration {
        private final ItemStack machine;
        private final MachineModuleType<?> behavior;

        private EnvironmentBehaviorRegistration(ItemStack machine,
                                                MachineModuleType<?> behavior) {
            this.machine = machine.copy();
            this.machine.setCount(1);
            this.behavior = behavior;
        }
    }

    private static String describe(CloudworksMachineDefinition definition) {
        String machine = definition.machine().getItem().getRegistryName() == null
                ? definition.machine().getItem().getClass().getSimpleName()
                : definition.machine().getItem().getRegistryName().toString();
        return machine + "@" + definition.machine().getItemDamage();
    }
}
