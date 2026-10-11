package mekanism.cloudworks.common.data;

import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Last absolute value of each changed resource since the durable watermark. */
public final class CloudworksWarehouseDelta {
    private final Map<String, Map<QIOResourceDescriptor, Long>> amounts = new LinkedHashMap<>();
    private final Map<String, Integer> cursors = new LinkedHashMap<>();

    public void set(String warehouse, QIOResourceDescriptor resource, long amount) {
        if (resource == null || amount < 0) throw new IllegalArgumentException("Invalid warehouse delta");
        amounts.computeIfAbsent(warehouse, ignored -> new LinkedHashMap<>()).put(resource, amount);
    }

    public void cursor(String warehouse, int cursor) {
        if (cursor < 0) throw new IllegalArgumentException("Invalid warehouse cursor");
        cursors.put(warehouse, cursor);
    }

    public void merge(CloudworksWarehouseDelta delta) {
        delta.amounts.forEach((warehouse, entries) ->
                amounts.computeIfAbsent(warehouse, ignored -> new LinkedHashMap<>()).putAll(entries));
        cursors.putAll(delta.cursors);
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        for (String name : new String[]{"receiving", "transfer", "sending"}) {
            Map<QIOResourceDescriptor, Long> changes = amounts.get(name);
            if (changes == null && !cursors.containsKey(name)) continue;
            NBTTagCompound warehouse = new NBTTagCompound();
            NBTTagList entries = new NBTTagList();
            if (changes != null) changes.forEach((resource, amount) -> {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setTag("descriptor", resource.write());
                entry.setLong("amount", amount);
                entries.appendTag(entry);
            });
            warehouse.setTag("entries", entries);
            if (cursors.containsKey(name)) warehouse.setInteger("cursor", cursors.get(name));
            tag.setTag(name, warehouse);
        }
        return tag;
    }
}
