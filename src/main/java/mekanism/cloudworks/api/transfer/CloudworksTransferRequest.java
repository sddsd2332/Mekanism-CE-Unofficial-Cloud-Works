package mekanism.cloudworks.api.transfer;

import java.util.Objects;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;

/** Trusted CRT/API cloud transfer. The caller owns the external buffer and its physical mutation. */
public final class CloudworksTransferRequest {
    public enum Direction { UPLOAD, DOWNLOAD }
    public enum Kind { MACHINE, UPGRADE, RESOURCE }
    public enum Warehouse { RECEIVING, TRANSFER, SENDING }
    private final Direction direction;
    private final Kind kind;
    private final ItemStack machine;
    private final ResourceLocation upgrade;
    private final QIOResourceDescriptor resource;
    private final long amount;
    private final Warehouse warehouse;

    private CloudworksTransferRequest(Direction direction, Kind kind, ItemStack machine,
            ResourceLocation upgrade, QIOResourceDescriptor resource, long amount) {
        this(direction, kind, machine, upgrade, resource, amount,
                direction == Direction.UPLOAD ? Warehouse.RECEIVING : Warehouse.SENDING);
    }

    private CloudworksTransferRequest(Direction direction, Kind kind, ItemStack machine,
            ResourceLocation upgrade, QIOResourceDescriptor resource, long amount, Warehouse warehouse) {
        if (amount <= 0) throw new IllegalArgumentException("Transfer amount must be positive");
        this.direction = Objects.requireNonNull(direction); this.kind = Objects.requireNonNull(kind);
        this.machine = machine == null ? ItemStack.EMPTY : machine.copy();
        if (!this.machine.isEmpty()) this.machine.setCount(1);
        this.upgrade = upgrade; this.resource = resource; this.amount = amount;
        this.warehouse = Objects.requireNonNull(warehouse);
        if (kind == Kind.MACHINE && (this.machine.isEmpty() || this.machine.getMetadata() == OreDictionary.WILDCARD_VALUE))
            throw new IllegalArgumentException("Machine requires one concrete identity");
        if (kind == Kind.UPGRADE && upgrade == null) throw new IllegalArgumentException("Upgrade is empty");
        if (kind == Kind.RESOURCE && (resource == null || !resource.isResolved()))
            throw new IllegalArgumentException("Resource is unresolved");
    }
    public static CloudworksTransferRequest uploadMachine(ItemStack machine, long amount) {
        return new CloudworksTransferRequest(Direction.UPLOAD, Kind.MACHINE, machine, null, null, amount);
    }
    public static CloudworksTransferRequest downloadMachine(ItemStack machine, long amount) {
        return new CloudworksTransferRequest(Direction.DOWNLOAD, Kind.MACHINE, machine, null, null, amount);
    }
    public static CloudworksTransferRequest uploadUpgrade(ResourceLocation upgrade, long amount) {
        return new CloudworksTransferRequest(Direction.UPLOAD, Kind.UPGRADE, null, upgrade, null, amount);
    }
    public static CloudworksTransferRequest downloadUpgrade(ResourceLocation upgrade, long amount) {
        return new CloudworksTransferRequest(Direction.DOWNLOAD, Kind.UPGRADE, null, upgrade, null, amount);
    }
    public static CloudworksTransferRequest uploadResource(QIOResourceDescriptor resource, long amount) {
        return new CloudworksTransferRequest(Direction.UPLOAD, Kind.RESOURCE, null, null, resource, amount);
    }
    public static CloudworksTransferRequest uploadResource(QIOResourceDescriptor resource, long amount, Warehouse warehouse) {
        return new CloudworksTransferRequest(Direction.UPLOAD, Kind.RESOURCE, null, null, resource, amount, warehouse);
    }
    public static CloudworksTransferRequest downloadResource(QIOResourceDescriptor resource, long amount) {
        return new CloudworksTransferRequest(Direction.DOWNLOAD, Kind.RESOURCE, null, null, resource, amount);
    }
    public static CloudworksTransferRequest downloadResource(QIOResourceDescriptor resource, long amount, Warehouse warehouse) {
        return new CloudworksTransferRequest(Direction.DOWNLOAD, Kind.RESOURCE, null, null, resource, amount, warehouse);
    }
    public Direction direction() { return direction; }
    public Kind kind() { return kind; }
    public ItemStack machine() { return machine.copy(); }
    public ResourceLocation upgrade() { return upgrade; }
    public QIOResourceDescriptor resource() { return resource; }
    public long amount() { return amount; }
    public Warehouse warehouse() { return warehouse; }
}
