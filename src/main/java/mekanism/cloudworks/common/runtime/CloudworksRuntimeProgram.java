package mekanism.cloudworks.common.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mekanism.cloudworks.api.CloudworksWarehouseCaps;
import mekanism.cloudworks.common.service.CloudworksDetachedState;
import mekanism.cloudworks.common.service.CloudworksQuantities;

/** Cold compiler output. Normal ticks resolve neither UUIDs nor port strings. */
public final class CloudworksRuntimeProgram {
    final int receiving, transfer, sending;
    final long receivingCapacity, transferCapacity, sendingCapacity;
    final int workRate, codecCount, infuseCodec, initialClock;
    final int[] resourceCodec, conversionTarget, conversionUnits;
    final long[] resourceCap;
    final MachineType[] types;
    final int[] baseTicks, threads, dimension;
    final double[] machineLoad;
    final mekanism.cloudworks.api.CloudworksGenerationPlan[] generationPlans;
    final boolean[] executable, generator, supported;
    final double[] timeMultiplier, energyMultiplier;
    final boolean[] configuredUpgrades, receivingToLine, lineToSending;
    final int[] machineStart, machineEnd, channelStart, channelEnd;
    final int[] linkStart, linkEnd;
    final boolean receivingToCloud, receivingToLines, cloudToSending, linesToSending;
    final int initialCursor;
    final long randomSeed;
    final CloudworksRuntimeRecipe[][] recipes, fuels;
    final boolean[] decayTypes;
    final CloudworksRuntimeHeatFuel[] heat;
    final mekanism.cloudworks.api.CloudworksThermalPlan[] thermalPlans;
    final int[] thermalNodes, thermalLinks;
    final double[] thermalEnvironmentHeat, thermalDissipation;
    final int[] thermalSolarCount;
    private final Map<String, Integer> codecIds = new LinkedHashMap<>();
    private final List<UUID> compiledLinkIds = new ArrayList<>();

    CloudworksRuntimeProgram(CloudworksDetachedState.View view, CloudworksRuntimeCatalog catalog,
            List<String> families, CloudworksPlayerRuntime runtime) {
        CloudworksRuntimeResourceStore stock = runtime.resources();
        CloudworksRuntimeMachineStore machines = runtime.machines();
        CloudworksRuntimeChannelStore channels = runtime.channels();
        stock.clear();
        machines.clear();
        channels.clear();
        runtime.links().clear();
        runtime.energy().clear();
        receiving = inventory(stock, view.receiving);
        transfer = inventory(stock, view.transfer);
        sending = inventory(stock, view.sending);
        receivingCapacity = view.receiving.capacity;
        transferCapacity = view.transfer.capacity;
        sendingCapacity = view.sending.capacity;
        workRate = Math.max(1, view.workRate);
        initialClock = view.generationContext.clockTick();
        for (CloudworksDetachedState.Line line : view.lines) {
            for (CloudworksDetachedState.Node node : line.nodes)
                for (CloudworksDetachedState.Slot slot : node.slots) codec(slot.codec);
            for (CloudworksDetachedState.Link link : line.links) {
                if (link.sourcePort.contains(":")) codec(link.sourcePort);
                if (link.targetPort.contains(":")) codec(link.targetPort);
            }
        }
        resourceCodec = new int[families.size()];
        resourceCap = new long[families.size()];
        conversionTarget = new int[families.size()];
        conversionUnits = new int[families.size()];
        for (int resource = 0; resource < families.size(); resource++) {
            resourceCodec[resource] = codec(catalog.codec(resource));
            resourceCap[resource] = CloudworksWarehouseCaps.capOf(families.get(resource));
            conversionTarget[resource] = view.resourceRules.conversionTarget(resource);
            conversionUnits[resource] = view.resourceRules.conversionUnits(resource);
        }
        codecCount = codecIds.size();
        infuseCodec = codecIds.getOrDefault("cloudworks:infuse", -1);
        Map<String, Integer> typeIds = new LinkedHashMap<>();
        List<MachineType> typeList = new ArrayList<>();
        List<CloudworksDetachedState.Node> nodes = new ArrayList<>();
        for (CloudworksDetachedState.Line line : view.lines)
            for (CloudworksDetachedState.Node node : line.nodes) {
                if (!typeIds.containsKey(node.machineKey)) {
                    typeIds.put(node.machineKey, typeList.size());
                    typeList.add(new MachineType(node, view.resourceRules));
                }
                nodes.add(node);
            }
        types = typeList.toArray(new MachineType[0]);
        recipes = new CloudworksRuntimeRecipe[types.length][];
        decayTypes = new boolean[types.length];
        fuels = new CloudworksRuntimeRecipe[types.length][];
        heat = new CloudworksRuntimeHeatFuel[types.length];
        for (Map.Entry<String, Integer> entry : typeIds.entrySet()) {
            heat[entry.getValue()] = catalog.heat(entry.getKey());
            recipes[entry.getValue()] = catalog.recipes(entry.getKey(), false)
                    .toArray(new CloudworksRuntimeRecipe[0]);
            fuels[entry.getValue()] = catalog.recipes(entry.getKey(), true)
                    .toArray(new CloudworksRuntimeRecipe[0]);
            for (CloudworksRuntimeRecipe recipe : recipes[entry.getValue()])
                decayTypes[entry.getValue()] |= recipe.decayAmount >= 0;
            if (decayTypes[entry.getValue()])
                for (CloudworksRuntimeRecipe recipe : recipes[entry.getValue()])
                    if (recipe.decayAmount < 0) throw new IllegalArgumentException("Mixed decay and processing contracts");
            CloudworksRuntimeHeatFuel heatPlan = heat[entry.getValue()];
            if (heatPlan != null) {
                MachineType type = types[entry.getValue()];
                int fluidCodec = resourceCodec[heatPlan.lava];
                for (int port = 0; port < type.portNames.length; port++)
                    if (type.input[port] && type.portCodec[port] == fluidCodec)
                        for (int resource = 0; resource < resourceCodec.length; resource++)
                            type.accepted[port][resource] = resource == heatPlan.lava;
            }
        }
        baseTicks = new int[nodes.size()];
        threads = new int[nodes.size()];
        dimension = new int[nodes.size()];
        machineLoad = new double[nodes.size()];
        generationPlans = new mekanism.cloudworks.api.CloudworksGenerationPlan[nodes.size()];
        executable = new boolean[nodes.size()];
        generator = new boolean[nodes.size()];
        supported = new boolean[nodes.size()];
        int lineCount = view.lines.size();
        timeMultiplier = new double[lineCount];
        energyMultiplier = new double[lineCount];
        configuredUpgrades = new boolean[lineCount];
        receivingToLine = new boolean[lineCount];
        lineToSending = new boolean[lineCount];
        machineStart = new int[lineCount];
        machineEnd = new int[lineCount];
        channelStart = new int[lineCount];
        channelEnd = new int[lineCount];
        linkStart = new int[lineCount];
        linkEnd = new int[lineCount];
        runtime.operations().resize(nodes.size());
        boolean thermalPresent = false;
        it.unimi.dsi.fastutil.ints.IntArrayList thermalIndices = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).thermal != null && nodes.get(index).machineCount > 0) thermalIndices.add(index);
        }
        thermalNodes = thermalIndices.toIntArray();
        thermalPresent = thermalNodes.length > 0;
        runtime.thermal().resize(nodes.size(), thermalPresent);
        thermalPlans = new mekanism.cloudworks.api.CloudworksThermalPlan[nodes.size()];
        thermalEnvironmentHeat = new double[nodes.size()];
        thermalDissipation = new double[nodes.size()];
        thermalSolarCount = new int[nodes.size()];
        Map<UUID, Integer> machineIds = new HashMap<>(), channelIds = new HashMap<>();
        runtime.energy().setCloud(view.energy, view.maxEnergy, view.unlimitedEnergy);
        runtime.energy().setRelays(view.receivingEnergy, view.sendingEnergy,
                view.receivingCapacity, view.sendingCapacity);
        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            CloudworksDetachedState.Line line = view.lines.get(lineIndex);
            runtime.energy().addLine(line.energy, line.maxEnergy);
            timeMultiplier[lineIndex] = line.timeMultiplier;
            energyMultiplier[lineIndex] = line.energyMultiplier;
            configuredUpgrades[lineIndex] = line.configuredUpgrades;
            receivingToLine[lineIndex] = line.energyFlowReceivingToLine;
            lineToSending[lineIndex] = line.energyFlowLineToSending;
            machineStart[lineIndex] = machines.size();
            for (CloudworksDetachedState.Node node : line.nodes) {
                int type = typeIds.get(node.machineKey);
                int index = machines.add(type, node.machineCount, node.active, Math.max(1, node.totalTicks));
                if (machineIds.put(node.id, index) != null)
                    throw new IllegalArgumentException("Duplicate runtime machine");
                int input = inventory(stock, node.inputs), output = inventory(stock, node.outputs);
                machines.bind(index, lineIndex, input, output);
                boolean hasConfiguration = false;
                for (boolean configuration : types[type].configuration) hasConfiguration |= configuration;
                if (hasConfiguration) machines.bindConfiguration(index, inventory(stock, node.templates));
                else if (!node.templates.isEmpty()) throw new IllegalArgumentException("Template without configuration port");
                machines.setProgress(index, Math.min(node.progress, Math.max(0, node.totalTicks - 1)),
                        Math.max(1, node.totalTicks));
                machines.setState(index, state(node.state));
                baseTicks[index] = node.baseTicks;
                threads[index] = Math.max(1, node.threads);
                dimension[index] = node.dimensionId;
                executable[index] = node.executable;
                machineLoad[index] = node.energyPerTick * node.machineCount * threads[index] * workRate;
                if (!Double.isFinite(machineLoad[index]) || machineLoad[index] < 0)
                    throw new IllegalArgumentException("Runtime machine load overflow");
                generationPlans[index] = catalog.generation(node.id);
                generator[index] = catalog.generator(node.id) || fuels[type].length > 0;
                supported[index] = !catalog.unsupported().containsKey(node.machineKey);
                thermalPlans[index] = node.machineCount == 0 ? null : node.thermal;
                runtime.thermal().restore(index, thermalPlans[index], node.machineCount, node.thermalState);
                if (node.thermal != null) {
                    thermalEnvironmentHeat[index] = node.thermal.lavaEnvironmentHeat * view.generationContext.lavaHeatCards()
                            + (view.generationContext.netherEnvironment() ? node.thermal.netherEnvironmentHeat : 0);
                    thermalDissipation[index] = node.thermal.transfer;
                    thermalSolarCount[index] = node.thermal.solarPanels;
                }
                if (node.operation != null)
                    runtime.operations().restore(index, node.operation.operations, node.operation.duration,
                            allocation(node.operation.inputs), allocation(node.operation.outputs),
                            node.operation.extraEnergyPerTick);
                verifyReservations(stock, input, runtime.operations(), index);
            }
            machineEnd[lineIndex] = machines.size();
            channelStart[lineIndex] = channels.size();
            for (CloudworksDetachedState.Channel channel : line.channels) {
                int index = channels.add(lineIndex, mode(channel.mode), channel.enabled,
                        channel.buffer.capacity, inventory(stock, channel.buffer), inventory(stock, channel.manifest));
                if (channelIds.put(channel.id, index) != null)
                    throw new IllegalArgumentException("Duplicate runtime channel");
            }
            channelEnd[lineIndex] = channels.size();
        }
        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            linkStart[lineIndex] = runtime.links().size();
            compileLinks(lineIndex, view.lines.get(lineIndex), machineIds, channelIds, runtime);
            linkEnd[lineIndex] = runtime.links().size();
        }
        it.unimi.dsi.fastutil.ints.IntArrayList heatLinks = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int index = 0; index < runtime.links().size(); index++)
            if (runtime.links().phase(index) == CloudworksRuntimeLinkStore.HEAT) heatLinks.add(index);
        thermalLinks = heatLinks.toIntArray();
        int[][] codecGroups = new int[resourceCodec.length][];
        for (int resource = 0; resource < resourceCodec.length; resource++)
            codecGroups[resource] = new int[]{resourceCodec[resource]};
        int[][][] inputGroups = new int[types.length][][];
        int[][][] configurationGroups = new int[types.length][][];
        int[][][] thermalOutputGroups = new int[types.length][][];
        for (int type = 0; type < types.length; type++) {
            MachineType machineType = types[type];
            inputGroups[type] = new int[resourceCodec.length][];
            configurationGroups[type] = new int[resourceCodec.length][];
            if (machineType.thermal) thermalOutputGroups[type] = new int[resourceCodec.length][];
            for (int resource = 0; resource < resourceCodec.length; resource++) {
                it.unimi.dsi.fastutil.ints.IntArrayList groups = new it.unimi.dsi.fastutil.ints.IntArrayList();
                it.unimi.dsi.fastutil.ints.IntArrayList configuration = new it.unimi.dsi.fastutil.ints.IntArrayList();
                groups.add(resourceCodec[resource]);
                configuration.add(resourceCodec[resource]);
                for (int port = 0; port < machineType.portNames.length; port++)
                    if (machineType.input[port] && machineType.accepted[port][resource]) {
                        if (machineType.configuration[port]) configuration.add(codecCount + port);
                        else groups.add(codecCount + port);
                    }
                inputGroups[type][resource] = groups.toIntArray();
                configurationGroups[type][resource] = configuration.toIntArray();
                if (machineType.thermal) {
                    it.unimi.dsi.fastutil.ints.IntArrayList output = new it.unimi.dsi.fastutil.ints.IntArrayList();
                    output.add(resourceCodec[resource]);
                    for (int port = 0; port < machineType.portNames.length; port++)
                        if (!machineType.input[port] && machineType.accepted[port][resource]) output.add(codecCount + port);
                    thermalOutputGroups[type][resource] = output.toIntArray();
                }
            }
        }
        for (int node = 0; node < machines.size(); node++) {
            int type = machines.machineType(node);
            stock.registerGroups(machines.input(node), codecCount + types[type].portNames.length, inputGroups[type]);
            stock.registerGroups(machines.output(node), types[type].thermal ? codecCount + types[type].portNames.length : codecCount,
                    types[type].thermal ? thermalOutputGroups[type] : codecGroups);
            if (machines.configuration(node) >= 0)
                stock.registerGroups(machines.configuration(node), codecCount + types[type].portNames.length,
                        configurationGroups[type]);
        }
        runtime.setLinkIds(compiledLinkIds.toArray(new UUID[0]));
        for (int channel = 0; channel < channels.size(); channel++) {
            boolean connected = true;
            for (int entry = stock.first(channels.manifest(channel)); entry >= 0; entry = stock.next(entry)) {
                int resource = stock.resource(entry);
                boolean found = false;
                for (int link = 0; link < runtime.links().size(); link++) {
                    byte phase = runtime.links().phase(link);
                    if ((phase == CloudworksRuntimeLinkStore.CHANNEL_INPUT
                            || phase == CloudworksRuntimeLinkStore.CHANNEL_TO_MACHINE)
                            && runtime.links().source(link) == channel
                            && runtime.links().sourcePort(link) == resourceCodec[resource]
                            && runtime.links().accepts(link, resource)) {
                        found = true;
                        break;
                    }
                }
                if (!found) { connected = false; break; }
            }
            channels.setConnected(channel, connected);
        }
        receivingToCloud = view.energyFlowReceivingToCloud;
        receivingToLines = view.energyFlowReceivingToLines;
        cloudToSending = view.energyFlowCloudToSending;
        linesToSending = view.energyFlowLinesToSending;
        initialCursor = lineCount == 0 ? 0 : Math.floorMod(view.receivingCursor, lineCount);
        randomSeed = catalog.seed();
    }

    private void compileLinks(int lineIndex, CloudworksDetachedState.Line line, Map<UUID, Integer> machines,
            Map<UUID, Integer> channels, CloudworksPlayerRuntime runtime) {
        Map<UUID, Integer> incoming = new LinkedHashMap<>(), rank = new HashMap<>();
        Map<UUID, List<UUID>> outgoing = new HashMap<>();
        for (CloudworksDetachedState.Node node : line.nodes) incoming.put(node.id, 0);
        for (CloudworksDetachedState.Link link : line.links)
            if (link.enabled && !thermalLink(link, machines)
                    && incoming.containsKey(link.source) && incoming.containsKey(link.target)) {
                incoming.merge(link.target, 1, Integer::sum);
                outgoing.computeIfAbsent(link.source, ignored -> new ArrayList<>()).add(link.target);
            }
        List<UUID> order = new ArrayList<>();
        incoming.forEach((id, degree) -> { if (degree == 0) order.add(id); });
        for (int index = 0; index < order.size(); index++) {
            UUID id = order.get(index);
            rank.put(id, index);
            for (UUID target : outgoing.getOrDefault(id, java.util.Collections.emptyList()))
                if (incoming.merge(target, -1, Integer::sum) == 0) order.add(target);
        }
        if (order.size() != line.nodes.size()) throw new IllegalArgumentException("Cyclic runtime topology");
        List<CloudworksDetachedState.Link> sorted = new ArrayList<>(line.links);
        sorted.sort(Comparator.comparingInt((CloudworksDetachedState.Link link) -> rank.getOrDefault(link.source, -1))
                .thenComparing(Comparator.comparingInt((CloudworksDetachedState.Link link) -> link.priority).reversed()));
        for (CloudworksDetachedState.Link link : sorted) {
            if (!link.enabled) continue;
            Integer sourceMachine = machines.get(link.source), targetMachine = machines.get(link.target);
            Integer sourceChannel = channels.get(link.source), targetChannel = channels.get(link.target);
            byte phase;
            int source, target, sourcePort, targetPort;
            if (sourceMachine != null && targetMachine != null) {
                phase = CloudworksRuntimeLinkStore.MACHINE_TO_MACHINE;
                source = sourceMachine; target = targetMachine;
                sourcePort = types[runtime.machines().machineType(source)].port(link.sourcePort, false);
                targetPort = types[runtime.machines().machineType(target)].port(link.targetPort, true);
                if (thermalLink(link, machines))
                    phase = CloudworksRuntimeLinkStore.HEAT;
            } else if (sourceChannel != null && targetMachine != null) {
                if (!runtime.channels().input(sourceChannel)) throw new IllegalArgumentException("Invalid channel source");
                phase = CloudworksRuntimeLinkStore.CHANNEL_TO_MACHINE;
                source = sourceChannel; target = targetMachine;
                sourcePort = codecIds.getOrDefault(link.sourcePort, -1);
                targetPort = types[runtime.machines().machineType(target)].port(link.targetPort, true);
            } else if (sourceMachine != null && targetChannel != null) {
                if (runtime.channels().input(targetChannel)) throw new IllegalArgumentException("Invalid channel target");
                phase = CloudworksRuntimeLinkStore.MACHINE_TO_CHANNEL;
                source = sourceMachine; target = targetChannel;
                sourcePort = types[runtime.machines().machineType(source)].port(link.sourcePort, false);
                targetPort = codecIds.getOrDefault(link.targetPort, -1);
            } else if (sourceChannel != null && targetChannel != null) {
                byte from = runtime.channels().mode(sourceChannel), to = runtime.channels().mode(targetChannel);
                if (from == CloudworksRuntimeChannelStore.RECEIVE_INPUT
                        && to == CloudworksRuntimeChannelStore.TRANSFER_INPUT)
                    phase = CloudworksRuntimeLinkStore.CHANNEL_INPUT;
                else if (from == CloudworksRuntimeChannelStore.TRANSFER_OUTPUT
                        && to == CloudworksRuntimeChannelStore.SEND_OUTPUT)
                    phase = CloudworksRuntimeLinkStore.CHANNEL_OUTPUT;
                else throw new IllegalArgumentException("Invalid runtime channel link");
                source = sourceChannel; target = targetChannel;
                sourcePort = codecIds.getOrDefault(link.sourcePort, -1);
                targetPort = codecIds.getOrDefault(link.targetPort, -1);
                if (sourcePort != targetPort) throw new IllegalArgumentException("Mismatched runtime channel ports");
            } else throw new IllegalArgumentException("Missing runtime link endpoint");
            if (sourcePort < 0 || targetPort < 0) throw new IllegalArgumentException("Missing runtime port");
            runtime.links().add(lineIndex, phase, source, target, sourcePort, targetPort,
                    link.filterResourceIds.stream().mapToInt(Integer::intValue).toArray(), link.ratio, link.ratioRemainder);
            compiledLinkIds.add(link.id);
        }
    }

    private int codec(String value) {
        return codecIds.computeIfAbsent(value, ignored -> codecIds.size());
    }

    private boolean thermalLink(CloudworksDetachedState.Link link, Map<UUID, Integer> machines) {
        Integer source = machines.get(link.source), target = machines.get(link.target);
        return source != null && target != null && thermalPlans[source] != null && thermalPlans[target] != null
                && link.sourcePort.equals("heat_output") && link.targetPort.equals("heat_input");
    }

    private static int inventory(CloudworksRuntimeResourceStore store, CloudworksDetachedState.Inventory inventory) {
        return inventory(store, inventory.contents);
    }

    private static int inventory(CloudworksRuntimeResourceStore store, Map<Integer, Long> contents) {
        int inventory = store.createInventory();
        contents.forEach((resource, amount) -> store.add(inventory, resource, amount));
        return inventory;
    }

    private static int inventory(CloudworksRuntimeResourceStore store, List<CloudworksDetachedState.Stack> stacks) {
        int inventory = store.createInventory();
        for (CloudworksDetachedState.Stack stack : stacks) {
            store.add(inventory, stack.resourceId, stack.amount);
            if (!store.reserve(inventory, stack.resourceId, stack.reserved))
                throw new IllegalArgumentException("Invalid runtime stack reservation");
        }
        return inventory;
    }

    private static CloudworksRuntimeRecipe.Allocation allocation(List<CloudworksDetachedState.Stack> stacks) {
        Map<Integer, Long> amounts = new LinkedHashMap<>();
        for (CloudworksDetachedState.Stack stack : stacks) amounts.merge(stack.resourceId, stack.amount, Math::addExact);
        int[] ids = new int[amounts.size()];
        long[] counts = new long[ids.length];
        int index = 0;
        for (Map.Entry<Integer, Long> entry : amounts.entrySet()) {
            ids[index] = entry.getKey();
            counts[index++] = entry.getValue();
        }
        return new CloudworksRuntimeRecipe.Allocation(ids, counts);
    }

    private static void verifyReservations(CloudworksRuntimeResourceStore stock, int inventory,
            CloudworksRuntimeOperationStore operations, int node) {
        CloudworksRuntimeRecipe.Allocation demand = operations.inputs(node);
        for (int entry = stock.first(inventory); entry >= 0; entry = stock.next(entry)) {
            long expected = 0;
            if (demand != null)
                for (int index = 0; index < demand.ids.length; index++)
                    if (demand.ids[index] == stock.resource(entry)) expected = demand.amounts[index];
            if (stock.reservedAt(entry) != expected)
                throw new IllegalArgumentException("Runtime promise reservation mismatch");
        }
        if (demand != null)
            for (int index = 0; index < demand.ids.length; index++)
                if (stock.reserved(inventory, demand.ids[index]) != demand.amounts[index])
                    throw new IllegalArgumentException("Runtime promise reservation missing");
    }

    static byte mode(String mode) {
        switch (mode) {
            case "RECEIVE_INPUT": return CloudworksRuntimeChannelStore.RECEIVE_INPUT;
            case "TRANSFER_INPUT": return CloudworksRuntimeChannelStore.TRANSFER_INPUT;
            case "TRANSFER_OUTPUT": return CloudworksRuntimeChannelStore.TRANSFER_OUTPUT;
            case "SEND_OUTPUT": return CloudworksRuntimeChannelStore.SEND_OUTPUT;
            default: throw new IllegalArgumentException("Unknown runtime channel mode");
        }
    }

    public static byte state(String state) {
        switch (state) {
            case "OFF": return CloudworksRuntimeMachineStore.OFF;
            case "READY": return CloudworksRuntimeMachineStore.READY;
            case "RUNNING": return CloudworksRuntimeMachineStore.RUNNING;
            case "WAITING_INPUT": return CloudworksRuntimeMachineStore.WAITING_INPUT;
            case "WAITING_OUTPUT": return CloudworksRuntimeMachineStore.WAITING_OUTPUT;
            case "WAITING_POWER": return CloudworksRuntimeMachineStore.WAITING_POWER;
            case "GENERATING": return CloudworksRuntimeMachineStore.GENERATING;
            case "WAITING_FUEL": return CloudworksRuntimeMachineStore.WAITING_FUEL;
            case "UNSUPPORTED_RECIPE": return CloudworksRuntimeMachineStore.UNSUPPORTED_RECIPE;
            case "WAITING_SUNLIGHT": return CloudworksRuntimeMachineStore.WAITING_SUNLIGHT;
            case "WAITING_WIND": return CloudworksRuntimeMachineStore.WAITING_WIND;
            case "WAITING_HEAT": return CloudworksRuntimeMachineStore.WAITING_HEAT;
            case "NO_GENERATION": return CloudworksRuntimeMachineStore.NO_GENERATION;
            case "NO_RECIPE": return CloudworksRuntimeMachineStore.NO_RECIPE;
            case "WRONG_DIMENSION": return CloudworksRuntimeMachineStore.WRONG_DIMENSION;
            case "INSUFFICIENT_INPUT": return CloudworksRuntimeMachineStore.INSUFFICIENT_INPUT;
            case "NO_MATCHING_RECIPE": return CloudworksRuntimeMachineStore.NO_MATCHING_RECIPE;
            case "MISSING_INPUT": return CloudworksRuntimeMachineStore.MISSING_INPUT;
            case "NO_MATCHING_FUEL": return CloudworksRuntimeMachineStore.NO_MATCHING_FUEL;
            case "INSUFFICIENT_FUEL": return CloudworksRuntimeMachineStore.INSUFFICIENT_FUEL;
            case "WAITING_TEMPLATE": return CloudworksRuntimeMachineStore.WAITING_TEMPLATE;
            case "DECAYING": return CloudworksRuntimeMachineStore.DECAYING;
            case "DECAY_BLOCKED": return CloudworksRuntimeMachineStore.DECAY_BLOCKED;
            default: return CloudworksRuntimeMachineStore.READY;
        }
    }

    public static String state(byte state) {
        switch (state) {
            case CloudworksRuntimeMachineStore.OFF: return "OFF";
            case CloudworksRuntimeMachineStore.READY: return "READY";
            case CloudworksRuntimeMachineStore.RUNNING: return "RUNNING";
            case CloudworksRuntimeMachineStore.WAITING_INPUT: return "WAITING_INPUT";
            case CloudworksRuntimeMachineStore.WAITING_OUTPUT: return "WAITING_OUTPUT";
            case CloudworksRuntimeMachineStore.WAITING_POWER: return "WAITING_POWER";
            case CloudworksRuntimeMachineStore.GENERATING: return "GENERATING";
            case CloudworksRuntimeMachineStore.WAITING_FUEL: return "WAITING_FUEL";
            case CloudworksRuntimeMachineStore.UNSUPPORTED_RECIPE: return "UNSUPPORTED_RECIPE";
            case CloudworksRuntimeMachineStore.WAITING_SUNLIGHT: return "WAITING_SUNLIGHT";
            case CloudworksRuntimeMachineStore.WAITING_WIND: return "WAITING_WIND";
            case CloudworksRuntimeMachineStore.WAITING_HEAT: return "WAITING_HEAT";
            case CloudworksRuntimeMachineStore.NO_GENERATION: return "NO_GENERATION";
            case CloudworksRuntimeMachineStore.NO_RECIPE: return "NO_RECIPE";
            case CloudworksRuntimeMachineStore.WRONG_DIMENSION: return "WRONG_DIMENSION";
            case CloudworksRuntimeMachineStore.INSUFFICIENT_INPUT: return "INSUFFICIENT_INPUT";
            case CloudworksRuntimeMachineStore.NO_MATCHING_RECIPE: return "NO_MATCHING_RECIPE";
            case CloudworksRuntimeMachineStore.MISSING_INPUT: return "MISSING_INPUT";
            case CloudworksRuntimeMachineStore.NO_MATCHING_FUEL: return "NO_MATCHING_FUEL";
            case CloudworksRuntimeMachineStore.INSUFFICIENT_FUEL: return "INSUFFICIENT_FUEL";
            case CloudworksRuntimeMachineStore.WAITING_TEMPLATE: return "WAITING_TEMPLATE";
            case CloudworksRuntimeMachineStore.DECAYING: return "DECAYING";
            case CloudworksRuntimeMachineStore.DECAY_BLOCKED: return "DECAY_BLOCKED";
            default: throw new IllegalArgumentException("Unknown runtime machine state");
        }
    }

    final class MachineType {
        final boolean thermal;
        final long[] inputCapacity, outputCapacity, configurationCapacity;
        final String[] portNames;
        final int[] portCodec;
        final long[] portCapacity;
        final boolean[] input, configuration;
        final boolean[][] accepted;

        MachineType(CloudworksDetachedState.Node node, CloudworksDetachedState.ResourceRules rules) {
            thermal = node.thermal != null;
            inputCapacity = new long[codecCount];
            outputCapacity = new long[codecCount];
            configurationCapacity = new long[codecCount];
            portNames = new String[node.slots.size()];
            portCodec = new int[node.slots.size()];
            portCapacity = new long[node.slots.size()];
            input = new boolean[node.slots.size()];
            configuration = new boolean[node.slots.size()];
            accepted = new boolean[node.slots.size()][];
            for (int port = 0; port < node.slots.size(); port++) {
                CloudworksDetachedState.Slot slot = node.slots.get(port);
                int codec = codecIds.getOrDefault(slot.codec, -1);
                if (codec < 0) {
                    throw new IllegalArgumentException("Uncompiled runtime codec");
                }
                portNames[port] = slot.id;
                portCodec[port] = codec;
                portCapacity[port] = slot.capacity;
                configuration[port] = "CONFIGURATION".equals(slot.role);
                input[port] = "INPUT".equals(slot.role) || configuration[port];
                boolean output = "OUTPUT".equals(slot.role);
                if (configuration[port]) configurationCapacity[codec] = CloudworksQuantities.addLimit(configurationCapacity[codec], slot.capacity);
                else if (input[port]) inputCapacity[codec] = CloudworksQuantities.addLimit(inputCapacity[codec], slot.capacity);
                if (output) outputCapacity[codec] = CloudworksQuantities.addLimit(outputCapacity[codec], slot.capacity);
                accepted[port] = new boolean[resourceCodec.length];
                for (int resource = 0; resource < resourceCodec.length; resource++)
                    accepted[port][resource] = resourceCodec[resource] == codec
                            && (input[port] ? rules.acceptsInput(node.machineKey, slot.id, resource)
                                    : output && rules.allowsOutput(node, resource, slot.id, slot.codec));
            }
        }

        int port(String name, boolean isInput) {
            for (int port = 0; port < portNames.length; port++)
                if (portNames[port].equals(name) && input[port] == isInput) return port;
            return -1;
        }
    }
}
