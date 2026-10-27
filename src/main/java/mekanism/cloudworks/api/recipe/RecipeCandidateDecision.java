package mekanism.cloudworks.api.recipe;

/** Candidate acceptance and deterministic ordering contribution. */
public final class RecipeCandidateDecision {
    private static final RecipeCandidateDecision DEFAULT = new RecipeCandidateDecision(true, 0);
    private final boolean accepted;
    private final int priority;

    public RecipeCandidateDecision(boolean accepted, int priority) {
        this.accepted = accepted;
        this.priority = priority;
    }

    public static RecipeCandidateDecision accept() { return DEFAULT; }
    public static RecipeCandidateDecision reject() { return new RecipeCandidateDecision(false, 0); }
    public static RecipeCandidateDecision priority(int value) { return new RecipeCandidateDecision(true, value); }
    public boolean accepted() { return accepted; }
    public int priority() { return priority; }
}
