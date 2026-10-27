package mekanism.cloudworks.api.module;

import io.netty.buffer.ByteBuf;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import mekanism.common.Upgrade;
import mekanism.common.base.IUpgradeItem;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.oredict.OreDictionary;

/**
 * Canonical upload/withdrawal items for the two counted cloud upgrade pools.
 * Replacing an item changes presentation and physical exchange, not the machine's upgrade formula.
 */
public final class CloudworksUpgradeItems {
    private static final CloudworksUpgradeItems LOCAL = new CloudworksUpgradeItems();
    private static volatile Snapshot synced;
    private volatile Map<Upgrade, ItemStack> overrides = Collections.emptyMap();
    private boolean frozen;
    private final java.util.Set<Upgrade> explicitBindings = new java.util.HashSet<>();

    public static CloudworksUpgradeItems local() { return LOCAL; }

    public synchronized void bind(Upgrade type, ItemStack stack) {
        mutable();
        supported(type);
        ItemStack template = concrete(stack);
        Upgrade other = type == Upgrade.SPEED ? Upgrade.ENERGY : Upgrade.SPEED;
        ItemStack otherStack = overrides.get(other);
        if (same(template, otherStack) || !overrides.containsKey(other) && nativeType(template) == other)
            throw new IllegalArgumentException("Cloud upgrade items must identify one type unambiguously");
        Map<Upgrade, ItemStack> next = new LinkedHashMap<>(overrides);
        next.put(type, template);
        overrides = Collections.unmodifiableMap(next);
        explicitBindings.add(type);
    }

    /** Installs the mod default without replacing an earlier addon declaration. */
    public synchronized void bindDefault(Upgrade type, ItemStack stack) {
        mutable();
        if (!explicitBindings.contains(type)) bind(type, stack);
    }

    public synchronized void useNative(Upgrade type) {
        mutable();
        supported(type);
        Upgrade other = type == Upgrade.SPEED ? Upgrade.ENERGY : Upgrade.SPEED;
        ItemStack otherStack = overrides.get(other);
        if (otherStack != null && nativeType(otherStack) == type)
            throw new IllegalArgumentException("Native cloud upgrade item conflicts with the other type");
        Map<Upgrade, ItemStack> next = new LinkedHashMap<>(overrides);
        next.remove(type);
        overrides = Collections.unmodifiableMap(next);
        explicitBindings.add(type);
    }

    public synchronized void freeze() { frozen = true; }

    private void mutable() {
        if (frozen) throw new IllegalStateException("Cloud upgrade item changes require a restart");
    }

    @Nullable
    public Upgrade typeOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Map<Upgrade, ItemStack> current = overrides;
        for (Map.Entry<Upgrade, ItemStack> entry : current.entrySet())
            if (same(stack, entry.getValue())) return entry.getKey();
        Upgrade nativeType = nativeType(stack);
        return nativeType != null && !current.containsKey(nativeType) ? nativeType : null;
    }

    public ItemStack stack(Upgrade type) {
        supported(type);
        ItemStack custom = overrides.get(type);
        return custom == null ? UpgradeUtils.getStack(type) : custom.copy();
    }

    public Snapshot snapshot() {
        Map<Upgrade, ItemStack> current = overrides;
        return new Snapshot(current.containsKey(Upgrade.SPEED) ? current.get(Upgrade.SPEED) : UpgradeUtils.getStack(Upgrade.SPEED),
                current.containsKey(Upgrade.ENERGY) ? current.get(Upgrade.ENERGY) : UpgradeUtils.getStack(Upgrade.ENERGY),
                current.containsKey(Upgrade.SPEED), current.containsKey(Upgrade.ENERGY));
    }

    public static ItemStack currentStack(Upgrade type) {
        Snapshot current = synced;
        return current == null ? LOCAL.stack(type) : current.stack(type);
    }

    public static boolean accepts(ItemStack stack) {
        Snapshot current = synced;
        return current == null ? LOCAL.typeOf(stack) != null : current.typeOf(stack) != null;
    }

    public static void writeServer(ByteBuf buffer) { LOCAL.snapshot().write(buffer); }

    public static void readServer(ByteBuf buffer) { synced = Snapshot.read(buffer); }

    public static void resetClient() { synced = null; }

    private static void supported(Upgrade type) {
        if (type != Upgrade.SPEED && type != Upgrade.ENERGY)
            throw new IllegalArgumentException("Only cloud speed and energy upgrade items can be replaced");
    }

    /** Registered, meta-concrete, count-1 template; shared with the custom-type declaration. */
    static ItemStack concrete(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getMetadata() < 0
                || stack.getMetadata() >= OreDictionary.WILDCARD_VALUE
                || stack.getItem().getRegistryName() == null
                || Item.REGISTRY.getObject(stack.getItem().getRegistryName()) != stack.getItem())
            throw new IllegalArgumentException("Cloud upgrade item requires a registered concrete ItemStack");
        ItemStack result = stack.copy();
        result.setCount(1);
        return result;
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return first != null && second != null && !first.isEmpty() && !second.isEmpty()
                && ItemStack.areItemsEqual(first, second) && ItemStack.areItemStackTagsEqual(first, second);
    }

    @Nullable
    private static Upgrade nativeType(ItemStack stack) {
        if (!(stack.getItem() instanceof IUpgradeItem item)) return null;
        Upgrade type = item.getUpgradeType(stack);
        return type == Upgrade.SPEED || type == Upgrade.ENERGY ? type : null;
    }

    /** Two immutable templates sent with Mek's module configuration on login, never from the client. */
    public static final class Snapshot {
        private final ItemStack speed, energy;
        private final boolean customSpeed, customEnergy;

        private Snapshot(ItemStack speed, ItemStack energy, boolean customSpeed, boolean customEnergy) {
            if (same(speed, energy)) throw new IllegalArgumentException("Ambiguous cloud upgrade items");
            if (customSpeed && speed.isEmpty() || customEnergy && energy.isEmpty())
                throw new IllegalArgumentException("Missing custom cloud upgrade item");
            this.speed = speed.isEmpty() ? ItemStack.EMPTY : concrete(speed);
            this.energy = energy.isEmpty() ? ItemStack.EMPTY : concrete(energy);
            this.customSpeed = customSpeed;
            this.customEnergy = customEnergy;
        }

        public ItemStack stack(Upgrade type) {
            supported(type);
            return (type == Upgrade.SPEED ? speed : energy).copy();
        }

        @Nullable
        public Upgrade typeOf(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return null;
            if (customSpeed && same(stack, speed)) return Upgrade.SPEED;
            if (customEnergy && same(stack, energy)) return Upgrade.ENERGY;
            Upgrade type = nativeType(stack);
            return type == Upgrade.SPEED && !customSpeed || type == Upgrade.ENERGY && !customEnergy ? type : null;
        }

        public void write(ByteBuf buffer) {
            buffer.writeByte((customSpeed ? 1 : 0) | (customEnergy ? 2 : 0));
            // ItemStack's normal network encoding may strip identity NBT through getNBTShareTag.
            ByteBufUtils.writeTag(buffer, speed.writeToNBT(new NBTTagCompound()));
            ByteBufUtils.writeTag(buffer, energy.writeToNBT(new NBTTagCompound()));
        }

        public static Snapshot read(ByteBuf buffer) {
            int flags = buffer.readUnsignedByte();
            if (flags > 3) throw new IllegalArgumentException("Invalid cloud upgrade item flags");
            return new Snapshot(new ItemStack(ByteBufUtils.readTag(buffer)), new ItemStack(ByteBufUtils.readTag(buffer)),
                    (flags & 1) != 0, (flags & 2) != 0);
        }
    }
}
