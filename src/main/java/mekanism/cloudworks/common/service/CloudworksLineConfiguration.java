package mekanism.cloudworks.common.service;

import java.util.List;
import java.util.ArrayList;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.*;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;

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
        draft.maxMachinesPerNode = mekanism.cloudworks.common.config.CloudworksConfig.settings().maxMachinesPerNode;
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
            machine.dimension = node.dimensionId();
            machine.waiting = node.waitingBlueprint();
            machine.required = node.waitingCount();
            machine.missing = machine.required;
            draft.machines.add(machine);
        }
        for (CloudworksChannelNode node : line.channels()) {
            CloudworksLineDraft.Channel channel = new CloudworksLineDraft.Channel(node.id(), node.mode(), node.x(), node.z());
            channel.active = node.configuredEnabled();
            channel.waiting = node.waitingBlueprint();
            node.resources().forEach((resource, amount) -> channel.resources.add(new CloudworksLineDraft.Resource(resource, amount)));
            draft.channels.add(channel);
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
