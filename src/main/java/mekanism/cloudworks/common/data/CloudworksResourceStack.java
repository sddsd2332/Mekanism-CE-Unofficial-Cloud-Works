package mekanism.cloudworks.common.data;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Objects;

public final class CloudworksResourceStack {
    private final QIOResourceDescriptor descriptor;
    private long amount;
    private long reserved;

    public CloudworksResourceStack(QIOResourceDescriptor descriptor, long amount) {
        this.descriptor = Objects.requireNonNull(descriptor);
        if (amount < 0) throw new IllegalArgumentException("Negative resource quantity");
        this.amount = amount;
    }

    private static void requireRange(long value, long maximum) {
        if (value < 0 || value > maximum)
            throw new IllegalArgumentException("Resource quantity exceeds available amount");
    }

    public static CloudworksResourceStack read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 10, "descriptor");
        CloudworksDataChecks.fields(tag, 4, "amount", "reserved");

        CloudworksDataChecks.schema(tag, 1);
        CloudworksResourceStack stack = new CloudworksResourceStack(
                CloudworksDataChecks.descriptor(tag.getCompoundTag("descriptor")), tag.getLong("amount"));
        long reserved = tag.getLong("reserved");
        requireRange(reserved, stack.amount);
        stack.reserved = reserved;
        return stack;
    }

    public QIOResourceDescriptor descriptor() {
        return descriptor;
    }

    public NBTTagCompound value() {
        // The descriptor is immutable and already owns the canonical payload.
        // Defer its defensive copy until a real payload consumer asks for it.
        return descriptor.getPayload();
    }

    public long amount() {
        return amount;
    }

    public long reserved() {
        return reserved;
    }

    public long available() {
        return Math.max(0, amount - reserved);
    }

    public void reserve(long value) {
        requireRange(value, available());
        reserved += value;
    }

    public void release(long value) {
        requireRange(value, reserved);
        reserved -= value;
    }

    public void consume(long value) {
        requireRange(value, available());
        amount -= value;
    }

    public void add(long value) {
        if (value < 0) throw new IllegalArgumentException("Negative resource quantity");
        amount = Math.addExact(amount, value);
    }

    public CloudworksResourceStack split(long value) {
        requireRange(value, available());
        consume(value);
        return new CloudworksResourceStack(descriptor, value);
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        tag.setTag("descriptor", descriptor.write());
        tag.setLong("amount", amount);
        tag.setLong("reserved", reserved);
        return tag;
    }
}
