package mekanism.cloudworks.common.data;

import mekanism.api.gas.GasStack;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.*;
import mekanism.cloudworks.api.card.*;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.node.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.transfer.*;
import mekanism.cloudworks.api.work.*;
import mekanism.cloudworks.common.recipe.MachineRecipeTypeEntry;
import mekanism.cloudworks.common.registry.CloudworksMachineAdapters;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineKey;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants.NBT;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeLimits;
import mekanism.common.recipe.inputs.ItemStackInput;
import net.minecraft.util.ResourceLocation;

public final class MachineNodeData {
    public static final int SCHEMA_VERSION = 7;
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
    private String waitingMechanism = "";
    /**
     * Upload transaction that created this node; null on command/legacy nodes.
     */
    private UUID transferId;
    /**
     * Identity of the source component that supplied the upload; null when not uploaded.
     */
    private UUID sourceId;
    private CloudworksOperation operation;
    private transient IdentityHashMap<Object, RecipeHit> recipeHits;
    private transient MachineCapabilities capabilities;
    private NBTTagCompound frozenMachine;
    private int x;
    private int z;
    private boolean active;
    private int progress;
    private int totalTicks = 1;
    private String state = "OFF";
    private String recipeType = "";
    private List<CloudworksOreRule> oreRules = Collections.emptyList();
    private int fusionInjectionRate = 2;
    private Map<String, CloudworksResourceCredit> processCredits = Collections.emptyMap();
    private CloudworksUpgradeSet customUpgrades = new CloudworksUpgradeSet();
    private long outputSequence;
    private CloudworksThermalState thermalState;
    private Map<String, CloudworksExecutionState> executionBranches = Collections.emptyMap();
    private Map<String, CloudworksExecutionState> processingBatches = Collections.emptyMap();
    private Map<QIOResourceDescriptor, Double> generationRemainders = Collections.emptyMap();
    private Map<String, Double> moduleCostRemainders = Collections.emptyMap();
    public Map<String, Double> moduleCostRemainders() { return moduleCostRemainders; }
    public void setModuleCostRemainders(Map<String, Double> values) {
        for (Double value : values.values())
            if (value == null || !Double.isFinite(value) || value < 0 || value >= 1)
                throw new IllegalArgumentException("Invalid module cost remainder");
        moduleCostRemainders = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

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
        waitingMechanism = source.waitingMechanism;
        transferId = source.transferId;
        sourceId = source.sourceId;
        capabilities = source.capabilities;
        frozenMachine = source.frozenMachine;
        x = source.x;
        z = source.z;
        active = source.active;
        progress = source.progress;
        totalTicks = source.totalTicks;
        state = source.state;
        recipeType = source.recipeType;
        oreRules = source.oreRules;
        fusionInjectionRate = source.fusionInjectionRate;
        processCredits = source.processCredits;
        customUpgrades = source.customUpgrades.copy();
        outputSequence = source.outputSequence;
        thermalState = source.thermalState;
        executionBranches = source.executionBranches;
        processingBatches = source.processingBatches;
        generationRemainders = source.generationRemainders;
        moduleCostRemainders = source.moduleCostRemainders;
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

        CloudworksDataChecks.field(tag, "schema", NBT.TAG_INT);
        if (tag.getInteger("schema") != 4 && tag.getInteger("schema") != 5 && tag.getInteger("schema") != 6
                && tag.getInteger("schema") != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported cloudworks node schema");
        MachineNodeData node;
        if (CloudworksNativeContracts.isWorker()) {
            node = new MachineNodeData(CloudworksNativeContracts.machine(
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
        if (tag.hasKey("waitingMechanism")) {
            CloudworksDataChecks.field(tag, "waitingMechanism", NBT.TAG_STRING);
            node.setWaitingMechanism(tag.getString("waitingMechanism"));
            if (node.waitingBlueprint == null) throw new IllegalArgumentException("Mechanism preview without waiting blueprint");
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
        node.active = tag.getBoolean("active");
        node.progress = tag.getInteger("progress");
        node.totalTicks = tag.getInteger("totalTicks");
        if (node.totalTicks < 1 || node.progress < 0 || node.progress > node.totalTicks)
            throw new IllegalArgumentException("Invalid node progress");
        node.state = tag.getString("state");
        // Native per-node assignments retired (axis-priority batch 3c); any
        // persisted "assignedSpeed"/"assignedEnergy" keys are ignored.
        node.customUpgrades = CloudworksUpgradeSet.read(tag, "customUpgrades");
        if (tag.hasKey("oreRules")) node.setOreRules(CloudworksOreRule.readList(CloudworksDataChecks.list(tag, "oreRules", 10)));
        if (tag.hasKey("fusionInjectionRate")) {
            CloudworksDataChecks.field(tag, "fusionInjectionRate", 3);
            node.setFusionInjectionRate(tag.getInteger("fusionInjectionRate"));
        }
        // Legacy dev worlds may still carry a "teAugments" list from the retired
        // Thermal Expansion equipment batch; it is ignored and dropped on the next save.
        if (tag.hasKey("outputSequence")) {
            CloudworksDataChecks.field(tag, "outputSequence", NBT.TAG_LONG);
            node.setOutputSequence(tag.getLong("outputSequence"));
        }
        if (tag.hasKey("recipeType")) {
            CloudworksDataChecks.field(tag, "recipeType", NBT.TAG_STRING);
            node.setRecipeType(tag.getString("recipeType"));
        }
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
        if (tag.hasKey("executionBranches")) {
            CloudworksDataChecks.field(tag, "executionBranches", 10);
            NBTTagCompound branches = tag.getCompoundTag("executionBranches");
            if (branches.getKeySet().size() > CloudworksNodeMechanisms.BRANCH_LIMIT)
                throw new IllegalArgumentException("Too many execution branches");
            Map<String, CloudworksExecutionState> states = new LinkedHashMap<>();
            for (String id : branches.getKeySet()) {
                CloudworksDataChecks.field(branches, id, 10);
                states.put(id, CloudworksExecutionState.read(branches.getCompoundTag(id)));
            }
            node.setExecutionBranches(states);
        }
        if (tag.hasKey("processingBatches")) {
            CloudworksDataChecks.field(tag, "processingBatches", 10);
            NBTTagCompound stored = tag.getCompoundTag("processingBatches");
            Map<String, CloudworksExecutionState> states = new LinkedHashMap<>();
            for (String id : stored.getKeySet()) {
                CloudworksDataChecks.field(stored, id, 10);
                states.put(id, CloudworksExecutionState.read(stored.getCompoundTag(id)));
            }
            node.setProcessingBatches(states);
        }
        node.generationRemainders = CloudworksExecutionState.readRemainders(tag);
        if (tag.hasKey("processCredits")) {
            CloudworksDataChecks.field(tag, "processCredits", 10);
            Map<String, CloudworksResourceCredit> credits = new LinkedHashMap<>();
            NBTTagCompound stored = tag.getCompoundTag("processCredits");
            for (String id : stored.getKeySet()) credits.put(id, CloudworksResourceCredit.read(stored.getCompoundTag(id)));
            node.setProcessCredits(credits);
        }
        Map<String, Double> moduleCosts = new LinkedHashMap<>();
        NBTTagCompound storedCosts = tag.getCompoundTag("moduleCostRemainders");
        for (String key : storedCosts.getKeySet()) moduleCosts.put(key, storedCosts.getDouble(key));
        node.setModuleCostRemainders(moduleCosts);
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

    public ItemStack machine() {
        return machine.copy();
    }

    /**
     * B5 derived frozen state: the machine item vanished (removed mod) while the
     * raw identity NBT survives. Native namespaces cannot vanish while this mod
     * runs, so only external namespaces qualify. Data is never mutated; the node
     * recovers automatically once the mod returns and the file loads again.
     */
    public boolean frozenByMissingMod() {
        if (!machine.isEmpty() || frozenMachine == null || !frozenMachine.hasKey("id", 8)) return false;
        String id = frozenMachine.getString("id");
        int separator = id.indexOf(':');
        if (separator <= 0) return false;
        String namespace = id.substring(0, separator);
        return !"minecraft".equals(namespace) && !"cloudworks".equals(namespace);
    }

    /** Raw persisted machine NBT of a frozen node; null otherwise (B5). */
    public NBTTagCompound frozenMachineTag() {
        return frozenByMissingMod() ? frozenMachine.copy() : null;
    }

    public MachineCapabilities capabilities() {
        if (frozenByMissingMod()) return MachineCapabilities.missingMod();
        boolean worker = CloudworksNativeContracts.isWorker();
        if (worker && capabilities != null && capabilities.matches(customUpgrades, machineCount, waitingMechanism))
            return capabilities;
        long version = worker && capabilities != null ? capabilities.version : CloudworksRegistries.version();
        if (capabilities == null || capabilities.version != version || !capabilities.matches(customUpgrades, machineCount, waitingMechanism)) {
            if (worker && capabilities != null)
                capabilities = resolveCapabilities(version, capabilities.key, capabilities.identityString);
            else {
                MachineKey key = CloudworksRegistries.identityKey(machine);
                capabilities = resolveCapabilities(version, key, key.displayStack().serializeNBT().toString());
            }
        }
        return capabilities;
    }

    private MachineCapabilities resolveCapabilities(long version, MachineKey key, String identity) {
        MachineRecipeTypeEntry selected = recipeType.isEmpty() ? null : CloudworksRegistries.recipeType(key, recipeType);
        List<CloudworksMachineAdapter> adapters = recipeType.isEmpty() ? CloudworksMachineAdapters.getAutomatic(key)
                : selected != null && selected.adapter() != null ? Collections.singletonList(selected.adapter())
                : Collections.emptyList();
        Map<ResourceLocation, Long> installed = new LinkedHashMap<>();
        boolean structural = false;
        for (ResourceLocation id : customUpgrades.ids()) {
            installed.put(id, customUpgrades.count(id));
            MachineModuleType<?> moduleType = CloudworksMachineModuleRegistry.type(id);
            structural |= moduleType != null && moduleType.hasRecipeHook();
        }
        CloudworksNodeMechanisms.Variant mechanism =
                CloudworksNodeMechanisms.resolve(key, recipeType, machineCount, installed);
        boolean preview = waitingBlueprint != null && !waitingMechanism.isEmpty();
        if (preview) mechanism = CloudworksNodeMechanisms.preview(key, recipeType, waitingMechanism);
        if (mekanism.cloudworks.common.recipe.CloudworksOredictionificator.isMachine(machine)) {
            adapters = Collections.singletonList(mekanism.cloudworks.common.recipe.CloudworksOredictionificator.adapter(
                    identity, CloudworksRegistries.profile(key), CloudworksRegistries.slots(key, recipeType), machine, oreRules));
        }
        mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.Variant fusion =
                mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.isController(machine)
                        ? mekanism.cloudworks.common.recipe.CloudworksFusionNodeContracts.resolve(identity, machine, recipeType, fusionInjectionRate) : null;
        if (fusion != null) adapters = Collections.singletonList(fusion.adapter);
        return new MachineCapabilities(version, key, mechanism == null ? CloudworksRegistries.profile(key) : mechanism.profile,
                preview ? Collections.emptyList() : mechanism == null ? adapters : mechanism.adapters,
                mechanism == null ? recipeType.isEmpty() ? CloudworksRegistries.energyGenerator(key) : null : mechanism.generator,
                fusion != null ? fusion.slots : mechanism == null ? CloudworksRegistries.slots(key, recipeType) : mechanism.slots,
                fusion != null ? fusion.thermal : mechanism == null ? recipeType.isEmpty() ? CloudworksRegistries.thermal(key)
                        : selected == null ? null : selected.adapter().thermalPlan() : mechanism.thermal, recipeType, identity,
                customUpgrades, structural || preview ? machineCount : -1,
                mechanism == null ? "" : mechanism.id, waitingMechanism,
                mechanism == null ? Collections.emptyList() : mechanism.branches,
                (oreRules.isEmpty() ? "" : "|oreRules=" + CloudworksOreRule.writeList(oreRules))
                        + (fusion == null ? "" : "|fusionInjection=" + fusionInjectionRate));
    }

    public String recipeType() {
        return recipeType;
    }

    public List<CloudworksOreRule> oreRules() { return oreRules; }
    public int fusionInjectionRate() { return fusionInjectionRate; }
    public void setFusionInjectionRate(int rate) {
        if (rate < 0 || (rate & 1) != 0) throw new IllegalArgumentException("cloudworks.error.fusion_injection_invalid");
        if (fusionInjectionRate != rate) { fusionInjectionRate = rate; capabilities = null; recipeHits = null; }
    }
    public Map<String, CloudworksResourceCredit> processCredits() { return processCredits; }
    public void setProcessCredits(Map<String, CloudworksResourceCredit> values) {
        values.forEach((key, value) -> { if (key == null || key.length() > 512 || value == null) throw new IllegalArgumentException("Invalid processing credit key"); });
        processCredits = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    public void setOreRules(List<CloudworksOreRule> rules) {
        List<CloudworksOreRule> copy = CloudworksOreRule.readList(CloudworksOreRule.writeList(rules));
        if (!oreRules.equals(copy)) { oreRules = copy; capabilities = null; }
    }

    /** Custom typed upgrade counts installed on this node; unknown ids keep their counts. */
    public CloudworksUpgradeSet customUpgrades() { return customUpgrades; }

    public void setRecipeType(String typeId) {
        if (typeId == null || !typeId.isEmpty() && !MachineRecipeTypeEntry.validId(typeId))
            throw new IllegalArgumentException("Invalid node recipe type");
        if (!recipeType.equals(typeId)) {
            recipeType = typeId;
            if (capabilities != null)
                capabilities = resolveCapabilities(capabilities.version, capabilities.key, capabilities.identityString);
            recipeHits = null;
        }
    }

    /**
     * Claimed machine count; physical machines live in the owner's machine cache.
     */
    public long machineCount() {
        return machineCount;
    }

    public void setMachineCount(long count) {
        if (count < 0) throw new IllegalArgumentException("machine count cannot be negative");
        if (!executionBranches.isEmpty() && count != machineCount && machineCount > 0) {
            Map<String, CloudworksExecutionState> scaled = new LinkedHashMap<>(executionBranches);
            for (CloudworksNodeMechanisms.Branch branch : capabilities().branches()) {
                CloudworksExecutionState value = executionBranches.get(branch.id);
                if (value == null || value.thermal == null || branch.thermal == null) continue;
                CloudworksThermalState old = value.thermal;
                double scale = (double) count / machineCount, capacity = old.capacity * scale;
                double heat = count < machineCount ? old.heat * scale : old.heat + 300 * (capacity - old.capacity);
                double plasma = count < machineCount ? old.plasma * scale
                        : old.plasma + 300 * branch.thermal.plasmaCapacity * (count - machineCount);
                scaled.put(branch.id, new CloudworksExecutionState(value.progress, value.duration, value.state,
                        value.outputSequence, value.operation, new CloudworksThermalState(heat, capacity, plasma,
                        old.fuelTicks, old.credit, old.produced, old.consumed, old.loss, old.burning), value.generationRemainders));
            }
            executionBranches = Collections.unmodifiableMap(scaled);
        }
        if (thermalState != null && (machineCount > 0 || count > 0) && count != machineCount) {
            double scale = (double) count / machineCount;
            double nextCapacity = thermalState.capacity * scale;
            double nextHeat = count < machineCount ? thermalState.heat * scale
                    : thermalState.heat + 300 * (nextCapacity - thermalState.capacity);
            CloudworksThermalPlan plan = capabilities().thermal();
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
    public void setWaitingBlueprint(UUID value) { waitingBlueprint = value; if (value == null) waitingMechanism = ""; }
    public long waitingCount() { return waitingCount; }
    public void setWaitingCount(long count) { waitingCount = count; }
    public String waitingMechanism() { return waitingMechanism; }
    public void setWaitingMechanism(String value) {
        CloudworksNodeMechanisms.split(value);
        waitingMechanism = value;
    }

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
        applyRuntimeDelta(input, output, configuration, promise, executionBranches, completedTicks, ticks, nextState);
    }

    public void applyRuntimeDelta(List<CloudworksResourceStack> input, List<CloudworksResourceStack> output,
            List<CloudworksResourceStack> configuration, CloudworksOperation promise,
            Map<String, CloudworksExecutionState> branches, int completedTicks, int ticks,
            String nextState) {
        applyRuntimeDelta(input, output, configuration, promise, branches, processingBatches,
                completedTicks, ticks, nextState);
    }

    public void applyRuntimeDelta(List<CloudworksResourceStack> input, List<CloudworksResourceStack> output,
            List<CloudworksResourceStack> configuration, CloudworksOperation promise,
            Map<String, CloudworksExecutionState> branches, Map<String, CloudworksExecutionState> batches,
            int completedTicks, int ticks, String nextState) {
        if (completedTicks < 0 || ticks < 1 || completedTicks > ticks
                || promise != null && promise.duration() != ticks)
            throw new IllegalArgumentException("Invalid Runtime machine projection");
        if (input != null || promise != operation || !executionBranches.isEmpty() || !branches.isEmpty()
                || !processingBatches.isEmpty() || !batches.isEmpty()) {
            Map<QIOResourceDescriptor, Long> reserved = new HashMap<>();
            for (CloudworksResourceStack stack : input == null ? inputs : input)
                if (stack.reserved() > 0) reserved.merge(stack.descriptor(), stack.reserved(), Math::addExact);
            Map<QIOResourceDescriptor, Long> required = new HashMap<>();
            if (promise != null)
                for (CloudworksResourceStack stack : promise.inputs())
                    required.merge(stack.descriptor(), stack.amount(), Math::addExact);
            for (CloudworksExecutionState branch : branches.values())
                if (branch.operation != null) for (CloudworksResourceStack stack : branch.operation.inputs())
                    required.merge(stack.descriptor(), stack.amount(), Math::addExact);
            for (CloudworksExecutionState batch : batches.values())
                if (batch.operation != null) for (CloudworksResourceStack stack : batch.operation.inputs())
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
        setExecutionBranches(branches);
        setProcessingBatches(batches);
        progress = completedTicks;
        totalTicks = ticks;
        state = Objects.requireNonNull(nextState);
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
        next.setInteger("schema", SCHEMA_VERSION);
        if (oreRules.isEmpty()) next.removeTag("oreRules"); else next.setTag("oreRules", CloudworksOreRule.writeList(oreRules));
        next.setInteger("fusionInjectionRate", fusionInjectionRate);
        next.setBoolean("active", active);
        next.setInteger("progress", progress);
        next.setInteger("totalTicks", totalTicks);
        next.setString("state", state);
        next.setLong("outputSequence", outputSequence);
        writeExecutions(next);
        if (recipeType.isEmpty()) next.removeTag("recipeType");
        else next.setString("recipeType", recipeType);
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

    /**
     * Immutable, NBT-free Runtime publication values. Resource vectors are
     * copied only when their Runtime inventory version changes; all other
     * referenced values are immutable snapshots owned by this projection.
     */
    public RuntimeProjection runtimeProjection(RuntimeProjection previous, boolean inputChanged,
            boolean outputChanged, boolean templateChanged) {
        return new RuntimeProjection(this,
                previous == null || inputChanged ? RuntimeProjection.resources(inputs) : previous.inputs,
                previous == null || outputChanged ? RuntimeProjection.resources(outputs) : previous.outputs,
                previous == null || templateChanged ? RuntimeProjection.resources(templates) : previous.templates);
    }

    public static final class RuntimeProjection {
        private final int progress, totalTicks, fusionInjectionRate;
        private final String state, recipeType;
        private final boolean active;
        private final long outputSequence;
        private final List<CloudworksOreRule> oreRules;
        private final ResourceValue[] inputs, outputs, templates;
        private final CloudworksOperation operation;
        private final Map<String, CloudworksResourceCredit> processCredits;
        private final Map<String, CloudworksExecutionState> processingBatches, executionBranches;
        private final Map<QIOResourceDescriptor, Double> generationRemainders;
        private final Map<String, Double> moduleCostRemainders;
        private final CloudworksThermalState thermalState;

        private RuntimeProjection(MachineNodeData source, ResourceValue[] inputs,
                ResourceValue[] outputs, ResourceValue[] templates) {
            this.progress = source.progress;
            this.totalTicks = source.totalTicks;
            this.fusionInjectionRate = source.fusionInjectionRate;
            this.state = source.state;
            this.recipeType = source.recipeType;
            this.active = source.active;
            this.outputSequence = source.outputSequence;
            this.oreRules = source.oreRules;
            this.inputs = inputs;
            this.outputs = outputs;
            this.templates = templates;
            this.operation = source.operation;
            this.processCredits = source.processCredits;
            this.processingBatches = source.processingBatches;
            this.executionBranches = source.executionBranches;
            this.generationRemainders = source.generationRemainders;
            this.moduleCostRemainders = source.moduleCostRemainders;
            this.thermalState = source.thermalState;
        }

        private static ResourceValue[] resources(List<CloudworksResourceStack> values) {
            ResourceValue[] snapshot = new ResourceValue[values.size()];
            for (int index = 0; index < snapshot.length; index++) snapshot[index] = new ResourceValue(values.get(index));
            return snapshot;
        }

        /** Writes the dynamic projection onto a private copy of its cold baseline tag. */
        public void writeOnto(NBTTagCompound tag) {
            tag.setInteger("schema", SCHEMA_VERSION);
            if (oreRules.isEmpty()) tag.removeTag("oreRules");
            else tag.setTag("oreRules", CloudworksOreRule.writeList(oreRules));
            tag.setInteger("fusionInjectionRate", fusionInjectionRate);
            tag.setBoolean("active", active);
            tag.setInteger("progress", progress);
            tag.setInteger("totalTicks", totalTicks);
            tag.setString("state", state);
            tag.setLong("outputSequence", outputSequence);
            writeExecutions(tag);
            if (recipeType.isEmpty()) tag.removeTag("recipeType");
            else tag.setString("recipeType", recipeType);
            if (thermalState == null) tag.removeTag("thermalState");
            else tag.setTag("thermalState", thermalState.write());
            tag.setTag("inputs", writeResources(inputs));
            tag.setTag("outputs", writeResources(outputs));
            if (templates.length == 0) tag.removeTag("templates");
            else tag.setTag("templates", writeResources(templates));
            tag.removeTag("operation");
            if (operation != null) tag.setTag("operation", operation.write());
        }

        private void writeExecutions(NBTTagCompound tag) {
            tag.removeTag("processCredits");
            if (!processCredits.isEmpty()) {
                NBTTagCompound credits = new NBTTagCompound();
                processCredits.forEach((key, credit) -> credits.setTag(key, credit.write()));
                tag.setTag("processCredits", credits);
            }
            tag.removeTag("processingBatches");
            if (!processingBatches.isEmpty()) {
                NBTTagCompound batches = new NBTTagCompound();
                processingBatches.forEach((id, value) -> batches.setTag(id, value.write()));
                tag.setTag("processingBatches", batches);
            }
            tag.removeTag("executionBranches");
            if (!executionBranches.isEmpty()) {
                NBTTagCompound branches = new NBTTagCompound();
                executionBranches.forEach((id, value) -> branches.setTag(id, value.write()));
                tag.setTag("executionBranches", branches);
            }
            CloudworksExecutionState.writeRemainders(tag, generationRemainders);
            NBTTagCompound storedCosts = new NBTTagCompound();
            moduleCostRemainders.forEach(storedCosts::setDouble);
            tag.setTag("moduleCostRemainders", storedCosts);
        }

        private static NBTTagList writeResources(ResourceValue[] resources) {
            NBTTagList list = new NBTTagList();
            for (ResourceValue value : resources) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setInteger("schema", 1);
                entry.setTag("descriptor", value.descriptor.write());
                entry.setLong("amount", value.amount);
                entry.setLong("reserved", value.reserved);
                list.appendTag(entry);
            }
            return list;
        }

        private static final class ResourceValue {
            final QIOResourceDescriptor descriptor;
            final long amount, reserved;

            ResourceValue(CloudworksResourceStack value) {
                descriptor = value.descriptor();
                amount = value.amount();
                reserved = value.reserved();
            }
        }
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

    public Object cachedRecipe(Object source, int version, Map<QIOResourceDescriptor, Long> stock) {
        RecipeHit hit = recipeHits == null ? null : recipeHits.get(source);
        return hit != null && hit.version == version && hit.stock != null && hit.stock.equals(stock) ? hit.recipe : null;
    }

    public void cacheRecipe(Object source, int version, Object recipe) {
        cacheRecipe(source, version, recipe, null);
    }

    public void cacheRecipe(Object source, int version, Object recipe,
                            Map<QIOResourceDescriptor, Long> stock) {
        if (recipeHits == null) recipeHits = new IdentityHashMap<>();
        recipeHits.put(source, new RecipeHit(version, recipe, stock == null ? null : new HashMap<>(stock)));
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
        Map<QIOResourceDescriptor, Long> required = new LinkedHashMap<>();
        for (CloudworksResourceStack stack : value.inputs())
            required.merge(stack.descriptor(), stack.amount(), Math::addExact);
        for (Map.Entry<QIOResourceDescriptor, Long> entry : required.entrySet())
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
        Map<QIOResourceDescriptor, Long> required = new LinkedHashMap<>();
        for (CloudworksResourceStack stack : operation.inputs())
            required.merge(stack.descriptor(), stack.amount(), Math::addExact);
        for (Map.Entry<QIOResourceDescriptor, Long> entry : required.entrySet())
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
        for (CloudworksResourceCredit credit : processCredits.values()) if (credit.units >= 1)
            addInput(credit.resource, (long) Math.floor(credit.units));
        processCredits = Collections.emptyMap();
        for (CloudworksOperation pending : allOperations())
            pending.inputs().forEach(stack -> releaseReserved(stack.descriptor(), stack.amount()));
        operation = null;
        progress = 0;
        Map<String, CloudworksExecutionState> next = new LinkedHashMap<>();
        executionBranches.forEach((id, branch) -> next.put(id, new CloudworksExecutionState(
                0, branch.duration, branch.state, branch.outputSequence, null, branch.thermal, branch.generationRemainders)));
        executionBranches = Collections.unmodifiableMap(next);
        processingBatches = Collections.emptyMap();
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
            GasStack stored = stack.descriptor().resolve(QIOResourceCodecs.GAS_STACK);
            if (stored != null && stored.isGasEqual(gas)) amount = Math.addExact(amount, stack.available());
        }
        return amount;
    }

    public long inputAmountForItem(ItemStack wanted) {
        long amount = 0;
        for (CloudworksResourceStack stack : inputs) {
            ItemStack stored = stack.descriptor().resolve(QIOResourceCodecs.ITEM_STACK);
            if (stored != null && ItemStackInput.inputContains(stored, wanted))
                amount = Math.addExact(amount, stack.available());
        }
        return amount;
    }

    public void consumeItem(ItemStack wanted, long amount) {
        if (amount < 0 || inputAmountForItem(wanted) < amount)
            throw new IllegalArgumentException("Insufficient item input");
        long remaining = amount;
        for (CloudworksResourceStack stack : inputs) {
            ItemStack stored = stack.descriptor().resolve(QIOResourceCodecs.ITEM_STACK);
            if (stored == null || !ItemStackInput.inputContains(stored, wanted)) continue;
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
            GasStack stored = stack.descriptor().resolve(QIOResourceCodecs.GAS_STACK);
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

    public long outputSequence() { return outputSequence; }
    public void setOutputSequence(long sequence) {
        if (sequence < 0) throw new IllegalArgumentException("Invalid output sequence");
        outputSequence = sequence;
    }

    public CloudworksThermalState thermalState() { return thermalState; }
    public void setThermalState(CloudworksThermalState state) { thermalState = state; }
    public Map<String, CloudworksExecutionState> executionBranches() { return executionBranches; }
    public Map<String, CloudworksExecutionState> processingBatches() { return processingBatches; }
    public void setProcessingBatches(Map<String, CloudworksExecutionState> batches) {
        batches.forEach((id, value) -> {
            if (id == null || id.length() > 128 || !id.startsWith(CloudworksProcessingBatches.PREFIX)
                    || value == null || value.operation == null || value.thermal != null
                    || !value.generationRemainders.isEmpty())
                throw new IllegalArgumentException("Invalid processing batch");
        });
        processingBatches = Collections.unmodifiableMap(new LinkedHashMap<>(batches));
    }
    public void setExecutionBranches(Map<String, CloudworksExecutionState> branches) {
        if (branches.size() > CloudworksNodeMechanisms.BRANCH_LIMIT) throw new IllegalArgumentException("Too many execution branches");
        branches.forEach((id, value) -> {
            if (id == null || id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || value == null)
                throw new IllegalArgumentException("Invalid execution branch");
        });
        executionBranches = Collections.unmodifiableMap(new LinkedHashMap<>(branches));
    }
    public Map<QIOResourceDescriptor, Double> generationRemainders() { return generationRemainders; }
    public void setGenerationRemainders(Map<QIOResourceDescriptor, Double> values) {
        generationRemainders = CloudworksExecutionState.remainders(values);
    }
    public List<CloudworksOperation> allOperations() {
        List<CloudworksOperation> result = new ArrayList<>();
        if (operation != null) result.add(operation);
        for (CloudworksExecutionState state : executionBranches.values()) if (state.operation != null) result.add(state.operation);
        for (CloudworksExecutionState state : processingBatches.values()) if (state.operation != null) result.add(state.operation);
        return result;
    }
    public boolean hasOperations() {
        if (!processCredits.isEmpty()) return true;
        if (operation != null) return true;
        for (CloudworksExecutionState state : executionBranches.values()) if (state.operation != null) return true;
        if (!processingBatches.isEmpty()) return true;
        return false;
    }
    private void writeExecutions(NBTTagCompound tag) {
        tag.removeTag("processCredits");
        if (!processCredits.isEmpty()) {
            NBTTagCompound credits = new NBTTagCompound(); processCredits.forEach((key, credit) -> credits.setTag(key, credit.write()));
            tag.setTag("processCredits", credits);
        }
        tag.removeTag("processingBatches");
        if (!processingBatches.isEmpty()) {
            NBTTagCompound batches = new NBTTagCompound();
            processingBatches.forEach((id, state) -> batches.setTag(id, state.write()));
            tag.setTag("processingBatches", batches);
        }
        tag.removeTag("executionBranches");
        if (!executionBranches.isEmpty()) {
            NBTTagCompound branches = new NBTTagCompound();
            executionBranches.forEach((id, state) -> branches.setTag(id, state.write()));
            tag.setTag("executionBranches", branches);
        }
        CloudworksExecutionState.writeRemainders(tag, generationRemainders);
        NBTTagCompound storedCosts = new NBTTagCompound();
        moduleCostRemainders.forEach(storedCosts::setDouble);
        tag.setTag("moduleCostRemainders", storedCosts);
    }

    public void setState(String state) {
        this.state = state == null ? "OFF" : state;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", SCHEMA_VERSION);
        if (!oreRules.isEmpty()) tag.setTag("oreRules", CloudworksOreRule.writeList(oreRules));
        tag.setInteger("fusionInjectionRate", fusionInjectionRate);
        tag.setString("nodeId", nodeId.toString());
        tag.setTag("machine", frozenMachine == null ? machine.writeToNBT(new NBTTagCompound()) : frozenMachine.copy());
        tag.setLong("machineCount", machineCount);
        tag.setBoolean("pooledClaim", pooledClaim);
        if (waitingBlueprint != null) {
            tag.setString("waitingBlueprint", waitingBlueprint.toString());
            tag.setLong("waitingCount", waitingCount);
            if (!waitingMechanism.isEmpty()) tag.setString("waitingMechanism", waitingMechanism);
        }
        // Optional keys keep legacy saves readable; absent simply means "not uploaded".
        if (transferId != null) tag.setString("transferId", transferId.toString());
        if (sourceId != null) tag.setString("sourceId", sourceId.toString());
        tag.setInteger("x", x);
        tag.setInteger("z", z);
        tag.setBoolean("active", active);
        tag.setInteger("progress", progress);
        tag.setInteger("totalTicks", totalTicks);
        tag.setString("state", state);
        tag.setLong("outputSequence", outputSequence);
        writeExecutions(tag);
        if (!recipeType.isEmpty()) tag.setString("recipeType", recipeType);
        customUpgrades.write(tag, "customUpgrades");
        if (thermalState != null) tag.setTag("thermalState", thermalState.write());
        tag.setTag("inputs", writeResources(inputs));
        tag.setTag("outputs", writeResources(outputs));
        if (!templates.isEmpty()) tag.setTag("templates", writeResources(templates));
        if (operation != null) tag.setTag("operation", operation.write());
        return tag;
    }

    public static final class MachineCapabilities {
        /**
         * B5 frozen marker for nodes whose machine item comes from a removed
         * mod: no key, no profile, no adapters. Consumers skip frozen nodes
         * everywhere except deletion and persistence.
         */
        private static final MachineCapabilities MISSING_MOD = new MachineCapabilities(Long.MIN_VALUE,
                null, null, Collections.<CloudworksMachineAdapter>emptyList(), null, null, null,
                "", "missing-mod", new CloudworksUpgradeSet(), -1, "", "", Collections.emptyList(), "");

        static MachineCapabilities missingMod() { return MISSING_MOD; }

        /** True when the machine item comes from a removed mod (B5 derived state). */
        public boolean frozen() { return key == null; }

        private final long version;
        private final MachineKey key;
        /**
         * Cached serialization of the key's display stack; stable per capabilities.
         */
        private final String keyString;
        private final String identityString;
        private final MachineExecutionProfile profile;
        private final List<CloudworksMachineAdapter> adapters;
        private final CloudworksEnergyGenerator generator;
        private final MachineSlotLayout slots;
        private final CloudworksThermalPlan thermal;
        private final CloudworksUpgradeSet upgradeSource;
        private final long upgradeVersion, mechanismVersion, mechanismMachineCount;
        private final String waitingMechanism;
        private final String mechanism;
        private final List<CloudworksNodeMechanisms.Branch> branches;

        private MachineCapabilities(long version, MachineKey key, MachineExecutionProfile profile,
                                    List<CloudworksMachineAdapter> adapters,
                                    CloudworksEnergyGenerator generator, MachineSlotLayout slots,
                                    CloudworksThermalPlan thermal, String recipeType,
                                    String identityString, CloudworksUpgradeSet upgrades, long mechanismMachineCount,
                                    String mechanism, String waitingMechanism,
                                    List<CloudworksNodeMechanisms.Branch> branches, String configurationKey) {
            this.version = version;
            this.key = key;
            this.profile = profile;
            this.adapters = adapters;
            this.generator = generator;
            this.slots = slots;
            this.thermal = thermal;
            upgradeSource = upgrades;
            upgradeVersion = upgrades.version();
            mechanismVersion = CloudworksNodeMechanisms.version();
            this.mechanismMachineCount = mechanismMachineCount;
            this.waitingMechanism = waitingMechanism;
            this.mechanism = mechanism;
            this.branches = branches;
            // Serialized once per capabilities instance: prepare, projection and
            // planning all need the same string and NBT serialization is hot.
            // Group cold execution contracts by selection without changing machine inventory identity.
            this.identityString = identityString;
            NBTTagCompound moduleCounts = new NBTTagCompound();
            upgrades.write(moduleCounts, "modules");
            this.keyString = identityString + configurationKey
                    + (recipeType.isEmpty() ? "" : "|recipe=" + recipeType)
                    + (mechanism.isEmpty() ? "" : (waitingMechanism.isEmpty() ? "|mechanism=" : "|waitingMechanism=") + mechanism)
                    + (mechanism.isEmpty() ? "" : "|moduleCounts=" + moduleCounts + "|machines=" + mechanismMachineCount);
        }

        private boolean matches(CloudworksUpgradeSet upgrades, long machines, String waiting) {
            boolean same = upgradeSource == upgrades && upgradeVersion == upgrades.version()
                    || upgradeVersion == 0 && upgrades.version() == 0 && upgradeSource.isEmpty() && upgrades.isEmpty();
            return same && mechanismVersion == CloudworksNodeMechanisms.version()
                    && (mechanismMachineCount < 0 || mechanismMachineCount == machines) && waitingMechanism.equals(waiting);
        }

        public MachineKey key() {
            return key;
        }

        /**
         * Frozen execution identity: physical key plus recipe mode and active module shape.
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

        public CloudworksEnergyGenerator generator() {
            return generator;
        }

        public MachineSlotLayout slots() {
            return slots;
        }

        public String mechanism() { return mechanism; }
        public List<CloudworksNodeMechanisms.Branch> branches() { return branches; }
        public List<CloudworksMachineAdapter> allAdapters() {
            if (branches.isEmpty()) return adapters;
            List<CloudworksMachineAdapter> result = new ArrayList<>(adapters);
            for (CloudworksNodeMechanisms.Branch branch : branches) result.addAll(branch.adapters);
            return Collections.unmodifiableList(result);
        }

        public boolean executable() {
            return !adapters.isEmpty() || generator != null || thermal != null || !branches.isEmpty();
        }
        public CloudworksThermalPlan thermal() { return thermal; }
        public CloudworksThermalPlan thermalPortPlan() {
            if (thermal != null) return thermal;
            for (CloudworksNodeMechanisms.Branch branch : branches)
                if (branch.thermal != null) return branch.thermal;
            return null;
        }
    }

    private static final class RecipeHit {
        private final int version;
        private final Object recipe;
        private final Map<QIOResourceDescriptor, Long> stock;

        private RecipeHit(int version, Object recipe, Map<QIOResourceDescriptor, Long> stock) {
            this.version = version;
            this.recipe = recipe;
            this.stock = stock;
        }
    }
}
