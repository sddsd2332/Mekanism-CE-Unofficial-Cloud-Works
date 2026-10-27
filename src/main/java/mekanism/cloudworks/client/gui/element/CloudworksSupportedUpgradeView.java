package mekanism.cloudworks.client.gui.element;

import java.util.*;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.common.Upgrade;

/** Supported icons describe the full local type registry, independent of owned counts. */
public final class CloudworksSupportedUpgradeView {
    private CloudworksSupportedUpgradeView() {}
    public static final class Entry {
        public final Upgrade nativeUpgrade;
        public final CustomUpgradeInfo custom;
        private Entry(Upgrade nativeUpgrade, CustomUpgradeInfo custom) {
            this.nativeUpgrade = nativeUpgrade; this.custom = custom;
        }
    }
    public static List<Entry> entries(Collection<CustomUpgradeInfo> customTypes) {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(Upgrade.SPEED, null));
        entries.add(new Entry(Upgrade.ENERGY, null));
        Set<String> seen = new HashSet<>();
        for (CustomUpgradeInfo type : customTypes) {
            if (type.item.isEmpty() || type.id.equals(MachineModuleType.SPEED_ID.toString())
                    || type.id.equals(MachineModuleType.ENERGY_ID.toString()) || !seen.add(type.id)) continue;
            entries.add(new Entry(null, type));
        }
        return Collections.unmodifiableList(entries);
    }
    public static int rows(int count, int firstRowRoom, int rowRoom) {
        if (count < 0 || firstRowRoom < 1 || rowRoom < 1) throw new IllegalArgumentException("Invalid supported upgrade layout");
        return count <= firstRowRoom ? 1 : 2 + (count - firstRowRoom - 1) / rowRoom;
    }
}
