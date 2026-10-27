package mekanism.cloudworks.client.gui.line;

import java.util.*;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.cloudworks.client.gui.element.GuiCloudSlotScroll;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.PacketCloudworksBlueprint;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager.BlueprintRequirement;
import mekanism.common.inventory.ISlotClickHandler.IScrollableSlot;
import net.minecraft.util.text.TextComponentString;
import mekanism.common.util.LangUtils;

public final class GuiLineBlueprintNeedsWindow extends GuiLineWindow {
    private final ContainerCloudworks container;
    private final UUID line;
    private final List<BlueprintRequirement> requirements = new ArrayList<>();
    private final MekanismButton fill, cancel;
    private long version;
    private boolean confirmed;
    private String feedback = "";
    public GuiLineBlueprintNeedsWindow(IGuiWrapper gui, ContainerCloudworks container, UUID line) {
        super(gui, (gui.getWidth() - 227) / 2, 30, 227, 163, "blueprint_needs", type("blueprint_needs"));
        this.container = container; this.line = line;
        addChild(new GuiCloudSlotScroll(gui, relativeX + 8, relativeY + 26, 5, 5,
                () -> slots(false), index -> {}, () -> -1, index -> tooltip(index, false)));
        addChild(new GuiCloudSlotScroll(gui, relativeX + 115, relativeY + 26, 5, 5,
                () -> slots(true), index -> {}, () -> -1, index -> tooltip(index, true)));
        fill = addChild(new MekanismButton(gui, relativeX + 8, relativeY + 139, 99, 18,
                new TextComponentString(tr("blueprint_fill")), () -> request(PacketCloudworksBlueprint.FILL), null));
        cancel = addChild(new MekanismButton(gui, relativeX + 115, relativeY + 139, 104, 18,
                new TextComponentString(tr("blueprint_cancel")), this::cancel, null));
        version = container.blueprintVersion();
        request(PacketCloudworksBlueprint.REQUIREMENTS);
    }
    private void request(int operation) {
        container.requestBlueprint(operation, line, null, "", null, 0, 0, Collections.emptySet());
    }
    private void cancel() {
        if (!confirmed) { confirmed = true; cancel.setMessage(new TextComponentString(tr("confirm_delete"))); }
        else request(PacketCloudworksBlueprint.CANCEL);
    }
    private List<IScrollableSlot> slots(boolean missing) {
        List<IScrollableSlot> slots = new ArrayList<>();
        for (BlueprintRequirement requirement : requirements)
            if (!missing || requirement.missing > 0)
                slots.add(GuiCloudSlotScroll.slot(requirement.stack, missing ? requirement.missing : requirement.required));
        return slots;
    }
    private List<String> tooltip(int index, boolean missing) {
        List<BlueprintRequirement> shown = new ArrayList<>();
        for (BlueprintRequirement requirement : requirements) if (!missing || requirement.missing > 0) shown.add(requirement);
        if (index < 0 || index >= shown.size()) return Collections.emptyList();
        BlueprintRequirement requirement = shown.get(index);
        return Collections.singletonList(LangUtils.localizeWithFormat(
                "cloudworks.editor.blueprint_requirement", requirement.required, requirement.missing));
    }
    @Override public void tick() {
        super.tick();
        if (version != container.blueprintVersion()) {
            version = container.blueprintVersion();
            PacketCloudworksBlueprint.Result result = container.blueprintResult;
            if (!result.error.isEmpty()) {
                feedback = LangUtils.localize(result.error);
                if (result.operation == PacketCloudworksBlueprint.CANCEL) {
                    confirmed = false;
                    cancel.setMessage(new TextComponentString(tr("blueprint_cancel")));
                }
            }
            else if (result.filled && (result.operation == PacketCloudworksBlueprint.FILL
                    || result.operation == PacketCloudworksBlueprint.CANCEL)) { close(); return; }
            else {
                requirements.clear(); requirements.addAll(result.requirements);
                feedback = result.operation == PacketCloudworksBlueprint.FILL ? tr("blueprint_still_missing") : "";
            }
        }
        fill.active = cancel.active = !container.blueprintBusy();
    }
    @Override public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        drawScrollingString(new TextComponentString(tr("blueprint_required")), 8, 16, TextAlignment.LEFT, 0x404040, 90, 1, false);
        drawScrollingString(new TextComponentString(tr("blueprint_missing")), 115, 16, TextAlignment.LEFT, 0x404040, 90, 1, false);
        drawScrollingString(new TextComponentString(feedback), 8, 120, TextAlignment.LEFT, 0x404040, 211, 1, false);
    }
}
