package mekanism.cloudworks.client.gui.line;

import java.util.*;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksChannelNode.Mode;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.service.CloudworksDraftGeometry;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits;
import mekanism.common.Upgrade;
import net.minecraft.item.ItemStack;

/** Local draft and resource budget. No edit methods below send network messages. */
public final class CloudworksLineEditorModel {
    private static final int HISTORY_LIMIT = 50;
    public CloudworksLineDraft draft;
    public UUID selectedNode, selectedWire;
    public final Set<UUID> selection = new LinkedHashSet<>();
    public final List<CloudworksGuiData.Machine> machines = new ArrayList<>();
    private final List<CloudworksLineDraft.Machine> original = new ArrayList<>();
    private int originalSpeed, originalEnergy, poolSpeed, poolEnergy;
    public int machineSelection = -1, channelSelection = -1;
    public int dimension;
    public static final Mode[] CHANNELS = {Mode.RECEIVE_INPUT, Mode.TRANSFER_INPUT, Mode.TRANSFER_OUTPUT, Mode.SEND_OUTPUT};
    private final Deque<CloudworksLineDraft> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    public void begin(CloudworksLineDraft value, CloudworksGuiData data) {
        clearHistory();
        draft = value;
        refreshBudget(value, data);
        refreshUpgradeLimits();
        selectedNode = selectedWire = null;
        selection.clear();
        machineSelection = channelSelection = -1;
    }

    public void refreshBudget(CloudworksLineDraft persisted, CloudworksGuiData data) {
        ItemStack selected = machineSelection >= 0 && machineSelection < machines.size()
                ? machines.get(machineSelection).stack : ItemStack.EMPTY;
        machines.clear();
        machines.addAll(data.machineCache.types);
        machineSelection = -1;
        if (!selected.isEmpty()) for (int i = 0; i < machines.size(); i++)
            if (ItemStack.areItemsEqual(selected, machines.get(i).stack)) { machineSelection = i; break; }
        original.clear();
        for (CloudworksLineDraft.Machine machine : persisted.machines)
            original.add(new CloudworksLineDraft.Machine(machine.id, machine.type, machine.x, machine.y, machine.count));
        originalSpeed = persisted.speed;
        originalEnergy = persisted.energy;
        poolSpeed = data.overview.speedUpgradePool;
        poolEnergy = data.overview.energyUpgradePool;
    }

    public long available(int index) {
        if (draft == null || index < 0 || index >= machines.size()) return 0;
        CloudworksGuiData.Machine entry = machines.get(index);
        long count = entry.available;
        for (CloudworksLineDraft.Machine machine : original)
            if (ItemStack.areItemsEqual(machine.type, entry.stack)) count = Math.addExact(count, machine.count);
        for (CloudworksLineDraft.Machine machine : draft.machines)
            if (ItemStack.areItemsEqual(machine.type, entry.stack)) count -= machine.count;
        return Math.max(0, count);
    }

    public CloudworksLineDraft.Machine selectedMachine() {
        if (draft != null) for (CloudworksLineDraft.Machine machine : draft.machines)
            if (machine.id.equals(selectedNode)) return machine;
        return null;
    }

    public CloudworksLineDraft.Channel selectedChannel() {
        if (draft != null) for (CloudworksLineDraft.Channel channel : draft.channels)
            if (channel.id.equals(selectedNode)) return channel;
        return null;
    }

    public CloudworksLineDraft.Wire selectedLink() {
        if (draft != null) for (CloudworksLineDraft.Wire link : draft.links)
            if (link.id.equals(selectedWire)) return link;
        return null;
    }

    public void placeMachine(int x, int y) {
        if (!canPlaceMachine(machineSelection)) return;
        ItemStack type = machines.get(machineSelection).stack;
        CloudworksLineDraft.Machine selected = selectedMachine();
        recordUndo();
        if (selected != null && selected.waiting == null && ItemStack.areItemsEqual(selected.type, type)) selected.count++;
        else if (draft.machines.size() < draft.maxNodes) {
            selected = new CloudworksLineDraft.Machine(UUID.randomUUID(), type, x, y, 1);
            selected.dimension = dimension;
            draft.machines.add(selected);
        }
        if (selected != null) selectedNode = selected.id;
        selection.clear();
        if (selectedNode != null) selection.add(selectedNode);
        selectedWire = null;
        refreshUpgradeLimits();
    }

    public boolean canPlaceMachine(int index) {
        if (available(index) <= 0) return false;
        CloudworksLineDraft.Machine selected = selectedMachine();
        if (selected != null && selected.waiting != null) return false;
        if (selected != null && ItemStack.areItemsEqual(selected.type, machines.get(index).stack))
            return selected.count < draft.maxMachinesPerNode;
        return draft.machines.size() < draft.maxNodes;
    }

    public boolean adjustSelectedMachine(int delta) {
        CloudworksLineDraft.Machine selected = selectedMachine();
        if (selected == null || selected.waiting != null || machineSelection < 0 || machineSelection >= machines.size()
                || !ItemStack.areItemsEqual(selected.type, machines.get(machineSelection).stack)
                || delta == 0) return false;
        if (delta > 0) {
            if (available(machineSelection) <= 0 || selected.count >= draft.maxMachinesPerNode) return false;
            recordUndo();
            selected.count = Math.addExact(selected.count, 1);
        } else if (selected.count > 1) {
            recordUndo();
            selected.count--;
        } else {
            return false;
        }
        refreshUpgradeLimits();
        return true;
    }

    public boolean channelAvailable(int index) {
        if (draft == null || index < 0 || index >= CHANNELS.length) return false;
        Mode mode = CHANNELS[index];
        return mode.transfer() || draft.channels.stream().noneMatch(channel -> channel.mode == mode);
    }

    public void placeChannel(int x, int y) {
        if (!channelAvailable(channelSelection)) return;
        recordUndo();
        CloudworksLineDraft.Channel channel = new CloudworksLineDraft.Channel(UUID.randomUUID(), CHANNELS[channelSelection], x, y);
        draft.channels.add(channel);
        draft.explicitRouting = true;
        selectedNode = channel.id;
        selection.clear();
        selection.add(channel.id);
        selectedWire = null;
    }

    public void remove(boolean all) {
        CloudworksLineDraft.Machine machine = selectedMachine();
        if (machine != null && machine.waiting != null) all = true;
        if (machine != null && !all && machine.count > 1) {
            recordUndo();
            machine.count--;
            refreshUpgradeLimits();
            return;
        }
        UUID removed = selectedNode;
        if (removed == null) return;
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(removed);
        UUID waiting = machine == null ? null : machine.waiting;
        CloudworksLineDraft.Channel selected = selectedChannel();
        if (selected != null) waiting = selected.waiting;
        boolean cancelsBlueprint = waiting != null;
        if (cancelsBlueprint) {
            for (CloudworksLineDraft.Machine node : draft.machines) if (waiting.equals(node.waiting)) ids.add(node.id);
            for (CloudworksLineDraft.Channel channel : draft.channels) if (waiting.equals(channel.waiting)) ids.add(channel.id);
            clearHistory();
        } else recordUndo();
        CloudworksDraftGeometry.remove(draft, ids);
        selection.removeAll(ids);
        selectedNode = selection.stream().findFirst().orElse(null);
        if (draft.links.stream().noneMatch(wire -> wire.id.equals(selectedWire))) selectedWire = null;
        refreshUpgradeLimits();
    }

    public boolean moveSelection(int dx, int dy) {
        if (draft == null || (dx == 0 && dy == 0) || !CloudworksDraftGeometry.canMove(draft, selection, dx, dy)) return false;
        recordUndo();
        CloudworksDraftGeometry.move(draft, selection, dx, dy);
        return true;
    }

    public boolean removeSelection() {
        if (draft == null || selection.isEmpty()) return false;
        Set<UUID> removed = new LinkedHashSet<>(selection);
        Set<UUID> waitingGroups = new HashSet<>();
        for (CloudworksLineDraft.Machine machine : draft.machines)
            if (removed.contains(machine.id) && machine.waiting != null) waitingGroups.add(machine.waiting);
        for (CloudworksLineDraft.Channel channel : draft.channels)
            if (removed.contains(channel.id) && channel.waiting != null) waitingGroups.add(channel.waiting);
        if (!waitingGroups.isEmpty()) {
            for (CloudworksLineDraft.Machine machine : draft.machines)
                if (waitingGroups.contains(machine.waiting)) removed.add(machine.id);
            for (CloudworksLineDraft.Channel channel : draft.channels)
                if (waitingGroups.contains(channel.waiting)) removed.add(channel.id);
        }
        boolean cancelsBlueprint = !waitingGroups.isEmpty();
        if (cancelsBlueprint) clearHistory();
        else recordUndo();
        CloudworksDraftGeometry.remove(draft, removed);
        selection.removeAll(removed);
        if (!selection.contains(selectedNode)) selectedNode = selection.stream().findFirst().orElse(null);
        if (draft.links.stream().noneMatch(wire -> wire.id.equals(selectedWire))) selectedWire = null;
        refreshUpgradeLimits();
        return true;
    }

    public void addLink(CloudworksLineDraft.Wire wire) {
        if (draft == null) return;
        recordUndo();
        draft.links.add(wire);
    }

    public boolean removeLink(UUID id) {
        if (draft == null || id == null || draft.links.stream().noneMatch(wire -> wire.id.equals(id))) return false;
        recordUndo();
        draft.links.removeIf(wire -> wire.id.equals(id));
        selectedWire = null;
        return true;
    }

    public boolean setName(String name) {
        if (draft == null || name == null || draft.name.equals(name)) return false;
        recordUndo(); draft.name = name; return true;
    }

    public boolean addMarker(QIOResourceDescriptor descriptor) {
        if (draft == null || descriptor == null || !descriptor.isResolved()
                || draft.markers.size() >= CloudworksLineDraft.MAX_MARKERS || draft.markers.contains(descriptor)) return false;
        recordUndo(); draft.markers.add(descriptor); return true;
    }

    public boolean removeMarker(QIOResourceDescriptor descriptor) {
        if (draft == null || descriptor == null || !draft.markers.contains(descriptor)) return false;
        recordUndo(); draft.markers.remove(descriptor); return true;
    }

    public boolean setEnabled(boolean enabled) { return setBoolean(draft == null ? enabled : draft.enabled, enabled, 0); }
    public boolean setOffline(boolean enabled) { return setBoolean(draft == null ? enabled : draft.runWhileOwnerOffline, enabled, 1); }
    public boolean setReceivingFlow(boolean enabled) { return setBoolean(draft == null ? enabled : draft.receivingFlow, enabled, 2); }
    public boolean setSendingFlow(boolean enabled) { return setBoolean(draft == null ? enabled : draft.sendingFlow, enabled, 3); }

    private boolean setBoolean(boolean current, boolean next, int field) {
        if (draft == null || current == next) return false;
        recordUndo();
        if (field == 0) draft.enabled = next;
        else if (field == 1) draft.runWhileOwnerOffline = next;
        else if (field == 2) draft.receivingFlow = next;
        else draft.sendingFlow = next;
        return true;
    }

    public boolean setMachineActive(UUID id, boolean active) {
        CloudworksLineDraft.Machine machine = findMachine(id);
        if (machine == null || machine.active == active) return false;
        recordUndo(); machine.active = active; return true;
    }

    public boolean addChannelResource(UUID id, QIOResourceDescriptor descriptor) {
        CloudworksLineDraft.Channel channel = findChannel(id);
        if (channel == null || descriptor == null || !descriptor.isResolved()
                || channel.resources.stream().anyMatch(resource -> resource.descriptor.equals(descriptor))) return false;
        recordUndo(); channel.resources.add(new CloudworksLineDraft.Resource(descriptor, 1)); return true;
    }

    public boolean removeChannelResource(UUID id, QIOResourceDescriptor descriptor) {
        CloudworksLineDraft.Channel channel = findChannel(id);
        if (channel == null || descriptor == null
                || channel.resources.stream().noneMatch(resource -> resource.descriptor.equals(descriptor))) return false;
        recordUndo(); channel.resources.removeIf(resource -> resource.descriptor.equals(descriptor)); return true;
    }

    public boolean setChannelResourceAmount(UUID id, QIOResourceDescriptor descriptor, long amount) {
        CloudworksLineDraft.Channel channel = findChannel(id);
        if (channel == null || descriptor == null || amount <= 0) return false;
        for (CloudworksLineDraft.Resource resource : channel.resources) {
            if (!resource.descriptor.equals(descriptor) || resource.amount == amount) continue;
            recordUndo(); resource.amount = amount; return true;
        }
        return false;
    }

    public boolean updateLink(UUID id, int priority, double ratio) {
        CloudworksLineDraft.Wire wire = findLink(id);
        if (wire == null || wire.priority == priority && wire.ratio == ratio) return false;
        recordUndo(); wire.priority = priority; wire.ratio = ratio; return true;
    }

    public boolean addLinkFilter(UUID id, QIOResourceDescriptor descriptor) {
        CloudworksLineDraft.Wire wire = findLink(id);
        if (wire == null || descriptor == null || !descriptor.isResolved() || wire.filters.contains(descriptor)) return false;
        recordUndo(); wire.filters.add(descriptor); return true;
    }

    public boolean removeLinkFilter(UUID id, QIOResourceDescriptor descriptor) {
        CloudworksLineDraft.Wire wire = findLink(id);
        if (wire == null || descriptor == null || !wire.filters.contains(descriptor)) return false;
        recordUndo(); wire.filters.remove(descriptor); return true;
    }

    private CloudworksLineDraft.Machine findMachine(UUID id) {
        if (draft != null) for (CloudworksLineDraft.Machine machine : draft.machines)
            if (machine.id.equals(id)) return machine;
        return null;
    }

    private CloudworksLineDraft.Channel findChannel(UUID id) {
        if (draft != null) for (CloudworksLineDraft.Channel channel : draft.channels)
            if (channel.id.equals(id)) return channel;
        return null;
    }

    private CloudworksLineDraft.Wire findLink(UUID id) {
        if (draft != null) for (CloudworksLineDraft.Wire wire : draft.links)
            if (wire.id.equals(id)) return wire;
        return null;
    }

    public void accept(CloudworksLineDraft authoritative) {
        draft = authoritative;
        pruneSelection();
    }

    private void pruneSelection() {
        Set<UUID> present = new HashSet<>();
        draft.machines.forEach(machine -> present.add(machine.id));
        draft.channels.forEach(channel -> present.add(channel.id));
        selection.retainAll(present);
        if (!present.contains(selectedNode)) selectedNode = null;
        if (draft.links.stream().noneMatch(wire -> wire.id.equals(selectedWire))) selectedWire = null;
    }

    private void refreshUpgradeLimits() {
        draft.maxSpeed = CloudworksUpgradeLimits.capacity(Upgrade.SPEED, draft.machines.stream().mapToLong(machine -> machine.count));
        draft.maxEnergy = CloudworksUpgradeLimits.capacity(Upgrade.ENERGY, draft.machines.stream().mapToLong(machine -> machine.count));
        draft.speed = Math.min(draft.speed, draft.maxSpeed);
        draft.energy = Math.min(draft.energy, draft.maxEnergy);
    }

    public int pool(boolean speed) {
        long available = speed ? poolSpeed + (long) originalSpeed - draft.speed : poolEnergy + (long) originalEnergy - draft.energy;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, available));
    }

    public int installed(boolean speed) {
        return speed ? draft.speed : draft.energy;
    }

    public void upgrade(boolean speed, boolean install, boolean all) {
        refreshUpgradeLimits();
        int current = installed(speed), maximum = speed ? draft.maxSpeed : draft.maxEnergy;
        int amount = install ? Math.min(pool(speed), maximum - current) : current;
        if (!all) amount = Math.min(1, amount);
        int updated = current + (install ? amount : -amount);
        if (updated != current) {
            recordUndo();
            if (speed) draft.speed = updated; else draft.energy = updated;
        }
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    public void recordUndo() {
        if (draft == null) return;
        undo.addFirst(mekanism.cloudworks.common.network.CloudworksLineDraftCodec.copy(draft));
        trim(undo);
        redo.clear();
    }

    public boolean undo() {
        if (draft == null || undo.isEmpty()) return false;
        redo.addFirst(mekanism.cloudworks.common.network.CloudworksLineDraftCodec.copy(draft));
        trim(redo);
        draft = undo.removeFirst();
        pruneSelection();
        return true;
    }

    public boolean redo() {
        if (draft == null || redo.isEmpty()) return false;
        undo.addFirst(mekanism.cloudworks.common.network.CloudworksLineDraftCodec.copy(draft));
        trim(undo);
        draft = redo.removeFirst();
        pruneSelection();
        return true;
    }

    public void clearHistory() { undo.clear(); redo.clear(); }

    private static void trim(Deque<CloudworksLineDraft> history) {
        while (history.size() > HISTORY_LIMIT) history.removeLast();
    }
}
