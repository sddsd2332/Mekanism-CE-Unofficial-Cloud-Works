package mekanism.cloudworks.client.gui.line;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.text.GuiTextField;
import mekanism.cloudworks.client.gui.element.CloudworksResourceIcon;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid.Entry;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.util.text.TextComponentString;

/** Catalog window layered above its owner; selection changes the local draft only. */
public final class GuiLineResourcePickerWindow extends GuiLineWindow {
    private static final int FILTER_INFUSE = 4;
    private static final int FILTER_OTHER = 5;
    private static final SelectedWindowData.WindowType TYPE = type("resources");
    private CloudworksResourceCatalog catalog;
    private final GuiTextField search;
    private final GuiLineResourceGrid grid;
    private final Predicate<QIOResourceDescriptor> allowed;
    private final List<Entry> shown = new ArrayList<>();
    private final Set<QIOResourceDescriptor> extra = new LinkedHashSet<>();
    private final List<GuiLineResourceTypeTab> tabs = new ArrayList<>();
    private int filter, seenSize = -1, rebuildTicks;
    private boolean extraChanged, hasOtherResources;
    private String query = "";

    public GuiLineResourcePickerWindow(IGuiWrapper gui, Collection<QIOResourceDescriptor> extra,
            Consumer<QIOResourceDescriptor> select) {
        this(gui, extra, select, resource -> true);
    }

    public GuiLineResourcePickerWindow(IGuiWrapper gui, Collection<QIOResourceDescriptor> extra,
            Consumer<QIOResourceDescriptor> select, Predicate<QIOResourceDescriptor> allowed) {
        super(gui, Math.max(26, (gui.getWidth() - 214) / 2), 24, 214, 184, "select_resources", TYPE);
        this.allowed = allowed;
        catalog = CloudworksResourceCatalog.shared();
        this.extra.addAll(extra);
        search = addChild(new GuiTextField(gui, relativeX + 8, relativeY + 23, 198, 18).setMaxLength(128));
        Consumer<QIOResourceDescriptor> selectAllowed = resource -> {
            if (allowed.test(resource)) select.accept(resource);
        };
        grid = addChild(new GuiLineResourceGrid(gui, relativeX + 8, relativeY + 45, 10, 7,
                () -> shown, selectAllowed, null, selectAllowed));
        String[] textures = {"button/inventory.png", "items.png", "fluids.png", "gases.png",
                "upgrade.png", "button/fuzzy.png"};
        String[] labels = {"resources_all", "resources_item", "resources_fluid", "resources_gas",
                "resources_infuse", "resources_other"};
        int[] colors = {0xFFE0E0E0, 0xFF7CCC90, 0xFF78B1E0, 0xFFE0C477, 0xFFE58A72, 0xFFD291BC};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            tabs.add(addChild(new GuiLineResourceTypeTab(gui, relativeX - 26, relativeY + 2 + i * 28,
                    textures[i], labels[i], colors[i], () -> {
                        filter = index;
                        rebuild();
                        updateTabs();
                    })));
        }
        updateTabs();
        rebuild();
    }

    public void addResources(Collection<QIOResourceDescriptor> resources) {
        extraChanged |= extra.addAll(resources);
    }

    private void updateTabs() {
        for (int i = 0; i < tabs.size(); i++)
            tabs.get(i).visible = i != filter && (i != FILTER_OTHER || hasOtherResources);
    }

    private boolean matchesType(QIOResourceDescriptor descriptor) {
        CloudworksResourceIcon.Family family = CloudworksResourceIcon.family(descriptor);
        if (filter == 1) return family == CloudworksResourceIcon.Family.ITEM;
        if (filter == 2) return family == CloudworksResourceIcon.Family.FLUID;
        if (filter == 3) return family == CloudworksResourceIcon.Family.GAS;
        if (filter == FILTER_INFUSE) return family == CloudworksResourceIcon.Family.INFUSE;
        if (filter == FILTER_OTHER) return family == CloudworksResourceIcon.Family.OTHER;
        return true;
    }

    private void rebuild() {
        query = search.getText().trim().toLowerCase(Locale.ROOT);
        List<QIOResourceDescriptor> matching = new ArrayList<>();
        Set<QIOResourceDescriptor> resources = new LinkedHashSet<>(catalog.resources());
        resources.addAll(extra);
        hasOtherResources = resources.stream()
                .anyMatch(resource -> CloudworksResourceIcon.family(resource) == CloudworksResourceIcon.Family.OTHER);
        if (filter == FILTER_OTHER && !hasOtherResources) filter = 0;
        updateTabs();
        for (QIOResourceDescriptor resource : resources) {
            if (!allowed.test(resource) || !matchesType(resource)) continue;
            String name = catalog.displayName(resource);
            if (name.toLowerCase(Locale.ROOT).contains(query)
                    || resource.getCodecId().toString().contains(query)
                    || resource.getPayload().toString().toLowerCase(Locale.ROOT).contains(query)) matching.add(resource);
        }
        matching.sort(Comparator.comparing(catalog::displayName, String.CASE_INSENSITIVE_ORDER));
        shown.clear();
        for (QIOResourceDescriptor resource : matching)
            shown.add(catalog.entry(resource));
        grid.resetScroll();
        seenSize = catalog.resources().size();
        extraChanged = false;
    }

    @Override
    public void tick() {
        super.tick();
        CloudworksResourceCatalog current = CloudworksResourceCatalog.shared();
        if (catalog != current) {
            catalog = current;
            seenSize = -1;
            extraChanged = true;
        }
        catalog.tick();
        if (extraChanged || !query.equals(search.getText().trim().toLowerCase(Locale.ROOT))
                || seenSize != catalog.resources().size() && (catalog.complete() || ++rebuildTicks % 5 == 0)) rebuild();
    }

    @Override
    public void renderForeground(int mouseX, int mouseY) {
        super.renderForeground(mouseX, mouseY);
        String[] labels = {"resources_all", "resources_item", "resources_fluid", "resources_gas",
                "resources_infuse", "resources_other"};
        drawScrollingString(new TextComponentString(tr(labels[filter]) + ": " + shown.size()),
                8, 173, TextAlignment.LEFT, 0x404040, 198, 2, false);
    }
}
