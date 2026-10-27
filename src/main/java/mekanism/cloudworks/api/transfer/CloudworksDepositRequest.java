package mekanism.cloudworks.api.transfer;

import java.util.Objects;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;

/** Trusted reward deposit, not a transfer from a physical inventory. */
public final class CloudworksDepositRequest {
    public enum Kind { MACHINE, UPGRADE_ITEM, UPGRADE_TYPE }
    private final Kind kind;
    private final ItemStack item;
    private final ResourceLocation upgrade;
    private final long amount;

    private CloudworksDepositRequest(Kind kind, ItemStack item, ResourceLocation upgrade, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Deposit amount must be positive");
        this.kind = kind; this.upgrade = upgrade; this.amount = amount;
        this.item = item == null ? ItemStack.EMPTY : item.copy();
        if (kind != Kind.UPGRADE_TYPE && (this.item.isEmpty() || this.item.getMetadata() == OreDictionary.WILDCARD_VALUE))
            throw new IllegalArgumentException("Deposit requires one concrete item identity");
        if (!this.item.isEmpty()) this.item.setCount(1);
    }

    public static CloudworksDepositRequest machines(ItemStack machine, long amount) {
        return new CloudworksDepositRequest(Kind.MACHINE, machine, null, amount);
    }
    public static CloudworksDepositRequest upgrades(ItemStack item, long amount) {
        return new CloudworksDepositRequest(Kind.UPGRADE_ITEM, item, null, amount);
    }
    public static CloudworksDepositRequest upgradeType(ResourceLocation type, long amount) {
        return new CloudworksDepositRequest(Kind.UPGRADE_TYPE, null, Objects.requireNonNull(type), amount);
    }
    public Kind kind() { return kind; }
    public ItemStack item() { return item.copy(); }
    public ResourceLocation upgrade() { return upgrade; }
    public long amount() { return amount; }
}
