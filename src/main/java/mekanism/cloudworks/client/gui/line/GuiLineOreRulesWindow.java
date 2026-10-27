package mekanism.cloudworks.client.gui.line;

import java.util.*;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.cloudworks.common.data.CloudworksOreRule;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.recipe.CloudworksOredictionificator;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.oredict.OreDictionary;

/** Native-style ore-name/target selection backed by an editor draft rather than a TileEntity. */
public final class GuiLineOreRulesWindow extends GuiLineWindow {
    private final CloudworksLineEditorModel model;
    private final UUID node;
    private final GuiTextField name;
    private final MekanismButton[] rows = new MekanismButton[5], toggles = new MekanismButton[5], deletes = new MekanismButton[5];
    private final MekanismButton apply;
    private List<ItemStack> candidates = Collections.emptyList();
    private String seenName = "";
    private int offset, selected;
    public GuiLineOreRulesWindow(IGuiWrapper gui, CloudworksLineEditorModel model, UUID node) {
        super(gui, 35, 25, 252, 226, "ore_rules", type("machine"));
        this.model = model; this.node = node;
        for (int row = 0; row < 5; row++) {
            final int index = row;
            rows[row] = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 24 + row * 20, 174, 18,
                    new TextComponentString(""), () -> select(offset + index), null));
            toggles[row] = addChild(new MekanismButton(gui, relativeX + 185, relativeY + 24 + row * 20, 28, 18,
                    new TextComponentString(""), () -> toggle(offset + index), null));
            deletes[row] = addChild(new MekanismButton(gui, relativeX + 216, relativeY + 24 + row * 20, 27, 18,
                    new TextComponentString("×"), () -> delete(offset + index), null));
        }
        addChild(new MekanismButton(gui, relativeX + 8, relativeY + 127, 32, 18, new TextComponentString("↑"), () -> offset = Math.max(0, offset - 5), null));
        addChild(new MekanismButton(gui, relativeX + 44, relativeY + 127, 32, 18, new TextComponentString("↓"),
                () -> offset = Math.min(Math.max(0, rules().size() - 5), offset + 5), null));
        name = addChild(new GuiTextField(gui, relativeX + 8, relativeY + 150, 235, 18).setMaxLength(128));
        addChild(new MekanismButton(gui, relativeX + 8, relativeY + 173, 32, 18, new TextComponentString("<"), () -> step(-1), null));
        addChild(new MekanismButton(gui, relativeX + 44, relativeY + 173, 32, 18, new TextComponentString(">"), () -> step(1), null));
        apply = addChild(new MekanismButton(gui, relativeX + 83, relativeY + 173, 160, 18,
                new TextComponentString(tr("ore_rule_apply")), this::apply, null));
    }
    private List<CloudworksOreRule> rules() {
        if (model.draft != null) for (CloudworksLineDraft.Machine machine : model.draft.machines)
            if (node.equals(machine.id)) return machine.oreRules;
        return Collections.emptyList();
    }
    private void select(int index) {
        if (index >= rules().size()) return;
        CloudworksOreRule rule = rules().get(index); name.setText(rule.ore); refresh();
        for (int candidate = 0; candidate < candidates.size(); candidate++)
            if (ItemStack.areItemsEqual(candidates.get(candidate), rule.target())) selected = candidate;
    }
    private void toggle(int index) {
        List<CloudworksOreRule> copy = new ArrayList<>(rules());
        if (index < copy.size()) { copy.set(index, copy.get(index).enabled(!copy.get(index).enabled)); model.setOreRules(node, copy); }
    }
    private void delete(int index) {
        List<CloudworksOreRule> copy = new ArrayList<>(rules());
        if (index < copy.size()) { copy.remove(index); model.setOreRules(node, copy); }
    }
    private void refresh() {
        String value = name.getText().trim(); if (seenName.equals(value)) return;
        seenName = value; selected = 0; candidates = new ArrayList<>();
        if (!CloudworksOredictionificator.allowed(value)) return;
        for (ItemStack stack : OreDictionary.getOres(value, false))
            if (!stack.isEmpty() && !stack.hasTagCompound() && stack.getMetadata() != OreDictionary.WILDCARD_VALUE) candidates.add(stack.copy());
    }
    private void step(int step) { refresh(); if (!candidates.isEmpty()) selected = Math.floorMod(selected + step, candidates.size()); }
    private void apply() {
        refresh(); if (candidates.isEmpty()) return;
        CloudworksOreRule rule = new CloudworksOreRule(seenName, candidates.get(selected), true);
        List<CloudworksOreRule> copy = new ArrayList<>(rules()); copy.removeIf(previous -> previous.ore.equals(rule.ore)); copy.add(rule);
        model.setOreRules(node, copy);
    }
    @Override public void tick() {
        super.tick(); refresh(); List<CloudworksOreRule> rules = rules();
        offset = Math.min(offset, Math.max(0, rules.size() - 1));
        for (int row = 0; row < 5; row++) {
            boolean present = offset + row < rules.size(); rows[row].visible = toggles[row].visible = deletes[row].visible = present;
            if (present) { CloudworksOreRule rule = rules.get(offset + row);
                rows[row].setMessage(new TextComponentString(""));
                toggles[row].setMessage(new TextComponentString(rule.enabled ? "✓" : "−"));
            }
        }
        apply.active = !candidates.isEmpty();
    }
    @Override public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        List<CloudworksOreRule> rules = rules();
        for (int row = 0; row < rows.length && offset + row < rules.size(); row++) {
            CloudworksOreRule rule = rules.get(offset + row);
            drawScrollingString(new TextComponentString(rule.ore + " → " + (rule.target().isEmpty() ? tr("ore_target_missing") : rule.target().getDisplayName())),
                    11, 29 + row * 20, TextAlignment.LEFT, 0x404040, 168, 2, false);
        }
        drawScrollingString(new TextComponentString(candidates.isEmpty() ? tr("ore_rule_hint") : candidates.get(selected).getDisplayName()),
                8, 203, TextAlignment.LEFT, 0x404040, 235, 2, false);
    }
}
