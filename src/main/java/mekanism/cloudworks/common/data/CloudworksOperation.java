package mekanism.cloudworks.common.data;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.ArrayList;
import java.util.Collections;
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
    private double modulePower = 1D;
    private long lifecycleId;
    private int parallelUnits;
    private long outputSequence = -1;
    private net.minecraft.util.ResourceLocation requiredEnvironmentAxis;
    private final boolean completionPrepared;
    private final String recipeId;
    private final List<CloudworksResourceStack> inputs;
    private final List<CloudworksResourceStack> outputs;
    private final List<CloudworksResourceStack> chanceOutputs;

    public CloudworksOperation(int operations, int duration, List<CloudworksResourceStack> inputs, List<CloudworksResourceStack> outputs) {
        this(operations, duration, inputs, outputs, 0D);
    }

    public CloudworksOperation(int operations, int duration, List<CloudworksResourceStack> inputs,
            List<CloudworksResourceStack> outputs, double extraEnergyPerTick) {
        this(UUID.randomUUID(), operations, duration, inputs, outputs, extraEnergyPerTick, false, "");
    }

    public CloudworksOperation(int operations, int duration, List<CloudworksResourceStack> inputs,
            List<CloudworksResourceStack> outputs, double extraEnergyPerTick, boolean completionPrepared) {
        this(UUID.randomUUID(), operations, duration, inputs, outputs, extraEnergyPerTick, completionPrepared, "");
    }

    public CloudworksOperation(int operations, int duration, List<CloudworksResourceStack> inputs,
            List<CloudworksResourceStack> outputs, double extraEnergyPerTick, boolean completionPrepared,
            String recipeId) {
        this(UUID.randomUUID(), operations, duration, inputs, outputs, extraEnergyPerTick, completionPrepared, recipeId);
    }

    private CloudworksOperation(UUID id, int operations, int duration, List<CloudworksResourceStack> inputs,
            List<CloudworksResourceStack> outputs, double extraEnergyPerTick, boolean completionPrepared,
            String recipeId) {
        this(id, operations, duration, inputs, outputs, Collections.emptyList(), extraEnergyPerTick,
                completionPrepared, recipeId);
    }

    public CloudworksOperation(int operations, int duration, List<CloudworksResourceStack> inputs,
            List<CloudworksResourceStack> outputs, List<CloudworksResourceStack> chanceOutputs,
            double extraEnergyPerTick, boolean completionPrepared, String recipeId) {
        this(UUID.randomUUID(), operations, duration, inputs, outputs, chanceOutputs,
                extraEnergyPerTick, completionPrepared, recipeId);
    }

    /** Reconstruct an existing promise without replacing its persistent identity. */
    public CloudworksOperation(UUID id, int operations, int duration, List<CloudworksResourceStack> inputs,
            List<CloudworksResourceStack> outputs, List<CloudworksResourceStack> chanceOutputs,
            double extraEnergyPerTick, boolean completionPrepared, String recipeId) {
        // A chance recipe may legitimately produce no item for this batch. Inputs
        // still must be reserved, while an empty output list is a valid no-product
        // completion rather than a failed operation.
        if (operations < 1 || duration < 1) throw new IllegalArgumentException("Invalid operation");
        if (!Double.isFinite(extraEnergyPerTick) || extraEnergyPerTick < 0D)
            throw new IllegalArgumentException("Invalid operation energy");
        this.id = Objects.requireNonNull(id);
        lifecycleId = id.getLeastSignificantBits();
        this.operations = operations;
        parallelUnits = operations;
        this.duration = duration;
        this.extraEnergyPerTick = extraEnergyPerTick;
        this.completionPrepared = completionPrepared;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.inputs = copy(inputs);
        this.outputs = copy(outputs);
        this.chanceOutputs = copy(chanceOutputs);
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
                tag.hasKey("chanceOutputs", 9) ? readList(tag.getTagList("chanceOutputs", 10)) : Collections.emptyList(),
                tag.getDouble("extraEnergyPerTick"), tag.getBoolean("completionPrepared"),
                tag.getString("recipeId")).withModulePower(tag.hasKey("modulePower", 6) ? tag.getDouble("modulePower") : 1D)
                .withLifecycleId(tag.hasKey("lifecycleId", 4) ? tag.getLong("lifecycleId")
                        : UUID.fromString(tag.getString("id")).getLeastSignificantBits())
                .withParallelism(tag.hasKey("parallelUnits", 3) ? tag.getInteger("parallelUnits") : tag.getInteger("operations"),
                        tag.hasKey("outputSequence", 4) ? tag.getLong("outputSequence") : -1)
                .withEnvironmentAxis(readEnvironmentAxis(tag));
    }
    private static net.minecraft.util.ResourceLocation readEnvironmentAxis(NBTTagCompound tag) {
        if (!tag.hasKey("requiredEnvironmentAxis")) return null;
        CloudworksDataChecks.field(tag, "requiredEnvironmentAxis", 8);
        String axis = tag.getString("requiredEnvironmentAxis");
        if (axis.isEmpty() || axis.length() > 128 || !axis.equals(new net.minecraft.util.ResourceLocation(axis).toString()))
            throw new IllegalArgumentException("Invalid operation environment axis");
        return new net.minecraft.util.ResourceLocation(axis);
    }
    public net.minecraft.util.ResourceLocation requiredEnvironmentAxis() { return requiredEnvironmentAxis; }
    public CloudworksOperation withEnvironmentAxis(net.minecraft.util.ResourceLocation axis) {
        if (axis != null && axis.toString().length() > 128) throw new IllegalArgumentException("Invalid operation environment axis");
        CloudworksOperation copy = withModulePower(modulePower); copy.requiredEnvironmentAxis = axis; return copy;
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
    public double modulePower() { return modulePower; }
    public CloudworksOperation withModulePower(double value) {
        if (!Double.isFinite(value) || value < 0D) throw new IllegalArgumentException("Invalid module power");
        CloudworksOperation copy = new CloudworksOperation(id, operations, duration, inputs, outputs, chanceOutputs,
                extraEnergyPerTick, completionPrepared, recipeId);
        copy.modulePower = value;
        copy.lifecycleId = lifecycleId;
        copy.parallelUnits = parallelUnits;
        copy.outputSequence = outputSequence;
        copy.requiredEnvironmentAxis = requiredEnvironmentAxis;
        return copy;
    }
    public int parallelUnits() { return parallelUnits; }
    public long outputSequence() { return outputSequence; }
    public CloudworksOperation withParallelism(int units, long sequence) {
        if (units < 1 || units > operations || sequence < -1) throw new IllegalArgumentException("Invalid operation parallelism");
        CloudworksOperation copy = withModulePower(modulePower);
        copy.parallelUnits = units;
        copy.outputSequence = sequence;
        return copy;
    }
    public long lifecycleId() { return lifecycleId; }
    public CloudworksOperation withLifecycleId(long value) {
        CloudworksOperation copy = withModulePower(modulePower); copy.lifecycleId = value; return copy;
    }
    public boolean completionPrepared() { return completionPrepared; }
    public String recipeId() { return recipeId; }

    public List<CloudworksResourceStack> inputs() {
        return copy(inputs);
    }

    public List<CloudworksResourceStack> outputs() {
        return copy(outputs);
    }

    public List<CloudworksResourceStack> chanceOutputs() {
        return copy(chanceOutputs);
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        tag.setString("id", id.toString());
        tag.setInteger("operations", operations);
        tag.setInteger("duration", duration);
        if (extraEnergyPerTick != 0D) tag.setDouble("extraEnergyPerTick", extraEnergyPerTick);
        tag.setDouble("modulePower", modulePower);
        tag.setLong("lifecycleId", lifecycleId);
        tag.setInteger("parallelUnits", parallelUnits);
        if (outputSequence >= 0) tag.setLong("outputSequence", outputSequence);
        if (requiredEnvironmentAxis != null) tag.setString("requiredEnvironmentAxis", requiredEnvironmentAxis.toString());
        if (completionPrepared) tag.setBoolean("completionPrepared", true);
        if (!recipeId.isEmpty()) tag.setString("recipeId", recipeId);
        tag.setTag("inputs", writeList(inputs));
        tag.setTag("outputs", writeList(outputs));
        if (!chanceOutputs.isEmpty()) tag.setTag("chanceOutputs", writeList(chanceOutputs));
        return tag;
    }
}
