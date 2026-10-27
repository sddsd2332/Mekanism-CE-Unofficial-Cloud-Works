package mekanism.cloudworks.common.service;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.*;

/** Absolute field/index assignments: applying the same patch twice is harmless. */
final class CloudworksNbtPatch {
    static NBTTagCompound between(NBTBase before, NBTBase after) {
        NBTTagCompound patch = new NBTTagCompound();
        if (after == before) return patch;
        if (before instanceof NBTTagCompound && after instanceof NBTTagCompound) {
            NBTTagCompound previous = (NBTTagCompound) before, next = (NBTTagCompound) after;
            NBTTagCompound fields = new NBTTagCompound();
            NBTTagList removed = new NBTTagList();
            Set<String> keys = new HashSet<>(previous.getKeySet());
            keys.addAll(next.getKeySet());
            for (String key : keys) {
                NBTBase value = next.getTag(key);
                if (value == null) removed.appendTag(new NBTTagString(key));
                else {
                    NBTTagCompound difference = between(previous.getTag(key), value);
                    if (!difference.isEmpty()) fields.setTag(key, difference);
                }
            }
            if (!fields.isEmpty() || removed.tagCount() != 0) {
                patch.setTag("fields", fields);
                patch.setTag("removed", removed);
            }
        } else if (before instanceof NBTTagList && after instanceof NBTTagList
                && ((NBTTagList) before).getTagType() == ((NBTTagList) after).getTagType()) {
            NBTTagList previous = (NBTTagList) before, next = (NBTTagList) after;
            NBTTagCompound entries = new NBTTagCompound();
            for (int index = 0; index < next.tagCount(); index++) {
                NBTBase old = index < previous.tagCount() ? previous.get(index) : null;
                NBTTagCompound difference = between(old, next.get(index));
                if (!difference.isEmpty()) entries.setTag(Integer.toString(index), difference);
            }
            if (!entries.isEmpty() || previous.tagCount() != next.tagCount()) {
                patch.setInteger("length", next.tagCount());
                patch.setTag("entries", entries);
            }
        } else if (!after.equals(before)) patch.setTag("value", after.copy());
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
