package mekanism.cloudworks.api;

import java.util.Objects;
import java.util.UUID;

/** One bounded page from a committed owner snapshot, not a live Runtime view. */
public final class CloudworksQuery {
    public enum Scope { LINES, NODES, RECEIVING, TRANSFER, SENDING, NODE_INPUTS, NODE_OUTPUTS }
    public static final int MAX_PAGE = 256;
    private final Scope scope;
    private final UUID line, node;
    private final int offset, limit;

    public CloudworksQuery(Scope scope, UUID line, UUID node, int offset, int limit) {
        this.scope = Objects.requireNonNull(scope);
        if (offset < 0 || limit < 1 || limit > MAX_PAGE)
            throw new IllegalArgumentException("Invalid query page");
        if ((scope == Scope.NODES || scope == Scope.NODE_INPUTS || scope == Scope.NODE_OUTPUTS) && line == null
                || (scope == Scope.NODE_INPUTS || scope == Scope.NODE_OUTPUTS) && node == null)
            throw new IllegalArgumentException("Missing query endpoint");
        this.line = line;
        this.node = node;
        this.offset = offset;
        this.limit = limit;
    }

    public Scope scope() { return scope; }
    public UUID line() { return line; }
    public UUID node() { return node; }
    public int offset() { return offset; }
    public int limit() { return limit; }
}
