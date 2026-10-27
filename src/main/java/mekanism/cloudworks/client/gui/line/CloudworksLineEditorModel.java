package mekanism.cloudworks.client.gui.line;

import java.util.*;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksChannelNode.Mode;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.service.CloudworksDraftGeometry;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.cloudworks.api.environment.EnvironmentAxisRegistry;
import mekanism.common.Upgrade;
import net.minecraft.item.ItemStack;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.network.CloudworksLineDraftCodec;
import mekanism.cloudworks.common.registry.CloudworksAuxiliaryNodeRegistry;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineKey;
import mekanism.cloudworks.common.service.CloudworksNodeEnvironment;
import net.minecraft.util.ResourceLocation;

/**
 * Local draft and resource budget. No edit methods below send network messages.
 */
public final class CloudworksLineEditorModel {
    private static final int HISTORY_LIMIT = 50;
    public CloudworksLineDraft draft;
    public UUID selectedNode, selectedWire;
    public final Set<UUID> selection = new LinkedHashSet<>();
    public final List<CloudworksGuiData.Machine> machines = new ArrayList<>();
    private final List<CloudworksLineDraft.Machine> original = new ArrayList<>();
    private long machineListVersion;
    private long machineUsageVersion;
    private long draftVersion;
    private long availableVersion = -1;
    private long[] availableCounts = new long[0];
    private int originalSpeed, originalEnergy, poolSpeed, poolEnergy;
    private final Map<String, Long> customCloudPool = new LinkedHashMap<>();
    private final Map<String, Long> customLineBacking = new LinkedHashMap<>();
    private final Map<String, Long> customAssigned = new LinkedHashMap<>();
    private CloudworksLineDraft customBudgetDraft;
    private long customBudgetVersion, cachedCustomBudgetVersion = -1;
    private final List<CloudworksGuiData.CustomUpgrade> customPoolEntries = new ArrayList<>();
    private CloudworksGuiData seenData;

    private final List<QIOResourceDescriptor> infiniteResources = new ArrayList<>();
    private final Map<QIOResourceDescriptor, CloudworksGuiData.SpecialResource> infinitePermissions = new LinkedHashMap<>();
    private long netherLimit;
    private int netherUsedOutside;
    private final Map<String, Long> environmentLimits = new LinkedHashMap<>();
    public int machineSelection = -1, channelSelection = -1;
    public static final Mode[] CHANNELS = {Mode.RECEIVE_INPUT, Mode.TRANSFER_INPUT, Mode.TRANSFER_OUTPUT, Mode.SEND_OUTPUT};
    private final Deque<CloudworksLineDraft> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private String notice = "";
    private boolean clientActionsAllowed;

    public void begin(CloudworksLineDraft value, CloudworksGuiData data) {
        clearHistory();
        draft = value;
        refreshBudget(value, data);
        refreshUpgradeLimits();
        selectedNode = selectedWire = null;
        selection.clear();
        machineSelection = channelSelection = -1;
    }

    public void allowClientActions(boolean allowed) {
        clientActionsAllowed = allowed;
    }

    public boolean clientActionsAllowed(CloudworksLineDraft expectedDraft) {
        return clientActionsAllowed && expectedDraft != null && draft == expectedDraft;
    }

    public void refreshBudget(CloudworksLineDraft persisted, CloudworksGuiData data) {
        ItemStack selected = machineSelection >= 0 && machineSelection < machines.size()
                ? machines.get(machineSelection).stack : ItemStack.EMPTY;
        machines.clear();
        machines.addAll(data.machineCache.types);
        machineSelection = -1;
        if (!selected.isEmpty()) for (int i = 0; i < machines.size(); i++)
            if (sameMachineType(selected, machines.get(i).stack)) {
                machineSelection = i;
                break;
            }
        original.clear();
        for (CloudworksLineDraft.Machine machine : persisted.machines)
            original.add(new CloudworksLineDraft.Machine(machine.id, machine.type, machine.x, machine.y, machine.count));
        originalSpeed = persisted.speed;
        originalEnergy = persisted.energy;
        poolSpeed = data.overview.speedUpgradePool;
        poolEnergy = data.overview.energyUpgradePool;
        customCloudPool.clear();
        customLineBacking.clear();
        customPoolEntries.clear();
        Map<String, CloudworksGuiData.CustomUpgrade> customTypes = new LinkedHashMap<>();
        // null overview catalogue = on-demand view closed; graph-side entries still merge in.
        if (data.overview.customUpgrades != null)
            for (CloudworksGuiData.CustomUpgrade entry : data.overview.customUpgrades) {
                customCloudPool.put(entry.id, entry.count);
                customTypes.put(entry.id, entry);
            }
        for (CloudworksGuiData.CustomUpgrade entry : data.graph.customUpgrades) {
            customLineBacking.put(entry.id, entry.count);
            customTypes.putIfAbsent(entry.id, entry);
        }
        customPoolEntries.addAll(customTypes.values());
        seenData = data;
        netherLimit = data.overview.netherHeatSourceLimit();
        netherUsedOutside = data.overview.netherHeatSourcesUsed;
        environmentLimits.clear();
        for (CloudworksGuiData.EffectLevel effect : data.overview.effectLevels)
            environmentLimits.put(effect.id, effect.level);
        infiniteResources.clear();
        infinitePermissions.clear();
        for (CloudworksGuiData.SpecialResource resource : data.overview.infiniteResources) {
            infiniteResources.add(resource.descriptor);
            infinitePermissions.put(resource.descriptor, resource);
        }
        invalidateMachineList();
    }

    public long machineListVersion() {
        return machineListVersion;
    }

    public long machineUsageVersion() {
        return machineUsageVersion;
    }

    /**
     * Monotonic local draft revision used by the canvas render cache.
     */
    public long draftVersion() {
        return draftVersion;
    }

    public long available(int index) {
        if (draft == null || index < 0 || index >= machines.size()) return 0;
        if (availableVersion != machineListVersion) rebuildAvailableCounts();
        return availableCounts[index];
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
        if (selected != null && selected.waiting == null && sameMachineType(selected.type, type)) selected.count++;
        else if (draft.machines.size() < draft.maxNodes) {
            selected = new CloudworksLineDraft.Machine(UUID.randomUUID(), type, x, y, 1);
            draft.machines.add(selected);
        } else {
            return;
        }
        invalidateMachineList();
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
        if (selected != null && sameMachineType(selected.type, machines.get(index).stack))
            return selected.count < draft.maxMachinesPerNode;
        return draft.machines.size() < draft.maxNodes;
    }

    public boolean canPlaceAuxiliary(int index) {
        if (draft == null || index < 0 || index >= machines.size() || available(index) <= 0)
            return false;
        return CloudworksAuxiliaryNodeRegistry.byItem(
                machines.get(index).stack) != null;
    }

    public void placeAuxiliary(int x, int y) {
        if (!canPlaceAuxiliary(machineSelection)) return;
        ItemStack type = machines.get(machineSelection).stack;
        recordUndo();
        CloudworksLineDraft.Auxiliary auxiliary =
                new CloudworksLineDraft.Auxiliary(UUID.randomUUID(), type, x, y);
        draft.auxiliaries.add(auxiliary);
        selectedNode = auxiliary.id;
        selection.clear();
        selection.add(auxiliary.id);
        selectedWire = null;
    }

    public boolean adjustSelectedMachine(int delta) {
        CloudworksLineDraft.Machine selected = selectedMachine();
        if (selected == null || selected.waiting != null || machineSelection < 0 || machineSelection >= machines.size()
                || !sameMachineType(selected.type, machines.get(machineSelection).stack)
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
        invalidateMachineList();
        refreshUpgradeLimits();
        return true;
    }

    private static boolean sameMachineType(ItemStack first, ItemStack second) {
        if (first.getItem() != second.getItem() || first.getMetadata() != second.getMetadata()) return false;
        return CloudworksRegistries.identityKey(first)
                .equals(CloudworksRegistries.identityKey(second));
    }

    public boolean channelAvailable(int index) {
        if (draft == null || index < 0 || index >= channelCount()) return false;
        if (index >= CHANNELS.length) {
            if (CloudworksNodeEnvironment.source(channelArchetype(index)))
                return environmentRemaining(channelArchetype(index)) > 0;
            return !infiniteResources.isEmpty();
        }
        Mode mode = channelMode(index);
        return mode.transfer() || draft.channels.stream().noneMatch(
                channel -> channel.archetypeId.isEmpty() && channel.mode == mode);
    }

    public void placeChannel(int x, int y) {
        if (!channelAvailable(channelSelection)) return;
        recordUndo();
        String archetype = channelArchetype(channelSelection);
        CloudworksLineDraft.Channel channel = new CloudworksLineDraft.Channel(
                UUID.randomUUID(), channelMode(channelSelection), archetype, x, y);
        draft.channels.add(channel);
        draft.explicitRouting = true;
        selectedNode = channel.id;
        selection.clear();
        selection.add(channel.id);
        selectedWire = null;
    }

    public int channelCount() {
        int count = CHANNELS.length + (infiniteResources.isEmpty() ? 0 : 1);
        for (String id : environmentArchetypes())
            if (environmentLimits.getOrDefault(effectForArchetype(id), 0L) > 0) count++;
        return count;
    }

    /**
     * Environment sources have their own palette; resource sources follow the I/O direction filter.
     */
    public List<Integer> channelPaletteIndices(boolean environment, int directionFilter) {
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < channelCount(); index++) {
            boolean environmentSource = CloudworksNodeEnvironment.source(
                    channelArchetype(index));
            if (environmentSource != environment) continue;
            if (!environment) {
                boolean input = channelMode(index).input();
                if (directionFilter == 1 && !input || directionFilter == 2 && input) continue;
            }
            indices.add(index);
        }
        return Collections.unmodifiableList(indices);
    }

    public Mode channelMode(int index) {
        return index < CHANNELS.length ? CHANNELS[index] : Mode.RECEIVE_INPUT;
    }

    public String channelArchetype(int index) {
        if (index < CHANNELS.length) return "";
        int cursor = CHANNELS.length;
        if (!infiniteResources.isEmpty() && index == cursor)
            return CloudworksSpecialNodeArchetypes.INFINITE_RESOURCES.toString();
        if (!infiniteResources.isEmpty()) cursor++;
        for (String id : environmentArchetypes()) {
            if (environmentLimits.getOrDefault(effectForArchetype(id), 0L) <= 0) continue;
            if (index == cursor) return id;
            cursor++;
        }
        return "";
    }

    private String[] environmentArchetypes() {
        List<String> result = new ArrayList<>();
        for (EnvironmentAxis axis : EnvironmentAxisRegistry.all())
            result.add(axis.id().toString());
        return result.toArray(new String[0]);
    }

    private String effectForArchetype(String archetype) {
        EnvironmentAxis axis = EnvironmentAxisRegistry.byIdString(archetype);
        return axis == null ? "" : axis.effectId().toString();
    }

    private long environmentRemaining(String archetype) {
        long limit = environmentLimits.getOrDefault(effectForArchetype(archetype), 0L);
        long used = draft.channels.stream().filter(channel -> archetype.equals(channel.archetypeId)).count();
        return Math.max(0L, limit - used);
    }

    public List<QIOResourceDescriptor> infiniteResources() {
        return Collections.unmodifiableList(infiniteResources);
    }

    public boolean canSelectInfiniteResource(UUID node, QIOResourceDescriptor descriptor) {
        CloudworksGuiData.SpecialResource permission = infinitePermissions.get(descriptor);
        if (permission == null || draft == null) return false;
        if (permission.unlimitedPlacement) return true;
        long used = permission.usedOutsideLine;
        for (CloudworksLineDraft.Channel channel : draft.channels)
            if (!channel.id.equals(node) && !channel.archetypeId.isEmpty())
                for (CloudworksLineDraft.Resource resource : channel.resources)
                    if (descriptor.equals(resource.descriptor)) used++;
        return used < permission.nodeLimit;
    }

    public void remove(boolean all) {
        CloudworksLineDraft.Machine machine = selectedMachine();
        if (machine != null && machine.waiting != null) all = true;
        if (machine != null && !all && machine.count > 1) {
            recordUndo();
            machine.count--;
            invalidateMachineList();
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
            for (CloudworksLineDraft.Channel channel : draft.channels)
                if (waiting.equals(channel.waiting)) ids.add(channel.id);
            clearHistory();
        } else recordUndo();
        CloudworksDraftGeometry.remove(draft, ids);
        invalidateMachineList();
        selection.removeAll(ids);
        selectedNode = selection.stream().findFirst().orElse(null);
        if (draft.links.stream().noneMatch(wire -> wire.id.equals(selectedWire))) selectedWire = null;
        refreshUpgradeLimits();
    }

    public boolean moveSelection(int dx, int dy) {
        if (draft == null || (dx == 0 && dy == 0) || !CloudworksDraftGeometry.canMove(draft, selection, dx, dy))
            return false;
        recordUndo();
        CloudworksDraftGeometry.move(draft, selection, dx, dy);
        machineUsageVersion++;
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
        invalidateMachineList();
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
        recordUndo();
        draft.name = name;
        return true;
    }

    public boolean addMarker(QIOResourceDescriptor descriptor) {
        if (draft == null || descriptor == null || !descriptor.isResolved()
                || draft.markers.size() >= CloudworksLineDraft.MAX_MARKERS || draft.markers.contains(descriptor))
            return false;
        recordUndo();
        draft.markers.add(descriptor);
        return true;
    }

    public boolean removeMarker(QIOResourceDescriptor descriptor) {
        if (draft == null || descriptor == null || !draft.markers.contains(descriptor)) return false;
        recordUndo();
        draft.markers.remove(descriptor);
        return true;
    }

    public boolean setEnabled(boolean enabled) {
        return setBoolean(draft == null ? enabled : draft.enabled, enabled, 0);
    }

    public boolean setOffline(boolean enabled) {
        return setBoolean(draft == null ? enabled : draft.runWhileOwnerOffline, enabled, 1);
    }

    public boolean setReceivingFlow(boolean enabled) {
        return setBoolean(draft == null ? enabled : draft.receivingFlow, enabled, 2);
    }

    public boolean setSendingFlow(boolean enabled) {
        return setBoolean(draft == null ? enabled : draft.sendingFlow, enabled, 3);
    }

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
        recordUndo();
        machine.active = active;
        return true;
    }

    public boolean setChannelActive(UUID id, boolean active) {
        CloudworksLineDraft.Channel channel = findChannel(id);
        if (channel == null || channel.waiting != null || channel.active == active) return false;
        recordUndo();
        channel.active = active;
        return true;
    }

    public boolean setMachineRecipeType(UUID id, String typeId) {
        CloudworksLineDraft.Machine machine = findMachine(id);
        if (machine == null || machine.waiting != null || machine.recipeType.equals(typeId)
                || !CloudworksRegistries.acceptsRecipeType(machine.type, typeId))
            return false;
        recordUndo();
        machine.recipeType = typeId;
        int removed = 0;
        MachineSlotLayout layout =
                CloudworksNodeMechanisms.slots(machine);
        for (Iterator<CloudworksLineDraft.Wire> iterator = draft.links.iterator(); iterator.hasNext(); ) {
            CloudworksLineDraft.Wire wire = iterator.next();
            if (!wire.source.equals(id) && !wire.target.equals(id)) continue;
            boolean valid = true;
            if (wire.source.equals(id)) {
                MachineSlotLayout.Slot slot =
                        layout == null ? null : layout.find(wire.sourcePort);
                valid = slot != null
                        && slot.role() == MachineSlotLayout.PortRole.OUTPUT
                        && wireCodec(wire.target, wire.targetPort).equals(slot.codecId().toString());
            }
            if (valid && wire.target.equals(id)) {
                MachineSlotLayout.Slot slot =
                        layout == null ? null : layout.find(wire.targetPort);
                valid = slot != null && slot.role().isInput();
                if (valid) valid = wireCodec(wire.source, wire.sourcePort).equals(slot.codecId().toString());
            }
            if (!valid) {
                iterator.remove();
                if (Objects.equals(selectedWire, wire.id)) selectedWire = null;
                removed++;
            }
        }
        if (removed > 0) notice = "cloudworks.editor.recipe_links_removed";
        return true;
    }

    public boolean setOreRules(UUID id, java.util.List<mekanism.cloudworks.common.data.CloudworksOreRule> rules) {
        CloudworksLineDraft.Machine machine = findMachine(id);
        if (machine == null || machine.oreRules.equals(rules)) return false;
        recordUndo(); machine.oreRules.clear(); machine.oreRules.addAll(rules); return true;
    }
    public boolean setFusionInjectionRate(UUID id, int rate) {
        CloudworksLineDraft.Machine machine = findMachine(id);
        if (machine == null || machine.waiting != null || machine.fusionInjectionRate == rate
                || !mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.isController(machine.type)) return false;
        mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.validateInjection(rate);
        recordUndo(); machine.fusionInjectionRate = rate; return true;
    }

    private String wireCodec(UUID node, String port) {
        if (draft == null) return "";
        for (CloudworksLineDraft.Machine machine : draft.machines) {
            if (!machine.id.equals(node)) continue;
            MachineSlotLayout layout =
                    CloudworksNodeMechanisms.slots(machine);
            MachineSlotLayout.Slot slot = layout == null ? null : layout.find(port);
            return slot == null ? "" : slot.codecId().toString();
        }
        for (CloudworksLineDraft.Channel channel : draft.channels) {
            if (!channel.id.equals(node)) continue;
            if (!channel.archetypeId.isEmpty())
                return channel.resources.isEmpty() ? "" :
                        channel.resources.get(0).descriptor.getCodecId().toString();
            return port;
        }
        return "";
    }

    public String takeNotice() {
        String value = notice;
        notice = "";
        return value;
    }

    public boolean addChannelResource(UUID id, QIOResourceDescriptor descriptor) {
        CloudworksLineDraft.Channel channel = findChannel(id);
        if (channel == null || descriptor == null || !descriptor.isResolved()
                || channel.resources.stream().anyMatch(resource -> resource.descriptor.equals(descriptor)))
            return false;
        if (!channel.archetypeId.isEmpty()) {
            if (!canSelectInfiniteResource(id, descriptor)) return false;
            recordUndo();
            channel.resources.clear();
            channel.resources.add(new CloudworksLineDraft.Resource(descriptor, 1));
            removeInvalidChannelLinks(id, descriptor.getCodecId().toString());
        } else {
            recordUndo();
            channel.resources.add(new CloudworksLineDraft.Resource(descriptor, 1));
        }
        return true;
    }

    public boolean removeChannelResource(UUID id, QIOResourceDescriptor descriptor) {
        CloudworksLineDraft.Channel channel = findChannel(id);
        if (channel == null || descriptor == null
                || channel.resources.stream().noneMatch(resource -> resource.descriptor.equals(descriptor)))
            return false;
        recordUndo();
        channel.resources.removeIf(resource -> resource.descriptor.equals(descriptor));
        if (!channel.archetypeId.isEmpty()) removeInvalidChannelLinks(id, "");
        return true;
    }

    public boolean setChannelResourceAmount(UUID id, QIOResourceDescriptor descriptor, long amount) {
        CloudworksLineDraft.Channel channel = findChannel(id);
        if (channel == null || descriptor == null || amount <= 0 || !channel.archetypeId.isEmpty()) return false;
        for (CloudworksLineDraft.Resource resource : channel.resources) {
            if (!resource.descriptor.equals(descriptor) || resource.amount == amount) continue;
            recordUndo();
            resource.amount = amount;
            return true;
        }
        return false;
    }

    public boolean updateLink(UUID id, int priority, double ratio) {
        CloudworksLineDraft.Wire wire = findLink(id);
        if (wire == null || wire.priority == priority && wire.ratio == ratio) return false;
        recordUndo();
        wire.priority = priority;
        wire.ratio = ratio;
        return true;
    }

    public boolean addLinkFilter(UUID id, QIOResourceDescriptor descriptor) {
        CloudworksLineDraft.Wire wire = findLink(id);
        if (wire == null || descriptor == null || !descriptor.isResolved() || wire.filters.contains(descriptor))
            return false;
        recordUndo();
        wire.filters.add(descriptor);
        return true;
    }

    public boolean removeLinkFilter(UUID id, QIOResourceDescriptor descriptor) {
        CloudworksLineDraft.Wire wire = findLink(id);
        if (wire == null || descriptor == null || !wire.filters.contains(descriptor)) return false;
        recordUndo();
        wire.filters.remove(descriptor);
        return true;
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

    private void removeInvalidChannelLinks(UUID channelId, String codec) {
        int removed = 0;
        for (Iterator<CloudworksLineDraft.Wire> iterator = draft.links.iterator(); iterator.hasNext(); ) {
            CloudworksLineDraft.Wire wire = iterator.next();
            if (!wire.source.equals(channelId) && !wire.target.equals(channelId)) continue;
            String port = wire.source.equals(channelId) ? wire.sourcePort : wire.targetPort;
            if (!port.equals(codec)) {
                iterator.remove();
                if (Objects.equals(selectedWire, wire.id)) selectedWire = null;
                removed++;
            }
        }
        if (removed > 0) notice = "cloudworks.editor.resource_links_removed";
    }

    public void accept(CloudworksLineDraft authoritative) {
        draft = authoritative;
        invalidateMachineList();
        pruneSelection();
    }

    private void pruneSelection() {
        Set<UUID> present = new HashSet<>();
        draft.machines.forEach(machine -> present.add(machine.id));
        draft.channels.forEach(channel -> present.add(channel.id));
        draft.auxiliaries.forEach(auxiliary -> present.add(auxiliary.id));
        selection.retainAll(present);
        if (!present.contains(selectedNode)) selectedNode = null;
        if (draft.links.stream().noneMatch(wire -> wire.id.equals(selectedWire))) selectedWire = null;
    }

    private void refreshUpgradeLimits() {
        CloudworksUpgradeLimits.normalizeDraft(draft);
        customBudgetVersion++;
        draft.maxSpeed = CloudworksUpgradeLimits.draftCapacity(Upgrade.SPEED, draft.machines);
        draft.maxEnergy = CloudworksUpgradeLimits.draftCapacity(Upgrade.ENERGY, draft.machines);
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

    // The per-node native assignment interaction retired with the axis-priority
    // plan (batch 3c): node upgrades are cloud modules installed per machine.

    /**
     * Metadata for cloud-owned and already-installed node upgrade types.
     */
    public List<CloudworksGuiData.CustomUpgrade> customPoolView() {
        return Collections.unmodifiableList(customPoolEntries);
    }

    /**
     * Includes ids installed locally by blueprint placement or restored by undo.
     */
    public List<String> customPoolIds() {
        refreshCustomBudget();
        Set<String> ids = new LinkedHashSet<>(customCloudPool.keySet());
        ids.addAll(customLineBacking.keySet());
        ids.addAll(customAssigned.keySet());
        return new ArrayList<>(ids);
    }

    /**
     * The graph snapshot the custom pool view was built from; identity-based staleness check.
     */
    public CloudworksGuiData graphSnapshot() {
        return seenData;
    }

    /**
     * Cloud availability after this draft's net install/refund, without touching live storage.
     */
    public long customCloudAvailable(String typeId) {
        refreshCustomBudget();
        long assigned = customAssigned.getOrDefault(typeId, 0L);
        long cloud = customCloudPool.getOrDefault(typeId, 0L);
        long backing = customLineBacking.getOrDefault(typeId, 0L);
        return CloudworksUpgradeLimits.availableCustom(cloud, backing, assigned);
    }

    private void refreshCustomBudget() {
        if (customBudgetDraft == draft && cachedCustomBudgetVersion == customBudgetVersion) return;
        customAssigned.clear();
        if (draft != null) for (CloudworksLineDraft.Machine machine : draft.machines)
            machine.assignedCustom.forEach((id, count) -> customAssigned.merge(id, count, Math::addExact));
        customBudgetDraft = draft;
        cachedCustomBudgetVersion = customBudgetVersion;
    }

    /**
     * Installs or releases one custom type on a node; server revalidates at submit (8.5-B5).
     */
    public void nodeCustomUpgrade(UUID node, String typeId, boolean install, boolean all) {
        CloudworksLineDraft.Machine machine = findMachine(node);
        if (machine == null || machine.waiting != null) return;
        ResourceLocation id;
        try {
            id = new ResourceLocation(typeId);
        } catch (Exception invalid) {
            return;
        }
        long current = machine.assignedCustom(typeId);
        long maximum = CloudworksUpgradeLimits.draftCustomCapacity(machine, id);
        long available = customCloudAvailable(typeId);
        long amount = install ? Math.min(maximum - current, available) : current;
        if (!all) amount = Math.min(1, amount);
        if (amount <= 0) return;
        CloudworksLineDraft.Machine candidate = new CloudworksLineDraft.Machine(
                machine.id, machine.type, machine.x, machine.y, machine.count);
        candidate.recipeType = machine.recipeType;
        candidate.oreRules.addAll(machine.oreRules);
        candidate.fusionInjectionRate = machine.fusionInjectionRate;
        candidate.assignedCustom.putAll(machine.assignedCustom);
        candidate.assignedCustom(typeId, current + (install ? amount : -amount));
        try {
            MachineSlotLayout before =
                    CloudworksNodeMechanisms.slots(machine);
            MachineSlotLayout after =
                    CloudworksNodeMechanisms.slots(candidate);
            if (before != null) for (MachineSlotLayout.Slot port : before.slots())
                if (!CloudworksNodeMechanisms.samePort(port, after))
                    for (CloudworksLineDraft.Wire wire : draft.links)
                        if (wire.source.equals(node) && wire.sourcePort.equals(port.id())
                                || wire.target.equals(node) && wire.targetPort.equals(port.id())) {
                            notice = "cloudworks.error.node_mechanism_connected";
                            return;
                        }
        } catch (IllegalArgumentException invalid) {
            notice = invalid.getMessage() != null && invalid.getMessage().startsWith("cloudworks.error.") ? invalid.getMessage()
                    : "cloudworks.error.node_upgrade_invalid";
            return;
        }
        recordUndo();
        machine.assignedCustom(typeId, current + (install ? amount : -amount));
    }

    public void upgrade(boolean speed, boolean install, boolean all) {
        refreshUpgradeLimits();
        int current = installed(speed), maximum = speed ? draft.maxSpeed : draft.maxEnergy;
        int amount = install ? Math.min(pool(speed), maximum - current) : Math.max(0, installed(speed));
        if (!all) amount = Math.min(1, amount);
        int updated = current + (install ? amount : -amount);
        if (updated != current) {
            recordUndo();
            if (speed) draft.speed = updated;
            else draft.energy = updated;
        }
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void recordUndo() {
        if (draft == null) return;
        draftVersion++;
        customBudgetVersion++;
        undo.addFirst(CloudworksLineDraftCodec.copy(draft));
        trim(undo);
        redo.clear();
    }

    public boolean undo() {
        if (draft == null || undo.isEmpty()) return false;
        redo.addFirst(CloudworksLineDraftCodec.copy(draft));
        trim(redo);
        draft = undo.removeFirst();
        invalidateMachineList();
        pruneSelection();
        return true;
    }

    public boolean redo() {
        if (draft == null || redo.isEmpty()) return false;
        undo.addFirst(CloudworksLineDraftCodec.copy(draft));
        trim(undo);
        draft = redo.removeFirst();
        invalidateMachineList();
        pruneSelection();
        return true;
    }

    private void rebuildAvailableCounts() {
        Map<MachineKey, Long> adjustments = new HashMap<>();
        for (CloudworksLineDraft.Machine machine : original)
            adjustments.merge(CloudworksRegistries.identityKey(machine.type),
                    machine.count, Math::addExact);
        for (CloudworksLineDraft.Machine machine : draft.machines)
            adjustments.merge(CloudworksRegistries.identityKey(machine.type),
                    -machine.count, Math::addExact);

        availableCounts = new long[machines.size()];
        for (int index = 0; index < machines.size(); index++) {
            CloudworksGuiData.Machine entry = machines.get(index);
            MachineKey key =
                    CloudworksRegistries.identityKey(entry.stack);
            availableCounts[index] = Math.max(0, Math.addExact(entry.available, adjustments.getOrDefault(key, 0L)));
        }
        availableVersion = machineListVersion;
    }

    private void invalidateMachineList() {
        machineListVersion++;
        machineUsageVersion++;
        customBudgetVersion++;
        availableVersion = -1;
    }

    public void clearHistory() {
        undo.clear();
        redo.clear();
    }

    private static void trim(Deque<CloudworksLineDraft> history) {
        while (history.size() > HISTORY_LIMIT) history.removeLast();
    }
}
