package mekanism.cloudworks.common.runtime;

import java.util.Arrays;
import java.util.SplittableRandom;
import mekanism.cloudworks.common.service.CloudworksEnergyBalancer;
import mekanism.cloudworks.common.service.CloudworksQuantities;
import mekanism.cloudworks.common.upgrade.CloudworksUpgradeEffects;

/** Persistent numerical systems for every active line in one player world. */
final class CloudworksRuntimeSimulation {
    private final CloudworksRuntimeProgram program;
    private final CloudworksRuntimeResourceStore stock;
    private final CloudworksRuntimeMachineStore machines;
    private final CloudworksRuntimeChannelStore channels;
    private final CloudworksRuntimeLinkStore links;
    private final CloudworksRuntimeOperationStore operations;
    private final CloudworksRuntimeEnergyStore energy;
    private final double[] loads, generation, pools, capacities;
    private final double[] generated;
    private final long[] outputByCodec;
    private final SplittableRandom random;
    private int cursor;
    private int clock;
    private final CloudworksRuntimeThermalSystem thermal;
    private final boolean[] thermalPowered;

    CloudworksRuntimeSimulation(CloudworksPlayerRuntime runtime, CloudworksRuntimeProgram program) {
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
        random = new SplittableRandom(program.randomSeed);
        cursor = program.initialCursor;
        clock = program.initialClock;
        thermal = new CloudworksRuntimeThermalSystem(runtime, program, this);
        thermalPowered = new boolean[loads.length];
    }

    int cursor() { return cursor; }

    void syncClock(int clockTick) { clock = clockTick; }

    void tick() {
        intake();
        route(CloudworksRuntimeLinkStore.CHANNEL_INPUT);
        route(CloudworksRuntimeLinkStore.CHANNEL_TO_MACHINE);
        plan();
        settleAndAdvance();
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

    private void route(byte phase) {
        for (int link = 0; link < links.size(); link++) {
            if (links.phase(link) != phase) continue;
            int source = links.source(link), target = links.target(link), sourceInventory, targetInventory;
            boolean sourceMachine = phase == CloudworksRuntimeLinkStore.MACHINE_TO_MACHINE
                    || phase == CloudworksRuntimeLinkStore.MACHINE_TO_CHANNEL;
            boolean targetMachine = phase == CloudworksRuntimeLinkStore.MACHINE_TO_MACHINE
                    || phase == CloudworksRuntimeLinkStore.CHANNEL_TO_MACHINE;
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
        if (configuration && stock.total(inventory) > 0 && stock.amount(inventory, resource) == 0) return 0;
        if (program.decayTypes[machines.machineType(node)] && stock.total(inventory) > 0
                && stock.amount(inventory, resource) == 0) return 0;
        long capacity = CloudworksQuantities.multiplyLimit(
                configuration ? type.configurationCapacity[codec] : type.inputCapacity[codec], machines.machineCount(node));
        long codecRoom = capacity - codecTotal(inventory, codec);
        long portHeld = stock.groupTotal(inventory, program.codecCount + port);
        long portRoom = CloudworksQuantities.multiplyLimit(type.portCapacity[port], machines.machineCount(node)) - portHeld;
        return Math.max(0, Math.min(codecRoom, portRoom));
    }

    private void convertInfuse(int node) {
        if (program.infuseCodec < 0) return;
        CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(node)];
        long capacity = CloudworksQuantities.multiplyLimit(type.inputCapacity[program.infuseCodec], machines.machineCount(node));
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
        Arrays.fill(loads, 0);
        Arrays.fill(generation, 0);
        Arrays.fill(generated, 0);
        thermal.produce(clock);
        for (int node = 0; node < machines.size(); node++) {
            if (!machines.active(node)) {
                machines.setState(node, CloudworksRuntimeMachineStore.OFF);
                continue;
            }
            int line = machines.line(node), type = machines.machineType(node);
            loads[line] = finiteSum(loads[line], program.machineLoad[node]);
            if (program.thermalPlans[node] != null) continue;
            convertInfuse(node);
            if (program.decayTypes[type]) {
                decay(node, type);
                continue;
            }
            if (!program.executable[node]) {
                machines.setState(node, CloudworksRuntimeMachineStore.NO_RECIPE);
                continue;
            }
            if (program.generator[node]) {
                generated[node] = burnFuel(node);
                generation[line] = finiteSum(generation[line], generated[node]);
                machines.setState(node, generated[node] > 0 ? CloudworksRuntimeMachineStore.GENERATING
                        : generatorWaitingState(node));
                continue;
            }
            if (operations.present(node)) {
                addRecipeLoad(node);
                continue;
            }
            if (!program.supported[node]) {
                machines.setState(node, CloudworksRuntimeMachineStore.UNSUPPORTED_RECIPE);
                continue;
            }
            if (program.recipes[type].length == 0) {
                machines.setState(node, CloudworksRuntimeMachineStore.NO_RECIPE);
                continue;
            }
            int base = CloudworksQuantities.operationLimit(machines.machineCount(node),
                    program.threads[node], program.workRate);
            boolean planned = false, outputBlocked = false, validDimension = false, validSunlight = false, configured = false;
            boolean incompleteInputs = false, insufficientInputs = false;
            for (CloudworksRuntimeRecipe recipe : program.recipes[type]) {
                if (recipe.dimensionRequired && recipe.dimension != program.dimension[node]) continue;
                validDimension = true;
                if (recipe.sunlightRequired && mekanism.cloudworks.api.CloudworksGenerationPlan.solarBrightness(clock) <= 0)
                    continue;
                validSunlight = true;
                if (!recipe.configured(stock, machines.configuration(node))) continue;
                configured = true;
                int duration = CloudworksUpgradeEffects.ticks(recipe.duration > 0 ? recipe.duration : program.baseTicks[node],
                        program.timeMultiplier[line], program.configuredUpgrades[line]);
                int rate = program.configuredUpgrades[line] && duration <= 0 && program.machineLoad[node] > 0
                        ? (int) Math.min(Integer.MAX_VALUE, 1L - duration) : 1;
                int count = base;
                if (rate > 1) {
                    int low = 0, high = Math.min(rate, Integer.MAX_VALUE / base);
                    while (low < high) {
                        int middle = low + (int) (((long) high - low + 1) / 2);
                        if (recipe.allocate(stock, machines.input(node), base * middle) != null
                                && worstOutputsFit(node, recipe, base * middle)) low = middle;
                        else high = middle - 1;
                    }
                    count = base * low;
                }
                CloudworksRuntimeRecipe.Allocation allocation = recipe.allocate(stock, machines.input(node), count);
                if (allocation == null || count == 0) {
                    byte inputState = recipeInputState(node, recipe);
                    insufficientInputs |= inputState == CloudworksRuntimeMachineStore.INSUFFICIENT_INPUT;
                    incompleteInputs |= inputState == CloudworksRuntimeMachineStore.MISSING_INPUT;
                    if (count == 0 && !worstOutputsFit(node, recipe, base)
                            && recipe.allocate(stock, machines.input(node), base) != null) outputBlocked = true;
                    continue;
                }
                CloudworksRuntimeRecipe.Allocation outputs = recipe.outputs(count, random);
                if (!outputsFit(node, outputs)) { outputBlocked = true; continue; }
                operations.plan(node, count, Math.max(1, duration), allocation, outputs, recipe.extraEnergyPerTick);
                addRecipeLoad(node);
                planned = true;
                break;
            }
            if (!planned) {
                byte state = outputBlocked ? CloudworksRuntimeMachineStore.WAITING_OUTPUT
                        : !validDimension ? CloudworksRuntimeMachineStore.WRONG_DIMENSION
                        : !validSunlight ? CloudworksRuntimeMachineStore.WAITING_SUNLIGHT
                        : !configured ? CloudworksRuntimeMachineStore.WAITING_TEMPLATE
                        : stock.total(machines.input(node)) == 0 ? CloudworksRuntimeMachineStore.WAITING_INPUT
                        : insufficientInputs ? CloudworksRuntimeMachineStore.INSUFFICIENT_INPUT
                        : incompleteInputs ? CloudworksRuntimeMachineStore.MISSING_INPUT
                        : CloudworksRuntimeMachineStore.NO_MATCHING_RECIPE;
                machines.setState(node, state);
            }
        }
        thermal.consumeAndGenerate(generation);
    }

    int thermalRecipe(int node, CloudworksRuntimeRecipe recipe, int maximum) {
        if (recipe.dimensionRequired && recipe.dimension != program.dimension[node]
                || recipe.sunlightRequired && mekanism.cloudworks.api.CloudworksGenerationPlan.solarBrightness(clock) <= 0
                || !recipe.configured(stock, machines.configuration(node))) return 0;
        int low = 0, high = maximum;
        while (low < high) {
            int middle = low + (int) (((long) high - low + 1) / 2);
            if (recipe.allocate(stock, machines.input(node), middle) != null && worstOutputsFit(node, recipe, middle)) low = middle;
            else high = middle - 1;
        }
        if (low <= 0) return 0;
        CloudworksRuntimeRecipe.Allocation input = recipe.allocate(stock, machines.input(node), low);
        CloudworksRuntimeRecipe.Allocation output = recipe.outputs(low, random);
        for (int index = 0; index < input.ids.length; index++) stock.take(machines.input(node), input.ids[index], input.amounts[index]);
        for (int index = 0; index < output.ids.length; index++) stock.add(machines.output(node), output.ids[index], output.amounts[index]);
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

    private void addRecipeLoad(int node) {
        double extra = operations.extraEnergyPerTick(node) * program.energyMultiplier[machines.line(node)]
                * machines.machineCount(node) * program.threads[node] * program.workRate;
        loads[machines.line(node)] = finiteSum(loads[machines.line(node)], extra);
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
        mekanism.cloudworks.api.CloudworksGenerationPlan plan = program.generationPlans[node];
        switch (plan.source()) {
            case SOLAR: return plan.peak() > 0 ? CloudworksRuntimeMachineStore.WAITING_SUNLIGHT
                    : CloudworksRuntimeMachineStore.NO_GENERATION;
            case WIND: return CloudworksRuntimeMachineStore.WAITING_WIND;
            case HEAT: return CloudworksRuntimeMachineStore.WAITING_HEAT;
            default: return CloudworksRuntimeMachineStore.NO_GENERATION;
        }
    }

    private double burnFuel(int node) {
        int type = machines.machineType(node), input = machines.input(node);
        double result = program.generationPlans[node].generation(clock);
        long units = CloudworksQuantities.multiplyLimit(
                CloudworksQuantities.multiplyLimit(machines.machineCount(node), program.threads[node]), program.workRate);
        CloudworksRuntimeHeatFuel heat = program.heat[type];
        if (heat != null) return finiteSum(result, burnHeat(node, heat, units));
        int remainingUnits = (int) Math.min(Integer.MAX_VALUE, units);
        for (CloudworksRuntimeRecipe fuel : program.fuels[type]) {
            if (remainingUnits <= 0) break;
            if (fuel.dimensionRequired && fuel.dimension != program.dimension[node]
                    || fuel.sunlightRequired && mekanism.cloudworks.api.CloudworksGenerationPlan.solarBrightness(clock) <= 0
                    || !fuel.configured(stock, machines.configuration(node))) continue;
            int count = remainingUnits;
            if (fuel.demands.length == 1) {
                long available = 0;
                for (int resource : fuel.matches[0])
                    available = CloudworksQuantities.addLimit(available, stock.available(input, resource));
                count = (int) Math.min(count, available / fuel.demands[0]);
            }
            if (count > 0 && (fuel.demands.length != 1 || !worstOutputsFit(node, fuel, count))) {
                int low = 0, high = count;
                while (low < high) {
                    int middle = low + (int) (((long) high - low + 1) / 2);
                    if (fuel.allocate(stock, input, middle) != null && worstOutputsFit(node, fuel, middle)) low = middle;
                    else high = middle - 1;
                }
                count = low;
            }
            if (count <= 0) continue;
            double generation = fuel.fuelEnergy * count;
            if (!Double.isFinite(generation)) throw new IllegalStateException("Runtime generation overflow");
            CloudworksRuntimeRecipe.Allocation consumed = fuel.allocate(stock, input, count);
            CloudworksRuntimeRecipe.Allocation output = fuel.outputs(count, random);
            for (int index = 0; index < consumed.ids.length; index++) stock.take(input, consumed.ids[index], consumed.amounts[index]);
            for (int index = 0; index < output.ids.length; index++) stock.add(machines.output(node), output.ids[index], output.amounts[index]);
            remainingUnits -= count;
            result = finiteSum(result, generation);
        }
        return result;
    }

    private double burnHeat(int node, CloudworksRuntimeHeatFuel heat, long machinesCount) {
        int input = machines.input(node), output = machines.output(node), fluidCodec = program.resourceCodec[heat.lava];
        CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(node)];
        long capacity = CloudworksQuantities.multiplyLimit(type.inputCapacity[fluidCodec], machines.machineCount(node));
        for (CloudworksRuntimeHeatFuel.Fuel fuel : heat.fuels) {
            if (fuel.units <= 0) continue;
            long room = Math.max(0, capacity - codecTotal(input, fluidCodec));
            long consumed = Math.min(stock.available(input, fuel.resource), room / fuel.units);
            if (fuel.container >= 0) {
                int codec = program.resourceCodec[fuel.container];
                long outputRoom = CloudworksQuantities.multiplyLimit(type.outputCapacity[codec],
                        machines.machineCount(node)) - codecTotal(output, codec);
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
        long burning = Math.min(machinesCount, stock.available(input, heat.lava) / heat.rate);
        double generated = heat.incrementalGeneration * burning;
        if (!Double.isFinite(generated)) throw new IllegalStateException("Runtime heat generation overflow");
        stock.take(input, heat.lava, Math.multiplyExact(burning, heat.rate));
        return generated;
    }

    private boolean worstOutputsFit(int node, CloudworksRuntimeRecipe recipe, int count) {
        Arrays.fill(outputByCodec, 0);
        for (int index = 0; index < recipe.outputIds.length; index++)
            addOutput(recipe.outputIds[index], CloudworksQuantities.multiplyLimit(recipe.outputAmounts[index], count));
        for (int index = 0; index < recipe.chanceIds.length; index++)
            if (recipe.chances[index] > 0)
                addOutput(recipe.chanceIds[index], CloudworksQuantities.multiplyLimit(recipe.chanceAmounts[index], count));
        if (recipe.choiceIds.length > 0) {
            addOutput(recipe.choiceIds[0], CloudworksQuantities.multiplyLimit(recipe.choiceAmount, count));
        }
        return codecOutputsFit(node);
    }

    private boolean outputsFit(int node, CloudworksRuntimeRecipe.Allocation output) {
        Arrays.fill(outputByCodec, 0);
        for (int index = 0; index < output.ids.length; index++) addOutput(output.ids[index], output.amounts[index]);
        return codecOutputsFit(node);
    }

    private void addOutput(int resource, long amount) {
        int codec = program.resourceCodec[resource];
        outputByCodec[codec] = CloudworksQuantities.addLimit(outputByCodec[codec], amount);
    }

    private boolean codecOutputsFit(int node) {
        CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(node)];
        for (int codec = 0; codec < outputByCodec.length; codec++) {
            long capacity = CloudworksQuantities.multiplyLimit(type.outputCapacity[codec], machines.machineCount(node));
            if (outputByCodec[codec] > capacity - codecTotal(machines.output(node), codec)) return false;
        }
        return true;
    }

    private long codecTotal(int inventory, int codec) {
        return stock.groupTotal(inventory, codec);
    }

    private void settleAndAdvance() {
        routeEnergy();
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
            if (!machines.active(node) || program.generator[node] || !operations.present(node)) continue;
            if (!settlement.powered(machines.line(node))) {
                machines.setState(node, CloudworksRuntimeMachineStore.WAITING_POWER);
                if (!operations.started(node)) operations.clear(node);
                continue;
            }
            if (!operations.started(node)) operations.start(node, machines.input(node), stock);
            int duration = operations.duration(node);
            if (machines.progress(node) >= duration - 1) {
                if (outputsFit(node, operations.outputs(node))) {
                    operations.complete(node, machines.input(node), machines.output(node), stock);
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
        }
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
