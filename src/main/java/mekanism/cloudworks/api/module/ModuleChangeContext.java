package mekanism.cloudworks.api.module;
import java.util.Objects;

/** Before/after module quantities within one candidate installation transaction. */
public final class ModuleChangeContext {
    public enum Reason { UPLOAD, UNLOAD, BLUEPRINT, RESTORE, SOURCE_CHANGED }

    private final long previousCount;
    private final long candidateCount;
    private final Reason reason;

    public ModuleChangeContext(long previousCount, long candidateCount, Reason reason) {
        if (previousCount < 0 || candidateCount < 0) throw new IllegalArgumentException("Negative module count");
        this.previousCount = previousCount;
        this.candidateCount = candidateCount;
        this.reason = Objects.requireNonNull(reason);
    }

    public long previousCount() { return previousCount; }
    public long candidateCount() { return candidateCount; }
    public boolean isFirstInstall() { return previousCount == 0 && candidateCount > 0; }
    public boolean isLastRemoval() { return previousCount > 0 && candidateCount == 0; }
    public Reason reason() { return reason; }
}
