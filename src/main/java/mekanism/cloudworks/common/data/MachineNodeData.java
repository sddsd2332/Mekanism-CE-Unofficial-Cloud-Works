package mekanism.cloudworks.common.data;

import mekanism.api.gas.GasStack;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.CloudworksMachineAdapter;
import mekanism.cloudworks.common.recipe.SpecialMachineAdapter;
import mekanism.cloudworks.common.registry.CloudworksMachineAdapters;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineExecutionProfile;
import mekanism.cloudworks.common.registry.MachineKey;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MachineNodeData {
    public static final int SCHEMA_VERSION = 4;
    private final UUID nodeId;
    private final List<CloudworksResourceStack> inputs = new ArrayList<>();
    private final List<CloudworksResourceStack> outputs = new ArrayList<>();
    private final List<CloudworksResourceStack> templates = new ArrayList<>();
    private ItemStack machine = ItemStack.EMPTY;
    /**
     * Claimed machines of the owner's machine cache; the cache holds the physical stacks.
     */
    private long machineCount;
    private boolean pooledClaim;
    private UUID waitingBlueprint;
    private long waitingCount;
    /**
     * Upload transaction that created this node; null on command/legacy nodes.
     */
    private UUID transferId;
    /**
     * Identity of the source component that supplied the upload; null when not uploaded.
     */
    private UUID sourceId;
    private CloudworksOperation operation;
    private transient java.util.IdentityHashMap<Object, RecipeHit> recipeHits;
    private transient MachineCapabilities capabilities;
    private NBTTagCompound frozenMachine;
    private int x;
    private int z;
    private int dimensionId;
    private boolean active;
    private int progress;
    private int totalTicks = 1;
    private String state = "OFF";
    private CloudworksThermalState thermalState;

    public MachineNodeData(UUID nodeId, ItemStack machine, int x, int z) {
        this.nodeId = nodeId;
        this.machine = machine.copy();
        this.machine.setCount(1);
        this.machineCount = 1;
        this.x = x;
        this.z = z;
    }

    private MachineNodeData(MachineNodeData source) {
        nodeId = source.nodeId;
        // No public method exposes or mutates this private item template.
        machine = source.machine;
        machineCount = source.machineCount;
        pooledClaim = source.pooledClaim;
        waitingBlueprint = source.waitingBlueprint;
        waitingCount = source.waitingCount;
        transferId = source.transferId;
        sourceId = source.sourceId;
        capabilities = source.capabilities;
        frozenMachine = source.frozenMachine;
        x = source.x;
        z = source.z;
        dimensionId = source.dimensionId;
        active = source.active;
        progress = source.progress;
        totalTicks = source.totalTicks;
        state = source.state;
        thermalState = source.thermalState;
    }

    /** Cold metadata only; Runtime fills inventories and promises from its numerical stores. */
    public MachineNodeData copyForRuntime() {
        return new MachineNodeData(this);
    }

    /** Independent mutable view of a worker's cached node, without NBT or native capability calls. */
    public MachineNodeData copyRuntimeState() {
        MachineNodeData copy = new MachineNodeData(this);
        for (CloudworksResourceStack stack : inputs) copy.inputs.add(copyRuntimeStack(stack));
        for (CloudworksResourceStack stack : outputs) copy.outputs.add(copyRuntimeStack(stack));
        for (CloudworksResourceStack stack : templates) copy.templates.add(copyRuntimeStack(stack));
        copy.operation = operation;
        return copy;
    }

    private static List<CloudworksResourceStack> merge(List<CloudworksResourceStack> first, List<CloudworksResourceStack> second) {
        List<CloudworksResourceStack> result = new ArrayList<>();
        for (CloudworksResourceStack stack : first) result.add(CloudworksResourceStack.read(stack.write()));
        for (CloudworksResourceStack stack : second) {
            CloudworksResourceStack match = null;
            for (CloudworksResourceStack existing : result)
                if (existing.descriptor().equals(stack.descriptor())) {
                    match = existing;
                    break;
                }
            if (match == null) result.add(new CloudworksResourceStack(stack.descriptor(), stack.amount()));
            else match.add(stack.amount());
        }
        return result;
    }

    public static MachineNodeData read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 8, "nodeId", "state");
        CloudworksDataChecks.fields(tag, 10, "machine");
        CloudworksDataChecks.fields(tag, 4, "machineCount");
        CloudworksDataChecks.fields(tag, 3, "x", "z", "progress", "totalTicks");
        CloudworksDataChecks.fields(tag, 1, "active");

        CloudworksDataChecks.schema(tag, SCHEMA_VERSION);
        MachineNodeData node;
        if (mekanism.cloudworks.common.service.CloudworksNativeContracts.isWorker()) {
            node = new MachineNodeData(mekanism.cloudworks.common.service.CloudworksNativeContracts.machine(
                    tag.getCompoundTag("machine")), UUID.fromString(tag.getString("nodeId")),
                    tag.getInteger("x"), tag.getInteger("z"));
        } else node = new MachineNodeData(UUID.fromString(tag.getString("nodeId")),
                new ItemStack(tag.getCompoundTag("machine")), tag.getInteger("x"), tag.getInteger("z"));
        // Schema 4+: only the claimed count; physical machines live in the machine cache.
        // A persisted node must own at least one machine: silently lifting a
        // corrupt zero to one would fabricate a machine out of bad bytes (P2).
        long machineCount = tag.getLong("machineCount");
        if (tag.hasKey("waitingBlueprint")) {
            CloudworksDataChecks.field(tag, "waitingBlueprint", NBT.TAG_STRING);
            node.waitingBlueprint = UUID.fromString(tag.getString("waitingBlueprint"));
            CloudworksDataChecks.field(tag, "waitingCount", NBT.TAG_LONG);
            node.waitingCount = tag.getLong("waitingCount");
            if (node.waitingCount <= 0) throw new IllegalArgumentException("Invalid blueprint count");
        }
        if (machineCount < 0 || (machineCount == 0) != (node.waitingBlueprint != null))
            throw new IllegalArgumentException("Invalid machine count");
        node.setMachineCount(machineCount);
        if (tag.hasKey("pooledClaim")) CloudworksDataChecks.field(tag, "pooledClaim", NBT.TAG_BYTE);
        node.pooledClaim = tag.getBoolean("pooledClaim");
        if (tag.hasKey("transferId")) {
            CloudworksDataChecks.field(tag, "transferId", NBT.TAG_STRING);
            node.transferId = UUID.fromString(tag.getString("transferId"));
        }
        if (tag.hasKey("sourceId")) {
            CloudworksDataChecks.field(tag, "sourceId", NBT.TAG_STRING);
            node.sourceId = UUID.fromString(tag.getString("sourceId"));
        }
        if (tag.hasKey("dimensionId")) CloudworksDataChecks.field(tag, "dimensionId", NBT.TAG_INT);
        node.dimensionId = tag.hasKey("dimensionId") ? tag.getInteger("dimensionId") : 0;
        node.active = tag.getBoolean("active");
        node.progress = tag.getInteger("progress");
        node.totalTicks = tag.getInteger("totalTicks");
        if (node.totalTicks < 1 || node.progress < 0 || node.progress > node.totalTicks)
            throw new IllegalArgumentException("Invalid node progress");
        node.state = tag.getString("state");
        if (tag.hasKey("thermalState")) {
            CloudworksDataChecks.field(tag, "thermalState", NBT.TAG_COMPOUND);
            node.thermalState = CloudworksThermalState.read(tag.getCompoundTag("thermalState"));
        }
        node.frozenMachine = tag.getCompoundTag("machine").copy();
        readResources(CloudworksDataChecks.list(tag, "inputs", NBT.TAG_COMPOUND), node.inputs);
        readResources(CloudworksDataChecks.list(tag, "outputs", NBT.TAG_COMPOUND), node.outputs);
        if (tag.hasKey("templates")) {
            readResources(CloudworksDataChecks.list(tag, "templates", NBT.TAG_COMPOUND), node.templates);
            for (CloudworksResourceStack template : node.templates)
                if (template.reserved() != 0) throw new IllegalArgumentException("Reserved configuration template");
        }
        if (tag.hasKey("operation")) {
            CloudworksDataChecks.field(tag, "operation", NBT.TAG_COMPOUND);
            node.operation = CloudworksOperation.read(tag.getCompoundTag("operation"));
            if (node.progress < 0 || node.progress > node.operation.duration())
                throw new IllegalArgumentException("Invalid operation progress");
            if (node.totalTicks != node.operation.duration())
                throw new IllegalArgumentException("Operation duration mismatch");
        }
        return node;
    }

    private static NBTTagList writeResources(List<CloudworksResourceStack> resources) {
        NBTTagList list = new NBTTagList();
        for (CloudworksResourceStack resource : resources) list.appendTag(resource.write());
        return list;
    }

    private static void readResources(NBTTagList list, List<CloudworksResourceStack> target) {
        target.clear();
        for (int i = 0; i < list.tagCount(); i++) target.add(CloudworksResourceStack.read(list.getCompoundTagAt(i)));
    }

    public UUID nodeId() {
        return nodeId;
    }

    public int x() {
        return x;
    }

    public int z() {
        return z;
    }

    public void setPosition(int x, int z) {
        this.x = x;
        this.z = z;
    }

    public int dimensionId() {
        return dimensionId;
    }

    public void setDimensionId(int dimensionId) {
        this.dimensionId = dimensionId;
    }

    public ItemStack machine() {
        return machine.copy();
    }

    public MachineCapabilities capabilities() {
        if (mekanism.cloudworks.common.service.CloudworksNativeContracts.isWorker() && capabilities != null)
            return capabilities;
        long version = CloudworksRegistries.version();
        if (capabilities == null || capabilities.version != version) {
            MachineKey key = CloudworksRegistries.identityKey(machine);
            capabilities = new MachineCapabilities(version, key, CloudworksRegistries.profile(key),
                    CloudworksMachineAdapters.getAll(key), CloudworksRegistries.special(key),
                    CloudworksRegistries.energyGenerator(key), CloudworksRegistries.slots(key), CloudworksRegistries.thermal(key));
        }
        return capabilities;
    }

    /**
     * Claimed machine count; physical machines live in the owner's machine cache.
     */
    public long machineCount() {
        return machineCount;
    }

    public void setMachineCount(long count) {
        if (count < 0) throw new IllegalArgumentException("machine count cannot be negative");
        if (thermalState != null && (machineCount > 0 || count > 0) && count != machineCount) {
            double scale = (double) count / machineCount;
            double nextCapacity = thermalState.capacity * scale;
            double nextHeat = count < machineCount ? thermalState.heat * scale
                    : thermalState.heat + 300 * (nextCapacity - thermalState.capacity);
            mekanism.cloudworks.api.CloudworksThermalPlan plan = capabilities().thermal();
            double plasma = count < machineCount ? thermalState.plasma * scale
                    : thermalState.plasma + (plan == null ? 0 : 300 * plan.plasmaCapacity * (count - machineCount));
            thermalState = new CloudworksThermalState(nextHeat, nextCapacity, plasma, thermalState.fuelTicks,
                    thermalState.credit, thermalState.produced, thermalState.consumed, thermalState.loss, thermalState.burning);
        }
        machineCount = count;
    }

    private MachineNodeData(MachineNodeData template, UUID id, int x, int z) {
        nodeId = id;
        machine = template.machine;
        machineCount = 1;
        capabilities = template.capabilities;
        this.x = x;
        this.z = z;
    }

    /** Editor nodes claim a type; the cache retains its exact NBT variants. */
    public boolean pooledClaim() {
        return pooledClaim;
    }

    public UUID waitingBlueprint() { return waitingBlueprint; }
    public void setWaitingBlueprint(UUID value) { waitingBlueprint = value; }
    public long waitingCount() { return waitingCount; }
    public void setWaitingCount(long count) { waitingCount = count; }

    public void setPooledClaim(boolean value) {
        pooledClaim = value;
    }

    /**
     * Durable upload transaction that created this node; null on command/legacy nodes.
     */
    public UUID transferId() {
        return transferId;
    }

    /**
     * Source component identity of the creating upload; null when not uploaded.
     */
    public UUID sourceId() {
        return sourceId;
    }

    /**
     * Stamped by the upload coordinator; ties the node to its durable transfer segment.
     */
    public void setUploadOrigin(UUID transferId, UUID sourceId) {
        this.transferId = transferId;
        this.sourceId = sourceId;
    }

    public List<CloudworksResourceStack> inputs() {
        return inputs;
    }

    public List<CloudworksResourceStack> outputs() {
        return outputs;
    }

    public List<CloudworksResourceStack> templates() { return templates; }

    public void addInput(QIOResourceDescriptor descriptor, long amount) {
        if (amount <= 0) return;
        for (CloudworksResourceStack stack : inputs) {
            if (stack.descriptor().equals(descriptor)) {
                stack.add(amount);
                return;
            }
        }
        inputs.add(new CloudworksResourceStack(descriptor, amount));
    }

    public void removeOutput(CloudworksResourceStack output) {
        outputs.remove(output);
    }

    public CloudworksResourceStack firstInput(QIOResourceDescriptor descriptor) {
        for (CloudworksResourceStack stack : inputs) if (stack.descriptor().equals(descriptor)) return stack;
        return null;
    }

    public void consumeInput(QIOResourceDescriptor descriptor, long amount) {
        if (amount < 0 || inputAmount(descriptor) < amount)
            throw new IllegalArgumentException("Insufficient node input");
        long remaining = amount;
        for (CloudworksResourceStack stack : inputs) {
            if (!stack.descriptor().equals(descriptor)) continue;
            long used = Math.min(remaining, stack.available());
            stack.consume(used);
            remaining -= used;
        }
        inputs.removeIf(stack -> stack.amount() == 0);
    }

    public long inputAmount(QIOResourceDescriptor descriptor) {
        long amount = 0;
        for (CloudworksResourceStack stack : inputs)
            if (stack.descriptor().equals(descriptor)) amount = Math.addExact(amount, stack.available());
        return amount;
    }

    public CloudworksOperation operation() {
        return operation;
    }

    /** Cold Runtime projection; quantities already include the operation's reservations. */
    public void applyRuntime(List<CloudworksResourceStack> input, List<CloudworksResourceStack> output,
            CloudworksOperation promise, int completedTicks, int ticks, String nextState) {
        applyRuntimeDelta(input, output, promise, completedTicks, ticks, nextState);
    }

    /** Null resource vectors mean unchanged; a null promise explicitly clears the operation. */
    public void applyRuntimeDelta(List<CloudworksResourceStack> input, List<CloudworksResourceStack> output,
            CloudworksOperation promise, int completedTicks, int ticks, String nextState) {
        applyRuntimeDelta(input, output, null, promise, completedTicks, ticks, nextState);
    }

    public void applyRuntimeDelta(List<CloudworksResourceStack> input, List<CloudworksResourceStack> output,
            List<CloudworksResourceStack> configuration, CloudworksOperation promise, int completedTicks,
            int ticks, String nextState) {
        if (completedTicks < 0 || ticks < 1 || completedTicks > ticks
                || promise != null && promise.duration() != ticks)
            throw new IllegalArgumentException("Invalid Runtime machine projection");
        if (input != null || promise != operation) {
            java.util.Map<QIOResourceDescriptor, Long> reserved = new java.util.HashMap<>();
            for (CloudworksResourceStack stack : input == null ? inputs : input)
                if (stack.reserved() > 0) reserved.merge(stack.descriptor(), stack.reserved(), Math::addExact);
            java.util.Map<QIOResourceDescriptor, Long> required = new java.util.HashMap<>();
            if (promise != null)
                for (CloudworksResourceStack stack : promise.inputs())
                    required.merge(stack.descriptor(), stack.amount(), Math::addExact);
            if (!reserved.equals(required)) throw new IllegalArgumentException("Runtime reservation mismatch");
        }
        if (input != null) {
            inputs.clear();
            for (CloudworksResourceStack stack : input) inputs.add(copyRuntimeStack(stack));
        }
        if (output != null) {
            outputs.clear();
            for (CloudworksResourceStack stack : output) outputs.add(copyRuntimeStack(stack));
        }
        if (configuration != null) {
            templates.clear();
            for (CloudworksResourceStack stack : configuration) {
                if (stack.reserved() != 0) throw new IllegalArgumentException("Reserved configuration template");
                templates.add(copyRuntimeStack(stack));
            }
        }
        operation = promise;
        progress = completedTicks;
        totalTicks = ticks;
        state = java.util.Objects.requireNonNull(nextState);
    }

    /** A new shallow tag header preserves all unchanged frozen inventory and item tags. */
    public NBTTagCompound writeRuntimeDelta(NBTTagCompound previous, boolean inputChanged,
            boolean outputChanged, boolean operationChanged) {
        return writeRuntimeDelta(previous, inputChanged, outputChanged, operationChanged, false);
    }

    public NBTTagCompound writeRuntimeDelta(NBTTagCompound previous, boolean inputChanged,
            boolean outputChanged, boolean operationChanged, boolean templateChanged) {
        if (previous == null) return write();
        NBTTagCompound next = new NBTTagCompound();
        for (String key : previous.getKeySet()) next.setTag(key, previous.getTag(key));
        next.setBoolean("active", active);
        next.setInteger("progress", progress);
        next.setInteger("totalTicks", totalTicks);
        next.setString("state", state);
        if (thermalState != null) next.setTag("thermalState", thermalState.write());
        else next.removeTag("thermalState");
        if (inputChanged) next.setTag("inputs", writeResources(inputs));
        if (outputChanged) next.setTag("outputs", writeResources(outputs));
        if (templateChanged) {
            if (templates.isEmpty()) next.removeTag("templates");
            else next.setTag("templates", writeResources(templates));
        }
        if (operationChanged) {
            next.removeTag("operation");
            if (operation != null) next.setTag("operation", operation.write());
        }
        return next;
    }

    private static CloudworksResourceStack copyRuntimeStack(CloudworksResourceStack stack) {
        CloudworksResourceStack copy = new CloudworksResourceStack(stack.descriptor(), stack.amount());
        if (stack.reserved() > 0) copy.reserve(stack.reserved());
        return copy;
    }

    public Object cachedRecipe(Object source, int version) {
        RecipeHit hit = recipeHits == null ? null : recipeHits.get(source);
        return hit != null && hit.version == version ? hit.recipe : null;
    }

    public Object cachedRecipe(Object source, int version, java.util.Map<QIOResourceDescriptor, Long> stock) {
        RecipeHit hit = recipeHits == null ? null : recipeHits.get(source);
        return hit != null && hit.version == version && hit.stock != null && hit.stock.equals(stock) ? hit.recipe : null;
    }

    public void cacheRecipe(Object source, int version, Object recipe) {
        cacheRecipe(source, version, recipe, null);
    }

    public void cacheRecipe(Object source, int version, Object recipe,
                            java.util.Map<QIOResourceDescriptor, Long> stock) {
        if (recipeHits == null) recipeHits = new java.util.IdentityHashMap<>();
        recipeHits.put(source, new RecipeHit(version, recipe, stock == null ? null : new java.util.HashMap<>(stock)));
    }

    public void clearRecipeCache(Object source) {
        if (recipeHits != null) recipeHits.remove(source);
    }

    /**
     * Starts an operation Mek-style: the recipe inputs are only validated and
     * reserved in place, never debited from the input cache; consumption happens
     * atomically at completion. Reserved inputs stay visible in the node cache
     * but are unavailable to other consumers.
     */
    public boolean beginOperation(CloudworksOperation value) {
        if (operation != null) return false;
        java.util.Map<QIOResourceDescriptor, Long> required = new java.util.LinkedHashMap<>();
        for (CloudworksResourceStack stack : value.inputs())
            required.merge(stack.descriptor(), stack.amount(), Math::addExact);
        for (java.util.Map.Entry<QIOResourceDescriptor, Long> entry : required.entrySet())
            if (inputAmount(entry.getKey()) < entry.getValue()) return false;
        required.forEach(this::reserveInput);
        operation = value;
        progress = 0;
        totalTicks = value.duration();
        return true;
    }

    /**
     * Completes atomically: releases and consumes the reserved inputs, then adds the promised outputs.
     */
    public boolean completeOperation(UUID id) {
        if (operation == null || !operation.id().equals(id)) return false;
        // Re-validate the reservation still covers the requirement (defensive fuse).
        java.util.Map<QIOResourceDescriptor, Long> required = new java.util.LinkedHashMap<>();
        for (CloudworksResourceStack stack : operation.inputs())
            required.merge(stack.descriptor(), stack.amount(), Math::addExact);
        for (java.util.Map.Entry<QIOResourceDescriptor, Long> entry : required.entrySet())
            if (reservedAmount(entry.getKey()) < entry.getValue()) {
                abandonOperation();
                return false;
            }
        required.forEach(this::consumeReserved);
        // Construct all output first; overflow must not consume inputs or commit half the products.
        List<CloudworksResourceStack> merged = merge(outputs, operation.outputs());
        outputs.clear();
        outputs.addAll(merged);
        operation = null;
        progress = 0;
        return true;
    }

    /**
     * Cancels by releasing the reservation; inputs never left the node cache.
     */
    public void cancelOperation() {
        if (operation == null) return;
        operation.inputs().forEach(stack -> releaseReserved(stack.descriptor(), stack.amount()));
        operation = null;
        progress = 0;
    }

    /**
     * Drops a broken operation whose reservation went inconsistent (defensive fuse path).
     */
    private void abandonOperation() {
        operation.inputs().forEach(stack -> {
            long releasable = Math.min(stack.amount(), reservedAmount(stack.descriptor()));
            if (releasable > 0) releaseReserved(stack.descriptor(), releasable);
        });
        operation = null;
        progress = 0;
        state = "WAITING_INPUT";
    }

    private void reserveInput(QIOResourceDescriptor descriptor, long amount) {
        long remaining = amount;
        for (CloudworksResourceStack stack : inputs) {
            if (remaining == 0 || !stack.descriptor().equals(descriptor)) continue;
            long taken = Math.min(remaining, stack.available());
            if (taken > 0) {
                stack.reserve(taken);
                remaining -= taken;
            }
        }
        if (remaining > 0) throw new IllegalStateException("Reservation exceeds available input");
    }

    private void consumeReserved(QIOResourceDescriptor descriptor, long amount) {
        long remaining = amount;
        for (CloudworksResourceStack stack : inputs) {
            if (remaining == 0 || !stack.descriptor().equals(descriptor)) continue;
            long taken = Math.min(remaining, stack.reserved());
            if (taken > 0) {
                stack.release(taken);
                stack.consume(taken);
                remaining -= taken;
            }
        }
        inputs.removeIf(stack -> stack.amount() == 0);
        if (remaining > 0) throw new IllegalStateException("Consumption exceeds reservation");
    }

    private void releaseReserved(QIOResourceDescriptor descriptor, long amount) {
        long remaining = amount;
        for (CloudworksResourceStack stack : inputs) {
            if (remaining == 0 || !stack.descriptor().equals(descriptor)) continue;
            long taken = Math.min(remaining, stack.reserved());
            if (taken > 0) {
                stack.release(taken);
                remaining -= taken;
            }
        }
    }

    /**
     * Total reserved quantity of one descriptor; the reservation mirror of {@link #inputAmount}.
     */
    public long reservedAmount(QIOResourceDescriptor descriptor) {
        long amount = 0;
        for (CloudworksResourceStack stack : inputs)
            if (stack.descriptor().equals(descriptor)) amount = Math.addExact(amount, stack.reserved());
        return amount;
    }

    public long inputAmountForGas(GasStack gas) {
        long amount = 0;
        for (CloudworksResourceStack stack : inputs) {
            GasStack stored = stack.descriptor().resolve(mekanism.api.qio.resource.QIOResourceCodecs.GAS_STACK);
            if (stored != null && stored.isGasEqual(gas)) amount = Math.addExact(amount, stack.available());
        }
        return amount;
    }

    public long inputAmountForItem(ItemStack wanted) {
        long amount = 0;
        for (CloudworksResourceStack stack : inputs) {
            ItemStack stored = stack.descriptor().resolve(mekanism.api.qio.resource.QIOResourceCodecs.ITEM_STACK);
            if (stored != null && mekanism.common.recipe.inputs.ItemStackInput.inputContains(stored, wanted))
                amount = Math.addExact(amount, stack.available());
        }
        return amount;
    }

    public void consumeItem(ItemStack wanted, long amount) {
        if (amount < 0 || inputAmountForItem(wanted) < amount)
            throw new IllegalArgumentException("Insufficient item input");
        long remaining = amount;
        for (CloudworksResourceStack stack : inputs) {
            ItemStack stored = stack.descriptor().resolve(mekanism.api.qio.resource.QIOResourceCodecs.ITEM_STACK);
            if (stored == null || !mekanism.common.recipe.inputs.ItemStackInput.inputContains(stored, wanted)) continue;
            long used = Math.min(remaining, stack.available());
            stack.consume(used);
            remaining -= used;
            if (remaining == 0) break;
        }
        inputs.removeIf(stack -> stack.amount() == 0);
    }

    public void consumeGas(GasStack gas, long amount) {
        if (amount < 0 || inputAmountForGas(gas) < amount) throw new IllegalArgumentException("Insufficient gas input");
        long remaining = amount;
        for (CloudworksResourceStack stack : inputs) {
            GasStack stored = stack.descriptor().resolve(mekanism.api.qio.resource.QIOResourceCodecs.GAS_STACK);
            if (stored == null || !stored.isGasEqual(gas)) continue;
            long used = Math.min(remaining, stack.available());
            stack.consume(used);
            remaining -= used;
            if (remaining == 0) break;
        }
        inputs.removeIf(stack -> stack.amount() == 0);
    }

    public void addOutput(QIOResourceDescriptor descriptor, long amount) {
        if (amount <= 0) return;
        for (CloudworksResourceStack stack : outputs) {
            if (stack.descriptor().equals(descriptor)) {
                stack.add(amount);
                return;
            }
        }
        outputs.add(new CloudworksResourceStack(descriptor, amount));
    }

    public boolean active() {
        return active && waitingBlueprint == null;
    }
    public boolean configuredActive() { return active; }

    public void setActive(boolean value) {
        active = value;
    }

    public int progress() {
        return progress;
    }

    public int totalTicks() {
        return totalTicks;
    }

    public void setProgress(int progress, int totalTicks) {
        this.progress = Math.max(0, progress);
        this.totalTicks = Math.max(1, totalTicks);
    }

    public String state() {
        return state;
    }

    public CloudworksThermalState thermalState() { return thermalState; }
    public void setThermalState(CloudworksThermalState state) { thermalState = state; }

    public void setState(String state) {
        this.state = state == null ? "OFF" : state;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", SCHEMA_VERSION);
        tag.setString("nodeId", nodeId.toString());
        tag.setTag("machine", frozenMachine == null ? machine.writeToNBT(new NBTTagCompound()) : frozenMachine.copy());
        tag.setLong("machineCount", machineCount);
        tag.setBoolean("pooledClaim", pooledClaim);
        if (waitingBlueprint != null) {
            tag.setString("waitingBlueprint", waitingBlueprint.toString());
            tag.setLong("waitingCount", waitingCount);
        }
        // Optional keys keep legacy saves readable; absent simply means "not uploaded".
        if (transferId != null) tag.setString("transferId", transferId.toString());
        if (sourceId != null) tag.setString("sourceId", sourceId.toString());
        tag.setInteger("x", x);
        tag.setInteger("z", z);
        tag.setInteger("dimensionId", dimensionId);
        tag.setBoolean("active", active);
        tag.setInteger("progress", progress);
        tag.setInteger("totalTicks", totalTicks);
        tag.setString("state", state);
        if (thermalState != null) tag.setTag("thermalState", thermalState.write());
        tag.setTag("inputs", writeResources(inputs));
        tag.setTag("outputs", writeResources(outputs));
        if (!templates.isEmpty()) tag.setTag("templates", writeResources(templates));
        if (operation != null) tag.setTag("operation", operation.write());
        return tag;
    }

    public static final class MachineCapabilities {
        private final long version;
        private final MachineKey key;
        /**
         * Cached serialization of the key's display stack; stable per capabilities.
         */
        private final String keyString;
        private final MachineExecutionProfile profile;
        private final List<CloudworksMachineAdapter> adapters;
        private final SpecialMachineAdapter special;
        private final CloudworksEnergyGenerator generator;
        private final MachineSlotLayout slots;
        private final mekanism.cloudworks.api.CloudworksThermalPlan thermal;

        private MachineCapabilities(long version, MachineKey key, MachineExecutionProfile profile,
                                    List<CloudworksMachineAdapter> adapters, SpecialMachineAdapter special,
                                    CloudworksEnergyGenerator generator, MachineSlotLayout slots,
                                    mekanism.cloudworks.api.CloudworksThermalPlan thermal) {
            this.version = version;
            this.key = key;
            this.profile = profile;
            this.adapters = adapters;
            this.special = special;
            this.generator = generator;
            this.slots = slots;
            this.thermal = thermal;
            // Serialized once per capabilities instance: prepare, projection and
            // planning all need the same string and NBT serialization is hot.
            this.keyString = key.displayStack().serializeNBT().toString();
        }

        public MachineKey key() {
            return key;
        }

        /**
         * Stable string form of {@link #key()}; shared by all catalog call sites.
         */
        public String keyString() {
            return keyString;
        }

        public MachineExecutionProfile profile() {
            return profile;
        }

        public List<CloudworksMachineAdapter> adapters() {
            return adapters;
        }

        public SpecialMachineAdapter special() {
            return special;
        }

        public CloudworksEnergyGenerator generator() {
            return generator;
        }

        public MachineSlotLayout slots() {
            return slots;
        }

        public boolean executable() {
            return !adapters.isEmpty() || special != null || generator != null || thermal != null;
        }
        public mekanism.cloudworks.api.CloudworksThermalPlan thermal() { return thermal; }
    }

    private static final class RecipeHit {
        private final int version;
        private final Object recipe;
        private final java.util.Map<QIOResourceDescriptor, Long> stock;

        private RecipeHit(int version, Object recipe, java.util.Map<QIOResourceDescriptor, Long> stock) {
            this.version = version;
            this.recipe = recipe;
            this.stock = stock;
        }
    }
}
