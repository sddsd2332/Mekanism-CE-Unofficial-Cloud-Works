package mekanism.cloudworks.common;

import mekanism.cloudworks.common.registry.CloudworksMekanismBuiltins;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.module.CloudworksUpgradeItems;
import mekanism.cloudworks.common.card.CloudworksCardEffects;
import mekanism.cloudworks.common.data.CloudworksExtensionRegistry;
import mekanism.cloudworks.common.integration.CloudworksEnvironmentModules;
import mekanism.cloudworks.common.integration.CloudworksHooks;
import mekanism.cloudworks.common.integration.CloudworksScriptRegistry;
import mekanism.cloudworks.common.item.ItemInfiniteResourceCard;
import mekanism.cloudworks.common.recipe.CloudworksAsyncRecipeAdmission;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.cloudworks.common.registry.CloudworksApiRegistration;
import mekanism.cloudworks.common.registry.CloudworksAuxiliaryNodeRegistry;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.common.Upgrade;
import net.minecraft.item.ItemStack;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;
import mekanism.cloudworks.common.service.CloudworksNodeEnvironment;
import mekanism.cloudworks.common.service.CloudworksLootInjections;
import mekanism.cloudworks.common.service.CloudworksSpawnTables;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;

/**
 * Single composition root for CloudWorks' own lifecycle composition. The order
 * of every stage here is load-bearing:
 *
 * <pre>
 * init:        built-in Mekanism declarations (after item registry events)
 * loadComplete optional integrations -&gt; CraftTweaker scripts -&gt; special-node
 *              archetypes -&gt; public API machine queue (closes the shared frozen
 *              boundary) -&gt; recipe admission freeze -&gt; card/upgrade/registry
 *              freezes -&gt; native-contract snapshot refresh.
 * </pre>
 *
 * No other core class may dispatch into {@code integration.*}; addons extend
 * through the public API in {@code mekanism.cloudworks.api}.
 */
public final class CloudworksBootstrap {
    private CloudworksBootstrap() {
    }

    /** Built-in Mekanism machine declarations; runs during mod init. */
    public static void initBuiltins() {
        // Axis-priority batch 1: default line media bind before addon/CrT overrides;
        // a script may still setItem/useNativeItem afterwards, then loadComplete
        // re-validates module item bindings before the freeze.
        CloudworksUpgradeItems.local().bindDefault(Upgrade.SPEED, new ItemStack(CloudworksItems.SpeedModule));
        CloudworksUpgradeItems.local().bindDefault(Upgrade.ENERGY, new ItemStack(CloudworksItems.EnergyModule));
        CloudworksEnvironmentModules.register();
        CloudworksMekanismBuiltins.register();
        CloudworksApiRegistration.applyPending();
    }

    /** Final composition at load-complete; see the class javadoc for the order. */
    public static void loadComplete() {
        mekanism.cloudworks.api.environment.BiomeAxes.refresh();
        CloudworksHooks.register();
        CloudworksApiRegistration.applyPending();
        CloudworksExtensionRegistry.freezeAndRegister();
        CloudworksMachineModuleRegistry.freezeAndRegister();
        CloudworksScriptRegistry.apply();
        // CrT may rebind upgrade items after the module drain above; re-validate
        // the final item bindings before anything freezes (axis-priority batch 1).
        CloudworksMachineModuleRegistry.validateUpgradeItemBindings();
        // Special archetypes drain BEFORE the machine drain: the shared API
        // frozen boundary closes with the machine queue (8.5/Phase-3 contract).
        CloudworksSpecialNodeArchetypes.freezeAndRegister();
        CloudworksAPI.freezeAndDrainAuxiliaryNodes(
                CloudworksAuxiliaryNodeRegistry::register);
        CloudworksAuxiliaryNodeRegistry.freeze();
        // Manual loot injections drain before the machine drain closes the
        // shared frozen boundary; the registry validates targets fail-fast.
        CloudworksLootInjections.freezeAndDrain();
        CloudworksApiRegistration.freezeAndRegister();
        CloudworksAsyncRecipeAdmission.freezeRegisteredAdapters();
        CloudworksApiRegistration.validateRegisteredContracts();
        CloudworksNodeMechanisms.freeze();
        CloudworksNodeEnvironment.freeze();
        // Mob-simulation pipeline (B4 step 3): spawn tables snapshot the live
        // biome registry; loot tables resolve lazily from the classpath.
        CloudworksSpawnTables.freeze();
        ((ItemInfiniteResourceCard)
                CloudworksItems.InfiniteResourceCard)
                .refreshCreativeCards();
        CloudworksCardEffects.freeze();
        CloudworksUpgradeItems.local().freeze();
        CloudworksRegistries.freeze();
        CloudworksNativeContracts.refresh();
    }
}
