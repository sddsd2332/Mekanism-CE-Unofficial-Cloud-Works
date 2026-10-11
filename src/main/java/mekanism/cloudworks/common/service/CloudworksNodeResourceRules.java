package mekanism.cloudworks.common.service;

import java.util.*;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksMachineAdapter;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.recipe.CloudworksFrozenRecipeView;
import mekanism.cloudworks.common.recipe.CloudworksInfuseResourceCodec;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import mekanism.cloudworks.common.registry.CloudworksMachineAdapters;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import mekanism.cloudworks.common.service.CloudworksDetachedState.Conversion;
import mekanism.cloudworks.common.service.CloudworksDetachedState.ResourceRules;

/** Owner-thread compiler for the worker's pure resource rules. */
public final class CloudworksNodeResourceRules {
    private CloudworksNodeResourceRules() {
    }

    public static ResourceRules prepare(List<ProductionLineData> lines, CloudworksResourceSymbols symbols) {
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
            for (CloudworksMachineAdapter adapter : machine.adapters())
                symbols.cachedFreeze(machine.keyString() + ":" + adapter.getClass().getName() + "#"
                        + CloudworksMachineAdapters.registrationId(adapter), adapter)
                        .ifPresent(view -> entries.addAll(view.entries()));
            if (machine.adapters().isEmpty() && machine.special() != null)
                symbols.cachedFreezeSpecial(machine.keyString() + ":special:" + machine.special().getClass().getName(),
                        machine.special()).ifPresent(view -> entries.addAll(view.entries()));
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
                    ports.computeIfAbsent(symbols.id(chance.descriptor()), ignored -> new HashSet<>()).add("");
                for (CloudworksResourceStack choice : entry.choiceOutputTemplate())
                    ports.computeIfAbsent(symbols.id(choice.descriptor()), ignored -> new HashSet<>()).add("");
            }
            affinity.put(machine.keyString(), ports);
            recipes.put(machine.keyString(), entries);
        }
        Map<String, Map<String, Set<Integer>>> inputPorts = new LinkedHashMap<>();
        List<QIOResourceDescriptor> resources = symbols.descriptors();
        recipes.forEach((machine, entries) -> {
            Map<String, Set<Integer>> ports = new LinkedHashMap<>();
            for (CloudworksFrozenRecipeView.Entry entry : entries)
                for (int index = 0; index < entry.inputCount(); index++) {
                    String port = entry.inputPort(index);
                    if (port.isEmpty()) continue;
                    Set<Integer> accepted = ports.computeIfAbsent(port, ignored -> new HashSet<>());
                    for (int id = 0; id < resources.size(); id++)
                        if (entry.inputMatcher(index).test(resources.get(id))) accepted.add(id);
                }
            for (CloudworksFrozenRecipeView.Entry entry : entries)
                for (int index = 0; index < entry.configurationCount(); index++) {
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
}
