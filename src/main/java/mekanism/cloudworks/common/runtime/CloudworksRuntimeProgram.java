package mekanism.cloudworks.common.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.common.service.CloudworksModuleBinding;
import mekanism.cloudworks.api.node.CloudworksPortDefinition;
import mekanism.cloudworks.api.transfer.CloudworksWarehouseCaps;
import mekanism.cloudworks.common.service.CloudworksDetachedState;
import mekanism.cloudworks.common.service.CloudworksQuantities;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetype;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import mekanism.cloudworks.api.node.CloudworksAuxiliaryNodeType;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;
import mekanism.cloudworks.api.work.PostWorkCapacitySnapshot;
import mekanism.cloudworks.common.data.CloudworksChannelNode;
import mekanism.cloudworks.common.registry.CloudworksAuxiliaryNodeRegistry;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.service.CloudworksOutputMultiplier;
import net.minecraft.util.ResourceLocation;

/** Cold compiler output. Normal ticks resolve neither UUIDs nor port strings. */
public final class CloudworksRuntimeProgram {
    private static final int[] NO_RESOURCES = new int[0];
    private static final double[] NO_RATES = new double[0];
    final int receiving, transfer, sending;
    final long receivingCapacity, transferCapacity, sendingCapacity;
    final int workRate, outputMultiplierUnits, codecCount, infuseCodec, initialClock;
    final int[] resourceCodec, conversionTarget, conversionUnits;
    final long[] resourceCap;
    final MachineType[] types;
    final int[] baseTicks, threads, nodeBufferMultiplier;
    final int[] physicalRoot, physicalEnd, thermalOwner;
    final String[] branchNames;
    final String[] completionMachineKeys;
    final long[] completionMachineCounts;
    final Map<ResourceLocation, Long>[] completionUpgrades;
    final List<CloudworksModuleBinding>[] modules;
    final List<CloudworksModuleBinding>[] postWorkModules;
    final PostWorkCapacitySnapshot[] postWorkCapacities;
    final boolean[] hasPreWork, hasRecipeCandidate, hasWorkStarted, hasWorkCommitted;
    final CloudworksGenerationContext[] environmentContexts;
    final boolean[] completionDiscardsChance;
    final List<QIOResourceDescriptor> resourceDescriptors;
    final int[][] generationResources;
    final double[][] generationRates;
    final double[][] generationCodecRates;
    final double[] generationTotalRates;
    final double[] machineLoad;
    final CloudworksGenerationPlan[] generationPlans;
    final boolean[] executable, generator, supported;
    final boolean[] recipeEnergy;
    final double[] timeMultiplier, energyMultiplier;
    final double[] secondaryChanceMultiplier;
    final double[] secondaryInputMultiplier;
    final boolean[] environmentReady;
    final boolean[] configuredUpgrades, receivingToLine, lineToSending;
    final int[] machineStart, machineEnd, channelStart, channelEnd;
    final int[] linkStart, linkEnd;
    final boolean receivingToCloud, receivingToLines, cloudToSending, linesToSending;
    final int initialCursor;
    final long[] nodeOutputSeeds;
    final Map<UUID, Integer> auxiliaryInventories;
    final Map<Integer, Long> auxiliaryCapacities;
    final CloudworksRuntimeRecipe[][] recipes, fuels;
    final boolean[] conditionalCandidates;
    final boolean[] decayTypes;
    final CloudworksRuntimeHeatFuel[] heat;
    final CloudworksRuntimeRecipe[] creditProcesses;
    final String[] creditKeys;
    final String[] creditPrefixes;
    final String[] costNodeKeys;
    final CloudworksThermalPlan[] thermalPlans;
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
        runtime.costRemainders().clear();
        receiving = inventory(stock, view.receiving);
        transfer = inventory(stock, view.transfer);
        sending = inventory(stock, view.sending);
        receivingCapacity = view.receiving.capacity;
        transferCapacity = view.transfer.capacity;
        sendingCapacity = view.sending.capacity;
        resourceDescriptors = Collections.unmodifiableList(new ArrayList<>(view.resourceDescriptors));
        auxiliaryInventories = new LinkedHashMap<>();
        auxiliaryCapacities = new LinkedHashMap<>();
        workRate = Math.max(1, view.workRate);
        outputMultiplierUnits = view.outputMultiplierUnits;
        initialClock = view.generationContext.clockTick();
        for (CloudworksDetachedState.Line line : view.lines) {
            for (CloudworksDetachedState.Node node : line.nodes)
                for (CloudworksDetachedState.Slot slot : node.slots) codec(slot.codec);
            for (CloudworksDetachedState.Link link : line.links) {
                if (link.sourcePort.contains(":")) codec(link.sourcePort);
                if (link.targetPort.contains(":")) codec(link.targetPort);
            }
            for (CloudworksDetachedState.Channel channel : line.channels) {
                CloudworksSpecialNodeArchetype archetype = specialArchetype(channel.archetypeId);
                if (archetype != null && archetype.explicitPorts())
                    for (CloudworksPortDefinition port : archetype.ports())
                        codec(port.codec().toString());
            }
        }
        resourceCodec = new int[families.size()];
        resourceCap = new long[families.size()];
        conversionTarget = new int[families.size()];
        conversionUnits = new int[families.size()];
        for (int resource = 0; resource < families.size(); resource++) {
            resourceCodec[resource] = codec(catalog.codec(resource));
            resourceCap[resource] = CloudworksWarehouseCaps.capOf(families.get(resource), view.warehouseItemTypeCap);
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
        conditionalCandidates = new boolean[types.length];
        decayTypes = new boolean[types.length];
        fuels = new CloudworksRuntimeRecipe[types.length][];
        heat = new CloudworksRuntimeHeatFuel[types.length];
        boolean[] typeRecipeEnergy = new boolean[types.length];
        for (Map.Entry<String, Integer> entry : typeIds.entrySet()) {
            heat[entry.getValue()] = catalog.heat(entry.getKey());
                recipes[entry.getValue()] = catalog.recipes(entry.getKey(), false)
                    .toArray(new CloudworksRuntimeRecipe[0]);
            fuels[entry.getValue()] = catalog.recipes(entry.getKey(), true)
                    .toArray(new CloudworksRuntimeRecipe[0]);
            for (CloudworksRuntimeRecipe recipe : recipes[entry.getValue()])
                conditionalCandidates[entry.getValue()] |= recipe.requiredEnvironmentAxis != null || !recipe.selectionGroup.isEmpty();
                for (CloudworksRuntimeRecipe recipe : recipes[entry.getValue()])
                    decayTypes[entry.getValue()] |= recipe.decayAmount >= 0;
            for (CloudworksRuntimeRecipe recipe : recipes[entry.getValue()])
                if (recipe.extraEnergyPerTick > 0D) typeRecipeEnergy[entry.getValue()] = true;
            if (decayTypes[entry.getValue()])
                for (CloudworksRuntimeRecipe recipe : recipes[entry.getValue()])
                    if (recipe.decayAmount < 0) throw new IllegalArgumentException("Mixed decay and processing contracts");
            CloudworksRuntimeHeatFuel heatPlan = heat[entry.getValue()];
            if (heatPlan != null) {
                MachineType type = types[entry.getValue()];
                int fluidCodec = resourceCodec[heatPlan.lava];
                int itemCodec = codecIds.getOrDefault(QIOResourceCodecs.ITEM_STACK_ID.toString(), -1);
                boolean[] fuels = new boolean[resourceCodec.length];
                for (CloudworksRuntimeHeatFuel.Fuel fuel : heatPlan.fuels) fuels[fuel.resource] = true;
                for (int port = 0; port < type.portNames.length; port++) {
                    if (!type.input[port] || !type.recipeFilteredInput[port]) continue;
                    if (type.portCodec[port] == fluidCodec)
                        for (int resource = 0; resource < resourceCodec.length; resource++)
                            type.accepted[port][resource] = type.thermalKind == CloudworksThermalPlan.Kind.HEAT_GENERATOR
                                    && resource == heatPlan.lava;
                    else if (type.portCodec[port] == itemCodec)
                        for (int resource = 0; resource < resourceCodec.length; resource++)
                            type.accepted[port][resource] = fuels[resource];
                }
            }
        }
        baseTicks = new int[nodes.size()];
        creditProcesses = new CloudworksRuntimeRecipe[nodes.size()]; creditKeys = new String[nodes.size()];
        creditPrefixes = new String[nodes.size()];
        costNodeKeys = new String[nodes.size()];
        threads = new int[nodes.size()];
        nodeBufferMultiplier = new int[nodes.size()];
        physicalRoot = new int[nodes.size()]; physicalEnd = new int[nodes.size()]; thermalOwner = new int[nodes.size()];
        branchNames = new String[nodes.size()];
        completionMachineKeys = new String[nodes.size()];
        completionMachineCounts = new long[nodes.size()];
        @SuppressWarnings("unchecked")
        Map<ResourceLocation, Long>[] upgrades = new Map[nodes.size()];
        completionUpgrades = upgrades;
        modules = new List[nodes.size()];
        postWorkModules = new List[nodes.size()];
        postWorkCapacities = new PostWorkCapacitySnapshot[nodes.size()];
        hasPreWork = new boolean[nodes.size()];
        hasRecipeCandidate = new boolean[nodes.size()];
        hasWorkStarted = new boolean[nodes.size()];
        hasWorkCommitted = new boolean[nodes.size()];
        environmentContexts = new CloudworksGenerationContext[nodes.size()];
        completionDiscardsChance = new boolean[nodes.size()];
        generationResources = new int[nodes.size()][]; generationRates = new double[nodes.size()][];
        generationCodecRates = new double[nodes.size()][];
        generationTotalRates = new double[nodes.size()];
        Arrays.fill(thermalOwner, -1);
        machineLoad = new double[nodes.size()];
        nodeOutputSeeds = new long[nodes.size()];
        generationPlans = new CloudworksGenerationPlan[nodes.size()];
        executable = new boolean[nodes.size()];
        generator = new boolean[nodes.size()];
        supported = new boolean[nodes.size()];
        recipeEnergy = new boolean[nodes.size()];
        int lineCount = view.lines.size();
        timeMultiplier = new double[nodes.size()];
        energyMultiplier = new double[nodes.size()];
        secondaryChanceMultiplier = new double[nodes.size()];
        secondaryInputMultiplier = new double[nodes.size()];
        environmentReady = new boolean[nodes.size()];
        configuredUpgrades = new boolean[nodes.size()];
        receivingToLine = new boolean[lineCount];
        lineToSending = new boolean[lineCount];
        machineStart = new int[lineCount];
        machineEnd = new int[lineCount];
        channelStart = new int[lineCount];
        channelEnd = new int[lineCount];
        linkStart = new int[lineCount];
        linkEnd = new int[lineCount];
        runtime.operations().resize(nodes.size());
        runtime.machines().bindOperations(runtime.operations());
        boolean thermalPresent = false;
        IntArrayList thermalIndices = new IntArrayList();
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).thermal != null && nodes.get(index).machineCount > 0) thermalIndices.add(index);
        }
        thermalNodes = thermalIndices.toIntArray();
        thermalPresent = thermalNodes.length > 0;
        runtime.thermal().resize(nodes.size(), thermalPresent);
        thermalPlans = new CloudworksThermalPlan[nodes.size()];
        thermalEnvironmentHeat = new double[nodes.size()];
        thermalDissipation = new double[nodes.size()];
        thermalSolarCount = new int[nodes.size()];
        Map<UUID, Integer> machineIds = new HashMap<>(), channelIds = new HashMap<>(),
                auxiliaryIds = new HashMap<>();
        runtime.energy().setCloud(view.energy, view.maxEnergy, view.unlimitedEnergy);
        runtime.energy().setRelays(view.receivingEnergy, view.sendingEnergy,
                view.receivingCapacity, view.sendingCapacity);
        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            CloudworksDetachedState.Line line = view.lines.get(lineIndex);
            runtime.energy().addLine(line.energy, line.maxEnergy);
            receivingToLine[lineIndex] = line.energyFlowReceivingToLine;
            lineToSending[lineIndex] = line.energyFlowLineToSending;
            machineStart[lineIndex] = machines.size();
            for (CloudworksDetachedState.Node node : line.nodes) {
                int type = typeIds.get(node.machineKey);
                int index = machines.add(type, node.machineCount, node.active, Math.max(1, node.totalTicks));
                int root = node.branchId.isEmpty() ? index : machineIds.get(node.physicalNode);
                physicalRoot[index] = root; physicalEnd[root] = index + 1; branchNames[index] = node.branchId;
                runtime.operations().bindPhysicalRoot(index, root);
                completionMachineKeys[index] = node.machineKey;
                completionMachineCounts[index] = node.machineCount;
                completionUpgrades[index] = Collections.unmodifiableMap(new HashMap<>(node.customUpgrades));
                MachineExecutionProfile moduleProfile =
                        CloudworksRegistries.profile(node.machineStack);
                modules[index] = new ArrayList<>(CloudworksModuleBinding.upgrades(node.physicalNode,
                        node.machineStack, node.machineCount, node.customUpgrades,
                        moduleProfile == null ? MachineUpgradePolicy.NONE : moduleProfile.upgradePolicy(),
                        node.environment, node.machineContext));
                modules[index].addAll(node.environmentModules);
                for (int binding = 0; binding < modules[index].size(); binding++)
                    modules[index].set(binding, modules[index].get(binding).forExecution(line.id, node.branchId));
                runtime.costRemainders().putAll(node.moduleCostRemainders);
                environmentContexts[index] = node.environment;
                completionDiscardsChance[index] = modules[index].stream().anyMatch(CloudworksModuleBinding::discardsChance);
                machines.restoreOutputSequence(index, node.outputSequence);
                nodeOutputSeeds[index] = CloudworksOutputMultiplier.mix(
                        catalog.seed() ^ view.owner.getMostSignificantBits()
                                ^ Long.rotateLeft(view.owner.getLeastSignificantBits(), 11)
                                ^ node.id.getMostSignificantBits() ^ Long.rotateLeft(node.id.getLeastSignificantBits(), 37));
                if (machineIds.put(node.id, index) != null)
                    throw new IllegalArgumentException("Duplicate runtime machine");
                int input = root == index ? inventory(stock, node.inputs) : machines.input(root);
                int output = root == index ? inventory(stock, node.outputs) : machines.output(root);
                machines.bind(index, lineIndex, input, output);
                boolean hasConfiguration = false;
                for (boolean configuration : types[type].configuration) hasConfiguration |= configuration;
                if (hasConfiguration) machines.bindConfiguration(index,
                        root == index ? inventory(stock, node.templates) : machines.configuration(root));
                else if (!node.templates.isEmpty()) throw new IllegalArgumentException("Template without configuration port");
                machines.setProgress(index, Math.min(node.progress, Math.max(0, node.totalTicks - 1)),
                        Math.max(1, node.totalTicks));
                machines.setState(index, state(node.state));
                baseTicks[index] = node.baseTicks;
                threads[index] = Math.max(1, node.threads);
                nodeBufferMultiplier[index] = view.lines.get(machines.line(index)).nodeBufferMultiplier;
                executable[index] = node.executable;
                recipeEnergy[index] = typeRecipeEnergy[type];
                costNodeKeys[index] = node.physicalNode.toString();
                creditPrefixes[index] = node.branchId + "|";
                for (CloudworksRuntimeRecipe[] family : new CloudworksRuntimeRecipe[][]{recipes[type], fuels[type]})
                    for (CloudworksRuntimeRecipe candidate : family) {
                        if (candidate.energyPerInput <= 0 && candidate.prepaidFuelUnits <= 0) continue;
                        if (creditProcesses[index] == null) creditProcesses[index] = candidate;
                        mekanism.cloudworks.common.data.CloudworksResourceCredit saved =
                                node.processCredits.get(creditPrefixes[index] + candidate.recipeId);
                        if (saved != null && saved.units > 0) creditProcesses[index] = candidate;
                    }
                if (creditProcesses[index] != null) {
                    creditKeys[index] = creditPrefixes[index] + creditProcesses[index].recipeId;
                    mekanism.cloudworks.common.data.CloudworksResourceCredit credit = node.processCredits.get(creditKeys[index]);
                    if (credit != null) {
                        if (!resourceDescriptors.get(creditProcesses[index].creditResource).equals(credit.resource))
                            throw new IllegalArgumentException("Processing credit resource changed");
                        machines.processCredit(index, credit.units);
                    }
                }
                timeMultiplier[index] = node.timeMultiplier;
                energyMultiplier[index] = node.energyMultiplier;
                secondaryChanceMultiplier[index] = node.secondaryChanceMultiplier;
                secondaryInputMultiplier[index] = node.secondaryInputMultiplier;
                environmentReady[index] = node.environmentReady;
                configuredUpgrades[index] = node.configuredUpgrades;
                machineLoad[index] = node.energyPerTick * node.machineCount * threads[index] * workRate;
                if (!Double.isFinite(machineLoad[index]) || machineLoad[index] < 0)
                    throw new IllegalArgumentException("Runtime machine load overflow");
                generationPlans[index] = catalog.generation(node.id);
                if (generationPlans[index].resourceOutputs().isEmpty() && node.generationRemainders.isEmpty()) {
                    generationResources[index] = NO_RESOURCES;
                    generationRates[index] = generationCodecRates[index] = NO_RATES;
                    machines.configureGenerationRemainders(index, NO_RATES);
                } else {
                Map<Integer, Double> rates = new LinkedHashMap<>();
                for (CloudworksGenerationPlan.ResourceOutput value : generationPlans[index].resourceOutputs())
                    rates.put(catalog.generationResource(value.resource()), value.unitsPerEnergy());
                for (Integer resource : node.generationRemainders.keySet()) rates.putIfAbsent(resource, 0D);
                if (rates.size() > 64) throw new IllegalArgumentException("Too many generation resource types");
                generationResources[index] = new int[rates.size()];
                generationRates[index] = new double[rates.size()];
                generationCodecRates[index] = rates.isEmpty() ? new double[0] : new double[codecCount];
                double[] credits = new double[rates.size()];
                int position = 0;
                for (Map.Entry<Integer, Double> value : rates.entrySet()) {
                    generationResources[index][position] = value.getKey(); generationRates[index][position] = value.getValue();
                    credits[position++] = node.generationRemainders.getOrDefault(value.getKey(), 0D);
                    generationCodecRates[index][resourceCodec[value.getKey()]] += value.getValue();
                    generationTotalRates[index] += value.getValue();
                    if (!Double.isFinite(generationTotalRates[index]))
                        throw new IllegalArgumentException("Generation rate overflow");
                }
                machines.configureGenerationRemainders(index, credits);
                }
                generator[index] = catalog.generator(node.id) || fuels[type].length > 0;
                supported[index] = !catalog.unsupported().containsKey(node.machineKey);
                thermalPlans[index] = node.machineCount == 0 ? null : node.thermal;
                runtime.thermal().restore(index, thermalPlans[index], node.machineCount, node.thermalState);
                if (thermalPlans[index] != null) {
                    if (thermalOwner[root] >= 0) throw new IllegalArgumentException("Multiple thermal owners of one node");
                    thermalOwner[root] = index;
                }
                if (node.thermal != null) {
                    thermalEnvironmentHeat[index] = node.environmentHeat;
                    thermalDissipation[index] = node.thermal.transfer;
                    thermalSolarCount[index] = node.thermal.solarPanels;
                }
                if (node.operation != null) {
                    restoreOperation(runtime, index, node.operation, node.progress, node.state,
                            Math.max(0, node.outputSequence - 1));
                }
                for (CloudworksDetachedState.ProcessingBatch batch : node.processingBatches)
                    restoreOperation(runtime, index, batch.operation, batch.progress, batch.state, batch.sequence);
                runtime.operations().resetSelection(index);
            }
            machineEnd[lineIndex] = machines.size();
            channelStart[lineIndex] = channels.size();
            for (CloudworksDetachedState.Channel channel : line.channels) {
                if (!channel.archetypeId.isEmpty()) {
                    CloudworksSpecialNodeArchetype archetype =
                            CloudworksSpecialNodeArchetypes.require(channel.archetypeId);
                    if (archetype.source() != (mode(channel.mode) == CloudworksRuntimeChannelStore.RECEIVE_INPUT))
                        throw new IllegalArgumentException("Special node direction mismatch");
                }
                int index = channels.add(lineIndex, mode(channel.mode), channel.archetypeId, channel.enabled,
                        channel.buffer.capacity, inventory(stock, channel.buffer), inventory(stock, channel.manifest),
                        channelPorts(channel, line));
                if (channelIds.put(channel.id, index) != null)
                    throw new IllegalArgumentException("Duplicate runtime channel");
            }
            channelEnd[lineIndex] = channels.size();
            for (CloudworksDetachedState.Auxiliary auxiliary : line.auxiliaries) {
                int inputInventory = inventory(stock, auxiliary.input);
                auxiliaryInventories.put(auxiliary.id, inputInventory);
                auxiliaryCapacities.put(inputInventory, auxiliary.input.capacity);
                runtime.costRemainders().putAll(auxiliary.costRemainders);
                for (Map.Entry<UUID, CloudworksModuleBinding> target
                        : auxiliary.effects.entrySet()) {
                    Integer machine = machineIds.get(target.getKey());
                    if (machine == null) continue;
                    for (int execution = machine; execution < physicalEnd[machine]; execution++) {
                        modules[execution].add(target.getValue().forExecution(line.id, branchNames[execution]));
                        completionDiscardsChance[execution] |= target.getValue().discardsChance();
                    }
                }
            }
            for (CloudworksDetachedState.Auxiliary auxiliary : line.auxiliaries)
                auxiliaryIds.put(auxiliary.id, auxiliaryInventories.get(auxiliary.id));
        }
        PostWorkCapacitySnapshot capacityResources =
                new PostWorkCapacitySnapshot(resourceDescriptors, Collections.emptyMap());
        Map<Integer, Map<Long, PostWorkCapacitySnapshot>> capacityCache = new HashMap<>();
        for (int node = 0; node < machines.size(); node++)
            {
                modules[node].sort(Comparator.comparingInt((CloudworksModuleBinding binding) -> 
                        binding.handle.getCustomInstance().postWorkPriority())
                        .thenComparing(binding -> binding.handle.getData().id())
                        .thenComparing(binding -> binding.handle.getSourceNodeId(),
                                Comparator.nullsFirst(Comparator.naturalOrder())));
                List<CloudworksModuleBinding> post = new ArrayList<>();
                for (CloudworksModuleBinding binding : modules[node]) {
                    if (binding.hasPostWork) post.add(binding);
                    hasPreWork[node] |= binding.callbacks.preWork;
                    hasRecipeCandidate[node] |= binding.callbacks.candidate;
                    hasWorkStarted[node] |= binding.callbacks.started;
                    hasWorkCommitted[node] |= binding.callbacks.committed;
                }
                postWorkModules[node] = post.isEmpty() ? Collections.emptyList()
                        : Collections.unmodifiableList(post);
                if (!post.isEmpty()) {
                    int type = machines.machineType(node);
                    long scale = CloudworksQuantities.multiplyLimit(machines.machineCount(node), nodeBufferMultiplier[node]);
                    Map<Long, PostWorkCapacitySnapshot> byScale =
                            capacityCache.computeIfAbsent(type, ignored -> new HashMap<>());
                    PostWorkCapacitySnapshot snapshot = byScale.get(scale);
                    if (snapshot == null) {
                        Map<ResourceLocation, Long> capacities = new LinkedHashMap<>();
                        for (Map.Entry<String, Integer> codec : codecIds.entrySet())
                            capacities.put(new ResourceLocation(codec.getKey()),
                                    CloudworksQuantities.multiplyLimit(types[type].outputCapacity[codec.getValue()], scale));
                        snapshot = capacityResources.withCodecCapacities(capacities);
                        byScale.put(scale, snapshot);
                    }
                    postWorkCapacities[node] = snapshot;
                }
                if (physicalRoot[node] == node)
                verifyReservations(stock, machines.input(node), runtime.operations(), node, physicalEnd[node]);
            }
        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            linkStart[lineIndex] = runtime.links().size();
            compileLinks(lineIndex, view.lines.get(lineIndex), machineIds, channelIds, auxiliaryIds, runtime);
            linkEnd[lineIndex] = runtime.links().size();
        }
        IntArrayList heatLinks = new IntArrayList();
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
                IntArrayList groups = new IntArrayList();
                IntArrayList configuration = new IntArrayList();
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
                    IntArrayList output = new IntArrayList();
                    output.add(resourceCodec[resource]);
                    for (int port = 0; port < machineType.portNames.length; port++)
                        if (!machineType.input[port] && machineType.accepted[port][resource]) output.add(codecCount + port);
                    thermalOutputGroups[type][resource] = output.toIntArray();
                }
            }
        }
        for (int node = 0; node < machines.size(); node++) {
            if (physicalRoot[node] != node) continue;
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
    }

    private void compileLinks(int lineIndex, CloudworksDetachedState.Line line, Map<UUID, Integer> machines,
            Map<UUID, Integer> channels, Map<UUID, Integer> auxiliaries, CloudworksPlayerRuntime runtime) {
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
            for (UUID target : outgoing.getOrDefault(id, Collections.emptyList()))
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
            Integer targetAuxiliary = auxiliaries.get(link.target);
            byte phase;
            int source, target, sourcePort, targetPort;
            if (sourceMachine != null && targetMachine != null) {
                phase = CloudworksRuntimeLinkStore.MACHINE_TO_MACHINE;
                source = sourceMachine; target = targetMachine;
                sourcePort = types[runtime.machines().machineType(source)].port(link.sourcePort, false);
                targetPort = types[runtime.machines().machineType(target)].port(link.targetPort, true);
                if (thermalLink(link, machines)) {
                    phase = CloudworksRuntimeLinkStore.HEAT;
                    source = thermalOwner[physicalRoot[source]];
                    target = thermalOwner[physicalRoot[target]];
                    if (source < 0 || target < 0) continue;
                }
            } else if (sourceChannel != null && targetMachine != null) {
                CloudworksPortDefinition sourceDefinition =
                        channelPort(runtime.channels(), sourceChannel, link.sourcePort);
                if (!sourceDefinition.canOutput())
                    throw new IllegalArgumentException("Invalid channel source");
                phase = CloudworksRuntimeLinkStore.CHANNEL_TO_MACHINE;
                source = sourceChannel; target = targetMachine;
                sourcePort = codecIds.getOrDefault(sourceDefinition.codec().toString(), -1);
                targetPort = types[runtime.machines().machineType(target)].port(link.targetPort, true);
            } else if (sourceMachine != null && targetChannel != null) {
                CloudworksPortDefinition targetDefinition =
                        channelPort(runtime.channels(), targetChannel, link.targetPort);
                if (!targetDefinition.canInput())
                    throw new IllegalArgumentException("Invalid channel target");
                phase = CloudworksRuntimeLinkStore.MACHINE_TO_CHANNEL;
                source = sourceMachine; target = targetChannel;
                sourcePort = types[runtime.machines().machineType(source)].port(link.sourcePort, false);
                targetPort = codecIds.getOrDefault(targetDefinition.codec().toString(), -1);
            } else if (sourceChannel != null && targetChannel != null) {
                byte from = runtime.channels().mode(sourceChannel), to = runtime.channels().mode(targetChannel);
                CloudworksPortDefinition sourceDefinition =
                        channelPort(runtime.channels(), sourceChannel, link.sourcePort);
                CloudworksPortDefinition targetDefinition =
                        channelPort(runtime.channels(), targetChannel, link.targetPort);
                if (!sourceDefinition.canOutput() || !targetDefinition.canInput()
                        || !sourceDefinition.codec().equals(targetDefinition.codec()))
                    throw new IllegalArgumentException("Invalid runtime channel link");
                if (from == CloudworksRuntimeChannelStore.RECEIVE_INPUT
                        || from == CloudworksRuntimeChannelStore.TRANSFER_INPUT)
                    phase = CloudworksRuntimeLinkStore.CHANNEL_INPUT;
                else
                    phase = CloudworksRuntimeLinkStore.CHANNEL_OUTPUT;
                source = sourceChannel; target = targetChannel;
                sourcePort = codecIds.getOrDefault(sourceDefinition.codec().toString(), -1);
                targetPort = codecIds.getOrDefault(targetDefinition.codec().toString(), -1);
                if (sourcePort != targetPort) throw new IllegalArgumentException("Mismatched runtime channel ports");
            } else if (sourceChannel != null && targetAuxiliary != null) {
                CloudworksPortDefinition sourceDefinition =
                        channelPort(runtime.channels(), sourceChannel, link.sourcePort);
                CloudworksPortDefinition targetDefinition = auxiliaryPort(line, link.target, link.targetPort);
                if (!sourceDefinition.canOutput() || !targetDefinition.canInput()
                        || !sourceDefinition.codec().equals(targetDefinition.codec()))
                    throw new IllegalArgumentException("Invalid auxiliary input link");
                phase = CloudworksRuntimeLinkStore.AUXILIARY_INPUT;
                source = sourceChannel; target = targetAuxiliary;
                sourcePort = codecIds.getOrDefault(sourceDefinition.codec().toString(), -1);
                targetPort = sourcePort;
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


    private static CloudworksSpecialNodeArchetype specialArchetype(String id) {
        return id == null || id.isEmpty() ? null : CloudworksSpecialNodeArchetypes.get(id);
    }

    private static List<CloudworksPortDefinition> channelPorts(CloudworksDetachedState.Channel channel,
            CloudworksDetachedState.Line line) {
        CloudworksSpecialNodeArchetype archetype = specialArchetype(channel.archetypeId);
        if (archetype != null && archetype.explicitPorts()) return archetype.ports();
        LinkedHashSet<String> codecs = new LinkedHashSet<>();
        for (CloudworksDetachedState.Link link : line.links) {
            if (channel.id.equals(link.source)) codecs.add(link.sourcePort);
            if (channel.id.equals(link.target)) codecs.add(link.targetPort);
        }
        List<CloudworksPortDefinition> definitions = new ArrayList<>(codecs.size());
        for (String codec : codecs) definitions.add(channelMode(mode(channel.mode)).port(codec));
        return definitions;
    }

    private CloudworksPortDefinition auxiliaryPort(CloudworksDetachedState.Line line, UUID id, String portId) {
        for (CloudworksDetachedState.Auxiliary auxiliary : line.auxiliaries)
            if (auxiliary.id.equals(id)) {
                CloudworksAuxiliaryNodeType type =
                        CloudworksAuxiliaryNodeRegistry.get(auxiliary.typeId);
                if (type != null) for (CloudworksPortDefinition port : type.ports())
                    if (port.id().equals(portId)) return port;
            }
        throw new IllegalArgumentException("Unknown auxiliary port");
    }

    private boolean thermalLink(CloudworksDetachedState.Link link, Map<UUID, Integer> machines) {
        Integer source = machines.get(link.source), target = machines.get(link.target);
        return source != null && target != null
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
            CloudworksRuntimeOperationStore operations, int start, int end) {
        Map<Integer, Long> required = new HashMap<>();
        for (int node = start; node < end; node++) {
            for (CloudworksRuntimeBatch batch : operations.batches(node)) {
                CloudworksRuntimeRecipe.Allocation demand = batch.inputs;
                if (demand != null) for (int index = 0; index < demand.ids.length; index++)
                    required.merge(demand.ids[index], demand.amounts[index], Math::addExact);
            }
        }
        for (int entry = stock.first(inventory); entry >= 0; entry = stock.next(entry)) {
            long expected = required.getOrDefault(stock.resource(entry), 0L);
            if (stock.reservedAt(entry) != expected)
                throw new IllegalArgumentException("Runtime promise reservation mismatch");
        }
        for (Map.Entry<Integer, Long> demand : required.entrySet())
                if (stock.reserved(inventory, demand.getKey()) != demand.getValue())
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

    private static CloudworksPortDefinition channelPort(CloudworksRuntimeChannelStore channels,
            int channel, String portId) {
        for (CloudworksPortDefinition definition : channels.ports(channel))
            if (definition.id().equals(portId)) return definition;
        throw new IllegalArgumentException("Unknown runtime channel port: " + portId);
    }

    private static CloudworksChannelNode.Mode channelMode(byte mode) {
        switch (mode) {
            case CloudworksRuntimeChannelStore.RECEIVE_INPUT:
                return CloudworksChannelNode.Mode.RECEIVE_INPUT;
            case CloudworksRuntimeChannelStore.TRANSFER_INPUT:
                return CloudworksChannelNode.Mode.TRANSFER_INPUT;
            case CloudworksRuntimeChannelStore.TRANSFER_OUTPUT:
                return CloudworksChannelNode.Mode.TRANSFER_OUTPUT;
            case CloudworksRuntimeChannelStore.SEND_OUTPUT:
                return CloudworksChannelNode.Mode.SEND_OUTPUT;
            default:
                throw new IllegalArgumentException("Unknown runtime channel mode");
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
            case "WAITING_TIME": return CloudworksRuntimeMachineStore.WAITING_TIME;
            case "WAITING_FUEL": return CloudworksRuntimeMachineStore.WAITING_FUEL;
            case "UNSUPPORTED_RECIPE": return CloudworksRuntimeMachineStore.UNSUPPORTED_RECIPE;
            case "WAITING_SUNLIGHT": return CloudworksRuntimeMachineStore.WAITING_SUNLIGHT;
            case "WAITING_WIND": return CloudworksRuntimeMachineStore.WAITING_WIND;
            case "WAITING_HEAT": return CloudworksRuntimeMachineStore.WAITING_HEAT;
            case "NO_GENERATION": return CloudworksRuntimeMachineStore.NO_GENERATION;
            case "NO_RECIPE": return CloudworksRuntimeMachineStore.NO_RECIPE;
            case "INSUFFICIENT_INPUT": return CloudworksRuntimeMachineStore.INSUFFICIENT_INPUT;
            case "NO_MATCHING_RECIPE": return CloudworksRuntimeMachineStore.NO_MATCHING_RECIPE;
            case "MISSING_INPUT": return CloudworksRuntimeMachineStore.MISSING_INPUT;
            case "NO_MATCHING_FUEL": return CloudworksRuntimeMachineStore.NO_MATCHING_FUEL;
            case "INSUFFICIENT_FUEL": return CloudworksRuntimeMachineStore.INSUFFICIENT_FUEL;
            case "WAITING_TEMPLATE": return CloudworksRuntimeMachineStore.WAITING_TEMPLATE;
            case "WAITING_ENVIRONMENT": return CloudworksRuntimeMachineStore.WAITING_ENVIRONMENT;
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
            case CloudworksRuntimeMachineStore.INSUFFICIENT_INPUT: return "INSUFFICIENT_INPUT";
            case CloudworksRuntimeMachineStore.NO_MATCHING_RECIPE: return "NO_MATCHING_RECIPE";
            case CloudworksRuntimeMachineStore.MISSING_INPUT: return "MISSING_INPUT";
            case CloudworksRuntimeMachineStore.NO_MATCHING_FUEL: return "NO_MATCHING_FUEL";
            case CloudworksRuntimeMachineStore.INSUFFICIENT_FUEL: return "INSUFFICIENT_FUEL";
            case CloudworksRuntimeMachineStore.WAITING_TEMPLATE: return "WAITING_TEMPLATE";
            case CloudworksRuntimeMachineStore.WAITING_ENVIRONMENT: return "WAITING_ENVIRONMENT";
            case CloudworksRuntimeMachineStore.DECAYING: return "DECAYING";
            case CloudworksRuntimeMachineStore.DECAY_BLOCKED: return "DECAY_BLOCKED";
            case CloudworksRuntimeMachineStore.WAITING_TIME: return "WAITING_TIME";
            default: throw new IllegalArgumentException("Unknown runtime machine state");
        }
    }

    private static void restoreOperation(CloudworksPlayerRuntime runtime, int index,
            CloudworksDetachedState.Operation operation, int progress, String state, long fallbackSequence) {
        CloudworksRuntimeOperationStore operations = runtime.operations();
        operations.select(index, null);
        operations.restore(index, operation.operations, operation.duration, allocation(operation.inputs),
                allocation(operation.outputs), allocation(operation.chanceOutputs), operation.extraEnergyPerTick,
                operation.completionPrepared, operation.recipeId);
        operations.current(index).id = operation.id;
        operations.current(index).requiredEnvironmentAxis = operation.requiredEnvironmentAxis;
        operations.setModulePower(index, operation.modulePower);
        operations.setLifecycleId(index, operation.lifecycleId);
        operations.setParallelism(index, operation.parallelUnits,
                operation.outputSequence >= 0 ? operation.outputSequence : fallbackSequence);
        runtime.machines().setProgress(index, Math.min(progress, operation.duration - 1), operation.duration);
        runtime.machines().setState(index, state(state));
    }

    final class MachineType {
        final boolean thermal;
        final CloudworksThermalPlan.Kind thermalKind;
        final long[] inputCapacity, outputCapacity, configurationCapacity;
        final String[] portNames;
        final int[] portCodec;
        final long[] portCapacity;
        final boolean[] input, configuration, recipeFilteredInput;
        final boolean[][] accepted;

        MachineType(CloudworksDetachedState.Node node, CloudworksDetachedState.ResourceRules rules) {
            thermal = node.slots.stream().anyMatch(slot -> CloudworksThermalPlan.HEAT.toString().equals(slot.codec));
            thermalKind = node.thermal == null ? null : node.thermal.kind;
            inputCapacity = new long[codecCount];
            outputCapacity = new long[codecCount];
            configurationCapacity = new long[codecCount];
            portNames = new String[node.slots.size()];
            portCodec = new int[node.slots.size()];
            portCapacity = new long[node.slots.size()];
            input = new boolean[node.slots.size()];
            configuration = new boolean[node.slots.size()];
            recipeFilteredInput = new boolean[node.slots.size()];
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
                recipeFilteredInput[port] = slot.recipeFilteredInput;
                input[port] = "INPUT".equals(slot.role) || configuration[port];
                boolean output = "OUTPUT".equals(slot.role);
                if (configuration[port]) configurationCapacity[codec] = CloudworksQuantities.addLimit(configurationCapacity[codec], slot.capacity);
                else if (input[port]) inputCapacity[codec] = CloudworksQuantities.addLimit(inputCapacity[codec], slot.capacity);
                if (output) outputCapacity[codec] = CloudworksQuantities.addLimit(outputCapacity[codec], slot.capacity);
                accepted[port] = new boolean[resourceCodec.length];
                for (int resource = 0; resource < resourceCodec.length; resource++)
                    accepted[port][resource] = resourceCodec[resource] == codec
                            && (input[port] ? rules.acceptsInput(node.rulesKey, slot.id, resource)
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
