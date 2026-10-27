package mekanism.cloudworks.common.data;

import java.util.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;

/** A chosen target is persisted by item identity, never by an unstable ore-list index. */
public final class CloudworksOreRule {
    public final String ore;
    public final boolean enabled;
    private final NBTTagCompound target;
    public CloudworksOreRule(String ore, ItemStack target, boolean enabled) {
        this(ore, target.serializeNBT(), enabled);
        if (target.isEmpty() || target.hasTagCompound() || target.getMetadata() == 32767)
            throw new IllegalArgumentException("Invalid ore conversion target");
    }
    private CloudworksOreRule(String ore, NBTTagCompound target, boolean enabled) {
        if (ore == null || !ore.matches("[A-Za-z0-9_:.-]{1,128}") || !target.hasKey("id", 8)
                || target.getString("id").isEmpty() || target.getShort("Damage") < 0 || target.getShort("Damage") == 32767)
            throw new IllegalArgumentException("Invalid ore conversion rule");
        this.ore = ore; this.enabled = enabled; this.target = target.copy();
        this.target.setByte("Count", (byte) 1);
        if (this.target.hasKey("tag") || this.target.hasKey("ForgeCaps")) throw new IllegalArgumentException("Stateful ore conversion target");
    }
    public ItemStack target() { return new ItemStack(target.copy()); }
    public CloudworksOreRule enabled(boolean value) { return new CloudworksOreRule(ore, target, value); }
    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound(); tag.setString("ore", ore);
        tag.setBoolean("enabled", enabled); tag.setTag("target", target.copy()); return tag;
    }
    public static CloudworksOreRule read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 8, "ore"); CloudworksDataChecks.fields(tag, 1, "enabled");
        CloudworksDataChecks.fields(tag, 10, "target");
        return new CloudworksOreRule(tag.getString("ore"), tag.getCompoundTag("target"), tag.getBoolean("enabled"));
    }
    public static NBTTagList writeList(List<CloudworksOreRule> rules) {
        NBTTagList tag = new NBTTagList(); rules.forEach(rule -> tag.appendTag(rule.write())); return tag;
    }
    public static List<CloudworksOreRule> readList(NBTTagList tag) {
        List<CloudworksOreRule> rules = new ArrayList<>(); Set<String> names = new HashSet<>();
        for (int index = 0; index < tag.tagCount(); index++) {
            CloudworksOreRule rule = read(tag.getCompoundTagAt(index));
            if (!names.add(rule.ore)) throw new IllegalArgumentException("Duplicate ore conversion rule");
            rules.add(rule);
        }
        return Collections.unmodifiableList(rules);
    }
    @Override public boolean equals(Object other) { return other instanceof CloudworksOreRule && write().equals(((CloudworksOreRule) other).write()); }
    @Override public int hashCode() { return write().hashCode(); }
}
