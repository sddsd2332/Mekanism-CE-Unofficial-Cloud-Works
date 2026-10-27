package mekanism.cloudworks.common.service;

import java.util.*;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.recipe.CloudworksFrozenRecipeView;
import mekanism.cloudworks.api.CloudworksInfuseResourceCodec;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import mekanism.cloudworks.common.registry.CloudworksMachineAdapters;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.service.CloudworksDetachedState.Conversion;
import mekanism.cloudworks.common.service.CloudworksDetachedState.ResourceRules;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;
import mekanism.cloudworks.common.data.CloudworksOperation;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;

/** Owner-thread compiler for the worker's pure resource rules. */
public final class CloudworksNodeResourceRules {
    private CloudworksNodeResourceRules() {
    }

    public static ResourceRules prepare(List<ProductionLineData> lines, CloudworksResourceSymbols symbols) {
        return prepare(lines, symbols, Collections.emptyMap());
    }

    public static ResourceRules prepare(List<ProductionLineData> lines, CloudworksResourceSymbols symbols,
            Map<UUID, CloudworksGenerationPlan> generation) {
        Map<String, MachineNodeData.MachineCapabilities> machines = new LinkedHashMap<>();
        boolean infuser = false;
        for (ProductionLineData line : lines) {
            for (MachineNodeData node : line.machines()) {
                MachineNodeData.MachineCapabilities capabilities = node.capabilities();
                machines.putIfAbsent(capabilities.keyString(), capabilities);
                if (capabilities.slots() != null) for (MachineSlotLayout.Slot slot : capabilities.slots().slots())
                    if (slot.role() == MachineSlotLayout.PortRole.INPUT && slot.codecId().equals(CloudworksInfuseResourceCodec.ID))
                        infuser = true;
            }
        }
        Map<String, Map<Integer, Set<String>>> affinity = new LinkedHashMap<>();
        Map<String, List<CloudworksFrozenRecipeView.Entry>> recipes = new LinkedHashMap<>();
        // Register producer outputs before deriving conversions: e.g. an upstream recipe may produce coal.
        for (MachineNodeData.MachineCapabilities machine : machines.values()) {
            List<CloudworksFrozenRecipeView.Entry> entries = new ArrayList<>();
            for (CloudworksMachineAdapter adapter : machine.allAdapters())
                symbols.cachedFreeze(machine.keyString() + ":" + adapter.getClass().getName() + "#"
                        + CloudworksMachineAdapters.registrationId(adapter), adapter)
                        .ifPresent(view -> entries.addAll(view.entries()));
            Map<Integer, Set<String>> ports = new LinkedHashMap<>();
            for (CloudworksFrozenRecipeView.Entry entry : entries) {
                for (int i = 0; i < entry.outputTemplate().size(); i++) {
                    CloudworksResourceStack stack = entry.outputTemplate().get(i);
                    String port = entry.outputPort(i);
                    if (!port.isEmpty()) {
                        MachineSlotLayout.Slot slot = machine.slots() == null ? null : machine.slots().find(port);
                        if (slot == null || slot.role() != MachineSlotLayout.PortRole.OUTPUT
                                || !slot.codecId().equals(stack.descriptor().getCodecId()))
                            throw new IllegalArgumentException("Recipe output references an incompatible port: " + port);
                    }
                    ports.computeIfAbsent(symbols.id(stack.descriptor()), ignored -> new HashSet<>()).add(port);
                }
                for (CloudworksRecipeBatch.ChanceOutput chance : entry.chanceOutputTemplate())
                    ports.computeIfAbsent(symbols.id(chance.descriptor()), ignored -> new HashSet<>()).add(chance.port());
                for (CloudworksResourceStack choice : entry.choiceOutputTemplate())
                    ports.computeIfAbsent(symbols.id(choice.descriptor()), ignored -> new HashSet<>()).add("");
            }
            // A recipe switch changes future work immediately, but an already
            // reserved operation must still be able to publish its promised outputs.
            for (ProductionLineData line : lines) for (MachineNodeData node : line.machines()) {
                if (!machine.keyString().equals(node.capabilities().keyString()) || !node.hasOperations()) continue;
                MachineSlotLayout executionLayout = CloudworksNodeMechanisms.executionSlots(node);
                if (executionLayout == null) continue;
                for (CloudworksOperation pending : node.allOperations())
                for (CloudworksResourceStack output : pending.outputs())
                    for (MachineSlotLayout.Slot slot : executionLayout.slots())
                        if (slot.role() == MachineSlotLayout.PortRole.OUTPUT
                                && slot.codecId().equals(output.descriptor().getCodecId()))
                            ports.computeIfAbsent(symbols.id(output.descriptor()), ignored -> new HashSet<>())
                                    .add(slot.id());
            }
            affinity.put(machine.keyString(), ports);
            recipes.put(machine.keyString(), entries);
        }
        for (ProductionLineData line : lines) for (MachineNodeData node : line.machines()) {
            Map<Integer, Set<String>> ports = affinity.get(node.capabilities().keyString());
            addGenerationPorts(ports, generation.get(node.nodeId()), symbols);
            for (CloudworksNodeMechanisms.Branch branch : node.capabilities().branches())
                addGenerationPorts(ports, generation.get(CloudworksNodeMechanisms
                        .branchId(node.nodeId(), branch.id)), symbols);
        }
        Map<String, Map<String, Set<Integer>>> inputPorts = new LinkedHashMap<>();
        List<QIOResourceDescriptor> resources = symbols.descriptors();
        recipes.forEach((machine, entries) -> {
            Map<String, Set<Integer>> ports = new LinkedHashMap<>();
            MachineSlotLayout layout = machines.get(machine).slots();
            Set<String> explicitInputs = new HashSet<>();
            for (CloudworksFrozenRecipeView.Entry entry : entries)
                for (int index = 0; index < entry.inputCount(); index++)
                    if (!entry.inputPort(index).isEmpty()) explicitInputs.add(entry.inputPort(index));
            if (layout != null && machines.get(machine).thermal() == null)
                for (MachineSlotLayout.Slot slot : layout.slots())
                    if (slot.role() == MachineSlotLayout.PortRole.INPUT && slot.recipeFilteredInput())
                        ports.put(slot.id(), new HashSet<>());
            for (CloudworksFrozenRecipeView.Entry entry : entries)
                for (int index = 0; index < entry.inputCount(); index++) {
                    String port = entry.inputPort(index);
                    if (port.isEmpty()) {
                        boolean implicitPort = false;
                        if (layout != null) for (MachineSlotLayout.Slot slot : layout.slots())
                            if (slot.role() == MachineSlotLayout.PortRole.INPUT
                                    && slot.codecId().equals(entry.inputCodec(index)) && !explicitInputs.contains(slot.id()))
                                implicitPort = true;
                        if (layout != null) for (MachineSlotLayout.Slot slot : layout.slots()) {
                            if (slot.role() != MachineSlotLayout.PortRole.INPUT || !slot.recipeFilteredInput()) continue;
                            if (implicitPort && explicitInputs.contains(slot.id())) continue;
                            Set<Integer> accepted = ports.computeIfAbsent(slot.id(), ignored -> new HashSet<>());
                            for (int id = 0; id < resources.size(); id++)
                                if (slot.codecId().equals(resources.get(id).getCodecId())
                                        && entry.inputMatcher(index).test(resources.get(id))) accepted.add(id);
                        }
                        continue;
                    }
                    MachineSlotLayout.Slot slot = layout == null ? null : layout.find(port);
                    if (slot != null && !slot.recipeFilteredInput()) continue;
                    Set<Integer> accepted = ports.computeIfAbsent(port, ignored -> new HashSet<>());
                    for (int id = 0; id < resources.size(); id++)
                        if (entry.inputMatcher(index).test(resources.get(id))) accepted.add(id);
                }
            for (CloudworksFrozenRecipeView.Entry entry : entries)
                for (int index = 0; index < entry.configurationCount(); index++) {
                    MachineSlotLayout.Slot slot = layout == null ? null : layout.find(entry.configurationPort(index));
                    if (slot != null && !slot.recipeFilteredInput()) continue;
                    Set<Integer> accepted = ports.computeIfAbsent(entry.configurationPort(index), ignored -> new HashSet<>());
                    for (int id = 0; id < resources.size(); id++)
                        if (entry.configurationMatcher(index).test(resources.get(id))) accepted.add(id);
                }
            inputPorts.put(machine, ports);
        });
        Map<Integer, Conversion> conversions = new LinkedHashMap<>();
        if (infuser) {
            List<QIOResourceDescriptor> known = symbols.descriptors();
            for (int id = 0; id < known.size(); id++) {
                QIOResourceDescriptor descriptor = known.get(id);
                if (!QIOResourceCodecs.ITEM_STACK_ID.equals(descriptor.getCodecId())) continue;
                CloudworksNativeContracts.Infusion object = CloudworksNativeContracts.infusion(descriptor);
                if (object == null) continue;
                int target = symbols.id(object.output);
                conversions.put(id, new Conversion(target, object.amount));
            }
        }
        Set<Integer> infuseTypes = new HashSet<>();
        List<String> codecs = symbols.codecs();
        for (int id = 0; id < codecs.size(); id++)
            if (CloudworksInfuseResourceCodec.ID.toString().equals(codecs.get(id))) infuseTypes.add(id);
        return new ResourceRules(conversions, infuseTypes, affinity, inputPorts);
    }

    private static void addGenerationPorts(Map<Integer, Set<String>> ports,
            CloudworksGenerationPlan plan, CloudworksResourceSymbols symbols) {
        if (plan == null) return;
        for (CloudworksGenerationPlan.ResourceOutput output : plan.resourceOutputs())
            ports.computeIfAbsent(symbols.id(output.resource()), ignored -> new HashSet<>()).add(output.port());
    }
}
