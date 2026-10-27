package mekanism.cloudworks.api.work;

import java.util.Objects;

public final class WorkCancelledContext {
    public enum Reason { NODE_REMOVED, LINE_REMOVED, CONTRACT_REPLACED, EXPLICIT_CANCEL }
    private final String recipeId;
    private final long operationId;
    private final int operationCount;
    private final Reason reason;
    public WorkCancelledContext(String recipeId, long operationId, int operationCount, Reason reason) {
        this.recipeId = recipeId == null ? "" : recipeId;
        this.operationId = operationId;
        this.operationCount = operationCount;
        this.reason = Objects.requireNonNull(reason);
    }
    public String recipeId() { return recipeId; }
    public long operationId() { return operationId; }
    public int operationCount() { return operationCount; }
    public Reason reason() { return reason; }
}
