package mekanism.cloudworks.api.card;
import java.util.Objects;

public final class CloudworksCardResult {
    public enum Status { APPLIED, NOT_ENABLED, AT_LIMIT, ALREADY_UNLOCKED, SERVER_DISABLED, INVALID_ITEM, UNAVAILABLE }
    public final Status status;
    public final int value;
    public final String messageKey;
    private final Object[] arguments;

    public CloudworksCardResult(Status status, int value, String messageKey, Object[] arguments) {
        this.status = Objects.requireNonNull(status);
        this.value = value;
        this.messageKey = messageKey;
        this.arguments = arguments.clone();
    }
    public boolean applied() { return status == Status.APPLIED; }
    public Object[] messageArguments() { return arguments.clone(); }
}
