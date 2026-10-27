package mekanism.cloudworks.common.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineDefinition;
import mekanism.cloudworks.api.machine.CloudworksMachineInfo;
import mekanism.cloudworks.api.machine.CloudworksMachineMutation;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.recipe.CloudworksAsyncRecipeAdmission;
import mekanism.cloudworks.common.recipe.CloudworksFrozenRecipeView;
import mekanism.cloudworks.common.recipe.MachineRecipeTypeEntry;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import net.minecraft.item.ItemStack;

import java.util.Optional;

import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.recipe.CloudworksRecipeBatch;
import net.minecraft.util.ResourceLocation;

/**
 * Internal lifecycle consumer for the public declaration queue.
 */
public final class CloudworksApiRegistration implements CloudworksAPI.MachineServices {
    private static final CloudworksApiRegistration SERVICES = new CloudworksApiRegistration();

    private CloudworksApiRegistration() {
    }

    public static void installServices() {
        CloudworksAPI.installMachineServices(SERVICES);
    }

    public static void validateRegisteredContracts() {
        SERVICES.validateRegisteredContracts0();
    }

    public static void freezeAndRegister() {
        registerPending(true);
    }

    public static void applyPending() {
        registerPending(false);
    }

    private static void registerPending(boolean freeze) {
        Object registryCheckpoint = CloudworksRegistries.checkpoint();
        Object adapterCheckpoint = CloudworksMachineAdapters.checkpoint();
        Object admissionCheckpoint = CloudworksAsyncRecipeAdmission.checkpoint();
        try {
            CloudworksAPI.installMachineServices(SERVICES);
            SERVICES.templates.clear();
            SERVICES.aliases.clear();
            if (freeze) CloudworksAPI.freezeAndDrain(CloudworksApiRegistration::register,
                    SERVICES::applyMachineMutation, SERVICES::finishBindings);
            else {
                CloudworksAPI.applyPendingMachines(CloudworksApiRegistration::register,
                        SERVICES::applyMachineMutation);
                SERVICES.finishBindings();
            }
        } catch (RuntimeException failure) {
            // Transactional drain: restore every side effect, including adapter
            // identity ids and async admission state.
            CloudworksRegistries.restore(registryCheckpoint);
            CloudworksMachineAdapters.restore(adapterCheckpoint);
            CloudworksAsyncRecipeAdmission.restore(admissionCheckpoint);
            throw failure;
        } finally {
            SERVICES.templates.clear();
            SERVICES.aliases.clear();
        }
    }

    private final Map<MachineKey, MachineKey> templates = new LinkedHashMap<>();
    private final List<CloudworksMachineMutation> aliases = new ArrayList<>();

    private static void register(CloudworksMachineDefinition definition) {
        List<MachineRecipeTypeEntry> entries = new ArrayList<>();
        for (CloudworksRecipeMode mode : definition.modes()) {
            MachineSlotLayout modeSlots =
                    mode.slots() == null ? definition.slots() : mode.slots();
            CloudworksMachineRegistration registration = new CloudworksMachineRegistration(
                    definition.machine(), definition.profile(), definition.identity(), modeSlots, mode.id());
            entries.add(MachineRecipeTypeEntry.forAdapter(mode.id(), mode.priority(), mode.adapter(), modeSlots));
        }
        CloudworksRegistries.registerMachine(definition.machine(), definition.profile(),
                definition.slots(), entries, definition.identity(), definition.category());
        if (definition.generator() != null)
            CloudworksRegistries.registerEnergyGenerator(definition.machine(), definition.generator());
        if (definition.thermal() != null)
            CloudworksRegistries.registerThermal(definition.machine(), definition.thermal());
    }

    @Override
    public CloudworksMachineInfo machineInfo(ItemStack machine) {
        if (!CloudworksRegistries.isRegistered(machine))
            return new CloudworksMachineInfo(machine, ItemStack.EMPTY, null, null,
                    Collections.emptyList(), 0, false, false, null, null);
        MachineKey key = CloudworksRegistries.identityKey(machine);
        List<String> modes = new ArrayList<>();
        for (MachineRecipeTypeEntry entry : CloudworksRegistries.recipeTypes(key)) modes.add(entry.typeId());
        return new CloudworksMachineInfo(machine, CloudworksRegistries.aliasTarget(machine),
                CloudworksRegistries.profile(machine), CloudworksRegistries.slots(machine), modes,
                CloudworksMachineAdapters.getAll(key).size(), true,
                CloudworksRegistries.energyGenerator(key) != null, CloudworksRegistries.thermal(key),
                CloudworksRegistries.category(key));
    }

    @Override
    public List<CloudworksMachineInfo> machineInfos() {
        List<CloudworksMachineInfo> result = new ArrayList<>();
        for (ItemStack machine : CloudworksRegistries.profileSnapshot().keySet().stream()
                .map(MachineKey::displayStack).toArray(ItemStack[]::new))
            result.add(machineInfo(machine));
        return Collections.unmodifiableList(result);
    }

    @Override
    public void applyMachineMutation(CloudworksMachineMutation mutation) {
        ItemStack machine = mutation.machine();
        switch (mutation.kind) {
            case ALIAS:
                aliases.add(mutation);
                break;
            case PROFILE:
                requireRegisteredNonAlias(machine);
                CloudworksRegistries.updateProfile(machine, mutation.profile().inheritUpgradePolicies(
                        CloudworksRegistries.profile(machine)));
                break;
            case ACCEPT_UPGRADE:
                requireRegisteredNonAlias(machine);
                MachineExecutionProfile previous = CloudworksRegistries.profile(machine);
                CloudworksRegistries.updateProfile(machine, previous.withUpgradePolicy(previous.upgradePolicy()
                        .withCustomType(mutation.upgrade(), mutation.upgradeLimit())));
                break;
            case TEMPLATE:
                registerTemplate(machine, mutation.target(), mutation.profile());
                break;
            case ADD_ADAPTER:
                requireRegisteredNonAlias(machine);
                CloudworksMachineAdapters.register(CloudworksRegistries.identityKey(machine), mutation.adapter());
                break;
            case CLEAR_ADAPTERS:
                requireRegisteredNonAlias(machine);
                CloudworksMachineAdapters.replace(CloudworksRegistries.identityKey(machine), Collections.emptyList());
                break;
            case GENERATOR:
                requireRegisteredNonAlias(machine);
                if (!CloudworksMachineAdapters.getAll(machine).isEmpty()
                        || CloudworksRegistries.thermal(CloudworksRegistries.identityKey(machine)) != null)
                    throw new IllegalArgumentException("Generator requires an empty non-thermal machine");
                CloudworksRegistries.registerEnergyGenerator(machine, mutation.generator());
                break;
            case THERMAL:
                requireRegisteredNonAlias(machine);
                CloudworksRegistries.registerThermal(machine, mutation.thermal());
                break;
            default:
                throw new IllegalArgumentException("Unsupported machine customization: " + mutation.kind);
        }
    }

    @Override
    public void validateMachineMutation(CloudworksMachineMutation mutation, CloudworksMachineInfo current) {
        switch (mutation.kind) {
            case ALIAS: {
                CloudworksMachineInfo target = CloudworksAPI.machineInfo(mutation.target());
                if (current.registered() || !target.registered() || !target.aliasTarget().isEmpty()
                        || target.adapterCount() == 0 && !target.hasEnergyGenerator() && target.thermal() == null)
                    throw new IllegalArgumentException("Alias or target is already registered or has no execution contract");
                break;
            }
            case PROFILE:
                requireRegisteredNonAlias(current);
                break;
            case ACCEPT_UPGRADE:
                requireRegisteredNonAlias(current);
                current.profile().upgradePolicy().withCustomType(mutation.upgrade(), mutation.upgradeLimit());
                break;
            case TEMPLATE: {
                CloudworksMachineInfo target = CloudworksAPI.machineInfo(mutation.target());
                if (current.registered() || !target.registered() || !target.aliasTarget().isEmpty()
                        || target.slots() == null)
                    throw new IllegalArgumentException("Template requires a new identity and a registered non-alias source");
                break;
            }
            case ADD_ADAPTER:
                requireRegisteredNonAlias(current);
                validate(current.slots(), mutation.adapter());
                validateNoMixedAddition(mutation.machine(), current, mutation.adapter());
                break;
            case CLEAR_ADAPTERS:
                requireRegisteredNonAlias(current);
                break;
            case GENERATOR:
                requireRegisteredNonAlias(current);
                if (current.adapterCount() != 0 || current.thermal() != null)
                    throw new IllegalArgumentException("Generator requires an empty non-thermal machine");
                break;
            case THERMAL:
                requireRegisteredNonAlias(current);
                break;
            default:
                throw new IllegalArgumentException("Unsupported machine customization: " + mutation.kind);
        }
    }

    private static void requireRegisteredNonAlias(CloudworksMachineInfo info) {
        if (!info.registered() || !info.aliasTarget().isEmpty())
            throw new IllegalArgumentException("A registered non-alias machine is required");
    }

    private static void validateNoMixedAddition(ItemStack machine, CloudworksMachineInfo current,
                                                CloudworksMachineAdapter addition) {
        boolean fuel = current.hasEnergyGenerator();
        boolean processing = false;
        for (CloudworksMachineAdapter adapter : CloudworksMachineAdapters.getAll(machine)) {
            CloudworksFrozenRecipeView view = CloudworksAsyncRecipeAdmission.capture(adapter)
                    .orElseThrow(() -> new IllegalArgumentException("Existing machine contract cannot be frozen"));
            for (CloudworksFrozenRecipeView.Entry entry : view.entries()) {
                fuel |= entry.energyPerOperation() > 0;
                processing |= entry.energyPerOperation() == 0;
            }
        }
        CloudworksFrozenRecipeView next = CloudworksAsyncRecipeAdmission.capture(addition)
                .orElseThrow(() -> new IllegalArgumentException("New machine contract cannot be frozen"));
        for (CloudworksFrozenRecipeView.Entry entry : next.entries()) {
            fuel |= entry.energyPerOperation() > 0;
            processing |= entry.energyPerOperation() == 0;
        }
        if (fuel && processing)
            throw new IllegalArgumentException("Mixed processing and generation contracts: " + machine);
    }

    private void registerTemplate(ItemStack machine, ItemStack target, MachineExecutionProfile profile) {
        requireRegisteredNonAlias(target);
        if (CloudworksRegistries.isRegistered(machine))
            throw new IllegalArgumentException("Machine already registered");
        MachineExecutionProfile targetProfile = CloudworksRegistries.profile(target);
        List<MachineRecipeTypeEntry> modes = new ArrayList<>(CloudworksRegistries.recipeTypes(target));
        CloudworksRegistries.registerMachine(machine, profile.inheritUpgradePolicies(targetProfile),
                CloudworksRegistries.slots(target), modes, MachineIdentityPolicy.exactNbt(),
                CloudworksRegistries.category(target));
        MachineKey key = CloudworksRegistries.identityKey(machine);
        CloudworksMachineAdapters.replace(key, CloudworksMachineAdapters.getAll(target));
        if (CloudworksRegistries.energyGenerator(target) != null)
            CloudworksRegistries.registerEnergyGenerator(machine, CloudworksRegistries.energyGenerator(target));
        if (CloudworksRegistries.thermal(CloudworksRegistries.identityKey(target)) != null)
            CloudworksRegistries.registerThermal(machine,
                    CloudworksRegistries.thermal(CloudworksRegistries.identityKey(target)));
        CloudworksRegistries.inheritCategory(key, CloudworksRegistries.identityKey(target));
        templates.put(key, CloudworksRegistries.identityKey(target));
    }

    private void finishBindings() {
        templates.forEach((key, target) -> {
            List<CloudworksMachineAdapter> adapters = new ArrayList<>(CloudworksMachineAdapters.getAll(target));
            for (CloudworksMachineAdapter adapter : CloudworksMachineAdapters.getAll(key))
                if (adapters.stream().noneMatch(existing -> existing == adapter)) adapters.add(adapter);
            CloudworksMachineAdapters.replace(key, adapters);
            if (adapters.isEmpty() && CloudworksRegistries.energyGenerator(target) == null
                    && CloudworksRegistries.thermal(target) == null)
                throw new IllegalArgumentException("Template has no execution contract: " + target.displayStack());
            if (CloudworksRegistries.energyGenerator(target) != null)
                CloudworksRegistries.registerEnergyGenerator(key.displayStack(), CloudworksRegistries.energyGenerator(target));
            if (CloudworksRegistries.thermal(target) != null)
                CloudworksRegistries.registerThermal(key.displayStack(), CloudworksRegistries.thermal(target));
        });
        for (CloudworksMachineMutation alias : aliases)
            CloudworksRegistries.registerAlias(alias.machine(), alias.target());
    }

    @Override
    public void validateMachineAdapter(ItemStack machine, CloudworksMachineAdapter adapter,
                                       MachineSlotLayout pendingSlots) {
        MachineSlotLayout slots = pendingSlots;
        if (slots == null) {
            requireRegisteredNonAlias(machine);
            slots = CloudworksRegistries.slots(machine);
        }
        validate(slots, adapter);
    }

    static void validate(MachineSlotLayout layout, CloudworksMachineAdapter adapter) {
        CloudworksFrozenRecipeView view = CloudworksAsyncRecipeAdmission.capture(adapter)
                .orElseThrow(() -> new IllegalArgumentException("Machine contract cannot be frozen"));
        validate(layout, view, true);
    }

    private static void validate(MachineSlotLayout layout, CloudworksFrozenRecipeView view, boolean checkInputs) {
        if (layout == null) throw new IllegalArgumentException("Machine layout is unavailable");
        for (CloudworksFrozenRecipeView.Entry entry : view.entries()) {
            if (checkInputs) {
                Map<String, Long> inputTotals = new LinkedHashMap<>();
                Map<ResourceLocation, Long> inputCodecTotals = new LinkedHashMap<>();
                for (int index = 0; index < entry.inputCount(); index++) {
                    if (entry.inputCodec(index) != null)
                        inputCodecTotals.merge(entry.inputCodec(index), entry.inputAmount(index), Math::addExact);
                    String name = entry.inputPort(index);
                    if (!name.isEmpty()) {
                        MachineSlotLayout.Slot port = layout.find(name);
                        if (port == null || port.role() != MachineSlotLayout.PortRole.INPUT
                                || port.capacity() < entry.inputAmount(index)
                                || entry.inputCodec(index) != null && !port.codecId().equals(entry.inputCodec(index)))
                            throw new IllegalArgumentException("Incompatible or undersized input port: " + name);
                        inputTotals.merge(name, entry.inputAmount(index), Math::addExact);
                    } else if (entry.inputCodec(index) != null) {
                        long room = 0;
                        for (MachineSlotLayout.Slot port : layout.slots())
                            if (port.role() == MachineSlotLayout.PortRole.INPUT
                                    && port.codecId().equals(entry.inputCodec(index))) room += port.capacity();
                        if (room < entry.inputAmount(index))
                            throw new IllegalArgumentException("Missing or undersized input codec: " + entry.inputCodec(index));
                    }
                }
                for (Map.Entry<String, Long> total : inputTotals.entrySet())
                    if (total.getValue() > layout.find(total.getKey()).capacity())
                        throw new IllegalArgumentException("Combined recipe inputs exceed port capacity: " + total.getKey());
                inputCodecTotals.forEach((codec, total) -> {
                    long room = 0;
                    for (MachineSlotLayout.Slot port : layout.slots())
                        if (port.role() == MachineSlotLayout.PortRole.INPUT && port.codecId().equals(codec))
                            room = Math.addExact(room, port.capacity());
                    if (total > room)
                        throw new IllegalArgumentException("Combined recipe inputs exceed codec capacity: " + codec);
                });
                for (int index = 0; index < entry.configurationCount(); index++) {
                    MachineSlotLayout.Slot port = layout.find(entry.configurationPort(index));
                    if (port == null || port.role() != MachineSlotLayout.PortRole.CONFIGURATION
                            || entry.configurationCodec(index) != null && !port.codecId().equals(entry.configurationCodec(index)))
                        throw new IllegalArgumentException("Incompatible template port");
                }
            }
            Map<String, Long> namedOutputTotals = new LinkedHashMap<>();
            for (int index = 0; index < entry.outputTemplate().size(); index++) {
                CloudworksResourceStack output = entry.outputTemplate().get(index);
                String name = entry.outputPort(index);
                if (!name.isEmpty()) namedOutputTotals.merge(name, output.amount(), Math::addExact);
                long room = 0;
                for (MachineSlotLayout.Slot port : layout.slots())
                    if (port.role() == MachineSlotLayout.PortRole.OUTPUT
                            && port.codecId().equals(output.descriptor().getCodecId())
                            && (name.isEmpty() || name.equals(port.id()))) room += port.capacity();
                if (room < output.amount())
                    throw new IllegalArgumentException("Incompatible or undersized output port: "
                            + name + " recipe=" + entry.recipeIdentity() + " codec=" + output.descriptor().getCodecId()
                            + " amount=" + output.amount() + " capacity=" + room);
            }
            Map<ResourceLocation, Long> outputTotals = new LinkedHashMap<>();
            for (CloudworksResourceStack output : entry.outputTemplate())
                outputTotals.merge(output.descriptor().getCodecId(), output.amount(), Math::addExact);
            for (CloudworksRecipeBatch.ChanceOutput output : entry.chanceOutputTemplate())
                if (output.chance() > 0) {
                    if (!output.port().isEmpty()) {
                        MachineSlotLayout.Slot port = layout.find(output.port());
                        if (port == null || port.role() != MachineSlotLayout.PortRole.OUTPUT
                                || !port.codecId().equals(output.descriptor().getCodecId()) || port.capacity() < output.amount())
                            throw new IllegalArgumentException("Incompatible chance output port: " + output.port());
                        namedOutputTotals.merge(output.port(), output.amount(), Math::addExact);
                    }
                    outputTotals.merge(output.descriptor().getCodecId(), output.amount(), Math::addExact);
                }
            for (Map.Entry<String, Long> total : namedOutputTotals.entrySet())
                if (total.getValue() > layout.find(total.getKey()).capacity())
                    throw new IllegalArgumentException("Combined recipe outputs exceed port capacity: " + total.getKey());
            Map<ResourceLocation, Long> choiceMaximum = new LinkedHashMap<>();
            for (CloudworksResourceStack output : entry.choiceOutputTemplate())
                choiceMaximum.merge(output.descriptor().getCodecId(), output.amount(), Math::max);
            choiceMaximum.forEach((codec, amount) -> outputTotals.merge(codec, amount, Math::addExact));
            outputTotals.forEach((codec, total) -> {
                long room = 0;
                for (MachineSlotLayout.Slot port : layout.slots())
                    if (port.role() == MachineSlotLayout.PortRole.OUTPUT && port.codecId().equals(codec))
                        room += port.capacity();
                if (room < total) throw new IllegalArgumentException("Combined recipe outputs exceed capacity: "
                        + codec + " recipe=" + entry.recipeIdentity() + " amount=" + total + " capacity=" + room);
            });
        }
    }

    private static void requireRegisteredNonAlias(ItemStack machine) {
        if (!CloudworksRegistries.isRegistered(machine) || !CloudworksRegistries.aliasTarget(machine).isEmpty())
            throw new IllegalArgumentException("A registered non-alias machine is required");
    }

    private void validateRegisteredContracts0() {
        List<String> invalid = new ArrayList<>();
        for (MachineKey key : CloudworksRegistries.profileSnapshot().keySet()) {
            boolean fuel = CloudworksRegistries.energyGenerator(key) != null;
            boolean processing = false;
            for (CloudworksMachineAdapter adapter : CloudworksMachineAdapters.getAutomatic(key)) {
                Optional<CloudworksFrozenRecipeView> captured = CloudworksAsyncRecipeAdmission.capture(adapter);
                if (!captured.isPresent()) continue;
                CloudworksFrozenRecipeView view = captured.get();
                try {
                    // Native auxiliary inputs may describe a whole cycle of
                    // per-tick consumption. This bootstrap scan validates the
                    // promised outputs, which must fit atomically at completion.
                    validate(CloudworksRegistries.slots(key), view, false);
                } catch (IllegalArgumentException failure) {
                    invalid.add(key.displayStack() + " mode=<automatic>: " + failure.getMessage());
                }
                for (CloudworksFrozenRecipeView.Entry entry : view.entries()) {
                    fuel |= entry.energyPerOperation() > 0;
                    processing |= entry.energyPerOperation() == 0;
                }
            }
            for (MachineRecipeTypeEntry mode : CloudworksRegistries.recipeTypes(key)) {
                Optional<CloudworksFrozenRecipeView> captured = CloudworksAsyncRecipeAdmission.capture(mode.adapter());
                if (!captured.isPresent()) continue;
                try {
                    validate(CloudworksRegistries.slots(key, mode.typeId()), captured.get(), false);
                } catch (IllegalArgumentException failure) {
                    invalid.add(key.displayStack() + " mode=" + mode.typeId() + ": " + failure.getMessage());
                }
            }
            if (fuel && processing)
                invalid.add("Mixed processing and generation contracts: " + key.displayStack());
        }
        if (!invalid.isEmpty())
            throw new IllegalArgumentException("Invalid registered machine contracts:\n" + String.join("\n", invalid));
    }

    @Override
    public List<MachineModuleType> upgradeTypes() {
        return Collections.unmodifiableList(new ArrayList<>(CloudworksMachineModuleRegistry.snapshot().values()));
    }
}
