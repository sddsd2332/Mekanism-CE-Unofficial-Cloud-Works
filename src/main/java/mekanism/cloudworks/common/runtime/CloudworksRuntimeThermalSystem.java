package mekanism.cloudworks.common.runtime;

import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan.Kind;
import mekanism.cloudworks.common.service.CloudworksQuantities;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;

/**
 * Native-free thermal dynamics. Resource mutations and heat changes share one worker tick.
 */
final class CloudworksRuntimeThermalSystem {
    private final CloudworksRuntimeProgram program;
    private final CloudworksRuntimeMachineStore machines;
    private final CloudworksRuntimeResourceStore stock;
    private final CloudworksRuntimeThermalStore thermal;
    private final CloudworksRuntimeLinkStore links;
    private final CloudworksRuntimeSimulation simulation;
    private final boolean present;
    private final double[] temperature, minimum, maximum, outgoing, incoming, requested, produced, consumed, loss;

    CloudworksRuntimeThermalSystem(CloudworksPlayerRuntime runtime, CloudworksRuntimeProgram program,
                                   CloudworksRuntimeSimulation simulation) {
        this.program = program;
        this.simulation = simulation;
        machines = runtime.machines();
        stock = runtime.resources();
        thermal = runtime.thermal();
        links = runtime.links();
        present = thermal.present();
        int size = present ? machines.size() : 0;
        temperature = new double[size];
        minimum = new double[size];
        maximum = new double[size];
        outgoing = new double[size];
        incoming = new double[size];
        produced = new double[size];
        consumed = new double[size];
        loss = new double[size];
        requested = new double[present ? links.size() : 0];
    }

    void produce(int clock) {
        if (!present) return;
        for (int node : program.thermalNodes) produced[node] = consumed[node] = loss[node] = 0;
        for (int node : program.thermalNodes) {
            CloudworksThermalPlan plan = program.thermalPlans[node];
            if (plan == null || !machines.active(node)) continue;
            addHeat(node, program.thermalEnvironmentHeat[node] * units(node));
            switch (plan.kind) {
                case FUELWOOD:
                    burnWood(node, plan);
                    break;
                case HEAT_GENERATOR:
                    burnLava(node, plan);
                    break;
                case FUSION:
                    fusionFuel(node, plan);
                    break;
                case FISSION:
                    fissionFuel(node, plan);
                    break;
                case EVAPORATION:
                    double heat = plan.solarGain * program.thermalSolarCount[node]
                            * mekanism.cloudworks.api.environment.CloudworksGenerationPlan.solarBrightness(clock)
                            * thermal.capacity(node) * program.workRate;
                    addHeat(node, heat);
                    break;
                default:
                    break;
            }
        }
        // Plasma and casing remain separate reservoirs even when injection is disabled.
        for (int node : program.thermalNodes) {
            CloudworksThermalPlan plan = program.thermalPlans[node];
            if (plan == null || plan.kind != Kind.FUSION) continue;
            double plasmaCapacity = plan.plasmaCapacity * machines.machineCount(node);
            double delta = thermal.plasma(node) / plasmaCapacity - thermal.temperature(node);
            if (delta > 0) {
                double transfer = Math.min(delta * plan.transfer * machines.machineCount(node) * program.workRate,
                        delta / (1 / plasmaCapacity + 1 / thermal.capacity(node)));
                thermal.setPlasma(node, thermal.plasma(node) - transfer);
                thermal.add(node, transfer);
            } else if (delta < 0) {
                double transfer = thermal.take(node, Math.min(-delta * plan.transfer * machines.machineCount(node) * program.workRate,
                        -delta / (1 / plasmaCapacity + 1 / thermal.capacity(node))), 0);
                thermal.setPlasma(node, thermal.plasma(node) + transfer);
            }
        }
    }

    void consumeAndGenerate(double[] generation) {
        if (!present) return;
        exchange();
        for (int node : program.thermalNodes) {
            CloudworksThermalPlan plan = program.thermalPlans[node];
            if (plan == null) continue;
            if (machines.active(node)) {
                switch (plan.kind) {
                    case BOILER:
                        boiler(node, plan);
                        break;
                    case FISSION:
                        fissionCooling(node, plan);
                        break;
                    case EVAPORATION:
                        evaporation(node, plan);
                        break;
                    case FUSION:
                        if (plan.cooled) fusionCooling(node, plan);
                        generation[machines.line(node)] += convert(node, plan.efficiency,
                                Math.max(0, thermal.temperature(node) - 300) / plan.conduction * units(node));
                        break;
                    case HEAT_GENERATOR:
                        double temperature = thermal.temperature(node);
                        double extracted = Math.max(0, temperature - 300) * plan.efficiency * units(node);
                        double efficiency = temperature <= 300 ? 0 : 1 - 300 / temperature;
                        generation[machines.line(node)] += convert(node, efficiency, extracted);
                        break;
                    default:
                        break;
                }
            }
            dissipate(node, plan);
            thermal.metrics(node, produced[node], consumed[node], loss[node]);
        }
    }

    void powered(boolean[] powered) {
        if (!present) return;
        for (int node : program.thermalNodes) {
            CloudworksThermalPlan plan = program.thermalPlans[node];
            if (plan == null || plan.kind != Kind.RESISTIVE || !machines.active(node)) continue;
            if (powered[machines.line(node)]) {
                double heat = program.machineLoad[node] * plan.efficiency;
                addHeat(node, heat);
                machines.setState(node, CloudworksRuntimeMachineStore.RUNNING);
            } else machines.setState(node, CloudworksRuntimeMachineStore.WAITING_POWER);
            thermal.metrics(node, produced[node], consumed[node], loss[node]);
        }
    }

    private long units(int node) {
        return CloudworksQuantities.operationLimit(machines.machineCount(node), program.threads[node], program.workRate);
    }

    private void addHeat(int node, double heat) {
        thermal.add(node, heat);
        produced[node] = CloudworksRuntimeThermalStore.finite(produced[node] + heat);
    }

    private double convert(int node, double efficiency, double requestedHeat) {
        if (simulation.generationLimit(node) <= 0) {
            machines.setState(node, program.generationPlans[node].source() == CloudworksGenerationPlan.Source.UNSUPPORTED
                    ? CloudworksRuntimeMachineStore.UNSUPPORTED_RECIPE : program.generationPlans[node].active(simulation.clock())
                    ? CloudworksRuntimeMachineStore.WAITING_OUTPUT : CloudworksRuntimeMachineStore.WAITING_TIME);
            return 0;
        }
        if (efficiency > 0) requestedHeat = Math.min(requestedHeat, simulation.generationLimit(node) / efficiency);
        double removed = thermal.take(node, requestedHeat, 300);
        consumed[node] += removed;
        double energy = removed * efficiency;
        CloudworksThermalPlan plan = program.thermalPlans[node];
        if (plan.kind == Kind.HEAT_GENERATOR) energy = Math.min(energy, plan.processRate * units(node));
        loss[node] += removed - energy;
        if (removed > 0) machines.setState(node, CloudworksRuntimeMachineStore.GENERATING);
        return simulation.convertGeneration(node, CloudworksRuntimeThermalStore.finite(energy));
    }

    private void burnWood(int node, CloudworksThermalPlan plan) {
        CloudworksRuntimeHeatFuel fuels = program.heat[machines.machineType(node)];
        if (fuels == null) throw new IllegalStateException("Fuelwood inputs were not frozen");
        double quota = units(node) * (double) plan.rate;
        double prepaid = thermal.fuel(node);
        for (CloudworksRuntimeHeatFuel.Fuel fuel : fuels.fuels) {
            if (prepaid >= quota) break;
            long count = Math.min(stock.available(machines.input(node), fuel.resource),
                    (long) Math.ceil((quota - prepaid) / fuel.burnTicks));
            if (fuel.container >= 0) count = Math.min(count, outputRoom(node, fuel.container) / fuel.containerCount);
            if (count <= 0) continue;
            stock.take(machines.input(node), fuel.resource, count);
            if (fuel.container >= 0)
                stock.add(machines.output(node), fuel.container, Math.multiplyExact(count, fuel.containerCount));
            prepaid += count * (double) fuel.burnTicks;
        }
        double burned = Math.min(prepaid, quota);
        thermal.setFuel(node, prepaid - burned);
        addHeat(node, burned * plan.heatPerUnit);
        machines.setState(node, burned > 0 ? CloudworksRuntimeMachineStore.RUNNING : CloudworksRuntimeMachineStore.WAITING_FUEL);
    }

    private void burnLava(int node, CloudworksThermalPlan plan) {
        CloudworksRuntimeHeatFuel fuels = program.heat[machines.machineType(node)];
        if (fuels == null) throw new IllegalStateException("Heat-generator inputs were not frozen");
        int inventory = machines.input(node);
        for (CloudworksRuntimeHeatFuel.Fuel fuel : fuels.fuels) {
            if (fuel.units <= 0) continue;
            long room = inputRoom(node, fuels.lava);
            long count = Math.min(stock.available(inventory, fuel.resource), room / fuel.units);
            if (fuel.container >= 0) count = Math.min(count, outputRoom(node, fuel.container) / fuel.containerCount);
            if (count <= 0) continue;
            stock.take(inventory, fuel.resource, count);
            stock.add(inventory, fuels.lava, Math.multiplyExact(count, fuel.units));
            if (fuel.container >= 0)
                stock.add(machines.output(node), fuel.container, Math.multiplyExact(count, fuel.containerCount));
        }
        long count = Math.min(units(node), stock.available(inventory, fuels.lava) / fuels.rate);
        stock.take(inventory, fuels.lava, Math.multiplyExact(count, fuels.rate));
        addHeat(node, count * plan.heatPerUnit);
        machines.setState(node, count > 0 || program.thermalEnvironmentHeat[node] > 0
                ? CloudworksRuntimeMachineStore.GENERATING : CloudworksRuntimeMachineStore.WAITING_HEAT);
    }

    private void fusionFuel(int node, CloudworksThermalPlan plan) {
        if (plan.rate == 0) {
            thermal.setBurning(node, false);
            machines.setState(node, CloudworksRuntimeMachineStore.OFF);
            return;
        }
        boolean fueled = false;
        for (CloudworksRuntimeRecipe recipe : program.fuels[machines.machineType(node)])
            fueled |= gasFuelCount(node, recipe, 1) > 0;
        if (!fueled) {
            thermal.setBurning(node, false);
            machines.setState(node, CloudworksRuntimeMachineStore.WAITING_FUEL);
            return;
        }
        double capacity = plan.plasmaCapacity * machines.machineCount(node);
        double temperature = thermal.plasma(node) / capacity;
        // The virtual representative grants the ignition device, but its energy is paid from the line pool.
        if (!thermal.burning(node)) {
            double ignition = Math.max(0, (plan.threshold + 1) * capacity - thermal.plasma(node));
            if (ignition > 0) {
                if (!simulation.drawThermalPower(machines.line(node), ignition)) {
                    machines.setState(node, CloudworksRuntimeMachineStore.WAITING_POWER);
                    return;
                }
                thermal.setPlasma(node, thermal.plasma(node) + ignition);
                produced[node] += ignition;
            }
            thermal.setBurning(node, true);
        }
        if (temperature < plan.threshold && thermal.plasma(node) / capacity < plan.threshold) {
            thermal.setBurning(node, false);
            machines.setState(node, CloudworksRuntimeMachineStore.WAITING_HEAT);
            return;
        }
        long remaining = units(node), burnedFuel = 0;
        for (CloudworksRuntimeRecipe recipe : program.fuels[machines.machineType(node)]) {
            int count = gasFuelCount(node, recipe, remaining);
            if (count <= 0) continue;
            for (int ingredient = 0; ingredient < recipe.demands.length; ingredient++) {
                if (!gasIngredient(recipe, ingredient)) continue;
                long amount = Math.multiplyExact(recipe.demands[ingredient], count);
                consumeMatching(node, recipe.matches[ingredient], amount);
                burnedFuel = Math.addExact(burnedFuel, amount);
            }
            remaining -= count;
            if (remaining == 0) break;
        }
        double heat = burnedFuel * plan.heatPerUnit;
        thermal.setPlasma(node, thermal.plasma(node) + heat);
        produced[node] += heat;
        if (burnedFuel == 0) thermal.setBurning(node, false);
        machines.setState(node, burnedFuel > 0 ? CloudworksRuntimeMachineStore.GENERATING : CloudworksRuntimeMachineStore.WAITING_FUEL);
    }

    private int gasFuelCount(int node, CloudworksRuntimeRecipe recipe, long quota) {
        long count = quota;
        boolean gas = false;
        for (int ingredient = 0; ingredient < recipe.demands.length; ingredient++) {
            if (!gasIngredient(recipe, ingredient)) continue;
            gas = true;
            count = Math.min(count, available(node, recipe.matches[ingredient]) / recipe.demands[ingredient]);
        }
        return gas ? (int) Math.min(Integer.MAX_VALUE, count) : 0;
    }

    private boolean gasIngredient(CloudworksRuntimeRecipe recipe, int ingredient) {
        return recipe.gasInputs[ingredient];
    }

    private void fissionFuel(int node, CloudworksThermalPlan plan) {
        CloudworksRuntimeRecipe recipe = first(node);
        if (recipe == null) {
            machines.setState(node, CloudworksRuntimeMachineStore.NO_RECIPE);
            return;
        }
        double credit = thermal.credit(node) + (double) units(node) / plan.rate;
        long count = Math.min((long) credit, available(node, recipe.matches[0]));
        count = Math.min(count, outputRoom(node, recipe.outputIds[0]));
        if (count > 0) {
            consumeMatching(node, recipe.matches[0], count);
            stock.add(machines.output(node), recipe.outputIds[0], count);
            addHeat(node, count * plan.heatPerUnit);
            credit -= count;
        }
        thermal.setCredit(node, Math.min(credit, units(node)));
        machines.setState(node, count > 0 ? CloudworksRuntimeMachineStore.RUNNING
                : outputRoom(node, recipe.outputIds[0]) == 0 ? CloudworksRuntimeMachineStore.WAITING_OUTPUT
                : CloudworksRuntimeMachineStore.WAITING_FUEL);
    }

    private CloudworksRuntimeRecipe first(int node) {
        CloudworksRuntimeRecipe[] recipes = program.recipes[machines.machineType(node)];
        return recipes.length == 0 ? null : recipes[0];
    }

    private void fissionCooling(int node, CloudworksThermalPlan plan) {
        CloudworksRuntimeRecipe recipe = first(node);
        if (recipe == null || recipe.matches.length < 2 || recipe.outputIds.length < 2) {
            machines.setState(node, CloudworksRuntimeMachineStore.NO_RECIPE);
            return;
        }
        double availableHeat = Math.max(0, thermal.heat(node) - plan.threshold * thermal.capacity(node))
                * Math.min(1, plan.processRate * program.workRate);
        long quota = (long) Math.min(Long.MAX_VALUE, availableHeat / plan.processHeat);
        long count = Math.min(quota, Math.min(available(node, recipe.matches[1]), outputRoom(node, recipe.outputIds[1])));
        if (count > 0) {
            double removed = thermal.take(node, count * plan.processHeat, plan.threshold);
            count = Math.min(count, (long) Math.floor(removed / plan.processHeat + 1e-9));
            consumeMatching(node, recipe.matches[1], count);
            stock.add(machines.output(node), recipe.outputIds[1], count);
            consumed[node] += count * plan.processHeat;
        }
    }

    private void boiler(int node, CloudworksThermalPlan plan) {
        CloudworksRuntimeRecipe recipe = first(node);
        if (recipe == null) {
            machines.setState(node, CloudworksRuntimeMachineStore.NO_RECIPE);
            return;
        }
        long coolant = Math.min(available(node, recipe.matches[1]), outputRoom(node, recipe.outputIds[1]));
        coolant = Math.min(coolant, Math.round(coolant * Math.min(1, plan.coolantEfficiency * program.workRate)
                * Math.max(0, 1 - thermal.temperature(node) / 100000)));
        if (coolant > 0) {
            consumeMatching(node, recipe.matches[1], coolant);
            stock.add(machines.output(node), recipe.outputIds[1], coolant);
            addHeat(node, coolant * plan.coolantHeat);
        }
        double availableHeat = Math.max(0, thermal.heat(node) - plan.threshold * thermal.capacity(node));
        availableHeat = Math.min(availableHeat * plan.processRate, plan.transfer * machines.machineCount(node) * program.workRate);
        long quota = (long) Math.min(Long.MAX_VALUE, availableHeat / plan.processHeat);
        double allowance = (double) plan.rate * units(node) / Math.max(1, program.baseTicks[node]);
        double credit = thermal.credit(node) + allowance;
        quota = Math.min(quota, (long) credit);
        long count = Math.min(quota, Math.min(available(node, recipe.matches[0]), outputRoom(node, recipe.outputIds[0])));
        if (count > 0) {
            double removed = thermal.take(node, count * plan.processHeat, plan.threshold);
            count = Math.min(count, (long) Math.floor(removed / plan.processHeat + 1e-9));
            consumeMatching(node, recipe.matches[0], count);
            stock.add(machines.output(node), recipe.outputIds[0], count);
            consumed[node] += count * plan.processHeat;
        }
        thermal.setCredit(node, Math.min(credit - count, Math.max(1, allowance)));
        machines.setState(node, count > 0 ? CloudworksRuntimeMachineStore.RUNNING
                : quota == 0 ? CloudworksRuntimeMachineStore.WAITING_HEAT
                : outputRoom(node, recipe.outputIds[0]) == 0 ? CloudworksRuntimeMachineStore.WAITING_OUTPUT
                : CloudworksRuntimeMachineStore.WAITING_INPUT);
    }

    private void evaporation(int node, CloudworksThermalPlan plan) {
        double rate = Math.max(0, Math.min(plan.maxTemperature, thermal.temperature(node)) - 300) * plan.processRate;
        double quota = rate * units(node) / Math.max(1, program.baseTicks[node]);
        double credit = Math.min(Integer.MAX_VALUE, thermal.credit(node) + quota);
        int maximum = (int) credit;
        boolean operated = false;
        if (maximum > 0) for (CloudworksRuntimeRecipe recipe : program.recipes[machines.machineType(node)]) {
            int count = simulation.thermalRecipe(node, recipe, maximum);
            if (count > 0) {
                credit -= count;
                operated = true;
                break;
            }
        }
        thermal.setCredit(node, Math.min(credit, Math.max(1, quota)));
        machines.setState(node, operated ? CloudworksRuntimeMachineStore.RUNNING
                : rate <= 0 ? CloudworksRuntimeMachineStore.WAITING_HEAT
                : stock.total(machines.input(node)) == 0 ? CloudworksRuntimeMachineStore.WAITING_INPUT
                : CloudworksRuntimeMachineStore.WAITING_OUTPUT);
    }

    private void fusionCooling(int node, CloudworksThermalPlan plan) {
        double requested = Math.max(0, thermal.temperature(node) - 300) * plan.processRate * units(node);
        requested = Math.min(requested, Math.max(0, thermal.heat(node) - 300 * thermal.capacity(node)));
        long quota = (long) Math.min(Long.MAX_VALUE, requested / plan.processHeat);
        for (CloudworksRuntimeRecipe recipe : program.fuels[machines.machineType(node)]) {
            if (recipe.outputIds.length == 0) continue;
            for (int ingredient = 0; ingredient < recipe.matches.length; ingredient++) {
                if (gasIngredient(recipe, ingredient)) continue;
                long ratio = recipe.outputAmounts[0] / recipe.demands[ingredient];
                if (ratio <= 0) continue;
                long count = Math.min(quota, Math.min(available(node, recipe.matches[ingredient]),
                        outputRoom(node, recipe.outputIds[0]) / ratio));
                if (count <= 0) continue;
                thermal.take(node, count * plan.processHeat, 300);
                consumeMatching(node, recipe.matches[ingredient], count);
                stock.add(machines.output(node), recipe.outputIds[0], Math.multiplyExact(count, ratio));
                consumed[node] += count * plan.processHeat;
                return;
            }
        }
    }

    private long available(int node, int[] matches) {
        long result = 0;
        for (int resource : matches)
            result = CloudworksQuantities.addLimit(result, stock.available(machines.input(node), resource));
        return result;
    }

    private void consumeMatching(int node, int[] matches, long amount) {
        for (int resource : matches) {
            long taken = Math.min(amount, stock.available(machines.input(node), resource));
            stock.take(machines.input(node), resource, taken);
            amount -= taken;
            if (amount == 0) return;
        }
        if (amount != 0) throw new IllegalStateException("Thermal resource allocation changed");
    }

    private long outputRoom(int node, int resource) {
        int codec = program.resourceCodec[resource];
        CloudworksRuntimeProgram.MachineType type = program.types[machines.machineType(node)];
        long capacity = CloudworksQuantities.multiplyLimit(type.outputCapacity[codec],
                machines.machineCount(node) * program.nodeBufferMultiplier[node]);
        long portRoom = 0;
        for (int port = 0; port < type.portNames.length; port++) {
            if (type.input[port] || !type.accepted[port][resource]) continue;
            long room = CloudworksQuantities.multiplyLimit(type.portCapacity[port],
                    machines.machineCount(node) * program.nodeBufferMultiplier[node])
                    - stock.groupTotal(machines.output(node), program.codecCount + port);
            portRoom = CloudworksQuantities.addLimit(portRoom, Math.max(0, room));
        }
        return Math.max(0, Math.min(portRoom, capacity - stock.groupTotal(machines.output(node), codec)));
    }

    private long inputRoom(int node, int resource) {
        int codec = program.resourceCodec[resource];
        long capacity = CloudworksQuantities.multiplyLimit(program.types[machines.machineType(node)].inputCapacity[codec],
                machines.machineCount(node) * program.nodeBufferMultiplier[node]);
        return Math.max(0, capacity - stock.groupTotal(machines.input(node), codec));
    }

    private void exchange() {
        for (int node : program.thermalNodes) {
            outgoing[node] = incoming[node] = 0;
            temperature[node] = minimum[node] = maximum[node] = thermal.temperature(node);
        }
        CloudworksRuntimeThermalExchange.exchange(thermal, program.thermalPlans, links, program.thermalLinks,
                program.workRate, temperature, minimum, maximum, outgoing, incoming, requested);
    }

    private void dissipate(int node, CloudworksThermalPlan plan) {
        double temperature = thermal.temperature(node);
        if (temperature > 300) {
            double requested = plan.kind == Kind.EVAPORATION
                    ? program.thermalDissipation[node] * Math.sqrt(temperature - 300) * thermal.capacity(node) * program.workRate
                    : (temperature - 300) * thermal.capacity(node)
                    / (10000 + plan.conduction + plan.insulation) * program.workRate;
            loss[node] += thermal.take(node, requested, 300);
        } else if (temperature < 300) {
            double warming = (300 - temperature) * thermal.capacity(node)
                    / (10000 + plan.conduction + plan.insulation) * program.workRate;
            double added = Math.min(warming, (300 - temperature) * thermal.capacity(node));
            thermal.add(node, added);
            produced[node] += added;
        }
    }
}
