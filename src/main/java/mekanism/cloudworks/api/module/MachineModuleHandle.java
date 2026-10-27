package mekanism.cloudworks.api.module;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;

/** Default defensive immutable implementation of {@link IMachineModule}. */
public final class MachineModuleHandle<MODULE extends ICustomMachineModule<MODULE>>
        implements IMachineModule<MODULE> {
    private final MachineModuleType<MODULE> type;
    private final int installed;
    private final int effective;
    private final MachineModuleSource source;
    private final UUID sourceNode;
    private final UUID machineNode;
    private final ItemStack machine;
    private final long machineCount;
    private final String recipeId;
    private final Map<ResourceLocation, Long> installedModules;
    private final CloudworksGenerationContext environment;
    private final MachineContextSnapshot machineContext;
    private final UUID lineId;
    private final String branchId;

    public MachineModuleHandle(MachineModuleType<MODULE> type, int installed, int effective,
            MachineModuleSource source, UUID sourceNode, UUID machineNode, ItemStack machine,
            long machineCount, String recipeId, Map<ResourceLocation, Long> installedModules,
            CloudworksGenerationContext environment) {
        this(type, installed, effective, source, sourceNode, machineNode, machine, machineCount, recipeId,
                installedModules, environment, MachineContextSnapshot.empty(environment), null, "", false);
    }
    private MachineModuleHandle(MachineModuleType<MODULE> type, int installed, int effective,
            MachineModuleSource source, UUID sourceNode, UUID machineNode, ItemStack machine,
            long machineCount, String recipeId, Map<ResourceLocation, Long> installedModules,
            CloudworksGenerationContext environment, MachineContextSnapshot machineContext,
            UUID lineId, String branchId, boolean frozen) {
        this.type = Objects.requireNonNull(type);
        if (installed < 0 || effective < 0 || machineCount < 0)
            throw new IllegalArgumentException("Negative machine module snapshot value");
        this.installed = installed;
        this.effective = effective;
        this.source = Objects.requireNonNull(source);
        this.sourceNode = sourceNode;
        this.machineNode = machineNode;
        // Only derived views may share our private, already frozen snapshot.
        // Public construction and the public getter retain defensive copies.
        this.machine = machine == null || machine.isEmpty() ? ItemStack.EMPTY : frozen ? machine : machine.copy();
        this.machineCount = machineCount;
        this.recipeId = recipeId == null ? "" : recipeId;
        this.installedModules = frozen ? installedModules
                : Collections.unmodifiableMap(new LinkedHashMap<>(installedModules));
        this.environment = Objects.requireNonNull(environment);
        this.machineContext = Objects.requireNonNull(machineContext);
        this.lineId = lineId; this.branchId = branchId == null ? "" : branchId;
    }

    @Override public MachineModuleType<MODULE> getData() { return type; }
    @Override public MODULE getCustomInstance() { return type.module(); }
    @Override public int getInstalledCount() { return installed; }
    @Override public int getEffectiveInstalledCount() { return effective; }
    @Override public MachineModuleSource getSource() { return source; }
    @Override public UUID getSourceNodeId() { return sourceNode; }
    @Override public UUID getMachineNodeId() { return machineNode; }
    @Override public UUID getLineId() { return lineId; }
    @Override public String getBranchId() { return branchId; }
    @Override public ItemStack getMachineIdentity() { return machine.isEmpty() ? ItemStack.EMPTY : machine.copy(); }
    @Override public long getMachineCount() { return machineCount; }
    @Override public String getRecipeId() { return recipeId; }
    @Override public long getInstalledCount(ResourceLocation moduleId) {
        return installedModules.getOrDefault(Objects.requireNonNull(moduleId), 0L);
    }
    @Override public Map<ResourceLocation, Long> getAllInstalledCounts() { return installedModules; }
    @Override public CloudworksGenerationContext getEnvironment() { return environment; }
    @Override public MachineContextSnapshot getMachineContext() { return machineContext; }

    public MachineModuleHandle<MODULE> forRecipe(String recipe) {
        return forContext(recipe, environment);
    }
    public MachineModuleHandle<MODULE> forContext(String recipe, CloudworksGenerationContext environment) {
        return new MachineModuleHandle<>(type, installed, effective, source, sourceNode, machineNode,
                machine, machineCount, recipe, installedModules, environment, machineContext, lineId, branchId, true);
    }
    public MachineModuleHandle<MODULE> forExecution(UUID line, String branch) {
        return new MachineModuleHandle<>(type, installed, effective, source, sourceNode, machineNode,
                machine, machineCount, recipeId, installedModules, environment, machineContext, line, branch, true);
    }
    public MachineModuleHandle<MODULE> withCounts(int actual, int effective) {
        return new MachineModuleHandle<>(type, actual, effective, source, sourceNode, machineNode,
                machine, machineCount, recipeId, installedModules, environment, machineContext, lineId, branchId, true);
    }

    public MachineModuleHandle<MODULE> withMachineContext(MachineContextSnapshot context) {
        return new MachineModuleHandle<>(type, installed, effective, source, sourceNode, machineNode,
                machine, machineCount, recipeId, installedModules, environment,
                Objects.requireNonNull(context), lineId, branchId, true);
    }
}
