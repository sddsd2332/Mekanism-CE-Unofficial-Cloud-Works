package mekanism.cloudworks.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Coalesced notification of committed changes. It never carries mutable state
 * or grants access.
 *
 * <p>Listeners receive batches on the CloudWorks API executor after the server
 * tick commit boundary. Listener code must not access World, TileEntity,
 * Minecraft network state, or synchronously re-enter CloudWorks mutations.</p>
 */
public final class CloudworksEvent {
    public enum Kind {
        CLOUD_CHANGED, LINE_CREATED, LINE_DELETED, LINE_STARTED, LINE_STOPPED, LINE_RENAMED,
        CARD_APPLIED, UPLOAD_COMPLETED, RUNTIME_COMMITTED, OPERATIONS_COMPLETED, STATE_CHANGED
    }
    public final UUID owner, line;
    public final Kind kind;
    public final long revision, count;
    public final String detail;

    public CloudworksEvent(UUID owner, UUID line, Kind kind, long revision, long count, String detail) {
        this.owner = Objects.requireNonNull(owner);
        this.line = line;
        this.kind = Objects.requireNonNull(kind);
        if (count < 1) throw new IllegalArgumentException("Nonpositive event count");
        this.revision = revision;
        this.count = count;
        this.detail = detail == null ? "" : detail;
    }
}
