package mekanism.cloudworks.client.gui.line;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.qio.QIOResourceSelection;
import mekanism.client.recipe_viewer.interfaces.IRecipeViewerGhostTarget;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid;
import mekanism.common.content.qio.QIOAmount;
import mekanism.common.content.qio.QIOResourceEntry;
import mekanism.common.util.text.TextUtils;

/** Selects resource identities, never inserts or extracts physical inventory contents. */
public final class GuiLineResourceGrid extends GuiCloudQIOResourceGrid implements IRecipeViewerGhostTarget {
    private final Supplier<List<Entry>> entries;
    private final Consumer<QIOResourceDescriptor> select, remove, add;

    public GuiLineResourceGrid(IGuiWrapper gui, int x, int y, int columns, int rows,
            Supplier<List<Entry>> entries, Consumer<QIOResourceDescriptor> select,
            Consumer<QIOResourceDescriptor> remove, Consumer<QIOResourceDescriptor> add) {
        super(gui, x, y, columns, rows, entries);
        this.entries = entries;
        this.select = select;
        this.remove = remove;
        this.add = add;
        CloudworksInfuseRenderer.register();
    }

    @Override
    protected boolean isValidClickButton(int button) {
        return button == 0 || button == 1 && remove != null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!active || !visible || !isMouseOverCheckWindows(mouseX, mouseY)) return false;
        if (add != null && !minecraft.player.inventory.getItemStack().isEmpty()) {
            QIOResourceSelection.Resolution resource =
                    QIOResourceSelection.fromCarried(minecraft.player.inventory.getItemStack(), button);
            if (resource.isUnique()) {
                add.accept(resource.getDescriptor());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected List<Entry> getSortedEntries() {
        // The catalog owns sorting; rebuilding and sorting thousands of resources per rendered slot is unnecessary.
        return entries.get();
    }

    @Override
    public void onClick(double mouseX, double mouseY, int button) {
        Entry entry = entryAt(mouseX, mouseY);
        if (entry != null) {
            if (button == 0) select.accept(entry.descriptor());
            else if (button == 1 && remove != null) remove.accept(entry.descriptor());
        }
    }

    @Override
    protected String getResourceName(QIOResourceEntry entry) {
        return CloudworksResourceCatalog.name(entry.getDescriptor());
    }

    @Override
    protected String getStoredTooltip(QIOAmount amount) {
        return GuiLineWindow.tr("resource_amount") + ": " + TextUtils.format(amount.toBigInteger());
    }

    @Override
    public IGhostIngredientConsumer getGhostHandler() {
        if (add == null || !visible || !active) return null;
        return new IGhostIngredientConsumer() {
            @Override
            public Object supportedTarget(Object ingredient) {
                return QIOResourceSelection.fromIngredient(ingredient).isUnique() ? ingredient : null;
            }

            @Override
            public void accept(Object ingredient) {
                QIOResourceSelection.Resolution result = QIOResourceSelection.fromIngredient(ingredient);
                if (active && visible && result.isUnique()) add.accept(result.getDescriptor());
            }
        };
    }
}
