package mekanism.cloudworks.common.recipe;

import mekanism.cloudworks.api.CloudworksMachineAdapter;
import mekanism.common.recipe.RecipeHandler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registrable async admission. A machine adapter class enters the worker path
 * only after an {@link AuditContract} has been registered for it; unknown and
 * special adapters stay on the owner thread. Every refusal carries a reason so
 * unsupported machines are diagnosed, never silently dropped.
 */
public final class CloudworksAsyncRecipeAdmission {
    private static final Map<Class<?>, AuditContract> AUDITED = new LinkedHashMap<>();
    /**
     * Contract for deterministic native recipe adapters.
     */
    private static final AuditContract NATIVE_CONTRACT = new AuditContract() {
        @Override
        public Optional<CloudworksFrozenRecipeView> capture(CloudworksMachineAdapter adapter) {
            if (!(adapter instanceof CloudworksNativeRecipeAdapter<?> nativeAdapter)) return Optional.empty();
            return nativeAdapter.captureFrozenView();
        }

        @Override
        public String describeRejection(CloudworksMachineAdapter adapter) {
            if (!(adapter instanceof CloudworksNativeRecipeAdapter)) return "not a deterministic native recipe adapter";
            return "native freeze refused: no safe matcher, oversized group or registry changed mid-capture";
        }
    };
    /**
     * Contract for chance adapters: per-operation rolls happen in the worker from frozen parameters.
     */
    private static final AuditContract CHANCE_CONTRACT = new AuditContract() {
        @Override
        public Optional<CloudworksFrozenRecipeView> capture(CloudworksMachineAdapter adapter) {
            if (!(adapter instanceof FreezableView freezable)) return Optional.empty();
            return freezable.captureFrozenView();
        }

        @Override
        public String describeRejection(CloudworksMachineAdapter adapter) {
            if (!(adapter instanceof FreezableView)) return "not a freezable chance adapter";
            return "chance freeze refused: unfreezable matcher or registry changed mid-capture";
        }
    };
    private static final Class<?>[] BUILT_INS = {
            CloudworksContractRecipeAdapter.class,
            CloudworksSPSRecipeAdapter.class,
            CloudworksBoilerRecipeAdapter.class,
            CloudworksFissionReactorRecipeAdapter.class,
            CloudworksTurbineRecipeAdapter.class,
            CloudworksFusionReactorRecipeAdapter.class,
            CloudworksBasicItemRecipeAdapter.class,
            CloudworksDoubleItemRecipeAdapter.class,
            CloudworksAdvancedGasItemRecipeAdapter.class,
            CloudworksInfuserRecipeAdapter.class,
            CloudworksPressurizedRecipeAdapter.class,
            CloudworksDissolutionRecipeAdapter.class,
            CloudworksGasFluidRecipeAdapter.class,
            CloudworksGasGasRecipeAdapter.class,
            CloudworksGasItemRecipeAdapter.class,
            CloudworksItemGasOutputRecipeAdapter.class,
            CloudworksChemicalPairRecipeAdapter.class,
            CloudworksSeparatorRecipeAdapter.class,
            CloudworksRotaryRecipeAdapter.class,
            CloudworksVoidMineralRecipeAdapter.class,
            CloudworksWasteBarrelRecipeAdapter.class,
            CloudworksThermalEvaporationRecipeAdapter.class,
            CloudworksSolarNeutronRecipeAdapter.class,
            CloudworksNutritionalRecipeAdapter.class,
            CloudworksNucleosynthesizerRecipeAdapter.class,
            CloudworksReplicatorItemRecipeAdapter.class,
            CloudworksReplicatorGasRecipeAdapter.class,
            CloudworksReplicatorFluidRecipeAdapter.class,
            CloudworksFusionCoolingRecipeAdapter.class};
    /**
     * Chance adapters admitted through {@link FreezableView}: rolled per operation in the worker.
     */
    private static final Class<?>[] CHANCE_BUILT_INS = {
            CloudworksChanceItemRecipeAdapter.class,
            CloudworksChance2ItemRecipeAdapter.class,
            CloudworksAmbientGasRecipeAdapter.class,
            CloudworksFarmRecipeAdapter.class};
    /**
     * Fuel-contract generators admitted through {@link FreezableView}: the
     * worker burns the frozen fuel and reports the actual generation (GPT fuel
     * contract design; replaces the prepare-time constant).
     */
    private static final Class<?>[] FUEL_BUILT_INS = {
            CloudworksGasGeneratorAdapter.class,
            CloudworksItemGeneratorAdapter.class,
            CloudworksHeatFuelAdapter.class};
    private static boolean bootstrapped;

    private CloudworksAsyncRecipeAdmission() {
    }

    /**
     * Seeds the built-in classes without touching the registry version: they are
     * part of the initial state, and a lazily-triggered version bump between a
     * capture and its commit would poison the transaction.
     */
    private static void ensureBootstrapped() {
        if (bootstrapped) return;
        synchronized (CloudworksAsyncRecipeAdmission.class) {
            if (bootstrapped) return;
            for (Class<?> type : BUILT_INS) AUDITED.put(type, NATIVE_CONTRACT);
            for (Class<?> type : CHANCE_BUILT_INS) AUDITED.put(type, CHANCE_CONTRACT);
            for (Class<?> type : FUEL_BUILT_INS) AUDITED.put(type, CHANCE_CONTRACT);
            bootstrapped = true;
        }
    }

    /**
     * Registers the audit contract of one adapter class. Registration re-uses the
     * machine registry version counter, so in-flight transactions re-validate.
     */
    public static void registerAudited(Class<?> type, AuditContract contract) {
        if (type == null || contract == null || !CloudworksMachineAdapter.class.isAssignableFrom(type))
            throw new IllegalArgumentException("Invalid async admission registration");
        ensureBootstrapped();
        synchronized (CloudworksAsyncRecipeAdmission.class) {
            AUDITED.put(type, contract);
        }
        mekanism.cloudworks.common.registry.CloudworksRegistries.adapterRegistered();
    }

    public static Optional<CloudworksFrozenRecipeView> capture(CloudworksMachineAdapter adapter) {
        if (adapter == null) return Optional.empty();
        ensureBootstrapped();
        AuditContract contract;
        synchronized (CloudworksAsyncRecipeAdmission.class) {
            contract = AUDITED.get(adapter.getClass());
        }
        if (contract == null) return Optional.empty();
        return contract.capture(adapter);
    }

    /**
     * Freeze entry for special adapters (fuel generators). They stay outside
     * {@code CloudworksMachineAdapters} for compatibility with the recipe registration API;
     * only classes admitted above and implementing {@link FreezableView} freeze.
     */
    public static Optional<CloudworksFrozenRecipeView> captureSpecial(SpecialMachineAdapter special) {
        if (!(special instanceof FreezableView freezable)) return Optional.empty();
        ensureBootstrapped();
        synchronized (CloudworksAsyncRecipeAdmission.class) {
            if (!AUDITED.containsKey(special.getClass())) return Optional.empty();
        }
        return freezable.captureFrozenView();
    }

    /**
     * Why a special adapter is (or would be) refused; never null, for diagnostics.
     */
    public static String specialRejectionReason(SpecialMachineAdapter special) {
        if (special == null) return "no special adapter";
        ensureBootstrapped();
        boolean audited;
        synchronized (CloudworksAsyncRecipeAdmission.class) {
            audited = AUDITED.containsKey(special.getClass());
        }
        // Legacy bridge-only specials keep the historical umbrella diagnosis.
        if (!audited || !(special instanceof FreezableView))
            return "special machine adapter is not frozen yet";
        return "fuel freeze refused: unfreezable fuel ingredient or registry changed mid-capture";
    }

    /**
     * Why this adapter is (or would be) refused; never null, for diagnostics.
     */
    public static String rejectionReason(CloudworksMachineAdapter adapter) {
        if (adapter == null) return "no adapter";
        ensureBootstrapped();
        AuditContract contract;
        synchronized (CloudworksAsyncRecipeAdmission.class) {
            contract = AUDITED.get(adapter.getClass());
        }
        if (contract == null)
            return "adapter class not registered for async admission (default deny): "
                    + adapter.getClass().getName();
        return contract.describeRejection(adapter);
    }

    /**
     * A captured view is stale after any recipe registry mutation.
     */
    public static boolean current(CloudworksFrozenRecipeView view) {
        return view != null && view.sourceVersion() == RecipeHandler.getGlobalRecipeVersion();
    }

    /**
     * Read-only snapshot of the audited classes; coverage audits use this.
     */
    public static synchronized Map<Class<?>, AuditContract> auditedClasses() {
        ensureBootstrapped();
        return Collections.unmodifiableMap(new LinkedHashMap<>(AUDITED));
    }

    /**
     * Freezes one adapter instance on the owner thread; empty means rejected.
     */
    public interface AuditContract {
        Optional<CloudworksFrozenRecipeView> capture(CloudworksMachineAdapter adapter);

        /**
         * Human-readable refusal used for diagnostics and coverage audits.
         */
        default String describeRejection(CloudworksMachineAdapter adapter) {
            return "audit contract refused the adapter instance";
        }
    }

    /**
     * Adapters that freeze an audited pure view themselves (chance recipes).
     */
    public interface FreezableView {
        /**
         * Owner-thread freeze; empty means rejected and must carry a diagnostic reason.
         */
        Optional<CloudworksFrozenRecipeView> captureFrozenView();
    }
}
