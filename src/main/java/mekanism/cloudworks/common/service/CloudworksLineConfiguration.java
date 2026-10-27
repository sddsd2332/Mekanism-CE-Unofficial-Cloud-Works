package mekanism.cloudworks.common.service;

import java.util.List;
import java.util.ArrayList;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.*;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import mekanism.cloudworks.common.config.CloudworksConfig;
import net.minecraft.util.ResourceLocation;

/** Projection shared by the observer and the editor snapshot. */
public final class CloudworksLineConfiguration {
    private CloudworksLineConfiguration() {
    }

    public static List<QIOResourceDescriptor> markers(ProductionLineData line) {
        return markers(line.dynamicResourceDisplayList());
    }

    public static List<QIOResourceDescriptor> markers(List<String> display) {
        List<QIOResourceDescriptor> result = new ArrayList<>();
        for (String encoded : display) {
            try {
                QIOResourceDescriptor descriptor = QIOResourceDescriptor.read(JsonToNBT.getTagFromJson(encoded));
                if (descriptor != null && descriptor.isResolved() && !result.contains(descriptor)) result.add(descriptor);
            } catch (NBTException | IllegalArgumentException ignored) {
                // Old display-only entries need not prevent opening a line.
            }
            if (result.size() == CloudworksLineDraft.MAX_MARKERS) break;
        }
        return result;
    }

    public static CloudworksLineDraft capture(ProductionLineData line) {
        CloudworksLineDraft draft = new CloudworksLineDraft();
        draft.line = line.lineId();
        draft.name = line.name();
        draft.enabled = line.enabled();
        draft.maxMachinesPerNode = CloudworksConfig.settings().maxMachinesPerNode;
        draft.nodeBufferMultiplier = line.nodeBufferMultiplier();
        draft.runWhileOwnerOffline = line.runWhileOwnerOffline();
        draft.explicitRouting = line.explicitRouting();
        draft.receivingFlow = line.energyFlowReceivingToLine();
        draft.sendingFlow = line.energyFlowLineToSending();
        draft.speed = line.speedUpgrades();
        draft.energy = line.energyUpgrades();
        draft.maxSpeed = line.maxSpeedUpgrades();
        draft.maxEnergy = line.maxEnergyUpgrades();
        draft.markers.addAll(markers(line));
        for (MachineNodeData node : line.machines()) {
            CloudworksLineDraft.Machine machine = new CloudworksLineDraft.Machine(node.nodeId(),
                    CloudworksRegistries.identityKey(node.machine()).displayStack(), node.x(), node.z(), node.machineCount());
            machine.active = node.configuredActive();
            machine.recipeType = node.recipeType();
            machine.oreRules.addAll(node.oreRules());
            machine.fusionInjectionRate = node.fusionInjectionRate();
            for (ResourceLocation id : node.customUpgrades().ids())
                machine.assignedCustom(id.toString(), node.customUpgrades().count(id));
            machine.waiting = node.waitingBlueprint();
            machine.waitingMechanism = node.waitingMechanism();
            machine.required = node.waitingCount();
            machine.missing = machine.required;
            draft.machines.add(machine);
        }
        for (CloudworksChannelNode node : line.channels()) {
            CloudworksLineDraft.Channel channel = new CloudworksLineDraft.Channel(
                    node.id(), node.mode(), node.archetypeId(), node.x(), node.z());
            channel.active = node.configuredEnabled();
            channel.waiting = node.waitingBlueprint();
            channel.config = node.config().copy();
            node.resources().forEach((resource, amount) -> channel.resources.add(new CloudworksLineDraft.Resource(resource, amount)));
            draft.channels.add(channel);
        }
        for (CloudworksAuxiliaryNodeData node : line.auxiliaries()) {
            CloudworksLineDraft.Auxiliary auxiliary = new CloudworksLineDraft.Auxiliary(
                    node.nodeId(), node.type(), node.x(), node.z());
            auxiliary.active = node.active();
            auxiliary.range = node.range();
            auxiliary.targets.addAll(node.linkedTargets());
            node.input().snapshot().forEach((resource, amount) ->
                    auxiliary.resources.add(new CloudworksLineDraft.Resource(resource, amount)));
            draft.auxiliaries.add(auxiliary);
        }
        for (ProductionLineLink link : line.links()) {
            CloudworksLineDraft.Wire wire = new CloudworksLineDraft.Wire(link.linkId(), link.sourceNode(),
                    link.sourcePort(), link.targetNode(), link.targetPort());
            wire.enabled = link.enabled();
            wire.priority = link.priority();
            wire.ratio = link.ratio();
            wire.filters.addAll(link.resourceFilters());
            draft.links.add(wire);
        }
        return draft;
    }
}
