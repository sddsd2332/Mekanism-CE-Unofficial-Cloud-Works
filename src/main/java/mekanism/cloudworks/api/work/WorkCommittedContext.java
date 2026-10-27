package mekanism.cloudworks.api.work;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mekanism.cloudworks.api.machine.MachineOutput;

public final class WorkCommittedContext {
    private final String recipeId;
    private final long operationId;
    private final int operationCount;
    private final List<MachineOutput> outputs;
    public WorkCommittedContext(String recipeId, long operationId, int operationCount,
            List<MachineOutput> outputs) {
        this.recipeId = recipeId == null ? "" : recipeId;
        this.operationId = operationId;
        this.operationCount = operationCount;
        this.outputs = Collections.unmodifiableList(new ArrayList<>(outputs));
    }
    public String recipeId() { return recipeId; }
    public long operationId() { return operationId; }
    public int operationCount() { return operationCount; }
    public List<MachineOutput> committedOutputs() { return outputs; }
}
