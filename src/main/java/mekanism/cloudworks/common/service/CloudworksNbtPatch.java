package mekanism.cloudworks.common.service;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.*;

/** Absolute field/index assignments: applying the same patch twice is harmless. */
final class CloudworksNbtPatch {
    static NBTTagCompound between(NBTBase before, NBTBase after) {
        NBTTagCompound patch = new NBTTagCompound();
        if (after.equals(before)) return patch;
        if (before instanceof NBTTagCompound && after instanceof NBTTagCompound) {
            NBTTagCompound previous = (NBTTagCompound) before, next = (NBTTagCompound) after;
            NBTTagCompound fields = new NBTTagCompound();
            NBTTagList removed = new NBTTagList();
            Set<String> keys = new HashSet<>(previous.getKeySet());
            keys.addAll(next.getKeySet());
            for (String key : keys) {
                NBTBase value = next.getTag(key);
                if (value == null) removed.appendTag(new NBTTagString(key));
                else if (!value.equals(previous.getTag(key)))
                    fields.setTag(key, between(previous.getTag(key), value));
            }
            patch.setTag("fields", fields);
            patch.setTag("removed", removed);
        } else if (before instanceof NBTTagList && after instanceof NBTTagList
                && ((NBTTagList) before).getTagType() == ((NBTTagList) after).getTagType()) {
            NBTTagList previous = (NBTTagList) before, next = (NBTTagList) after;
            NBTTagCompound entries = new NBTTagCompound();
            patch.setInteger("length", next.tagCount());
            for (int index = 0; index < next.tagCount(); index++) {
                NBTBase old = index < previous.tagCount() ? previous.get(index) : null;
                if (!next.get(index).equals(old)) entries.setTag(Integer.toString(index), between(old, next.get(index)));
            }
            patch.setTag("entries", entries);
        } else patch.setTag("value", after.copy());
        return patch;
    }

    static NBTBase apply(NBTBase before, NBTTagCompound patch) {
        if (patch.hasKey("value")) return patch.getTag("value").copy();
        if (patch.hasKey("fields", 10)) {
            NBTTagCompound next = before instanceof NBTTagCompound
                    ? ((NBTTagCompound) before).copy() : new NBTTagCompound();
            NBTTagList removed = patch.getTagList("removed", 8);
            for (int index = 0; index < removed.tagCount(); index++) next.removeTag(removed.getStringTagAt(index));
            NBTTagCompound fields = patch.getCompoundTag("fields");
            for (String key : fields.getKeySet())
                next.setTag(key, apply(next.getTag(key), fields.getCompoundTag(key)));
            return next;
        }
        if (patch.hasKey("entries", 10)) {
            NBTTagList previous = before instanceof NBTTagList ? (NBTTagList) before : new NBTTagList();
            NBTTagList next = new NBTTagList();
            NBTTagCompound entries = patch.getCompoundTag("entries");
            int length = patch.getInteger("length");
            if (length < 0) throw new IllegalArgumentException("Negative patch list length");
            for (int index = 0; index < length; index++) {
                String key = Integer.toString(index);
                NBTBase old = index < previous.tagCount() ? previous.get(index) : null;
                NBTBase value = entries.hasKey(key, 10) ? apply(old, entries.getCompoundTag(key)) : old;
                if (value == null) throw new IllegalArgumentException("Missing patch list value");
                next.appendTag(value.copy());
            }
            return next;
        }
        if (before == null) throw new IllegalArgumentException("Missing patch baseline");
        return before.copy();
    }
}
