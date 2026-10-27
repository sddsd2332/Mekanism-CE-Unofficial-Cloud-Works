package mekanism.cloudworks.client.gui.element;

import mekanism.api.EnumColor;
import mekanism.cloudworks.api.module.CloudworksUpgradeItems;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.common.Upgrade;
import mekanism.common.util.LangUtils;
import net.minecraft.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One selectable row of the cloud upgrade window (8.5-B3): the two native
 * Mekanism slots plus every registered custom type the server projected.
 * Identity is the wire id, so cached renderers survive data refreshes.
 */
public final class CloudUpgradeEntry {
    /**
     * "speed", "energy" or a namespaced custom type id — exactly what the download action sends.
     */
    public final String wireId;
    public final String name;
    /**
     * Description lines; one entry per line in tooltips and detail panels.
     */
    public final List<String> description;
    public final ItemStack stack;
    @Nullable
    public final EnumColor color;

    public CloudUpgradeEntry(String wireId, String name, String description, ItemStack stack, @Nullable EnumColor color) {
        this(wireId, name, Collections.singletonList(description), stack, color);
    }

    public CloudUpgradeEntry(String wireId, String name, List<String> description, ItemStack stack, @Nullable EnumColor color) {
        this.wireId = wireId;
        this.name = name;
        this.description = description;
        this.stack = stack;
        this.color = color;
    }

    public static CloudUpgradeEntry nativeType(Upgrade upgrade) {
        return new CloudUpgradeEntry(upgrade == Upgrade.SPEED ? "speed" : "energy",
                upgrade.getName(),upgrade.getDescription(),
                CloudworksUpgradeItems.currentStack(upgrade), upgrade.getColor());
    }

    public static CloudUpgradeEntry custom(CloudworksGuiData.CustomUpgrade data) {
        return custom(data.id);
    }

    /**
     * Capability resolves from the frozen local registries (display key, item,
     * description keys, mechanism metadata); the wire entry only carries the id.
     */
    public static CloudUpgradeEntry custom(String id) {
        CustomUpgradeInfo info = CustomUpgradeInfo.fromRegistry(id);
        String label = info.displayKey.isEmpty() ? id : LangUtils.localize(info.displayKey);
        if (label.equals(info.displayKey)) label = id; // untranslated key falls back to the raw id
        List<String> description = new ArrayList<>();
        for (String key : info.descriptionKeys) {
            String line = LangUtils.localize(key);
            if (!line.equals(key)) description.add(line); // untranslated description keys are skipped
        }
        if (description.isEmpty() && info.mechanism != null) {
            // One aspect per line: recipe mode, activation requirement, added ports.
            description.add(LangUtils.localize(info.mechanism.parallel
                    ? "cloudworks.editor.mechanism_parallel" : info.mechanism.replacesRecipes
                    ? "cloudworks.editor.mechanism_replace" : "cloudworks.editor.mechanism_append"));
            description.add(LangUtils.localizeWithFormat(info.mechanism.perMachine
                    ? "cloudworks.editor.mechanism_per_machine" : "cloudworks.editor.mechanism_per_node",
                    info.mechanism.required));
            description.add(LangUtils.localizeWithFormat("cloudworks.editor.mechanism_ports",
                    info.mechanism.ports.slots().size()));
        }
        if (description.isEmpty()) description.add(id);
        return new CloudUpgradeEntry(id, label, Collections.unmodifiableList(description), info.item, null);
    }

    /**
     * Window-side construction from already-localized pieces (8.5-B5 node window).
     */
    public static CloudUpgradeEntry custom(String id, String label, ItemStack item) {
        return new CloudUpgradeEntry(id, label == null || label.isEmpty() ? id : label,
                Collections.singletonList(id), item == null ? ItemStack.EMPTY : item, null);
    }

    public boolean customType() {
        return wireId.indexOf(':') >= 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CloudUpgradeEntry && ((CloudUpgradeEntry) other).wireId.equals(wireId);
    }

    @Override
    public int hashCode() {
        return wireId.hashCode();
    }
}
