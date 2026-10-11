package mekanism.cloudworks.client.gui.line;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import mekanism.api.processing.MachineResourceStack;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.slot.GuiDynamicResourceSlot;
import mekanism.client.gui.qio.QIOResourceSelection;
import mekanism.client.recipe_viewer.interfaces.IRecipeViewerGhostTarget;

/** Mek dynamic resource display with the same JEI ghost-resource resolver as QIO. */
public final class GuiLineResourceMarkers extends GuiDynamicResourceSlot implements IRecipeViewerGhostTarget {
    private final Consumer<QIOResourceDescriptor> add, remove;
    private Consumer<QIOResourceDescriptor> select = descriptor -> {};

    public GuiLineResourceMarkers select(Consumer<QIOResourceDescriptor> select) {
        this.select = select;
        return this;
    }

    public GuiLineResourceMarkers(IGuiWrapper gui, int x, int y, int columns, int rows,
            Supplier<List<MachineResourceStack>> resources, Consumer<QIOResourceDescriptor> add,
            Consumer<QIOResourceDescriptor> remove) {
        super(gui, x, y, columns, rows, resources::get);
        this.add = add;
        this.remove = remove;
        borderColor(BorderColor.NORMAL);
    }

    @Override
    public IGhostIngredientConsumer getGhostHandler() {
        return new IGhostIngredientConsumer() {
            @Override
            public Object supportedTarget(Object ingredient) {
                return active && QIOResourceSelection.fromIngredient(ingredient).isUnique() ? ingredient : null;
            }

            @Override
            public void accept(Object ingredient) {
                QIOResourceSelection.Resolution result = QIOResourceSelection.fromIngredient(ingredient);
                if (active && result.isUnique()) add.accept(result.getDescriptor());
            }
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || !isMouseOverCheckWindows(mouseX, mouseY)) return false;
        if (button == 1) {
            QIOResourceSelection.Resolution hovered = QIOResourceSelection.fromIngredient(getIngredient(mouseX, mouseY));
            if (hovered.isUnique()) remove.accept(hovered.getDescriptor());
        } else if (button == 0) {
            QIOResourceSelection.Resolution carried = QIOResourceSelection.fromCarried(minecraft.player.inventory.getItemStack(), 0);
            if (carried.isUnique()) add.accept(carried.getDescriptor());
            else {
                QIOResourceSelection.Resolution hovered = QIOResourceSelection.fromIngredient(getIngredient(mouseX, mouseY));
                if (hovered.isUnique()) select.accept(hovered.getDescriptor());
            }
        }
        return true;
    }
}
