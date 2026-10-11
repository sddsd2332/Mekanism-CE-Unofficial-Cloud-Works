package mekanism.cloudworks.common.data;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An in-flight operation: the recipe input requirement and the promised output. Inputs stay in the node cache (reserved); output is a promise, never a second input copy.
 */
public final class CloudworksOperation {
    private final UUID id;
    private final int operations;
    private final int duration;
    private final double extraEnergyPerTick;
    private final List<CloudworksResourceStack> inputs;
    private final List<CloudworksResourceStack> outputs;

    public CloudworksOperation(int operations, int duration, List<CloudworksResourceStack> inputs, List<CloudworksResourceStack> outputs) {
        this(operations, duration, inputs, outputs, 0D);
    }

    public CloudworksOperation(int operations, int duration, List<CloudworksResourceStack> inputs,
            List<CloudworksResourceStack> outputs, double extraEnergyPerTick) {
        this(UUID.randomUUID(), operations, duration, inputs, outputs, extraEnergyPerTick);
    }

    private CloudworksOperation(UUID id, int operations, int duration, List<CloudworksResourceStack> inputs,
            List<CloudworksResourceStack> outputs, double extraEnergyPerTick) {
        // A chance recipe may legitimately produce no item for this batch. Inputs
        // still must be reserved, while an empty output list is a valid no-product
        // completion rather than a failed operation.
        if (operations < 1 || duration < 1) throw new IllegalArgumentException("Invalid operation");
        if (!Double.isFinite(extraEnergyPerTick) || extraEnergyPerTick < 0D)
            throw new IllegalArgumentException("Invalid operation energy");
        this.id = Objects.requireNonNull(id);
        this.operations = operations;
        this.duration = duration;
        this.extraEnergyPerTick = extraEnergyPerTick;
        this.inputs = copy(inputs);
        this.outputs = copy(outputs);
    }

    private static List<CloudworksResourceStack> copy(List<CloudworksResourceStack> values) {
        List<CloudworksResourceStack> result = new ArrayList<>();
        for (CloudworksResourceStack stack : values) {
            if (stack.amount() <= 0 || stack.reserved() != 0)
                throw new IllegalArgumentException("Invalid operation quantity");
            result.add(new CloudworksResourceStack(stack.descriptor(), stack.amount()));
        }
        return result;
    }

    private static NBTTagList writeList(List<CloudworksResourceStack> values) {
        NBTTagList list = new NBTTagList();
        for (CloudworksResourceStack value : values) list.appendTag(value.write());
        return list;
    }

    private static List<CloudworksResourceStack> readList(NBTTagList list) {
        List<CloudworksResourceStack> values = new ArrayList<>();
        for (int i = 0; i < list.tagCount(); i++) values.add(CloudworksResourceStack.read(list.getCompoundTagAt(i)));
        return values;
    }

    public static CloudworksOperation read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 8, "id");
        CloudworksDataChecks.fields(tag, 3, "operations", "duration");

        CloudworksDataChecks.schema(tag, 1);
        if (tag.hasKey("extraEnergyPerTick")) CloudworksDataChecks.field(tag, "extraEnergyPerTick", 6);
        return new CloudworksOperation(UUID.fromString(tag.getString("id")), tag.getInteger("operations"), tag.getInteger("duration"),
                readList(CloudworksDataChecks.list(tag, "inputs", 10)), readList(CloudworksDataChecks.list(tag, "outputs", 10)),
                tag.getDouble("extraEnergyPerTick"));
    }

    public UUID id() {
        return id;
    }

    public int operations() {
        return operations;
    }

    public int duration() {
        return duration;
    }

    public double extraEnergyPerTick() { return extraEnergyPerTick; }

    public List<CloudworksResourceStack> inputs() {
        return copy(inputs);
    }

    public List<CloudworksResourceStack> outputs() {
        return copy(outputs);
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        tag.setString("id", id.toString());
        tag.setInteger("operations", operations);
        tag.setInteger("duration", duration);
        if (extraEnergyPerTick != 0D) tag.setDouble("extraEnergyPerTick", extraEnergyPerTick);
        tag.setTag("inputs", writeList(inputs));
        tag.setTag("outputs", writeList(outputs));
        return tag;
    }
}
