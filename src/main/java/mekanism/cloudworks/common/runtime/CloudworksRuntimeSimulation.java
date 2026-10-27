package mekanism.cloudworks.common.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.machine.MachineOutput;
import mekanism.cloudworks.api.work.PostWorkContext;
import mekanism.cloudworks.api.work.WorkContribution;
import mekanism.cloudworks.api.recipe.RecipeCandidateContext;
import mekanism.cloudworks.api.recipe.RecipeCandidateDecision;
import mekanism.cloudworks.common.service.CloudworksModuleBinding;
import mekanism.cloudworks.common.service.CloudworksEnergyBalancer;
import mekanism.cloudworks.common.service.CloudworksQuantities;
import mekanism.cloudworks.common.service.CloudworksOutputMultiplier;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;
import mekanism.cloudworks.api.module.MachineModuleSource;
import mekanism.cloudworks.api.work.WorkCommittedContext;
import mekanism.cloudworks.api.work.WorkStartedContext;
import mekanism.cloudworks.common.service.CloudworksModuleEvents;

/** Persistent numerical systems for every active line in one player world. */
final class CloudworksRuntimeSimulation {
    private final CloudworksRuntimeProgram program;
    private final CloudworksPlayerRuntime runtime;
    private final CloudworksRuntimeResourceStore stock;
    private final CloudworksRuntimeMachineStore machines;
    private final CloudworksRuntimeChannelStore channels;
    private final CloudworksRuntimeLinkStore links;
    private final CloudworksRuntimeOperationStore operations;
    private final CloudworksRuntimeEnergyStore energy;
    private final double[] loads, generation, pools, capacities;
    private final double[] generated;
    private final long[] outputByCodec;
    private final CloudworksRuntimePromises promises;
    private final CloudworksRuntimePlanningCache planningCache;
    private final double[] generationCredits;
    private final long[] generationAmounts = new long[64];
    private final double[] nextGenerationRemainders = new double[64];
    private int cursor;
    private int clock;
    private final CloudworksRuntimeThermalSystem thermal;
    private final boolean[] thermalPowered;
    private final Map<String, Double> planningRemainders = new LinkedHashMap<>();
    private final Map<String, long[]> secondaryBudgets = new HashMap<>();
    private final CloudworksRuntimePowerBudget powerBudget = new CloudworksRuntimePowerBudget();

    CloudworksRuntimeSimulation(CloudworksPlayerRuntime runtime, CloudworksRuntimeProgram program) {
        this.runtime = runtime;
        this.program = program;
        stock = runtime.resources();
        machines = runtime.machines();
        channels = runtime.channels();
        links = runtime.links();
        operations = runtime.operations();
        energy = runtime.energy();
        loads = new double[program.machineStart.length];
        generation = new double[loads.length];
        pools = new double[loads.length];
        capacities = new double[loads.length];
        generated = new double[machines.size()];
        outputByCodec = new long[program.codecCount];
        promises = new CloudworksRuntimePromises(program.codecCount);
        planningCache = new CloudworksRuntimePlanningCache(machines.size());
        generationCredits = new double[program.codecCount];
        cursor = program.initialCursor;
        clock = program.initialClock;
        thermal = new CloudworksRuntimeThermalSystem(runtime, program, this);
        thermalPowered = new boolean[loads.length];
    }

    int cursor() { return cursor; }
    int clock() { return clock; }

    void syncClock(int clockTick) { clock = clockTick; }

    void tick() {
        intake();
        route(CloudworksRuntimeLinkStore.CHANNEL_INPUT);
        route(CloudworksRuntimeLinkStore.CHANNEL_TO_MACHINE);
        route(CloudworksRuntimeLinkStore.AUXILIARY_INPUT);
        routeEnergy();
        plan();
        settleAndAdvance();
        advancePowerProcesses();
        route(CloudworksRuntimeLinkStore.MACHINE_TO_MACHINE);
        route(CloudworksRuntimeLinkStore.MACHINE_TO_CHANNEL);
        route(CloudworksRuntimeLinkStore.CHANNEL_OUTPUT);
        outflow();
        clock = clock >= 24000 ? 1 : clock + 1;
    }

    private void intake() {
        int count = program.machineStart.length;
        if (count == 0) return;
        int start = cursor;
        for (int offset = 0; offset < count; offset++) {
            int line = (start + offset) % count;
            for (int channel = program.channelStart[line]; channel < program.channelEnd[line]; channel++) {
                if (!channels.enabled(channel) || !channels.input(channel) || !channels.connected(channel)) continue;
                if (channels.special(channel)) {
                    specialSource(channel);
                    continue;
                }
                int inventory = channels.inventory(channel);
                int source = channels.transfer(channel) ? program.transfer : program.receiving;
                for (int entry = stock.first(channels.manifest(channel)); entry >= 0; entry = stock.next(entry)) {
                    int resource = stock.resource(entry);
                    long missing = stock.amountAt(entry) - stock.amount(inventory, resource);
                    if (missing <= 0) continue;
                    long room = channels.capacity(channel) - stock.total(inventory);
                    long amount = Math.min(missing, Math.min(room, stock.available(source, resource)));
                    if (amount > 0) stock.move(source, inventory, resource, amount);
                }
            }
        }
        // All lines advance every tick; only first access to scarce resources rotates.
        cursor = (start + 1) % count;
    }

    private void specialSource(int channel) {
        int inventory = channels.inventory(channel);
        long room = Math.max(0, channels.capacity(channel) - stock.total(inventory));
        if (room <= 0) return;
        int manifest = channels.manifest(channel);
        for (int entry = stock.first(manifest); entry >= 0 && room > 0; entry = stock.next(entry)) {
            int resource = stock.resource(entry);
            long amount = Math.min(room, Math.max(0, stock.amountAt(entry) - stock.amount(inventory, resource)));
            if (amount <= 0) continue;
            stock.add(inventory, resource, amount);
            room -= amount;
        }
    }

    private void route(byte phase) {
        for (int link = 0; link < links.size(); link++) {
            if (links.phase(link) != phase) continue;
            int source = links.source(link), target = links.target(link), sourceInventory, targetInventory;
            boolean sourceMachine = phase == CloudworksRuntimeLinkStore.MACHINE_TO_MACHINE
                    || phase == CloudworksRuntimeLinkStore.MACHINE_TO_CHANNEL;
            boolean targetMachine = phase == CloudworksRuntimeLinkStore.MACHINE_TO_MACHINE
                    || phase == CloudworksRuntimeLinkStore.CHANNEL_TO_MACHINE;
            boolean targetAuxiliary = phase == CloudworksRuntimeLinkStore.AUXILIARY_INPUT;
            if (sourceMachine) {
                if (!machines.active(source)) continue;
                sourceInventory = machines.output(source);
            } else {
                if (!channels.enabled(source)) continue;
                sourceInventory = channels.inventory(source);
            }
            if (targetMachine) {
                if (!machines.active(target)) continue;
                CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(target)];
                targetInventory = type.configuration[links.targetPort(link)]
                        ? machines.configuration(target) : machines.input(target);
            } else if (targetAuxiliary) {
                targetInventory = target;
            } else {
                if (!channels.enabled(target)) continue;
                targetInventory = channels.inventory(target);
            }
            for (int entry = stock.first(sourceInventory); entry >= 0; entry = stock.next(entry)) {
                long available = stock.availableAt(entry);
                if (available <= 0) continue;
                int resource = stock.resource(entry);
                if (!links.accepts(link, resource)) continue;
                if (sourceMachine) {
                    CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(source)];
                    if (!type.accepted[links.sourcePort(link)][resource]) continue;
                } else if (links.sourcePort(link) != program.resourceCodec[resource]) continue;
                long room;
                if (targetMachine) {
                    CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(target)];
                    int port = links.targetPort(link);
                    if (!type.accepted[port][resource]) continue;
                    room = inputRoom(target, port, resource);
                } else if (targetAuxiliary) {
                    if (links.targetPort(link) != program.resourceCodec[resource]) continue;
                    room = program.auxiliaryCapacities.getOrDefault(targetInventory, Long.MAX_VALUE)
                            - stock.total(targetInventory);
                } else {
                    if (links.targetPort(link) != program.resourceCodec[resource]) continue;
                    room = channels.capacity(target) - stock.total(targetInventory);
                }
                if (room <= 0) continue;
                long moved = Math.min(room, links.allowance(link, available));
                if (moved > 0) stock.move(sourceInventory, targetInventory, resource, moved);
            }
        }
    }

    private long inputRoom(int node, int port, int resource) {
        CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(node)];
        boolean configuration = type.configuration[port];
        int inventory = configuration ? machines.configuration(node) : machines.input(node);
        int codec = program.resourceCodec[resource];
        long portHeld = stock.groupTotal(inventory, program.codecCount + port);
        CloudworksRuntimeRecipe creditProcess = program.creditProcesses[node];
        long prepaid = creditProcess != null && creditProcess.prepaidFuelUnits > 0
                && program.resourceCodec[creditProcess.creditResource] == codec ? (long) Math.ceil(machines.processCredit(node)) : 0;
        portHeld = Math.addExact(portHeld, prepaid);
        if (configuration && portHeld > 0 && stock.amount(inventory, resource) == 0) return 0;
        if (program.decayTypes[machines.machineType(node)] && stock.total(inventory) > 0
                && stock.amount(inventory, resource) == 0) return 0;
        long capacity = CloudworksQuantities.multiplyLimit(
                configuration ? type.configurationCapacity[codec] : type.inputCapacity[codec],
                machines.machineCount(node) * program.nodeBufferMultiplier[node]);
        long codecRoom = capacity - codecTotal(inventory, codec) - prepaid;
        long portRoom = CloudworksQuantities.multiplyLimit(type.portCapacity[port],
                machines.machineCount(node) * program.nodeBufferMultiplier[node]) - portHeld;
        return Math.max(0, Math.min(codecRoom, portRoom));
    }

    private void convertInfuse(int node) {
        if (program.infuseCodec < 0) return;
        CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(node)];
        long capacity = CloudworksQuantities.multiplyLimit(type.inputCapacity[program.infuseCodec],
                machines.machineCount(node) * program.nodeBufferMultiplier[node]);
        if (capacity <= 0) return;
        int inventory = machines.input(node), currentType = -1;
        long held = 0;
        for (int entry = stock.first(inventory); entry >= 0; entry = stock.next(entry))
            if (stock.amountAt(entry) > 0 && program.resourceCodec[stock.resource(entry)] == program.infuseCodec) {
                int resource = stock.resource(entry);
                if (currentType >= 0 && currentType != resource) return;
                currentType = resource;
                held = Math.addExact(held, stock.amountAt(entry));
            }
        for (int entry = stock.first(inventory); entry >= 0 && held < capacity; entry = stock.next(entry)) {
            int resource = stock.resource(entry), target = program.conversionTarget[resource];
            if (target < 0 || currentType >= 0 && currentType != target) continue;
            int units = program.conversionUnits[resource];
            long count = Math.min(stock.availableAt(entry), (capacity - held) / units);
            if (count <= 0) continue;
            long produced = Math.multiplyExact(count, units);
            stock.add(inventory, target, produced);
            stock.take(inventory, resource, count);
            currentType = target;
            held += produced;
        }
    }

    private void plan() {
        planningRemainders.clear();
        secondaryBudgets.clear();
        planningRemainders.putAll(runtime.costRemainders());
        Arrays.fill(loads, 0); Arrays.fill(generation, 0); Arrays.fill(generated, 0);
        thermal.produce(clock);
        for (int node = 0; node < machines.size(); node++) {
            operations.resetSelection(node);
            for (CloudworksRuntimeBatch batch : operations.batches(node)) batch.reservedEarly = false;
            if (machines.active(node) && program.environmentReady[node] && program.thermalPlans[node] == null && !program.generator[node]
                    && !program.decayTypes[machines.machineType(node)]) {
                for (CloudworksRuntimeBatch batch : operations.batches(node)) {
                    if (!batchEnvironmentReady(node, batch)) continue;
                    operations.select(node, batch);
                    addRecipeLoad(node, 1);
                }
                operations.resetSelection(node);
            }
        }
        for (int scanned = 0; scanned < machines.size(); scanned++) {
            int root = program.physicalRoot[scanned], countInGroup = program.physicalEnd[root] - root;
            int node = countInGroup == 1 ? scanned : root + Math.floorMod(scanned - root + clock, countInGroup);
            if (!machines.active(node)) { machines.setState(node, CloudworksRuntimeMachineStore.OFF); continue; }
            int line = machines.line(node), type = machines.machineType(node);
            if (!program.environmentReady[node]) {
                machines.setState(node, CloudworksRuntimeMachineStore.WAITING_ENVIRONMENT);
                continue;
            }
            // Failed simple plans are stamped against the recipe array and the
            // input/output/configuration inventory versions. Once those remain
            // unchanged, skip the rest of recipe selection and energy planning.
            if (planningCache.unchanged(node, program, machines, operations, stock)) continue;
            selectCreditProcess(node, program.recipes[type]);
            if (continuousProcessing(node)) continue;
            if (program.thermalPlans[node] != null) {
                loads[line] = finiteSum(loads[line], program.machineLoad[node]);
                double ambient = Math.min(generationLimit(node), program.generationPlans[node].generation(clock));
                if (ambient > 0) generation[line] = finiteSum(generation[line], convertGeneration(node, ambient));
                continue;
            }
            convertInfuse(node);
            if (program.decayTypes[type]) {
                loads[line] = finiteSum(loads[line], program.machineLoad[node]); decay(node, type); continue;
            }
            if (program.generator[node]) {
                loads[line] = finiteSum(loads[line], program.machineLoad[node]);
                double raw = burnFuel(node);
                generated[node] = convertGeneration(node, raw);
                generation[line] = finiteSum(generation[line], generated[node]);
                machines.setState(node, raw > 0 ? CloudworksRuntimeMachineStore.GENERATING : generatorWaitingState(node));
                continue;
            }
            if (!program.executable[node]) { machines.setState(node, CloudworksRuntimeMachineStore.NO_RECIPE); continue; }
            int maximum = CloudworksQuantities.operationLimit(machines.machineCount(node), program.threads[node], program.workRate);
            int free = Math.max(0, maximum - operations.occupied(node));
            if (free == 0) continue;
            if (!program.supported[node]) { machines.setState(node, CloudworksRuntimeMachineStore.UNSUPPORTED_RECIPE); continue; }
            if (program.recipes[type].length == 0) { machines.setState(node, CloudworksRuntimeMachineStore.NO_RECIPE); continue; }
            boolean planned = false, outputBlocked = false, validSunlight = false, configured = false;
            boolean incompleteInputs = false, insufficientInputs = false, powerBlocked = false;
            CloudworksRuntimeRecipe[] candidates = candidates(node, program.recipes[type]);
            if (candidates.length == 0) {
                machines.setState(node, Arrays.stream(program.recipes[type]).anyMatch(recipe -> !environmentMatches(node, recipe))
                        ? CloudworksRuntimeMachineStore.WAITING_ENVIRONMENT : CloudworksRuntimeMachineStore.NO_MATCHING_RECIPE);
                continue;
            }
            int first = program.modules[node].isEmpty() ? (int) Math.floorMod(machines.outputSequence(node), candidates.length) : 0;
            for (int offset = 0; offset < candidates.length && free > 0; offset++) {
                CloudworksRuntimeRecipe recipe = candidates[(first + offset) % candidates.length];
                if (recipe.prepaidFuelUnits > 0 || recipe.energyPerInput > 0) continue;
                if (recipe.sunlightRequired && CloudworksGenerationPlan.sunlightBlocked(clock, program.environmentContexts[node])) continue;
                validSunlight = true;
                if (!recipe.configured(stock, machines.configuration(node))) continue;
                configured = true;
                operations.select(node, null);
                int baseDuration = recipe.duration > 0 ? recipe.duration : program.baseTicks[node];
                ModuleWork work = moduleWork(node, recipe, free, baseDuration);
                int duration = CloudworksUpgradeEffects.ticks(baseDuration, program.timeMultiplier[node] * work.time,
                        program.configuredUpgrades[node]);
                int rate = Math.max(recipe.operationsPerCycle, configuredRate(program.configuredUpgrades[node], duration, program.machineLoad[node], program.recipeEnergy[node]));
                int count = maximumBatch(node, recipe, (int) Math.min(Integer.MAX_VALUE, (long) free * rate), rate, work);
                for (int attempt = 0; count > 0 && (program.hasPreWork[node] || !recipe.simpleBatch()); attempt++) {
                    if (attempt >= 32) throw new IllegalArgumentException("Module batch contribution does not converge");
                    work = moduleWork(node, recipe, count, baseDuration);
                    duration = CloudworksUpgradeEffects.ticks(baseDuration, program.timeMultiplier[node] * work.time,
                            program.configuredUpgrades[node]);
                    rate = Math.max(recipe.operationsPerCycle, configuredRate(program.configuredUpgrades[node], duration, program.machineLoad[node], program.recipeEnergy[node]));
                    int refined = maximumBatch(node, recipe, (int) Math.min(count, (long) free * rate), rate, work);
                    if (refined == count) break;
                    count = refined;
                }
                if (count == 0) {
                    byte inputState = recipeInputState(node, recipe);
                    insufficientInputs |= inputState == CloudworksRuntimeMachineStore.INSUFFICIENT_INPUT;
                    incompleteInputs |= inputState == CloudworksRuntimeMachineStore.MISSING_INPUT;
                    if (allocateInputs(node, recipe, 1) != null) {
                        outputBlocked |= !planningOutputsFit(node, recipe, 1, work.chance);
                        powerBlocked |= !canPay(node, processingLoad(node, 1, 1, recipe.extraEnergyPerTick, work.energy));
                    }
                    continue;
                }
                var allocation = allocateInputs(node, recipe, count);
                var outputs = recipe.outputs(count, program.outputMultiplierUnits, outputSeed(node), work.chance);
                if (!outputsFitForPlan(node, recipe, outputs)) { outputBlocked = true; continue; }
                int units = (int) (((long) count + rate - 1) / rate);
                long sequence = operations.nextSequence(node, machines.outputSequence(node));
                operations.plan(node, count, Math.max(1, duration), allocation, outputs, recipe.extraEnergyPerTick, recipe.recipeId);
                operations.setModulePower(node, work.energy);
                operations.setParallelism(node, units, sequence);
                operations.current(node).requiredEnvironmentAxis = recipe.requiredEnvironmentAxis;
                operations.setLifecycleId(node, CloudworksOutputMultiplier.mix(program.nodeOutputSeeds[node] ^ (sequence + 1)));
                commitSecondaryCredits(node, recipe, count, work.remainders);
                reserveModuleCosts(node, work.costs);
                if (!work.remainders.isEmpty())
                    operations.current(node).pendingModuleWork().remainders = work.remainders;
                planningRemainders.putAll(work.remainders);
                operations.start(node, machines.input(node), stock);
                operations.current(node).reservedEarly = true;
                addRecipeLoad(node, rate);
                free -= units; planned = true;
            }
            operations.resetSelection(node);
            if (!planned && operations.batchCount(node) == 0) {
                byte state = outputBlocked ? CloudworksRuntimeMachineStore.WAITING_OUTPUT
                        : !validSunlight ? CloudworksRuntimeMachineStore.WAITING_SUNLIGHT
                        : !configured ? CloudworksRuntimeMachineStore.WAITING_TEMPLATE
                        : powerBlocked ? CloudworksRuntimeMachineStore.WAITING_POWER
                        : stock.total(machines.input(node)) == 0 ? CloudworksRuntimeMachineStore.WAITING_INPUT
                        : insufficientInputs ? CloudworksRuntimeMachineStore.INSUFFICIENT_INPUT
                        : incompleteInputs ? CloudworksRuntimeMachineStore.MISSING_INPUT
                        : CloudworksRuntimeMachineStore.NO_MATCHING_RECIPE;
                machines.setState(node, state);
                planningCache.remember(node, state, program, machines, stock);
            }
        }
        thermal.consumeAndGenerate(generation);
    }

    private int maximumBatch(int node, CloudworksRuntimeRecipe recipe, int maximum, int rate, ModuleWork work) {
        boolean hintedSearch = canUseBatchHint(node, recipe);
        if (recipe.simpleBatch()) {
            maximum = recipe.independentInputLimit(stock, machines.input(node), maximum);
            if (maximum <= 0) return 0;
            if (!program.hasPreWork[node] && program.postWorkModules[node].isEmpty()
                    && (program.outputMultiplierUnits == CloudworksOutputMultiplier.SCALE
                    || !recipe.affectedByOutputMultiplier())) {
                int bounded = simpleOutputLimit(node, recipe, maximum);
                if (bounded >= 0) {
                    maximum = bounded;
                    if (maximum == 0) return 0;
                    int units = (int) (((long) maximum + rate - 1) / rate);
                    if (canPay(node, processingLoad(node, units, maximum, recipe.extraEnergyPerTick, work.energy))) {
                        if (hintedSearch) planningCache.rememberBatchHint(node, recipe, maximum);
                        return maximum;
                    }
                    // Inputs and output capacity are already bounded exactly.
                    // Estimate its linear upper bound once; the original predicate validates
                    // both sides of the candidate boundary and falls back to exact search.
                    preparePowerSearch(node);
                    double fullUnits = machines.machineCount(node) * (double) program.threads[node]
                            * program.workRate;
                    double basePerUnit = fullUnits <= 0D ? 0D : program.machineLoad[node] / fullUnits;
                    double extraPerOperation = recipe.extraEnergyPerTick * program.energyMultiplier[node];
                    int candidate = powerBudget.maximumBatchCandidate(maximum, rate, basePerUnit,
                            extraPerOperation, work.energy);
                    int result = CloudworksRuntimeBatchSearch.maximumVerifiedCandidate(maximum,
                            hintedSearch ? planningCache.batchHint(node, recipe) : 0, candidate, count -> {
                                int unitsForCount = (int) (((long) count + rate - 1) / rate);
                                return canPay(node, processingLoad(node, unitsForCount, count,
                                        recipe.extraEnergyPerTick, work.energy));
                            });
                    if (hintedSearch) planningCache.rememberBatchHint(node, recipe, result);
                    return result;
                }
            }
            int units = (int) (((long) maximum + rate - 1) / rate);
            if (planningOutputsFit(node, recipe, maximum, work.chance)
                    && canPay(node, processingLoad(node, units, maximum, recipe.extraEnergyPerTick, work.energy))) {
                if (hintedSearch) planningCache.rememberBatchHint(node, recipe, maximum);
                return maximum;
            }
        }
        if (hintedSearch) {
            preparePowerSearch(node);
            int result = CloudworksRuntimeBatchSearch.maximumVerified(maximum,
                    planningCache.batchHint(node, recipe), count -> {
                int units = (int) (((long) count + rate - 1) / rate);
                return (recipe.simpleBatch() ? recipe.canAllocate(stock, machines.input(node), count)
                        : allocateInputs(node, recipe, count) != null)
                        && planningOutputsFit(node, recipe, count, work.chance)
                        && canPayEstimated(node, processingLoad(node, units, count,
                                recipe.extraEnergyPerTick, work.energy));
            }, count -> {
                int units = (int) (((long) count + rate - 1) / rate);
                return (recipe.simpleBatch() ? recipe.canAllocate(stock, machines.input(node), count)
                        : allocateInputs(node, recipe, count) != null)
                        && planningOutputsFit(node, recipe, count, work.chance)
                        && canPay(node, processingLoad(node, units, count,
                                recipe.extraEnergyPerTick, work.energy));
            });
            planningCache.rememberBatchHint(node, recipe, result);
            return result;
        }
        int low = 0, high = maximum;
        while (low < high) {
            int count = low + (int) (((long) high - low + 1) / 2);
            int units = (int) (((long) count + rate - 1) / rate);
            if ((recipe.simpleBatch() ? recipe.canAllocate(stock, machines.input(node), count)
                    : allocateInputs(node, recipe, count) != null)
                    && planningOutputsFit(node, recipe, count, work.chance)
                    && canPay(node, processingLoad(node, units, count, recipe.extraEnergyPerTick, work.energy))) low = count;
            else high = count - 1;
        }
        return low;
    }

    /**
     * Reuse a prior successful count only where the admission predicate is provably monotone.
     * Choice/chance sampling, module hooks and scaled outputs can change per-count resource shape.
     */
    private boolean canUseBatchHint(int node, CloudworksRuntimeRecipe recipe) {
        return !program.hasPreWork[node] && program.postWorkModules[node].isEmpty()
                && !recipe.secondaryGasInputs && recipe.nativeSecondaryMode == 0
                && recipe.chanceIds.length == 0 && recipe.choiceIds.length == 0
                && (program.outputMultiplierUnits == CloudworksOutputMultiplier.SCALE
                        || !recipe.affectedByOutputMultiplier());
    }

    /** Exact linear headroom bound; -1 keeps an unusual contract on the old path. */
    private int simpleOutputLimit(int node, CloudworksRuntimeRecipe recipe, int maximum) {
        CloudworksRuntimePlanningCache.OutputShape shape = planningCache.simpleOutputs(
                recipe, program.resourceCodec, program.codecCount);
        if (shape == null) return -1;
        int root = program.physicalRoot[node];
        if (!promises.prepare(root, program.physicalEnd[root], operations, program.resourceCodec)) return 0;
        CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(node)];
        long promisedTotal = 0;
        try {
            for (int codec = 0; codec < program.codecCount; codec++) {
                long capacity = CloudworksQuantities.multiplyLimit(type.outputCapacity[codec],
                        machines.machineCount(node) * program.nodeBufferMultiplier[node]);
                long room = capacity - codecTotal(machines.output(node), codec);
                long promised = promises.amount(codec);
                if (promised > room) return 0;
                promisedTotal = Math.addExact(promisedTotal, promised);
            }
        } catch (ArithmeticException overflow) { return 0; }
        for (int output = 0; output < shape.codecs.length; output++) {
            int codec = shape.codecs[output];
            long capacity = CloudworksQuantities.multiplyLimit(type.outputCapacity[codec],
                    machines.machineCount(node) * program.nodeBufferMultiplier[node]);
            long room = capacity - codecTotal(machines.output(node), codec) - promises.amount(codec);
            maximum = (int) Math.min(maximum, room / shape.amounts[output]);
        }
        long room = Long.MAX_VALUE - stock.total(machines.output(node));
        if (promisedTotal > room) return 0;
        if (shape.totalPerOperation > 0)
            maximum = (int) Math.min(maximum, (room - promisedTotal) / shape.totalPerOperation);
        return maximum;
    }

    private boolean planningOutputsFit(int node, CloudworksRuntimeRecipe recipe, int count, double chance) {
        // Keep the existing multiplier safety gate only for recipes it actually affects.
        // Unscaled/native chance recipes use their deterministic promise, not a new worst-case gate.
        if (program.outputMultiplierUnits != CloudworksOutputMultiplier.SCALE && recipe.affectedByOutputMultiplier())
            return worstOutputsFit(node, recipe, count, program.outputMultiplierUnits, chance);
        if (recipe.chanceIds.length == 0 && recipe.choiceIds.length == 0) {
            Arrays.fill(outputByCodec, 0);
            try {
                for (int output = 0; output < recipe.outputIds.length; output++)
                    addOutput(recipe.outputIds[output], Math.multiplyExact(recipe.outputAmounts[output], count));
            } catch (ArithmeticException overflow) { return false; }
            return promisedOutputsFit(node);
        }
        try {
            return outputsFitForPlan(node, recipe,
                    recipe.outputs(count, program.outputMultiplierUnits, outputSeed(node), chance));
        } catch (ArithmeticException overflow) { return false; }
    }

    private double processingLoad(int node, int units, int count, double extra, double modulePower) {
        double fullUnits = machines.machineCount(node) * (double) program.threads[node] * program.workRate;
        double base = fullUnits <= 0 ? 0 : program.machineLoad[node] / fullUnits;
        return (base * units + extra * program.energyMultiplier[node] * count) * modulePower;
    }

    private String secondaryKey(int node, CloudworksRuntimeRecipe recipe, int ingredient) {
        return program.costNodeKeys[node] + "|secondary:" + program.branchNames[node]
                + ":" + recipe.recipeId + ":" + ingredient;
    }
    private double[] secondaryCredits(int node, CloudworksRuntimeRecipe recipe) {
        double[] credits = new double[recipe.demands.length];
        if (recipe.secondaryGasInputs) for (int ingredient = 0; ingredient < credits.length; ingredient++)
            credits[ingredient] = planningRemainders.getOrDefault(secondaryKey(node, recipe, ingredient), 0D);
        return credits;
    }
    private CloudworksRuntimeRecipe.Allocation allocateInputs(int node, CloudworksRuntimeRecipe recipe, int count) {
        if (recipe.nativeSecondaryMode > 0) {
            String key = node + "|" + recipe.recipeId + "|" + operations.nextSequence(node, machines.outputSequence(node));
            long[] demand = secondaryBudgets.computeIfAbsent(key, ignored -> {
                long[] quantities = recipe.demands.clone();
                int baseTicks = recipe.duration > 0 ? recipe.duration : program.baseTicks[node];
                int ticks = Math.max(1, CloudworksUpgradeEffects.ticks(baseTicks, program.timeMultiplier[node], program.configuredUpgrades[node]));
                long budget = recipe.nativeSecondaryMode == 2
                        ? CloudworksNativeSecondaryBudget.sample(program.secondaryInputMultiplier[node] / program.timeMultiplier[node], ticks, outputSeed(node))
                        : Math.max(1L, Math.round(baseTicks * program.secondaryInputMultiplier[node]));
                for (int ingredient = 0; ingredient < quantities.length; ingredient++) if (recipe.secondaryInputs[ingredient])
                    quantities[ingredient] = Math.multiplyExact(Math.max(1, quantities[ingredient] / baseTicks), budget);
                return quantities;
            });
            return recipe.allocateNativeSecondary(stock, machines.input(node), count, demand);
        }
        return recipe.secondaryGasInputs ? recipe.allocateSecondary(stock, machines.input(node), count,
                program.secondaryInputMultiplier[node], secondaryCredits(node, recipe)) : recipe.allocate(stock, machines.input(node), count);
    }
    private void commitSecondaryCredits(int node, CloudworksRuntimeRecipe recipe, int count, Map<String, Double> remainders) {
        if (!recipe.secondaryGasInputs || recipe.nativeSecondaryMode > 0) return;
        double[] credits = secondaryCredits(node, recipe);
        for (int ingredient = 0; ingredient < credits.length; ingredient++) if (recipe.secondaryInputs[ingredient]) {
            double required = recipe.demands[ingredient] * (double) count * program.secondaryInputMultiplier[node];
            double spent = Math.max(0, Math.ceil(required - credits[ingredient] - 1E-10D));
            remainders.put(secondaryKey(node, recipe, ingredient), Math.max(0, Math.min(Math.nextDown(1D), credits[ingredient] + spent - required)));
        }
    }

    private boolean canPay(int node, double additional) {
        if (energy.unlimited()) return true;
        double deficit = 0;
        for (int line = 0; line < loads.length; line++) {
            double load = loads[line] + (line == machines.line(node) ? additional : 0);
            deficit += Math.max(0, load - energy.lineEnergy(line) - generation[line]);
        }
        return Double.isFinite(deficit) && deficit <= energy.cloudEnergy();
    }

    /** Precomputes the fixed deficit from every line except the one being planned. */
    private void preparePowerSearch(int node) {
        powerBudget.prepare(machines.line(node), loads, generation, energy);
    }

    /** Fast estimate for binary-search probes; exact boundary probes preserve the original sum order. */
    private boolean canPayEstimated(int node, double additional) {
        if (!powerBudget.targets(machines.line(node))) return canPay(node, additional);
        return powerBudget.canPay(additional);
    }

    int thermalRecipe(int node, CloudworksRuntimeRecipe recipe, int maximum) {
        if (!environmentMatches(node, recipe)
                || recipe.sunlightRequired
                        && CloudworksGenerationPlan.sunlightBlocked(
                                clock, program.environmentContexts[node])
                || !recipe.configured(stock, machines.configuration(node))) return 0;
        int low = 0, high = maximum;
        while (low < high) {
            int middle = low + (int) (((long) high - low + 1) / 2);
            if (recipe.allocate(stock, machines.input(node), middle) != null
                    && worstOutputsFit(node, recipe, middle, program.outputMultiplierUnits)) low = middle;
            else high = middle - 1;
        }
        if (low <= 0) return 0;
        CloudworksRuntimeRecipe.Allocation input = recipe.allocate(stock, machines.input(node), low);
        CloudworksRuntimeRecipe.Allocation output = recipe.outputs(low, program.outputMultiplierUnits, outputSeed(node));
        for (int index = 0; index < input.ids.length; index++) stock.take(machines.input(node), input.ids[index], input.amounts[index]);
        for (int index = 0; index < output.ids.length; index++) stock.add(machines.output(node), output.ids[index], output.amounts[index]);
        machines.advanceOutputSequence(node);
        return low;
    }

    boolean drawThermalPower(int line, double amount) {
        if (!Double.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid ignition energy");
        if (energy.unlimited()) return true;
        if (energy.lineEnergy(line) + energy.cloudEnergy() < amount) return false;
        double fromLine = Math.min(amount, energy.lineEnergy(line));
        energy.setLineEnergy(line, energy.lineEnergy(line) - fromLine);
        energy.setCloud(energy.cloudEnergy() - (amount - fromLine), energy.cloudCapacity(), false);
        return true;
    }

    private void addRecipeLoad(int node, int rate) {
        CloudworksRuntimeBatch batch = operations.current(node);
        loads[machines.line(node)] = finiteSum(loads[machines.line(node)], processingLoad(node,
                batch.parallelUnits, batch.operations, batch.extraEnergyPerTick, batch.modulePower));
    }

    private ModuleWork moduleWork(int node, CloudworksRuntimeRecipe recipe, int count, int duration) {
        ModuleWork result = new ModuleWork(program.hasPreWork[node],
                recipe.secondaryGasInputs && recipe.nativeSecondaryMode <= 0);
        result.chance = program.secondaryChanceMultiplier[node];
        if (!program.hasPreWork[node]) return result;
        for (CloudworksModuleBinding binding : program.modules[node]) {
            if (!binding.callbacks.preWork) continue;
            int inventory = binding.handle.getSource() == MachineModuleSource.AUXILIARY_NODE
                    ? program.auxiliaryInventories.getOrDefault(binding.handle.getSourceNodeId(), -1) : machines.input(node);
            QIOResourceDescriptor coolant = binding.costResource;
            if (binding.handle.getSource() == MachineModuleSource.AUXILIARY_NODE) {
                coolant = null;
                for (int entry = stock.first(inventory); entry >= 0; entry = stock.next(entry))
                    if (stock.availableAt(entry) > 0) { coolant = descriptor(stock.resource(entry)); break; }
            }
            double power = binding.basePower > 0 ? binding.basePower
                    : program.machineLoad[node] / Math.max(1D, machines.machineCount(node) * program.threads[node] * program.workRate);
            WorkContribution contribution = binding.pre(recipe.recipeId, count, duration, power, coolant, clock);
            if (contribution.costs().isEmpty()) {
                result.time *= contribution.durationMultiplier();
                result.energy *= contribution.energyPerTickMultiplier();
                result.chance *= contribution.secondaryChanceMultiplier();
                continue;
            }
            Map<Long, Long> sourceCosts = new LinkedHashMap<>();
            Map<String, Double> remainders = new LinkedHashMap<>();
            for (WorkContribution.ResourceCost cost : contribution.costs()) {
                int resource = resourceId(cost.resource());
                if (resource < 0) throw new IllegalArgumentException("Unknown module cost resource");
                double units = cost.amount() * (cost.unit() == WorkContribution.ResourceCost.Unit.PER_OPERATION
                        ? count : Math.max(1, duration) * (double) machines.machineCount(node));
                if (!Double.isFinite(units) || units >= Long.MAX_VALUE) throw new IllegalArgumentException("Module cost overflow");
                String remainderKey = binding.handle.getSourceNodeId() + "|" + binding.handle.getData().id()
                        + "|" + cost.resource().write().toString();
                double cumulative = units + result.remainders.getOrDefault(remainderKey,
                        planningRemainders.getOrDefault(remainderKey, 0D));
                long whole = (long) Math.floor(cumulative + 1E-10D);
                remainders.put(remainderKey, Math.max(0D, cumulative - whole));
                sourceCosts.merge(costKey(inventory, resource), whole, Math::addExact);
            }
            boolean payable = true;
            CloudworksRuntimeRecipe.Allocation recipeInput = allocateInputs(node, recipe, count);
            for (Map.Entry<Long, Long> cost : sourceCosts.entrySet())
                {
                    long demanded = 0;
                    if ((int) (cost.getKey() >>> 32) == machines.input(node) && recipeInput != null)
                        for (int i = 0; i < recipeInput.ids.length; i++)
                            if (recipeInput.ids[i] == (int) (long) cost.getKey()) demanded += recipeInput.amounts[i];
                    if (stock.available((int) (cost.getKey() >>> 32), (int) (long) cost.getKey())
                            < Math.addExact(demanded, Math.addExact(result.costs.getOrDefault(cost.getKey(), 0L), cost.getValue())))
                        payable = false;
                }
            if (!payable) continue;
            result.time *= contribution.durationMultiplier();
            result.energy *= contribution.energyPerTickMultiplier();
            result.chance *= contribution.secondaryChanceMultiplier();
            result.remainders.putAll(remainders);
            for (Map.Entry<Long, Long> cost : sourceCosts.entrySet())
                result.costs.merge(cost.getKey(), cost.getValue(), Math::addExact);
        }
        if (!Double.isFinite(result.time) || !Double.isFinite(result.energy) || !Double.isFinite(result.chance))
            throw new IllegalArgumentException("Combined module effect overflow");
        return result;
    }

    private static long costKey(int inventory, int resource) { return ((long) inventory << 32) | (resource & 0xFFFFFFFFL); }
    private void reserveModuleCosts(int node, Map<Long, Long> costs) {
        if (costs.isEmpty()) return;
        for (Map.Entry<Long, Long> cost : costs.entrySet())
            if (!stock.reserve((int) (cost.getKey() >>> 32), (int) (long) cost.getKey(), cost.getValue()))
                throw new IllegalStateException("Module cost reservation failed");
        operations.current(node).pendingModuleWork().costs = costs;
    }
    private void finishModuleCosts(int node, boolean started) {
        CloudworksRuntimeBatch batch = operations.current(node);
        if (batch == null || batch.pendingModuleWork == null) return;
        CloudworksRuntimeBatch.PendingModuleWork pending = batch.pendingModuleWork;
        batch.pendingModuleWork = null;
        Map<Long, Long> costs = pending.costs;
        Map<String, Double> remainders = pending.remainders;
        if (started && remainders != null) runtime.costRemainders().putAll(remainders);
        if (costs == null) return;
        for (Map.Entry<Long, Long> cost : costs.entrySet()) {
            int inventory = (int) (cost.getKey() >>> 32), resource = (int) (long) cost.getKey();
            if (started) stock.consumeReserved(inventory, resource, cost.getValue());
            else stock.release(inventory, resource, cost.getValue());
        }
    }
    private static final class ModuleWork {
        double time = 1D, energy = 1D, chance = 1D;
        final Map<Long, Long> costs;
        final Map<String, Double> remainders;
        ModuleWork(boolean callbacks, boolean secondaryCredits) {
            costs = callbacks ? new LinkedHashMap<>() : Collections.emptyMap();
            remainders = callbacks || secondaryCredits ? new LinkedHashMap<>() : Collections.emptyMap();
        }
    }

    private CloudworksRuntimeRecipe[] candidates(int node, CloudworksRuntimeRecipe[] recipes) {
        if (!program.hasRecipeCandidate[node] && !program.conditionalCandidates[machines.machineType(node)]) return recipes;
        if (!program.hasRecipeCandidate[node]) {
            CloudworksRuntimeRecipe[] cached = planningCache.candidates(node, recipes, program.environmentContexts[node]);
            if (cached != null) return cached;
        }
        Map<QIOResourceDescriptor, Long> configuration = new LinkedHashMap<>();
        if (machines.configuration(node) >= 0)
            for (int entry = stock.first(machines.configuration(node)); entry >= 0; entry = stock.next(entry))
                configuration.put(descriptor(stock.resource(entry)), stock.amountAt(entry));
        Map<CloudworksRuntimeRecipe, Long> priorities = new IdentityHashMap<>();
        List<CloudworksRuntimeRecipe> selected = new ArrayList<>();
        for (int index = 0; index < recipes.length; index++) {
            CloudworksRuntimeRecipe recipe = recipes[index];
            if (!environmentMatches(node, recipe)) continue;
            long priority = recipe.selectionPriority;
            boolean accepted = true;
            if (program.hasRecipeCandidate[node] && recipe.definition != null
                    && recipe.configured(stock, machines.configuration(node))) {
                RecipeCandidateContext context = new RecipeCandidateContext(recipe.definition, index,
                        configuration, environment(node));
                for (CloudworksModuleBinding binding : program.modules[node]) {
                    if (!binding.callbacks.candidate) continue;
                    RecipeCandidateDecision decision = binding.candidate(context);
                    accepted &= decision.accepted();
                    priority = Math.addExact(priority, decision.priority());
                }
            }
            if (accepted) { selected.add(recipe); priorities.put(recipe, priority); }
        }
        selected.sort(Comparator.comparingLong((CloudworksRuntimeRecipe recipe) -> priorities.get(recipe)).reversed());
        Set<String> groups = new HashSet<>();
        selected.removeIf(recipe -> !recipe.selectionGroup.isEmpty() && !groups.add(recipe.selectionGroup));
        CloudworksRuntimeRecipe[] result = selected.toArray(new CloudworksRuntimeRecipe[0]);
        if (!program.hasRecipeCandidate[node]) planningCache.candidates(node, recipes, program.environmentContexts[node], result);
        return result;
    }
    private boolean environmentMatches(int node, CloudworksRuntimeRecipe recipe) {
        return recipe.requiredEnvironmentAxis == null || program.environmentContexts[node].hasEnvironment(recipe.requiredEnvironmentAxis);
    }
    private boolean batchEnvironmentReady(int node, CloudworksRuntimeBatch batch) {
        return program.environmentReady[node] && (batch.requiredEnvironmentAxis == null
                || program.environmentContexts[node].hasEnvironment(batch.requiredEnvironmentAxis));
    }

    static int configuredRate(boolean configured, int duration, double machineLoad, boolean recipeEnergy) {
        return configured && duration <= 0 && (machineLoad > 0 || recipeEnergy)
                ? (int) Math.min(Integer.MAX_VALUE, 1L - duration) : 1;
    }

    private byte recipeInputState(int node, CloudworksRuntimeRecipe recipe) {
        boolean any = false, all = true;
        for (int[] matches : recipe.matches) {
            boolean present = false;
            for (int resource : matches)
                if (stock.available(machines.input(node), resource) > 0) { present = true; break; }
            any |= present;
            all &= present;
        }
        return all ? CloudworksRuntimeMachineStore.INSUFFICIENT_INPUT
                : any ? CloudworksRuntimeMachineStore.MISSING_INPUT : CloudworksRuntimeMachineStore.NO_MATCHING_RECIPE;
    }

    private void decay(int node, int type) {
        int input = machines.input(node);
        for (CloudworksRuntimeRecipe recipe : program.recipes[type]) {
            if (recipe.decayAmount <= 0) continue;
            for (int resource : recipe.matches[0]) {
                long available = stock.available(input, resource);
                if (available <= 0) continue;
                int ticks = Math.max(1, recipe.duration);
                int progress = Math.min(machines.progress(node), ticks - 1);
                if (progress >= ticks - 1) {
                    long quota = CloudworksQuantities.multiplyLimit(recipe.decayAmount,
                            CloudworksQuantities.multiplyLimit(machines.machineCount(node), program.workRate));
                    stock.take(input, resource, Math.min(available, quota));
                    progress = 0;
                } else progress++;
                machines.setProgress(node, progress, ticks);
                machines.setState(node, CloudworksRuntimeMachineStore.DECAYING);
                return;
            }
        }
        machines.setState(node, stock.total(input) == 0 ? CloudworksRuntimeMachineStore.WAITING_INPUT
                : CloudworksRuntimeMachineStore.DECAY_BLOCKED);
    }

    private byte generatorWaitingState(int node) {
        if (program.generationPlans[node].source() == CloudworksGenerationPlan.Source.UNSUPPORTED)
            return CloudworksRuntimeMachineStore.UNSUPPORTED_RECIPE;
        if (!program.generationPlans[node].active(clock)) return CloudworksRuntimeMachineStore.WAITING_TIME;
        if (generationLimit(node) <= 0) return CloudworksRuntimeMachineStore.WAITING_OUTPUT;
        int type = machines.machineType(node);
        if (program.heat[type] != null) return CloudworksRuntimeMachineStore.WAITING_HEAT;
        if (program.fuels[type].length > 0) {
            if (stock.total(machines.input(node)) == 0) return CloudworksRuntimeMachineStore.WAITING_FUEL;
            for (CloudworksRuntimeRecipe fuel : program.fuels[type]) {
                if (fuel.allocate(stock, machines.input(node), 1) != null && !worstOutputsFit(node, fuel, 1))
                    return CloudworksRuntimeMachineStore.WAITING_OUTPUT;
                for (int[] ingredient : fuel.matches)
                    for (int resource : ingredient)
                        if (stock.available(machines.input(node), resource) > 0)
                            return CloudworksRuntimeMachineStore.INSUFFICIENT_FUEL;
            }
            return CloudworksRuntimeMachineStore.NO_MATCHING_FUEL;
        }
        CloudworksGenerationPlan plan = program.generationPlans[node];
        switch (plan.source()) {
            case UNSUPPORTED: return CloudworksRuntimeMachineStore.UNSUPPORTED_RECIPE;
            case SOLAR: return plan.peak() > 0 ? CloudworksRuntimeMachineStore.WAITING_SUNLIGHT
                    : CloudworksRuntimeMachineStore.NO_GENERATION;
            case WIND: return CloudworksRuntimeMachineStore.WAITING_WIND;
            case HEAT: return CloudworksRuntimeMachineStore.WAITING_HEAT;
            default: return CloudworksRuntimeMachineStore.NO_GENERATION;
        }
    }

    private double burnFuel(int node) {
        int type = machines.machineType(node), input = machines.input(node);
        double limit = generationLimit(node);
        if (limit <= 0) return 0;
        double result = Math.min(limit, program.generationPlans[node].generation(clock));
        long units = CloudworksQuantities.multiplyLimit(
                CloudworksQuantities.multiplyLimit(machines.machineCount(node), program.threads[node]), program.workRate);
        CloudworksRuntimeHeatFuel heat = program.heat[type];
        if (heat != null) return finiteSum(result, burnHeat(node, heat, units, Math.max(0, limit - result)));
        int remainingUnits = (int) Math.min(Integer.MAX_VALUE, units);
        CloudworksRuntimeRecipe activeCredit = selectCreditProcess(node, program.fuels[type]);
        for (CloudworksRuntimeRecipe fuel : program.fuels[type]) {
            if (remainingUnits <= 0) break;
            if (!environmentMatches(node, fuel)
                    || fuel.sunlightRequired
                        && CloudworksGenerationPlan.sunlightBlocked(
                                clock, program.environmentContexts[node])
                    || !fuel.configured(stock, machines.configuration(node))) continue;
            if (fuel.prepaidFuelUnits > 0) {
                if (fuel != activeCredit) continue;
                int count = (int) Math.min(remainingUnits, Math.max(0, Math.floor((limit - result) / fuel.fuelEnergy)));
                double credit = machines.processCredit(node);
                long available = primaryAvailable(node, fuel);
                count = (int) Math.min(count, Math.min(Integer.MAX_VALUE, credit + available * (double) fuel.prepaidFuelUnits));
                int low = 0, high = count;
                while (low < high) {
                    int middle = low + (int) (((long) high - low + 1) / 2);
                    if (fuel.allocatePrepaid(stock, input, middle, credit) != null) low = middle;
                    else high = middle - 1;
                }
                count = low;
                if (count > 0) {
                    long physical = (long) Math.ceil(Math.max(0, count - credit) / fuel.prepaidFuelUnits);
                    CloudworksRuntimeRecipe.Allocation consumed = fuel.allocatePrepaid(stock, input, count, credit);
                    for (int index = 0; index < consumed.ids.length; index++) stock.take(input, consumed.ids[index], consumed.amounts[index]);
                    machines.processCredit(node, credit + physical * (double) fuel.prepaidFuelUnits - count);
                    result = finiteSum(result, count * fuel.fuelEnergy); remainingUnits -= count;
                }
                continue;
            }
            int count = remainingUnits;
            if (Double.isFinite(limit))
                count = (int) Math.min(count, Math.max(0, Math.floor((limit - result) / fuel.fuelEnergy)));
            if (fuel.demands.length == 1) {
                long available = 0;
                for (int resource : fuel.matches[0])
                    available = CloudworksQuantities.addLimit(available, stock.available(input, resource));
                count = (int) Math.min(count, available / fuel.demands[0]);
            }
            if (count > 0 && (fuel.demands.length != 1 || !fuelOutputsFit(node, fuel, count, result))) {
                int low = 0, high = count;
                while (low < high) {
                    int middle = low + (int) (((long) high - low + 1) / 2);
                    if (fuel.allocate(stock, input, middle) != null && fuelOutputsFit(node, fuel, middle, result)) low = middle;
                    else high = middle - 1;
                }
                count = low;
            }
            if (count <= 0) continue;
            double generation = fuel.fuelEnergy * count;
            if (!Double.isFinite(generation)) throw new IllegalStateException("Runtime generation overflow");
            CloudworksRuntimeRecipe.Allocation consumed = fuel.allocate(stock, input, count);
            CloudworksRuntimeRecipe.Allocation output = fuel.outputs(count, CloudworksOutputMultiplier.SCALE, outputSeed(node));
            for (int index = 0; index < consumed.ids.length; index++) stock.take(input, consumed.ids[index], consumed.amounts[index]);
            for (int index = 0; index < output.ids.length; index++) stock.add(machines.output(node), output.ids[index], output.amounts[index]);
            machines.advanceOutputSequence(node);
            remainingUnits -= count;
            result = finiteSum(result, generation);
        }
        return result;
    }

    private double burnHeat(int node, CloudworksRuntimeHeatFuel heat, long machinesCount, double limit) {
        if (heat.incrementalGeneration > 0 && Double.isFinite(limit))
            machinesCount = Math.min(machinesCount, (long) Math.max(0, Math.floor(limit / heat.incrementalGeneration)));
        int input = machines.input(node), output = machines.output(node), fluidCodec = program.resourceCodec[heat.lava];
        CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(node)];
            long capacity = CloudworksQuantities.multiplyLimit(type.inputCapacity[fluidCodec],
                    machines.machineCount(node) * program.nodeBufferMultiplier[node]);
        for (CloudworksRuntimeHeatFuel.Fuel fuel : heat.fuels) {
            if (fuel.units <= 0) continue;
            long room = Math.max(0, capacity - codecTotal(input, fluidCodec));
            long consumed = Math.min(stock.available(input, fuel.resource), room / fuel.units);
            if (fuel.container >= 0) {
                int codec = program.resourceCodec[fuel.container];
                long outputRoom = CloudworksQuantities.multiplyLimit(type.outputCapacity[codec],
                        machines.machineCount(node) * program.nodeBufferMultiplier[node]) - codecTotal(output, codec);
                consumed = Math.min(consumed, Math.max(0, outputRoom) / fuel.containerCount);
            }
            if (consumed <= 0) continue;
            long produced = Math.multiplyExact(consumed, fuel.units);
            // Capacity checks precede all writes; containers share normal output backpressure.
            stock.take(input, fuel.resource, consumed);
            stock.add(input, heat.lava, produced);
            if (fuel.container >= 0)
                stock.add(output, fuel.container, Math.multiplyExact(consumed, fuel.containerCount));
        }
        if (heat.incrementalGeneration > 0)
            machinesCount = Math.min(machinesCount, (long) Math.max(0,
                    Math.floor(Math.min(limit, generationLimit(node)) / heat.incrementalGeneration)));
        long burning = Math.min(machinesCount, stock.available(input, heat.lava) / heat.rate);
        double generated = heat.incrementalGeneration * burning;
        if (!Double.isFinite(generated)) throw new IllegalStateException("Runtime heat generation overflow");
        stock.take(input, heat.lava, Math.multiplyExact(burning, heat.rate));
        return generated;
    }

    private void advancePowerProcesses() {
        for (int node = 0; node < machines.size(); node++) {
            if (operations.batchCount(node) > 0) continue;
            CloudworksRuntimeRecipe recipe = selectCreditProcess(node, program.recipes[machines.machineType(node)]);
            if (recipe == null || !machines.active(node)) continue;
            if (recipe.prepaidFuelUnits > 0 && recipe.fuelEnergy == 0) {
                if (continuousProcessing(node)) advancePrepaidOutputs(node, recipe);
                continue;
            }
            if (recipe.energyPerInput <= 0) continue;
            if (!program.environmentReady[node] || !environmentMatches(node, recipe)) {
                machines.setState(node, CloudworksRuntimeMachineStore.WAITING_ENVIRONMENT); continue;
            }
            int input = machines.input(node), output = machines.output(node), line = machines.line(node);
            double credit = machines.processCredit(node), fraction = credit - Math.floor(credit);
            int inputResource = recipe.matches[0][0], outputResource = recipe.outputIds[0];
            long capacity = CloudworksQuantities.multiplyLimit(program.types[machines.machineType(node)]
                    .outputCapacity[program.resourceCodec[outputResource]], machines.machineCount(node) * program.nodeBufferMultiplier[node]);
            long room = Math.max(0, capacity - codecTotal(output, program.resourceCodec[outputResource]));
            long outputBatch = recipe.outputAmounts[0];
            double available = stock.available(input, inputResource) / recipe.demands[0] - fraction;
            double processable = Math.min(available, (room / outputBatch) * (double) recipe.unitsPerOutput - credit);
            double power = energy.unlimited() ? Double.MAX_VALUE : energy.lineEnergy(line) + energy.cloudEnergy();
            if (recipe.extraEnergyPerTick > 0)
                power = Math.min(power, recipe.extraEnergyPerTick * machines.machineCount(node) * program.threads[node] * program.workRate);
            double processed = Math.max(0, Math.min(processable, power / recipe.energyPerInput));
            if (processed > 0) {
                double next = credit + processed;
                long consumed = (long) Math.floor(next) - (long) Math.floor(credit);
                long produced = (long) Math.floor(next / recipe.unitsPerOutput);
                if (!drawThermalPower(line, processed * recipe.energyPerInput)) throw new IllegalStateException("Power conversion payment changed");
                stock.take(input, inputResource, Math.multiplyExact(consumed, recipe.demands[0]));
                if (produced > 0) stock.add(output, outputResource, Math.multiplyExact(produced, outputBatch));
                machines.processCredit(node, next - produced * (double) recipe.unitsPerOutput);
                machines.setState(node, CloudworksRuntimeMachineStore.RUNNING);
            } else machines.setState(node, room < outputBatch ? CloudworksRuntimeMachineStore.WAITING_OUTPUT
                    : available <= 0 ? CloudworksRuntimeMachineStore.WAITING_INPUT : CloudworksRuntimeMachineStore.WAITING_POWER);
            int duration = (int) Math.min(Integer.MAX_VALUE, recipe.unitsPerOutput);
            int progress = (int) Math.min(duration - 1D, Math.floor(machines.processCredit(node) / recipe.unitsPerOutput * duration));
            machines.setProgress(node, progress, duration);
        }
    }

    /** Paid progress pins its recipe; an empty converter can select another available input. */
    private CloudworksRuntimeRecipe selectCreditProcess(int node, CloudworksRuntimeRecipe[] candidates) {
        CloudworksRuntimeRecipe current = program.creditProcesses[node];
        if (current == null || machines.processCredit(node) > 0 || machines.progress(node) > 0) return current;
        for (CloudworksRuntimeRecipe candidate : candidates) {
            if (candidate.energyPerInput <= 0 && candidate.prepaidFuelUnits <= 0) continue;
            if (!environmentMatches(node, candidate)
                    || primaryAvailable(node, candidate) == 0) continue;
            program.creditProcesses[node] = candidate;
            program.creditKeys[node] = program.creditPrefixes[node] + candidate.recipeId;
            return candidate;
        }
        return current;
    }

    private long primaryAvailable(int node, CloudworksRuntimeRecipe recipe) {
        long available = 0;
        for (int resource : recipe.matches[0])
            available = CloudworksQuantities.addLimit(available, stock.available(machines.input(node), resource));
        return available / recipe.demands[0];
    }

    private boolean continuousProcessing(int node) {
        CloudworksRuntimeRecipe recipe = program.creditProcesses[node];
        if (recipe == null) return false;
        if (recipe.energyPerInput > 0) return true;
        return operations.batchCount(node) == 0 && recipe.prepaidFuelUnits > 0 && recipe.fuelEnergy == 0
                && (machines.processCredit(node) > 0 || machines.progress(node) > 0
                    || primaryAvailable(node, recipe) > 0 && recipe.configured(stock, machines.configuration(node)));
    }

    private void advancePrepaidOutputs(int node, CloudworksRuntimeRecipe recipe) {
        if (!recipe.configured(stock, machines.configuration(node))) {
            machines.setState(node, CloudworksRuntimeMachineStore.WAITING_TEMPLATE);
            return;
        }
        if (!program.environmentReady[node] || !environmentMatches(node, recipe)) {
            machines.setState(node, CloudworksRuntimeMachineStore.WAITING_ENVIRONMENT);
            return;
        }
        double credit = machines.processCredit(node);
        long available = primaryAvailable(node, recipe);
        long units = CloudworksQuantities.multiplyLimit(CloudworksQuantities.multiplyLimit(
                machines.machineCount(node), program.threads[node]), recipe.operationsPerCycle);
        int low = 0, high = (int) Math.min(Integer.MAX_VALUE, Math.min(units * (double) program.workRate,
                credit + available * (double) recipe.prepaidFuelUnits));
        boolean blocked = !worstOutputsFit(node, recipe, 1);
        while (low < high) {
            int middle = low + (int) (((long) high - low + 1) / 2);
            if (recipe.allocatePrepaid(stock, machines.input(node), middle, credit) != null
                    && worstOutputsFit(node, recipe, middle)) low = middle;
            else high = middle - 1;
        }
        if (low == 0) {
            machines.setState(node, blocked ? CloudworksRuntimeMachineStore.WAITING_OUTPUT : CloudworksRuntimeMachineStore.WAITING_INPUT);
            return;
        }
        long prepaid = (long) Math.ceil(Math.max(0D, low - credit) / recipe.prepaidFuelUnits);
        long primary = Math.multiplyExact(prepaid, recipe.demands[0]);
        for (int resource : recipe.matches[0]) {
            long take = Math.min(primary, stock.available(machines.input(node), resource));
            stock.take(machines.input(node), resource, take);
            primary -= take;
        }
        credit += prepaid * (double) recipe.prepaidFuelUnits;
        machines.processCredit(node, credit);
        int baseDuration = recipe.duration > 0 ? recipe.duration : program.baseTicks[node];
        ModuleWork work = moduleWork(node, recipe, low, baseDuration);
        int duration = CloudworksUpgradeEffects.ticks(baseDuration, program.timeMultiplier[node] * work.time,
                program.configuredUpgrades[node]);
        if (machines.progress(node) < duration - 1) {
            machines.setProgress(node, machines.progress(node) + 1, duration);
            machines.setState(node, CloudworksRuntimeMachineStore.RUNNING);
            return;
        }
        CloudworksRuntimeRecipe.Allocation input = recipe.allocatePrepaid(stock, machines.input(node), low, credit);
        CloudworksRuntimeRecipe.Allocation output = recipe.outputs(low, CloudworksOutputMultiplier.SCALE, outputSeed(node));
        long charges = (long) Math.ceil(Math.max(0D, low - credit) / recipe.prepaidFuelUnits);
        for (int index = 0; index < input.ids.length; index++) stock.take(machines.input(node), input.ids[index], input.amounts[index]);
        for (int index = 0; index < output.ids.length; index++) stock.add(machines.output(node), output.ids[index], output.amounts[index]);
        machines.processCredit(node, credit + charges * (double) recipe.prepaidFuelUnits - low);
        machines.advanceOutputSequence(node);
        machines.setProgress(node, 0, duration);
        machines.setState(node, CloudworksRuntimeMachineStore.RUNNING);
    }

    /** Conservative backpressure precedes fuel/heat consumption, including codec-shared output capacity. */
    double generationLimit(int node) {
        if (program.generationPlans[node].source() == CloudworksGenerationPlan.Source.UNSUPPORTED) return 0;
        if (!program.generationPlans[node].active(clock)) return 0;
        int[] resources = program.generationResources[node];
        double[] rates = program.generationRates[node];
        if (resources.length == 0) return Double.POSITIVE_INFINITY;
        double result = Double.POSITIVE_INFINITY;
        CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(node)];
        long units = CloudworksQuantities.multiplyLimit(machines.machineCount(node), program.nodeBufferMultiplier[node]);
        double[] byCodec = program.generationCodecRates[node];
        Arrays.fill(generationCredits, 0);
        double credits = 0;
        for (int output = 0; output < resources.length; output++) if (rates[output] > 0) {
            double value = machines.generationRemainder(node, output);
            generationCredits[program.resourceCodec[resources[output]]] += value;
            credits += value;
        }
        for (int codec = 0; codec < byCodec.length; codec++) if (byCodec[codec] > 0) {
            long room = Math.max(0, CloudworksQuantities.multiplyLimit(type.outputCapacity[codec], units)
                    - codecTotal(machines.output(node), codec));
            result = Math.min(result, Math.max(0, room - generationCredits[codec]) / byCodec[codec]);
        }
        double totalRate = program.generationTotalRates[node];
        if (totalRate > 0)
            result = Math.min(result, Math.max(0, Long.MAX_VALUE - stock.total(machines.output(node)) - credits) / totalRate);
        return Math.max(0, result);
    }

    double convertGeneration(int node, double rawEnergy) {
        if (!Double.isFinite(rawEnergy) || rawEnergy < 0) throw new IllegalArgumentException("Invalid raw generation");
        if (rawEnergy == 0) return 0;
        int[] resources = program.generationResources[node];
        if (resources.length == 0) return rawEnergy * program.generationPlans[node].electricityFraction();
        Arrays.fill(outputByCodec, 0);
        for (int output = 0; output < resources.length; output++) {
            double rate = program.generationRates[node][output];
            double quantity = rawEnergy * rate + machines.generationRemainder(node, output);
            if (!Double.isFinite(quantity) || quantity >= Long.MAX_VALUE)
                throw new IllegalStateException("Generation resource overflow");
            long whole = (long) Math.floor(quantity);
            generationAmounts[output] = whole;
            nextGenerationRemainders[output] = quantity - whole;
            addOutput(resources[output], whole);
        }
        if (resources.length > 0 && !codecOutputsFit(node))
            throw new IllegalStateException("Generation output backpressure violated");
        for (int output = 0; output < resources.length; output++) {
            if (generationAmounts[output] > 0) stock.add(machines.output(node), resources[output], generationAmounts[output]);
            machines.generationRemainder(node, output, nextGenerationRemainders[output]);
        }
        return rawEnergy * program.generationPlans[node].electricityFraction();
    }

    private boolean worstOutputsFit(int node, CloudworksRuntimeRecipe recipe, int count) {
        return worstOutputsFit(node, recipe, count, CloudworksOutputMultiplier.SCALE,
                program.secondaryChanceMultiplier[node]);
    }

    private boolean worstOutputsFit(int node, CloudworksRuntimeRecipe recipe, int count, int multiplierUnits) {
        return worstOutputsFit(node, recipe, count, multiplierUnits, program.secondaryChanceMultiplier[node]);
    }

    private boolean worstOutputsFit(int node, CloudworksRuntimeRecipe recipe, int count,
            int multiplierUnits, double chanceMultiplier) {
        Arrays.fill(outputByCodec, 0);
        try {
            for (int index = 0; index < recipe.outputIds.length; index++)
                addOutput(recipe.outputIds[index], CloudworksOutputMultiplier.maximum(recipe.outputAmounts[index],
                        recipe.scalableOutputs[index] ? multiplierUnits : CloudworksOutputMultiplier.SCALE, count));
            int secondaryUnits = recipe.scalableSecondary ? multiplierUnits : CloudworksOutputMultiplier.SCALE;
            if (!program.completionDiscardsChance[node]) {
                for (int index = 0; index < recipe.chanceIds.length; index++)
                    if (recipe.chances[index] > 0)
                        addOutput(recipe.chanceIds[index], Math.multiplyExact(recipe.chanceAmounts[index],
                        Math.multiplyExact((long) Math.ceil(Math.min(1D, recipe.chances[index] * chanceMultiplier)
                                * secondaryUnits
                                / (double) CloudworksOutputMultiplier.SCALE), count)));
            }
            if (recipe.choiceIds.length > 0)
                addOutput(recipe.choiceIds[0], CloudworksOutputMultiplier.maximum(recipe.choiceAmount, secondaryUnits, count));
        } catch (ArithmeticException overflow) { return false; }
        return promisedOutputsFit(node);
    }

    private boolean fuelOutputsFit(int node, CloudworksRuntimeRecipe recipe, int count, double ambient) {
        if (!worstOutputsFit(node, recipe, count)) return false;
        double raw = ambient + recipe.fuelEnergy * count;
        if (!Double.isFinite(raw)) return false;
        try {
            for (int output = 0; output < program.generationResources[node].length; output++) {
                double value = raw * program.generationRates[node][output] + machines.generationRemainder(node, output);
                if (!Double.isFinite(value) || value >= Long.MAX_VALUE) return false;
                addOutput(program.generationResources[node][output], (long) Math.floor(value));
            }
        } catch (ArithmeticException overflow) { return false; }
        return codecOutputsFit(node);
    }

    private long outputSeed(int node) {
        return CloudworksOutputMultiplier.mix(program.nodeOutputSeeds[node]
                ^ operations.nextSequence(node, machines.outputSequence(node)));
    }

    private boolean outputsFit(int node, CloudworksRuntimeRecipe.Allocation output) {
        Arrays.fill(outputByCodec, 0);
        try {
            for (int index = 0; index < output.ids.length; index++) addOutput(output.ids[index], output.amounts[index]);
        } catch (ArithmeticException overflow) { return false; }
        return codecOutputsFit(node);
    }

    private void addOutput(int resource, long amount) {
        int codec = program.resourceCodec[resource];
        outputByCodec[codec] = Math.addExact(outputByCodec[codec], amount);
    }

    private boolean promisedOutputsFit(int node) {
        int root = program.physicalRoot[node];
        return promises.addTo(outputByCodec, root, program.physicalEnd[root], operations, program.resourceCodec)
                && codecOutputsFit(node);
    }

    private boolean codecOutputsFit(int node) {
        CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(node)];
        long total = 0;
        for (int codec = 0; codec < outputByCodec.length; codec++) {
            long capacity = CloudworksQuantities.multiplyLimit(type.outputCapacity[codec],
                    machines.machineCount(node) * program.nodeBufferMultiplier[node]);
            if (outputByCodec[codec] > capacity - codecTotal(machines.output(node), codec)) return false;
            if (outputByCodec[codec] > Long.MAX_VALUE - total) return false;
            total += outputByCodec[codec];
        }
        return total <= Long.MAX_VALUE - stock.total(machines.output(node));
    }

    private boolean outputsFitForPlan(int node, CloudworksRuntimeRecipe recipe,
                                     CloudworksRuntimeRecipe.Allocation outputs) {
        if (!program.completionDiscardsChance[node]) return outputsFit(node, outputs) && promisedOutputsFit(node);
        Set<Integer> primary = new HashSet<>();
        for (int id : recipe.outputIds) primary.add(id);
        int count = 0;
        for (int index = 0; index < outputs.ids.length; index++)
            if (!contains(recipe.chanceIds, outputs.ids[index]) || primary.contains(outputs.ids[index])) count++;
        int[] ids = new int[count];
        long[] amounts = new long[count];
        int write = 0;
        for (int index = 0; index < outputs.ids.length; index++)
            if (!contains(recipe.chanceIds, outputs.ids[index]) || primary.contains(outputs.ids[index])) {
                ids[write] = outputs.ids[index];
                amounts[write++] = outputs.amounts[index];
            }
        return outputsFit(node, new CloudworksRuntimeRecipe.Allocation(ids, amounts)) && promisedOutputsFit(node);
    }

    private static boolean contains(int[] values, int value) {
        for (int candidate : values) if (candidate == value) return true;
        return false;
    }

    private long codecTotal(int inventory, int codec) {
        return stock.groupTotal(inventory, codec);
    }

    private void settleAndAdvance() {
        for (int line = 0; line < pools.length; line++) {
            pools[line] = energy.lineEnergy(line);
            capacities[line] = energy.lineCapacity(line);
        }
        CloudworksEnergyBalancer.Settlement settlement = CloudworksEnergyBalancer.calculate(
                energy.cloudEnergy(), energy.cloudCapacity(), energy.unlimited(), pools, capacities, loads, generation);
        energy.setCloud(settlement.cloudEnergy(), energy.cloudCapacity(), energy.unlimited());
        for (int line = 0; line < pools.length; line++) energy.setLineEnergy(line, settlement.lineEnergy(line));
        for (int line = 0; line < thermalPowered.length; line++) thermalPowered[line] = settlement.powered(line);
        thermal.powered(thermalPowered);
        for (int node = 0; node < machines.size(); node++) {
            if (!machines.active(node) || program.generator[node]) continue;
            List<CloudworksRuntimeBatch> batches = operations.batches(node);
            int index = 0;
            while (index < batches.size()) {
                CloudworksRuntimeBatch batch = batches.get(index);
                operations.select(node, batch);
                if (!batchEnvironmentReady(node, batch)) {
                    machines.setState(node, CloudworksRuntimeMachineStore.WAITING_ENVIRONMENT);
                    index++;
                    continue;
                }
                if (!settlement.powered(machines.line(node))) {
                    machines.setState(node, CloudworksRuntimeMachineStore.WAITING_POWER);
                    finishModuleCosts(node, false);
                    if (batch.reservedEarly) operations.cancel(node, machines.input(node), stock);
                    else if (!operations.started(node)) operations.clear(node);
                    index++;
                    continue;
                }
                boolean beginning = batch.reservedEarly || !operations.started(node);
                if (!operations.started(node)) operations.start(node, machines.input(node), stock);
                if (beginning) {
                    if (batch.sequence < 0) machines.advanceOutputSequence(node);
                    else machines.claimOutputSequence(node, Math.addExact(batch.sequence, 1));
                }
                finishModuleCosts(node, true);
                batch.reservedEarly = false;
                if (beginning && program.hasWorkStarted[node]) for (CloudworksModuleBinding binding : program.modules[node])
                    if (binding.callbacks.started)
                    runtime.moduleEvent(new CloudworksModuleEvents.Event(binding,
                            new WorkStartedContext(operations.recipeId(node), operations.lifecycleId(node),
                                    operations.operations(node), operations.duration(node), machines.machineCount(node), environment(node))));
                int duration = operations.duration(node);
                if (machines.progress(node) >= duration - 1) {
                    if (!operations.completionPrepared(node)) {
                        if (!program.postWorkModules[node].isEmpty())
                            operations.replaceOutputs(node, applyModuleOutputs(node, operations.outputs(node)));
                        operations.prepareCompletion(node);
                    }
                    if (outputsFit(node, operations.outputs(node))) {
                        List<MachineOutput> committed = program.hasWorkCommitted[node]
                                ? outputList(operations.outputs(node)) : Collections.emptyList();
                        String recipeId = operations.recipeId(node);
                        int count = operations.operations(node);
                        long operationId = operations.lifecycleId(node);
                        operations.complete(node, machines.input(node), machines.output(node), stock);
                        if (program.hasWorkCommitted[node]) for (CloudworksModuleBinding binding : program.modules[node])
                            if (binding.callbacks.committed)
                            runtime.moduleEvent(new CloudworksModuleEvents.Event(binding,
                                    new WorkCommittedContext(recipeId, operationId, count, committed)));
                        machines.setProgress(node, 0, duration);
                        machines.setState(node, CloudworksRuntimeMachineStore.READY);
                    } else {
                        machines.setProgress(node, duration - 1, duration);
                        machines.setState(node, CloudworksRuntimeMachineStore.WAITING_OUTPUT);
                    }
                } else {
                    machines.setProgress(node, machines.progress(node) + 1, duration);
                    machines.setState(node, CloudworksRuntimeMachineStore.RUNNING);
                }
                index++;
            }
            operations.resetSelection(node);
            if (!batches.isEmpty()) {
                byte state = batches.get(0).state;
                for (CloudworksRuntimeBatch batch : batches)
                    if (batch.state == CloudworksRuntimeMachineStore.RUNNING) state = batch.state;
                machines.setAggregateState(node, state);
            }
        }
    }

    private CloudworksRuntimeRecipe.Allocation applyModuleOutputs(int node,
            CloudworksRuntimeRecipe.Allocation allocation) {
        List<MachineOutput> planned = outputList(allocation);
        Map<QIOResourceDescriptor, Long> stored = new LinkedHashMap<>();
        int inventory = machines.output(node);
        for (int entry = stock.first(inventory); entry >= 0; entry = stock.next(entry)) {
            QIOResourceDescriptor descriptor = descriptor(stock.resource(entry));
            if (descriptor != null) stored.put(descriptor, stock.amountAt(entry));
        }
        return transformModuleOutputs(node, planned, stored);
    }

    private List<MachineOutput> outputList(CloudworksRuntimeRecipe.Allocation allocation) {
        List<MachineOutput> planned = new ArrayList<>();
        for (int index = 0; index < allocation.ids.length; index++) {
            QIOResourceDescriptor descriptor = descriptor(allocation.ids[index]);
            if (descriptor != null)
                planned.add(new MachineOutput(descriptor, allocation.amounts[index], "",
                        allocation.chance[index]));
        }
        return planned;
    }

    private CloudworksRuntimeRecipe.Allocation transformModuleOutputs(int node, List<MachineOutput> planned,
            Map<QIOResourceDescriptor, Long> stored) {
        CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(node)];
        PostWorkContext context = new PostWorkContext(operations.recipeId(node),
                operations.lifecycleId(node), operations.operations(node), program.completionMachineCounts[node],
                program.completionUpgrades[node], stored, program.postWorkCapacities[node], environment(node));
        List<MachineOutput> transformed = planned;
        for (CloudworksModuleBinding binding : program.postWorkModules[node])
            transformed = binding.post(context, transformed);
        Map<Integer, Long> merged = new LinkedHashMap<>();
        Map<Integer, Boolean> mergedChance = new LinkedHashMap<>();
        for (MachineOutput output : transformed) {
            if (output == null) throw new IllegalArgumentException("Module returned null output");
            int resource = resourceId(output.descriptor());
            if (resource < 0) throw new IllegalArgumentException("Module returned undeclared resource; declare outputResources()");
            boolean validPort = false;
            for (int port = 0; port < type.portNames.length; port++)
                if (!type.input[port] && !type.configuration[port]
                        && program.resourceCodec[resource] == type.portCodec[port]
                        && (output.port().isEmpty() || output.port().equals(type.portNames[port]))) validPort = true;
            if (!validPort) throw new IllegalArgumentException("Module output has no matching declared output port");
            merged.merge(resource, output.amount(), Math::addExact);
            mergedChance.merge(resource, output.chanceOutput(), (left, right) -> left || right);
        }
        int[] ids = new int[merged.size()];
        long[] amounts = new long[merged.size()];
        boolean[] chance = new boolean[merged.size()];
        int index = 0;
        for (Map.Entry<Integer, Long> entry : merged.entrySet()) {
            ids[index] = entry.getKey();
            amounts[index++] = entry.getValue();
        }
        for (int i = 0; i < ids.length; i++) chance[i] = mergedChance.getOrDefault(ids[i], false);
        return new CloudworksRuntimeRecipe.Allocation(ids, amounts, chance);
    }

    private QIOResourceDescriptor descriptor(int resource) {
        return resource >= 0 && resource < program.resourceDescriptors.size()
                ? program.resourceDescriptors.get(resource) : null;
    }
    private CloudworksGenerationContext environment(int node) {
        CloudworksGenerationContext base = program.environmentContexts[node];
        return new CloudworksGenerationContext(base.simulatedHeight(), clock,
                base.lavaHeatCards(), base.environmentTypes());
    }

    private int resourceId(QIOResourceDescriptor descriptor) {
        return program.resourceDescriptors.indexOf(descriptor);
    }

    private void routeEnergy() {
        double cloud = energy.cloudEnergy(), receive = energy.receivingEnergy(), send = energy.sendingEnergy();
        if (program.receivingToCloud && !energy.unlimited()) {
            double moved = Math.min(receive, energy.cloudCapacity() - cloud);
            receive -= moved;
            cloud += moved;
        }
        if (program.receivingToLines)
            for (int line = 0; line < loads.length && receive > 0; line++) {
                if (!program.receivingToLine[line]) continue;
                double moved = Math.min(receive, energy.lineCapacity(line) - energy.lineEnergy(line));
                energy.setLineEnergy(line, energy.lineEnergy(line) + moved);
                receive -= moved;
            }
        // Preserve existing gate order; generation settlement follows these relays.
        if (program.cloudToSending && !energy.unlimited()) {
            double moved = Math.min(cloud, energy.sendingCapacity() - send);
            cloud -= moved;
            send += moved;
        }
        if (program.linesToSending)
            for (int line = 0; line < loads.length && send < energy.sendingCapacity(); line++) {
                if (!program.lineToSending[line]) continue;
                double moved = Math.min(energy.lineEnergy(line), energy.sendingCapacity() - send);
                energy.setLineEnergy(line, energy.lineEnergy(line) - moved);
                send += moved;
            }
        energy.setCloud(cloud, energy.cloudCapacity(), energy.unlimited());
        energy.setRelays(receive, send, energy.receivingCapacity(), energy.sendingCapacity());
    }

    private void outflow() {
        for (int channel = 0; channel < channels.size(); channel++) {
            if (!channels.enabled(channel) || channels.input(channel)) continue;
            int destination = channels.transfer(channel) ? program.transfer : program.sending;
            long capacity = channels.transfer(channel) ? program.transferCapacity : program.sendingCapacity;
            int inventory = channels.inventory(channel);
            for (int entry = stock.first(inventory); entry >= 0; entry = stock.next(entry)) {
                int resource = stock.resource(entry);
                long room = Math.min(capacity - stock.total(destination),
                        program.resourceCap[resource] - stock.amount(destination, resource));
                long moved = Math.min(stock.availableAt(entry), Math.max(0, room));
                if (moved > 0) stock.move(inventory, destination, resource, moved);
            }
        }
    }


    private static double finiteSum(double first, double second) {
        double sum = first + second;
        if (!Double.isFinite(sum)) throw new IllegalStateException("Runtime energy overflow");
        return sum;
    }
}
