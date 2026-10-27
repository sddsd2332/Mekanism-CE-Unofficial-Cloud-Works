package mekanism.cloudworks.client.gui.line;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.window.GuiWindow;
import net.minecraft.item.ItemStack;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.client.gui.element.CustomUpgradeInfo;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.node.ChannelNode;
import mekanism.cloudworks.common.network.data.node.MachineNode;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.cloudworks.api.client.CloudworksClientActions;
import mekanism.cloudworks.api.client.CloudworksClientCloudView;
import mekanism.cloudworks.api.client.CloudworksClientCustomUpgrade;
import mekanism.cloudworks.api.client.CloudworksClientLinkPanelProvider;
import mekanism.cloudworks.api.client.CloudworksClientLinkView;
import mekanism.cloudworks.api.client.CloudworksClientNodeView;
import mekanism.cloudworks.api.client.CloudworksClientPanelProvider;
import mekanism.cloudworks.api.client.CloudworksClientPanels;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.service.CloudworksNodeEnvironment;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetype;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;
import mekanism.cloudworks.common.special.CloudworksSpecialResourceNames;

/**
 * Client-side window providers for line nodes. The canvas only reports the
 * selected node; node-specific window selection stays in this factory.
 */
public final class CloudworksLineWindowFactory {
    private static final List<NodeProvider> NODE_PROVIDERS = new ArrayList<>();

    static {
        registerNodeProvider(CloudworksLineWindowFactory::machineWindow);
        registerNodeProvider(CloudworksLineWindowFactory::channelWindow);
    }

    private CloudworksLineWindowFactory() {
    }

    public interface NodeProvider {
        Supplier<GuiWindow> create(IGuiWrapper gui, ContainerCloudworks container,
                CloudworksLineEditorModel model, UUID line, CloudworksLineDraft configuration,
                UUID nodeId, boolean editing);
    }

    public static synchronized void registerNodeProvider(NodeProvider provider) {
        if (provider == null) throw new IllegalArgumentException("Node window provider cannot be null");
        NODE_PROVIDERS.add(provider);
    }

    public static Supplier<GuiWindow> node(IGuiWrapper gui, ContainerCloudworks container,
            CloudworksLineEditorModel model, UUID line, CloudworksLineDraft configuration,
            UUID nodeId, boolean editing) {
        if (configuration == null || nodeId == null) return null;
        Supplier<GuiWindow> fallback = internalNode(gui, container, model, line,
                configuration, nodeId, editing);
        CloudworksClientNodeView view =
                nodeView(line, configuration, nodeId, graphNode(container, nodeId), editing);
        if (view == null) return fallback;
        CloudworksClientCloudView cloud = cloudView(container);
        CloudworksClientActions actions =
                actions(container, model, line, nodeId, editing);
        return addonPanelOrFallback(gui, view, cloud, actions, fallback);
    }

    private static Supplier<GuiWindow> internalNode(IGuiWrapper gui, ContainerCloudworks container,
            CloudworksLineEditorModel model, UUID line, CloudworksLineDraft configuration,
            UUID nodeId, boolean editing) {
        List<NodeProvider> providers;
        synchronized (CloudworksLineWindowFactory.class) {
            providers = new ArrayList<>(NODE_PROVIDERS);
        }
        for (NodeProvider provider : providers) {
            Supplier<GuiWindow> window = provider.create(gui, container, model, line,
                    configuration, nodeId, editing);
            if (window != null) return window;
        }
        return null;
    }

    private static Supplier<GuiWindow> machineWindow(IGuiWrapper gui, ContainerCloudworks container,
            CloudworksLineEditorModel model, UUID line, CloudworksLineDraft configuration,
            UUID nodeId, boolean editing) {
        CloudworksLineDraft.Machine machine = null;
        for (CloudworksLineDraft.Machine candidate : configuration.machines)
            if (nodeId.equals(candidate.id)) { machine = candidate; break; }
        if (machine == null) return null;
        if (editing) {
            CloudworksLineDraft.Machine selected = machine;
            return () -> new GuiLineMachineWindow(gui, container, model, selected);
        }
        CloudworksLineDraft.Machine selected = machine;
        return () -> new GuiLineNodeCacheWindow(gui, container, line, selected.id,
                selected.type.getDisplayName(), true,
                GuiLineNodeCacheWindow.hasTemplatePort(selected));
    }

    private static Supplier<GuiWindow> channelWindow(IGuiWrapper gui, ContainerCloudworks container,
            CloudworksLineEditorModel model, UUID line, CloudworksLineDraft configuration,
            UUID nodeId, boolean editing) {
        CloudworksLineDraft.Channel channel = null;
        for (CloudworksLineDraft.Channel candidate : configuration.channels)
            if (nodeId.equals(candidate.id)) { channel = candidate; break; }
        if (channel == null) return null;
        CloudworksLineDraft.Channel selected = channel;
        if (CloudworksNodeEnvironment.source(selected.archetypeId))
            return () -> new GuiLineEnvironmentSourceWindow(gui, container, model, line, nodeId,
                    selected.archetypeId, editing);
        if (CloudworksSpecialNodeArchetypes.INFINITE_RESOURCES
                .toString().equals(selected.archetypeId))
            return () -> new GuiLineInfiniteResourceWindow(gui, container, model, line, nodeId, editing);
        if (editing) return () -> new GuiLineChannelWindow(gui, container, model, selected);
        String title = channelTitle(selected);
        return () -> new GuiLineNodeCacheWindow(gui, container, line, selected.id, title, false);
    }

    static CloudworksClientNodeView nodeView(
            UUID line, CloudworksLineDraft configuration, UUID nodeId,
            mekanism.cloudworks.common.network.data.node.Node runtimeNode, boolean editing) {
        List<CloudworksClientNodeView.Resource> input =
                resources(runtimeNode == null ? null : runtimeNode.input);
        List<CloudworksClientNodeView.Resource> output =
                resources(runtimeNode == null ? null : runtimeNode.output);
        List<CloudworksClientNodeView.Resource> templates =
                resources(runtimeNode == null ? null : runtimeNode.templates);
        List<CloudworksClientNodeView.Port> ports =
                ports(runtimeNode == null ? null : runtimeNode.ports);
        for (CloudworksLineDraft.Machine machine : configuration.machines) {
            if (!nodeId.equals(machine.id)) continue;
            MachineNode projected = runtimeNode instanceof MachineNode ? (MachineNode) runtimeNode : null;
            // Icon is a client capability fact: the draft copy carried by the same graph.
            ItemStack icon = machine.type.copy();
            String registryId = icon.getItem().getRegistryName() == null
                    ? "" : icon.getItem().getRegistryName().toString();
            UUID id = machine.id;
            long count = machine.count;
            boolean active = editing ? machine.active : runtimeNode != null && runtimeNode.active;
            String recipeType = machine.recipeType;
            Map<String, Long> installed = Collections.unmodifiableMap(new LinkedHashMap<>(machine.assignedCustom));
            List<CloudworksClientNodeView.ExecutionBranch> branchViews = new ArrayList<>();
            if (runtimeNode != null) for (CloudworksGuiData.ExecutionBranch branch : runtimeNode.branches)
                branchViews.add(new CloudworksClientNodeView.ExecutionBranch(
                        branch.id, branch.state, branch.progress, branch.duration));
            List<CloudworksClientNodeView.ExecutionBranch> execution =
                    Collections.unmodifiableList(branchViews);
            List<CloudworksClientNodeView.Port> effectivePorts = new ArrayList<>();
            MachineSlotLayout effective =
                    CloudworksNodeMechanisms.slots(machine);
            if (effective != null) for (MachineSlotLayout.Slot port : effective.slots())
                effectivePorts.add(new CloudworksClientNodeView.Port(
                        port.id(), port.codecId().toString(), port.role().name()));
            List<CloudworksClientNodeView.Port> machinePorts =
                    Collections.unmodifiableList(effectivePorts);
            return new CloudworksClientNodeView() {
                @Override public UUID id() { return id; }
                @Override public UUID line() { return line; }
                @Override public String kind() { return "machine"; }
                @Override public String archetypeId() { return ""; }
                @Override public String titleKey() { return ""; }
                @Override public boolean editing() { return editing; }
                @Override public ItemStack icon() { return icon.copy(); }
                @Override public long machineCount() { return count; }
                @Override public boolean active() { return active; }
                @Override public String recipeType() { return recipeType; }
                @Override public String state() { return runtimeNode == null ? "" : runtimeNode.state; }
                @Override public int progress() { return projected == null ? 0 : projected.progress; }
                @Override public int duration() { return projected == null ? 0 : projected.duration; }
                @Override public List<Resource> inputs() { return input; }
                @Override public List<Resource> outputs() { return output; }
                @Override public List<Resource> templates() { return templates; }
                @Override public List<Port> ports() { return machinePorts; }
                @Override public List<ExecutionBranch> executionBranches() { return execution; }
                @Override public Map<String, Long> installedCustomUpgrades() { return installed; }
                public String machineRegistryId() { return registryId; }
                public int machineMetadata() { return icon.getItemDamage(); }
            };
        }
        for (CloudworksLineDraft.Channel channel : configuration.channels) {
            if (!nodeId.equals(channel.id)) continue;
            String archetype = channel.archetypeId == null ? "" : channel.archetypeId;
            CloudworksSpecialNodeArchetype declaration =
                    CloudworksSpecialNodeArchetypes.get(archetype);
            String mode = channel.mode.name();
            String title = archetype.isEmpty() ? "cloudworks.editor.channel_" + mode.toLowerCase(Locale.ROOT)
                    : declaration == null ? "cloudworks.editor.special_node_invalid" : declaration.translationKey();
            UUID id = channel.id;
            boolean active = editing ? channel.active : runtimeNode != null && runtimeNode.active;
            ChannelNode projected = runtimeNode instanceof ChannelNode ? (ChannelNode) runtimeNode : null;
            QIOResourceDescriptor selectedResource = projected == null || projected.input.isEmpty()
                    ? null : projected.input.get(0).descriptor;
            ItemStack icon = CloudworksChannelIcons.stack(archetype, channel.mode, selectedResource);
            List<CloudworksClientNodeView.Resource> marked = new ArrayList<>();
            for (CloudworksLineDraft.Resource resource : channel.resources)
                marked.add(new CloudworksClientNodeView.Resource(
                        resource.descriptor, resource.amount));
            List<CloudworksClientNodeView.Resource> resources =
                    Collections.unmodifiableList(marked);
            return new CloudworksClientNodeView() {
                @Override public UUID id() { return id; }
                @Override public UUID line() { return line; }
                @Override public String kind() { return "channel"; }
                @Override public String archetypeId() { return archetype; }
                @Override public String titleKey() { return title; }
                @Override public boolean editing() { return editing; }
                @Override public ItemStack icon() { return icon.copy(); }
                @Override public boolean active() { return active; }
                @Override public String state() { return runtimeNode == null ? "" : runtimeNode.state; }
                @Override public String channelMode() { return mode; }
                @Override public List<Resource> resources() { return resources; }
                @Override public List<Resource> inputs() { return input; }
                @Override public List<Port> ports() { return ports; }
            };
        }
        return null;
    }

    private static mekanism.cloudworks.common.network.data.node.Node graphNode(ContainerCloudworks container, UUID id) {
        for (mekanism.cloudworks.common.network.data.node.Node node : container.guiData().graph.nodes)
            if (id.equals(node.id)) return node;
        return null;
    }

    private static List<CloudworksClientNodeView.Resource> resources(
            List<CloudworksGuiData.ResourceSummary> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<CloudworksClientNodeView.Resource> result = new ArrayList<>();
        for (CloudworksGuiData.ResourceSummary value : values)
            result.add(new CloudworksClientNodeView.Resource(
                    value.descriptor, value.amount));
        return Collections.unmodifiableList(result);
    }

    private static List<CloudworksClientNodeView.Port> ports(
            List<CloudworksGuiData.Port> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<CloudworksClientNodeView.Port> result = new ArrayList<>();
        for (CloudworksGuiData.Port value : values)
            result.add(new CloudworksClientNodeView.Port(
                    value.id, value.codec, value.output));
        return Collections.unmodifiableList(result);
    }

    private static CloudworksClientCloudView cloudView(ContainerCloudworks container) {
        List<CloudworksClientCustomUpgrade> upgrades = new ArrayList<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        // On-demand catalogue: null means not yet requested (upgrade windows ask for
        // it); building a node view before the catalogue arrives sees an empty pool,
        // matching poolCount's absent-means-zero tolerance.
        List<CloudworksGuiData.CustomUpgrade> custom = container.customUpgrades();
        if (custom != null) for (CloudworksGuiData.CustomUpgrade entry : custom) {
            // Capability (display key, item) resolves from the local registry.
            CustomUpgradeInfo info = CustomUpgradeInfo.fromRegistry(entry.id);
            ItemStack item = info.item.copy();
            upgrades.add(new CloudworksClientCustomUpgrade() {
                @Override public String id() { return entry.id; }
                @Override public String displayKey() { return info.displayKey; }
                @Override public long count() { return entry.count; }
                @Override public ItemStack item() { return item.copy(); }
            });
            counts.put(entry.id, entry.count);
        }
        List<CloudworksClientCloudView.CloudworksClientEffectLevel> levels =
                new ArrayList<>();
        for (CloudworksGuiData.EffectLevel entry : container.guiData().overview.effectLevels)
            levels.add(new CloudworksClientCloudView.CloudworksClientEffectLevel() {
                @Override public String id() { return entry.id; }
                @Override public long level() { return entry.level; }
            });
        int speedPool = container.upgradePool(true), energyPool = container.upgradePool(false);
        return new CloudworksClientCloudView() {
            @Override public List<CloudworksClientCustomUpgrade> customUpgrades() {
                return Collections.unmodifiableList(upgrades);
            }
            @Override public long customUpgradeCount(String id) {
                return counts.getOrDefault(id, 0L);
            }
            @Override public int upgradePool(boolean speed) {
                return speed ? speedPool : energyPool;
            }
            @Override public List<CloudworksClientCloudView.CloudworksClientEffectLevel> effectLevels() {
                return Collections.unmodifiableList(levels);
            }
        };
    }

    static CloudworksClientActions actions(
            ContainerCloudworks container, CloudworksLineEditorModel model, UUID line, boolean editing) {
        return actions(container, model, line, model.selectedNode, editing);
    }

    private static CloudworksClientActions actions(
            ContainerCloudworks container, CloudworksLineEditorModel model, UUID line, UUID node, boolean editing) {
        CloudworksLineDraft expectedDraft = model.draft;
        BooleanSupplier writable = () -> editing && model.clientActionsAllowed(expectedDraft);
        return new CloudworksClientActions() {
            @Override public void installCustomUpgrade(String typeId) {
                if (writable.getAsBoolean())
                    model.nodeCustomUpgrade(node, typeId, true, false);
            }
            @Override public void removeCustomUpgrade(String typeId) {
                if (writable.getAsBoolean())
                    model.nodeCustomUpgrade(node, typeId, false, false);
            }
            @Override public boolean setMachineActive(UUID node, boolean active) {
                return writable.getAsBoolean() && model.setMachineActive(node, active);
            }
            @Override public boolean setMachineRecipeType(UUID node, String recipeType) {
                return writable.getAsBoolean() && model.setMachineRecipeType(node, recipeType);
            }
            @Override public boolean setChannelActive(UUID node, boolean active) {
                return writable.getAsBoolean() && model.setChannelActive(node, active);
            }
            @Override public boolean addChannelResource(UUID node, QIOResourceDescriptor resource) {
                return writable.getAsBoolean() && model.addChannelResource(node, resource);
            }
            @Override public boolean removeChannelResource(UUID node, QIOResourceDescriptor resource) {
                return writable.getAsBoolean() && model.removeChannelResource(node, resource);
            }
        };
    }

    public static Supplier<GuiWindow> link(IGuiWrapper gui, ContainerCloudworks container,
            CloudworksLineEditorModel model, CloudworksLineDraft.Wire wire) {
        if (wire == null) return null;
        UUID line = container.guiData().graph.id;
        Supplier<GuiWindow> fallback = () -> new GuiLineLinkWindow(gui, container, model, wire);
        CloudworksClientLinkView view = linkView(line, wire);
        CloudworksClientCloudView cloud = cloudView(container);
        return addonLinkPanelOrFallback(gui, view, cloud, fallback);
    }

    static Supplier<GuiWindow> addonPanelOrFallback(IGuiWrapper gui,
            CloudworksClientNodeView view,
            CloudworksClientCloudView cloud,
            CloudworksClientActions actions,
            Supplier<GuiWindow> fallback) {
        return () -> {
            for (CloudworksClientPanelProvider provider
                    : CloudworksClientPanels.providers()) {
                if (!provider.handles(view)) continue;
                GuiWindow window = provider.createWindow(gui, view, cloud, actions);
                if (window != null) return window;
            }
            return fallback == null ? null : fallback.get();
        };
    }

    static Supplier<GuiWindow> addonLinkPanelOrFallback(IGuiWrapper gui,
            CloudworksClientLinkView view,
            CloudworksClientCloudView cloud, Supplier<GuiWindow> fallback) {
        return () -> {
            for (CloudworksClientLinkPanelProvider provider
                    : CloudworksClientPanels.linkProviders()) {
                if (!provider.handles(view)) continue;
                GuiWindow window = provider.createWindow(gui, view, cloud);
                if (window != null) return window;
            }
            return fallback == null ? null : fallback.get();
        };
    }

    static CloudworksClientLinkView linkView(
            UUID line, CloudworksLineDraft.Wire wire) {
        return new CloudworksClientLinkView(wire.id, line,
                wire.source, wire.sourcePort, wire.target, wire.targetPort, wire.enabled, true,
                wire.priority, wire.ratio, wire.filters);
    }

    private static String channelTitle(CloudworksLineDraft.Channel channel) {
        CloudworksSpecialNodeArchetype archetype =
                channel.archetypeId == null || channel.archetypeId.isEmpty() ? null
                        : CloudworksSpecialNodeArchetypes
                        .get(channel.archetypeId);
        if (archetype == null)
            return channel.archetypeId == null || channel.archetypeId.isEmpty()
                    ? GuiLineWindow.tr("channel_" + channel.mode.name().toLowerCase(Locale.ROOT))
                    : GuiLineWindow.tr("special_node_invalid");
        return CloudworksSpecialResourceNames.node(
                channel.resources.isEmpty() ? null : channel.resources.get(0).descriptor);
    }
}
