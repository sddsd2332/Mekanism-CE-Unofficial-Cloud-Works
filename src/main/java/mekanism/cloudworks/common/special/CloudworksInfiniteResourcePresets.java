package mekanism.cloudworks.common.special;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.nbt.JsonToNBT;
import java.util.function.BiConsumer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTException;

/** Configuration syntax only. Resource resolution and all card validation use the existing card parser. */
public final class CloudworksInfiniteResourcePresets {
    private CloudworksInfiniteResourcePresets() {}

    public static ItemStack card(Item item, String preset) {
        if (preset == null || preset.trim().isEmpty()) throw new IllegalArgumentException("Empty preset");
        List<String> sections = splitTopLevel(preset.trim(), '|');
        if (sections.size() > 2) throw new IllegalArgumentException("Multiple preset suffixes");
        NBTTagCompound tag = new NBTTagCompound();
        String resourceReference = sections.get(0).trim();
        if (resourceReference.isEmpty()) throw new IllegalArgumentException("Empty resource reference");
        PresetOptions options = sections.size() == 2
                ? parseOptions(resourceReference, sections.get(1))
                : new PresetOptions();
        tag.setString("Resources", resourceReference);
        if (!options.data.isEmpty()) {
            tag.setTag("Data", options.data);
        }
        ItemStack result = new ItemStack(item);
        result.setTagCompound(tag);
        if (options.itemNbt != null) {
            CloudworksInfiniteResourceCardData base = CloudworksInfiniteResourceCardData.parse(result);
            ItemStack resource = base.descriptor.resolve(QIOResourceCodecs.ITEM_STACK);
            if (resource == null || resource.isEmpty()) {
                throw new IllegalArgumentException("NBT suffix requires an item resource");
            }
            resource.setTagCompound(options.itemNbt.copy());
            QIOResourceDescriptor descriptor = QIOResourceCodecs.item(resource);
            tag.setTag("Descriptor", descriptor.write());
        }
        CloudworksInfiniteResourceCardData.parse(result);
        return result;
    }

    public static List<ItemStack> cards(Item item, String[] presets,
            BiConsumer<String, RuntimeException> warn) {
        List<ItemStack> result = new ArrayList<>();
        result.add(new ItemStack(item));
        for (String preset : presets) {
            try {
                result.add(card(item, preset));
            } catch (RuntimeException invalid) {
                warn.accept(preset, invalid);
            }
        }
        return result;
    }

    private static PresetOptions parseOptions(String resourceReference, String suffix) {
        if (suffix == null || suffix.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty preset suffix");
        }
        PresetOptions options = new PresetOptions();
        Set<String> seen = new HashSet<>();
        for (String field : splitTopLevel(suffix, ',')) {
            int separator = field.indexOf('=');
            if (separator <= 0 || separator == field.length() - 1) {
                throw new IllegalArgumentException("Invalid preset assignment");
            }
            String key = field.substring(0, separator).trim();
            String value = field.substring(separator + 1).trim();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Invalid or duplicate preset key: " + key);
            }
            if ("nbt".equals(key)) {
                if (!resourceReference.regionMatches(true, 0, "item:", 0, 5)) {
                    throw new IllegalArgumentException("NBT suffix requires an item resource");
                }
                options.itemNbt = parseNbt(value);
            } else if ("nodeLimit".equals(key) || "outputAmount".equals(key)) {
                long parsed = Long.parseLong(value);
                if (parsed <= 0) throw new IllegalArgumentException("Preset value must be positive");
                options.data.setLong(key, parsed);
                if ("nodeLimit".equals(key)) options.data.setBoolean("unlimitedPlacement", false);
            } else {
                throw new IllegalArgumentException("Invalid preset assignment: " + key);
            }
        }
        return options;
    }

    private static NBTTagCompound parseNbt(String value) {
        try {
            return JsonToNBT.getTagFromJson(value);
        } catch (NBTException | RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid item NBT", invalid);
        }
    }

    private static List<String> splitTopLevel(String value, char delimiter) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '{' || current == '[') {
                depth++;
            } else if (current == '}' || current == ']') {
                if (--depth < 0) throw new IllegalArgumentException("Unbalanced preset NBT");
            } else if (current == delimiter && depth == 0) {
                result.add(value.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (quote != 0 || depth != 0) throw new IllegalArgumentException("Unbalanced preset NBT");
        result.add(value.substring(start).trim());
        return result;
    }

    private static final class PresetOptions {
        private final NBTTagCompound data = new NBTTagCompound();
        private NBTTagCompound itemNbt;
    }
}
