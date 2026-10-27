package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.List;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid.Entry;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.button.MekanismImageButton;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import java.util.function.Consumer;

public final class GuiLineIdentityWindow extends GuiLineWindow {
    private static final SelectedWindowData.WindowType TYPE = type("identity");
    private GuiLineResourcePickerWindow picker;

    public GuiLineIdentityWindow(IGuiWrapper gui, CloudworksLineEditorModel model) {
        super(gui, 30, 30, 180, 140, "identity", TYPE);
        Consumer<QIOResourceDescriptor> mark = model::addMarker;
        addChild(new GuiLineResourceGrid(gui, relativeX + 8, relativeY + 20, 5, 5, () -> {
            List<Entry> result = new ArrayList<>();
            for (QIOResourceDescriptor marker : model.draft.markers)
                result.add(new Entry(marker, 1));
            if (result.isEmpty() && !model.draft.machines.isEmpty())
                result.add(new Entry(QIOResourceCodecs.item(model.draft.machines.get(0).type), 1));
            return result;
        }, descriptor -> {}, model::removeMarker, mark));
        addChild(new MekanismButton(gui, relativeX + 120, relativeY + 20, 50, 18,
                new TextComponentString(tr("select_resources")), () -> {
                    if (picker != null) return;
                    picker = new GuiLineResourcePickerWindow(gui(), model.draft.markers, mark);
                    picker.setTabListeners(window -> picker = null, null);
                    gui().addWindow(picker);
                }, null));
        GuiTextField name = addChild(new GuiTextField(gui, relativeX + 8, relativeY + 114, 141, 18).setMaxLength(64));
        name.setText(model.draft.name);
        addChild(new MekanismImageButton(gui, relativeX + 152, relativeY + 114, 18,
                new ResourceLocation("mekanism", "gui/button/checkmark.png"), () -> {
                    if (!name.getText().trim().isEmpty()) model.setName(name.getText().trim());
                }).setTooltip(tr("rename")));
    }

    @Override
    public void close() {
        if (picker != null) picker.close();
        super.close();
    }
}
