package mekanism.cloudworks.common.data;

import java.util.*;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Cold, immutable state of one independent execution branch. Inventories belong to the parent node. */
public final class CloudworksExecutionState {
    public final int progress, duration;
    public final String state;
    public final long outputSequence;
    public final CloudworksOperation operation;
    public final CloudworksThermalState thermal;
    public final Map<QIOResourceDescriptor, Double> generationRemainders;

    public CloudworksExecutionState(int progress, int duration, String state, long outputSequence,
            CloudworksOperation operation, CloudworksThermalState thermal,
            Map<QIOResourceDescriptor, Double> generationRemainders) {
        if (progress < 0 || duration < 1 || progress >= duration || outputSequence < 0
                || operation != null && operation.duration() != duration)
            throw new IllegalArgumentException("Invalid execution branch state");
        this.progress = progress; this.duration = duration; this.state = Objects.requireNonNull(state);
        this.outputSequence = outputSequence; this.operation = operation; this.thermal = thermal;
        this.generationRemainders = remainders(generationRemainders);
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("progress", progress); tag.setInteger("duration", duration);
        tag.setString("state", state); tag.setLong("outputSequence", outputSequence);
        if (operation != null) tag.setTag("operation", operation.write());
        if (thermal != null) tag.setTag("thermal", thermal.write());
        writeRemainders(tag, generationRemainders);
        return tag;
    }

    public static CloudworksExecutionState read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 3, "progress", "duration");
        CloudworksDataChecks.fields(tag, 8, "state");
        CloudworksDataChecks.fields(tag, 4, "outputSequence");
        if (tag.hasKey("operation")) CloudworksDataChecks.field(tag, "operation", 10);
        if (tag.hasKey("thermal")) CloudworksDataChecks.field(tag, "thermal", 10);
        return new CloudworksExecutionState(tag.getInteger("progress"), tag.getInteger("duration"), tag.getString("state"),
                tag.getLong("outputSequence"), tag.hasKey("operation", 10) ? CloudworksOperation.read(tag.getCompoundTag("operation")) : null,
                tag.hasKey("thermal", 10) ? CloudworksThermalState.read(tag.getCompoundTag("thermal")) : null, readRemainders(tag));
    }

    public static Map<QIOResourceDescriptor, Double> remainders(Map<QIOResourceDescriptor, Double> values) {
        if (values.size() > 64) throw new IllegalArgumentException("Too many generation remainders");
        Map<QIOResourceDescriptor, Double> result = new LinkedHashMap<>();
        values.forEach((resource, amount) -> {
            if (resource == null || !resource.isResolved() || amount == null || !Double.isFinite(amount) || amount < 0 || amount >= 1)
                throw new IllegalArgumentException("Invalid generation remainder");
            if (amount > 0) result.put(resource, amount);
        });
        return Collections.unmodifiableMap(result);
    }

    public static void writeRemainders(NBTTagCompound tag, Map<QIOResourceDescriptor, Double> values) {
        tag.removeTag("generationRemainders");
        if (values.isEmpty()) return;
        NBTTagList list = new NBTTagList();
        values.forEach((resource, amount) -> {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setTag("descriptor", resource.write()); entry.setDouble("amount", amount);
            list.appendTag(entry);
        });
        tag.setTag("generationRemainders", list);
    }

    public static Map<QIOResourceDescriptor, Double> readRemainders(NBTTagCompound tag) {
        Map<QIOResourceDescriptor, Double> result = new LinkedHashMap<>();
        if (!tag.hasKey("generationRemainders")) return Collections.emptyMap();
        NBTTagList list = CloudworksDataChecks.list(tag, "generationRemainders", 10);
        if (list.tagCount() > 64) throw new IllegalArgumentException("Too many generation remainders");
        for (int index = 0; index < list.tagCount(); index++) {
            NBTTagCompound entry = list.getCompoundTagAt(index);
            CloudworksDataChecks.field(entry, "descriptor", 10); CloudworksDataChecks.field(entry, "amount", 6);
            QIOResourceDescriptor resource = CloudworksNativeContracts.isWorker()
                    ? CloudworksNativeContracts.resource(entry.getCompoundTag("descriptor"))
                    : QIOResourceDescriptor.read(entry.getCompoundTag("descriptor"));
            if (result.put(resource, entry.getDouble("amount")) != null)
                throw new IllegalArgumentException("Duplicate generation remainder");
        }
        return remainders(result);
    }
}
