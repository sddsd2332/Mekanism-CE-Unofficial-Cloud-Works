package mekanism.cloudworks.api.work;

import java.util.Objects;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;

public final class WorkStartedContext {
    private final String recipeId;
    private final long operationId;
    private final int operationCount;
    private final int duration;
    private final long machineCount;
    private final CloudworksGenerationContext environment;
    public WorkStartedContext(String recipeId, long operationId, int operationCount, int duration,
            long machineCount, CloudworksGenerationContext environment) {
        this.recipeId = recipeId == null ? "" : recipeId;
        this.operationId = operationId;
        this.operationCount = operationCount;
        this.duration = duration;
        this.machineCount = machineCount;
        this.environment = Objects.requireNonNull(environment);
    }
    public String recipeId() { return recipeId; }
    public long operationId() { return operationId; }
    public int operationCount() { return operationCount; }
    public int duration() { return duration; }
    public long machineCount() { return machineCount; }
    public CloudworksGenerationContext environment() { return environment; }
}
