package mekanism.cloudworks.common.service;

import java.util.*;
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
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.registry.*;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;
import net.minecraft.util.ResourceLocation;
import mekanism.cloudworks.common.card.CloudworksCardEffects;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetype;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import mekanism.common.config.GeneratorsConfig;
import mekanism.common.config.MekanismConfig;

/** Environment interpretation is frozen per physical node, never looked up by Runtime workers. */
public final class CloudworksNodeEnvironment {
    public static final int SOURCE_SIZE = (CloudworksAreaGeometry.NODE_WIDTH + CloudworksAreaGeometry.GRID) * 5;
    /** Kept for source compatibility with the original Nether heat API. */
    public static final int NETHER_SIZE = SOURCE_SIZE;
    public static CloudworksAreaGeometry.Rect bounds(int x, int y, int height) {
        return CloudworksAreaGeometry.square(x + CloudworksAreaGeometry.NODE_WIDTH / 2D, y + height / 2D, SOURCE_SIZE);
    }
    private static volatile double netherHeat = 10;
    private CloudworksNodeEnvironment() {}
    public static long quota(CloudworksData cloud) {
        return cloud.cloudEffects().level(CloudworksCardEffects.NETHER.id());
    }

    public static long quota(CloudworksData cloud, String archetype) {
        EnvironmentAxis axis = EnvironmentAxisRegistry.byIdString(archetype);
        if (axis != null)
            return cloud.cloudEffects().level(axis.effectId());
        if (environmentModule(archetype) == null) return 0;
        return cloud.cloudEffects().level(new ResourceLocation(archetype));
    }

    public static void freeze() {
        GeneratorsConfig config = MekanismConfig.current().generators;
        double heat = config == null ? 10 : config.heatGenerationNether.val();
        if (!Double.isFinite(heat) || heat < 0) throw new IllegalArgumentException("Invalid nether heat");
        netherHeat = heat;
    }

    public static boolean source(String id) {
        MachineModuleType<?> type = environmentModule(id);
        return type != null && type.environment() != null;
    }
    public static boolean netherSource(String id) {
        return CloudworksSpecialNodeArchetypes.NETHER_HEAT_SOURCE.toString().equals(id);
    }
    public static boolean accepts(CloudworksThermalPlan plan) {
        return plan != null && plan.kind != CloudworksThermalPlan.Kind.FISSION;
    }
    public static int installed(MachineNodeData node, ResourceLocation id) {
        if (node.machineCount() <= 0 || node.capabilities().profile() == null) return 0;
        return (int) Math.min(node.capabilities().profile().upgradePolicy().maxCustom(id),
                node.customUpgrades().count(id) / node.machineCount());
    }
    public static int installed(CloudworksLineDraft.Machine node, ResourceLocation id) {
        MachineExecutionProfile profile = CloudworksRegistries.profile(CloudworksRegistries.identityKey(node.type));
        return node.count <= 0 || profile == null ? 0
                : (int) Math.min(profile.upgradePolicy().maxCustom(id), node.assignedCustom(id.toString()) / node.count);
    }
    public static CloudworksGenerationContext generation(ProductionLineData line, MachineNodeData node,
            CloudworksGenerationContext clock) {
        boolean eligible = node != null && node.active() && node.waitingBlueprint() == null && node.machineCount() > 0;
        Set<ResourceLocation> environmentTypes = new LinkedHashSet<>();
        int height = 0, lava = 0;
        if (eligible) {
            for (ResourceLocation id : node.customUpgrades().ids()) {
                MachineModuleType<?> type = CloudworksMachineModuleRegistry.type(id);
                if (type == null || type.environment() == null) continue;
                int count = installed(node, id);
                height = Math.addExact(height, Math.multiplyExact(type.environment().heightPerModule(), count));
                lava = Math.addExact(lava, Math.multiplyExact(type.environment().lavaPerModule(), count));
                if (count > 0) environmentTypes.addAll(type.environment().flags());
            }
            // Built-in identities remain readable before optional content bootstrap in data fixtures.
            if (!CloudworksMachineModuleRegistry.isKnown(MachineModuleType.HEIGHT_ID))
                height = installed(node, MachineModuleType.HEIGHT_ID);
            if (!CloudworksMachineModuleRegistry.isKnown(MachineModuleType.LAVA_HEAT_ID))
                lava = installed(node, MachineModuleType.LAVA_HEAT_ID);
            for (CloudworksChannelNode channel : line.channels()) {
                MachineModuleType<?> type = environmentModule(channel.archetypeId());
                if (type != null && type.environment() != null && covered(line, node, channel.archetypeId())) {
                    environmentTypes.addAll(type.environment().flags());
                    environmentTypes.add(CloudworksGenerationContext.ENVIRONMENT_NODE_COVERAGE);
                }
            }
            // Derived snow state: rain/thunder crossing any covered snowy biome axis
            // (vanilla has no isSnowing — snow is rain times a snowy biome).
            if (environmentTypes.contains(CloudworksGenerationContext.RAIN_ENVIRONMENT)
                    || environmentTypes.contains(CloudworksGenerationContext.THUNDER_ENVIRONMENT)) {
                for (CloudworksChannelNode channel : line.channels()) {
                    EnvironmentAxis axis = EnvironmentAxisRegistry.byIdString(channel.archetypeId());
                    if (axis != null && axis.snowy() && covered(line, node, channel.archetypeId())) {
                        environmentTypes.add(CloudworksGenerationContext.SNOWY_ENVIRONMENT);
                        break;
                    }
                }
            }
        }
        return new CloudworksGenerationContext(height, clock.clockTick(), lava, environmentTypes);
    }
    public static MachineModuleType<?> environmentModule(String id) {
        if (id == null || id.isEmpty()) return null;
        CloudworksSpecialNodeArchetype archetype =
                CloudworksSpecialNodeArchetypes.get(id);
        if (archetype != null && archetype.module() != null) return archetype.module();
        MachineModuleType<?> type = CloudworksMachineModuleRegistry.type(new ResourceLocation(id));
        if (type != null && type.acceptsSource(MachineModuleSource.ENVIRONMENT_NODE)) return type;
        for (MachineModuleType<?> builtin : MachineModuleType.environmentSources())
            if (builtin.id().toString().equals(id)) return builtin;
        return null;
    }
    public static List<CloudworksAreaGeometry.Rect> sources(ProductionLineData line) {
        List<CloudworksAreaGeometry.Rect> result = new ArrayList<>();
        for (CloudworksChannelNode channel : line.channels())
            if (netherSource(channel.archetypeId()) && channel.enabled() && channel.waitingBlueprint() == null)
                result.add(bounds(channel.x(), channel.z(), CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT));
        return result;
    }
    public static List<CloudworksAreaGeometry.Rect> environmentSources(ProductionLineData line) {
        List<CloudworksAreaGeometry.Rect> result = new ArrayList<>();
        for (CloudworksChannelNode channel : line.channels())
            if (source(channel.archetypeId()) && channel.enabled() && channel.waitingBlueprint() == null)
                result.add(bounds(channel.x(), channel.z(), CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT));
        return result;
    }
    public static List<CloudworksAreaGeometry.Rect> sources(CloudworksLineDraft draft) {
        List<CloudworksAreaGeometry.Rect> result = new ArrayList<>();
        for (CloudworksLineDraft.Channel channel : draft.channels)
            if (netherSource(channel.archetypeId) && channel.active && channel.waiting == null)
                result.add(bounds(channel.x, channel.y, CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT));
        return result;
    }
    public static List<CloudworksAreaGeometry.Rect> environmentSources(CloudworksLineDraft draft) {
        List<CloudworksAreaGeometry.Rect> result = new ArrayList<>();
        for (CloudworksLineDraft.Channel channel : draft.channels)
            if (source(channel.archetypeId) && channel.active && channel.waiting == null)
                result.add(bounds(channel.x, channel.y, CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT));
        return result;
    }
    public static CloudworksThermalPlan thermal(CloudworksLineDraft.Machine machine) {
        CloudworksNodeMechanisms.Variant variant = CloudworksNodeMechanisms.resolve(
                CloudworksRegistries.identityKey(machine.type), machine.recipeType, machine.count, assignments(machine));
        if (variant != null) {
            if (variant.thermal != null) return variant.thermal;
            for (CloudworksNodeMechanisms.Branch branch : variant.branches) if (branch.thermal != null) return branch.thermal;
            return null;
        }
        if (mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.isController(machine.type))
            return mekanism.cloudworks.common.integration.CloudworksGeneratorsIntegration.fusionPlan(
                    machine.fusionInjectionRate, "cloudworks:fusion_water".equals(machine.recipeType));
        return machine.recipeType.isEmpty() ? CloudworksRegistries.thermal(CloudworksRegistries.identityKey(machine.type))
                : CloudworksRegistries.recipeType(CloudworksRegistries.identityKey(machine.type), machine.recipeType) == null ? null
                : CloudworksRegistries.recipeType(CloudworksRegistries.identityKey(machine.type), machine.recipeType).adapter().thermalPlan();
    }
    private static Map<ResourceLocation, Long> assignments(CloudworksLineDraft.Machine machine) {
        Map<ResourceLocation, Long> result = new LinkedHashMap<>();
        machine.assignedCustom.forEach((id, count) -> result.put(new ResourceLocation(id), count));
        return result;
    }
    private static boolean eligible(boolean active, boolean waiting, long count, CloudworksThermalPlan thermal) {
        return active && !waiting && count > 0 && accepts(thermal);
    }
    private static CloudworksThermalPlan thermal(MachineNodeData node) {
        if (node.capabilities().thermal() != null) return node.capabilities().thermal();
        for (CloudworksNodeMechanisms.Branch branch : node.capabilities().branches())
            if (branch.thermal != null) return branch.thermal;
        return null;
    }
    public static List<CloudworksAreaGeometry.Target> targets(ProductionLineData line) {
        List<CloudworksAreaGeometry.Target> result = new ArrayList<>();
        for (MachineNodeData node : line.machines()) {
            MachineSlotLayout slots = node.capabilities().slots();
            int height = CloudworksAreaGeometry.nodeHeight(slots == null ? 0 : slots.slots().size());
            result.add(new CloudworksAreaGeometry.Target(node.nodeId(), new CloudworksAreaGeometry.Rect(
                    node.x(), node.z(), node.x() + CloudworksDraftGeometry.WIDTH, node.z() + height),
                    eligible(node.active(), node.waitingBlueprint() != null, node.machineCount(), thermal(node))));
        }
        return Collections.unmodifiableList(result);
    }
    public static List<CloudworksAreaGeometry.Target> environmentTargets(ProductionLineData line) {
        List<CloudworksAreaGeometry.Target> result = new ArrayList<>();
        for (MachineNodeData node : line.machines()) {
            MachineSlotLayout slots = node.capabilities().slots();
            int height = CloudworksAreaGeometry.nodeHeight(slots == null ? 0 : slots.slots().size());
            result.add(new CloudworksAreaGeometry.Target(node.nodeId(), new CloudworksAreaGeometry.Rect(
                    node.x(), node.z(), node.x() + CloudworksDraftGeometry.WIDTH, node.z() + height),
                    node.active() && node.waitingBlueprint() == null && node.machineCount() > 0));
        }
        return Collections.unmodifiableList(result);
    }
    public static List<CloudworksAreaGeometry.Target> targets(CloudworksLineDraft draft) {
        List<CloudworksAreaGeometry.Target> result = new ArrayList<>();
        for (CloudworksLineDraft.Machine machine : draft.machines)
            result.add(new CloudworksAreaGeometry.Target(machine.id, new CloudworksAreaGeometry.Rect(
                    machine.x, machine.y, machine.x + CloudworksDraftGeometry.WIDTH, machine.y + CloudworksDraftGeometry.height(machine)),
                    eligible(machine.active, machine.waiting != null, machine.count, thermal(machine))));
        return Collections.unmodifiableList(result);
    }
    public static List<CloudworksAreaGeometry.Target> environmentTargets(CloudworksLineDraft draft) {
        List<CloudworksAreaGeometry.Target> result = new ArrayList<>();
        for (CloudworksLineDraft.Machine machine : draft.machines)
            result.add(new CloudworksAreaGeometry.Target(machine.id,
                    new CloudworksAreaGeometry.Rect(machine.x, machine.y,
                            machine.x + CloudworksDraftGeometry.WIDTH,
                            machine.y + CloudworksDraftGeometry.height(machine)),
                    machine.active && machine.waiting == null && machine.count > 0));
        return Collections.unmodifiableList(result);
    }

    /** Shared frozen geometry predicate for biome/weather environment consumers. */
    public static boolean covered(ProductionLineData line, MachineNodeData machine, String archetype) {
        if (!source(archetype) || machine == null) return false;
        ModuleArea area = environmentModule(archetype).area();
        for (CloudworksChannelNode channel : line.channels())
            if (archetype.equals(channel.archetypeId()) && channel.enabled()
                    && channel.waitingBlueprint() == null
                    && CloudworksAreaGeometry.exceeds(
                            area == null ? bounds(channel.x(), channel.z(), CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT)
                                    : area.bounds(channel.x(), channel.z(), CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT, 0),
                            new CloudworksAreaGeometry.Rect(machine.x(), machine.z(),
                                    machine.x() + CloudworksDraftGeometry.WIDTH,
                                    machine.z() + CloudworksAreaGeometry.nodeHeight(
                                            machine.capabilities().slots() == null ? 0
                                                    : machine.capabilities().slots().slots().size())),
                            area == null ? .5D : area.threshold()))
                return true;
        return false;
    }

    public static boolean covered(CloudworksLineDraft draft, CloudworksLineDraft.Machine machine,
            String archetype) {
        if (!source(archetype) || machine == null) return false;
        ModuleArea area = environmentModule(archetype).area();
        for (CloudworksLineDraft.Channel channel : draft.channels)
            if (archetype.equals(channel.archetypeId) && channel.active && channel.waiting == null
                    && CloudworksAreaGeometry.exceeds(
                            area == null ? bounds(channel.x, channel.y, CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT)
                                    : area.bounds(channel.x, channel.y, CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT, 0),
                            new CloudworksAreaGeometry.Rect(machine.x, machine.y,
                                    machine.x + CloudworksDraftGeometry.WIDTH,
                                    machine.y + CloudworksDraftGeometry.height(machine)),
                            area == null ? .5D : area.threshold()))
                return true;
        return false;
    }
    public static int hits(CloudworksLineDraft draft, UUID sourceId) {
        for (CloudworksLineDraft.Channel channel : draft.channels)
            if (channel.id.equals(sourceId) && source(channel.archetypeId) && channel.active && channel.waiting == null)
                return hits(bounds(channel.x, channel.y, CloudworksDraftGeometry.ENVIRONMENT_SOURCE_HEIGHT),
                        netherSource(channel.archetypeId) ? targets(draft) : environmentTargets(draft));
        return 0;
    }
    public static int hits(CloudworksAreaGeometry.Rect source, List<CloudworksAreaGeometry.Target> targets) {
        return CloudworksAreaGeometry.targets(Collections.singletonList(source), targets, .5).size();
    }
    public static double heat(MachineNodeData node, CloudworksThermalPlan plan,
            List<CloudworksAreaGeometry.Rect> sources, int height) {
        if (!eligible(node.active(), node.waitingBlueprint() != null, node.machineCount(), plan)) return 0;
        boolean nether = CloudworksAreaGeometry.covered(sources, new CloudworksAreaGeometry.Rect(
                node.x(), node.z(), node.x() + CloudworksDraftGeometry.WIDTH, node.z() + height));
        double heat = nether ? (plan.netherEnvironmentHeat > 0 ? plan.netherEnvironmentHeat : netherHeat) : 0;
        if (plan.kind == CloudworksThermalPlan.Kind.HEAT_GENERATOR)
            heat += installed(node, MachineModuleType.LAVA_HEAT_ID) * plan.lavaEnvironmentHeat;
        if (!Double.isFinite(heat) || heat < 0) throw new IllegalArgumentException("Environment heat overflow");
        return heat;
    }
}
