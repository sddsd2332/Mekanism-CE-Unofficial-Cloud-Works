package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.cloudworks.client.gui.GuiCloudworksLine;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid.Entry;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.recipe.MachineRecipeTypeEntry;
import mekanism.common.util.LangUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;

/** Runtime contents are read-only; the enabled flag belongs to the editor draft. */
public final class GuiLineMachineWindow extends GuiLineWindow {
    private final ContainerCloudworks container;
    private final CloudworksLineEditorModel model;
    private final CloudworksLineDraft.Machine machine;
    private final List<Entry> inputs = new ArrayList<>(), outputs = new ArrayList<>(), templates = new ArrayList<>();
    private final boolean configuration;
    private final MekanismButton enable, disable;
    private final MekanismButton clear;
    private final MekanismButton recipe;
    private final MekanismButton upgrades;
    private final List<String> recipeTypes = new ArrayList<>();
    private CloudworksGuiData.Graph seen;

    public GuiLineMachineWindow(IGuiWrapper gui, ContainerCloudworks container, CloudworksLineEditorModel model,
            CloudworksLineDraft.Machine machine) {
        super(gui, 35, 30, 230, baseHeight(machine)
                        + (hasRecipeSelection(machine.type, machine.recipeType) ? 22 : 0) + 22
                        + (mekanism.cloudworks.common.recipe.CloudworksOredictionificator.isMachine(machine.type) ? 22 : 0)
                        + (mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.isController(machine.type) ? 22 : 0),
                "machine_details", type("machine"));
        this.container = container;
        this.model = model;
        this.machine = machine;
        configuration = GuiLineNodeCacheWindow.hasTemplatePort(machine);
        CloudworksInfuseRenderer.register();
        addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 8, relativeY + 40, 5, 4, () -> inputs));
        addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 115, relativeY + 40, 5, 4, () -> outputs));
        enable = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 129, 99, 18,
                new TextComponentString(tr("enable_node")), () -> model.setMachineActive(machine.id, true), null));
        disable = addChild(new MekanismButton(gui, relativeX + 115, relativeY + 129, 107, 18,
                new TextComponentString(tr("disable_node")), () -> model.setMachineActive(machine.id, false), null));
        clear = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 151, 214, 18,
                new TextComponentString(tr("clear_cache")), () ->
                        container.sendNodeAction(Operation.CLEAR_NODE_CACHE,
                                container.guiData().graph.id, machine.id), null));
        if (configuration)
            addChild(new GuiCloudQIOResourceGrid(gui, relativeX + 8, relativeY + 185, 5, 1, () -> templates));
        if (thermal(machine))
            addChild(new GuiLineThermalScreen(gui, relativeX + 8, relativeY + (configuration ? 207 : 175), container, machine.id));
        recipeTypes.add("");
        for (MachineRecipeTypeEntry entry : CloudworksRegistries.recipeTypes(machine.type))
            if (entry.adapter() != null) recipeTypes.add(entry.typeId());
        recipe = hasRecipeSelection(machine.type, machine.recipeType)
                ? addChild(new MekanismButton(gui, relativeX + 8,
                        relativeY + baseHeight(machine) - 2, 214, 18,
                        new TextComponentString(recipeTypeLabel(machine.recipeType)), this::cycleRecipeType, null)) : null;
        upgrades = addChild(new MekanismButton(gui, relativeX + 8,
                relativeY + baseHeight(machine) - 2 + (recipe == null ? 0 : 22), 214, 18,
                new TextComponentString(tr("node_upgrades")), () -> {
                    close();
                    if (gui() instanceof GuiCloudworksLine lineGui) lineGui.setCatalogRequested(true);
                    gui().addWindow(new GuiLineNodeUpgradeWindow(gui(), model, machine.id));
                }, null));
        if (mekanism.cloudworks.common.recipe.CloudworksOredictionificator.isMachine(machine.type))
            addChild(new MekanismButton(gui, relativeX + 8,
                    relativeY + baseHeight(machine) + 20 + (recipe == null ? 0 : 22), 214, 18,
                    new TextComponentString(tr("ore_rules")), () -> gui().addWindow(new GuiLineOreRulesWindow(gui(), model, machine.id)), null));
        if (mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.isController(machine.type))
            addChild(new MekanismButton(gui, relativeX + 8,
                    relativeY + baseHeight(machine) + 20 + (recipe == null ? 0 : 22), 214, 18,
                    new TextComponentString(tr("fusion_settings")), () -> {
                        CloudworksLineDraft.Machine current = currentMachine();
                        if (current != null) gui().addWindow(new GuiLineFusionSettingsWindow(gui(), model, current.id, current.fusionInjectionRate));
                    }, null));
        tick();
    }

    private static int baseHeight(CloudworksLineDraft.Machine machine) {
        return (GuiLineNodeCacheWindow.hasTemplatePort(machine) ? 209 : 177) + (thermal(machine) ? 54 : 0);
    }

    private static boolean thermal(CloudworksLineDraft.Machine machine) {
        MachineSlotLayout slots = CloudworksNodeMechanisms.slots(machine);
        return slots != null && slots.slots().stream().anyMatch(slot -> slot.codecId().equals(
                CloudworksThermalPlan.HEAT));
    }

    static boolean hasRecipeSelection(ItemStack stack, String selected) {
        return !selected.isEmpty() || CloudworksRegistries.recipeTypes(stack).size() > 1;
    }

    static String recipeTypeLabel(String typeId) {
        String name = tr("recipe_type_auto");
        if (!typeId.isEmpty()) {
            String key = "cloudworks.recipe_type." + typeId.replace(':', '.');
            name = LangUtils.localize(key);
            if (name.equals(key)) name = typeId;
        }
        return LangUtils.localizeWithFormat("cloudworks.editor.recipe_type", name);
    }

    private CloudworksLineDraft.Machine currentMachine() {
        if (model.draft != null) for (CloudworksLineDraft.Machine current : model.draft.machines)
            if (current.id.equals(machine.id)) return current;
        return null;
    }

    private void cycleRecipeType() {
        CloudworksLineDraft.Machine current = currentMachine();
        if (current != null)
            model.setMachineRecipeType(machine.id,
                    recipeTypes.get((recipeTypes.indexOf(current.recipeType) + 1) % recipeTypes.size()));
    }

    static boolean thermal(ItemStack stack) {
        return CloudworksRegistries.thermal(
                CloudworksRegistries.identityKey(stack)) != null;
    }

    @Override
    public void tick() {
        super.tick();
        CloudworksGuiData.Graph graph = container.guiData().graph;
        if (graph != seen) {
            seen = graph;
            inputs.clear();
            outputs.clear();
            templates.clear();
            for (mekanism.cloudworks.common.network.data.node.Node node : graph.nodes) {
                if (!node.id.equals(machine.id)) continue;
                collect(node.input, inputs);
                collect(node.output, outputs);
                collect(node.templates, templates);
                break;
            }
        }
        CloudworksLineDraft.Machine current = currentMachine();
        enable.active = current != null && !current.active;
        disable.active = current != null && current.active;
        clear.active = container.guiData().graph.enabled == false;
        upgrades.active = current != null && current.waiting == null;
        if (recipe != null) {
            recipe.active = current != null && current.waiting == null;
            recipe.setMessage(new TextComponentString(recipeTypeLabel(current == null ? machine.recipeType : current.recipeType)));
        }
    }

    private static void collect(List<CloudworksGuiData.ResourceSummary> resources, List<Entry> target) {
        Map<QIOResourceDescriptor, Long> totals = new LinkedHashMap<>();
        for (CloudworksGuiData.ResourceSummary resource : resources)
            if (resource.amount > 0) totals.merge(resource.descriptor, resource.amount, Math::addExact);
        totals.forEach((resource, amount) -> target.add(new Entry(resource, amount)));
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(tr("input_cache")), 8, 27,
                TextAlignment.LEFT, 0x404040, 99, 2, false);
        drawScrollingString(new TextComponentString(tr("output_cache")), 115, 27,
                TextAlignment.LEFT, 0x404040, 107, 2, false);
        drawScrollingString(new TextComponentString(machine.type.getDisplayName()), 8, 116,
                TextAlignment.LEFT, 0x404040, 214, 2, false);
        if (configuration)
            drawScrollingString(new TextComponentString(tr("template_cache")), 115, 190,
                    TextAlignment.LEFT, 0x404040, 107, 2, false);
    }
}
