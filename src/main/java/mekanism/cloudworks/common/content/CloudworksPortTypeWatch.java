package mekanism.cloudworks.common.content;

import mekanism.api.inventory.IInventorySlot;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 协议储存箱 type-unblock watch (P1-6/P1-7): the single place a paused upload
 * port's cloud-side listener lives. The tile owns the pause flags and the
 * buffer; this class owns exactly one live registration at any time:
 *
 * <ul>
 *   <li>{@link #release} captures its fields into locals BEFORE clearing them —
 *       the old {@code resumeUpload} nulled {@code blockedDescriptor} first and
 *       then read it back for the unregistration, so the unregistration never
 *       ran and every pause/resume cycle leaked one listener (a strong tile
 *       reference pinned in the manager) (P1-6);</li>
 *   <li>{@link #establish} is idempotent: it releases whatever was watched (a
 *       no-op when the manager's dispatch already consumed the listener), then
 *       registers the constructor callback for the new descriptor — no path can
 *       accumulate a second live registration, and a temporarily unavailable
 *       manager simply leaves the watch clear for the next retry;</li>
 *   <li>{@link #evaluateResume} re-establishes the watch on the CURRENT buffer
 *       descriptor whenever the port stays paused. That heals every
 *       listener-less death path (P1-7 and two siblings): a port that restarted
 *       paused (the listener is deliberately never persisted), a listener the
 *       dispatch consumed while the player swapped the blocked type, and a
 *       registration that failed while the manager was unavailable.</li>
 * </ul>
 *
 * <p>No world access and an injected manager instance, exactly like
 * {@link CloudworksPortIo}: the whole contract is unit-testable without a
 * tile.</p>
 */
public final class CloudworksPortTypeWatch {
    /**
     * Fires when the cloud regains room for the watched type (dispatch-driven).
     * In the tile this re-runs the resume evaluation, closing the loop.
     */
    private final Runnable onUnblock;
    @Nullable
    private QIOResourceDescriptor watched;

    public CloudworksPortTypeWatch(Runnable onUnblock) {
        this.onUnblock = onUnblock;
    }

    public boolean isWatching() {
        return watched != null;
    }

    /**
     * Idempotent (re)registration on the given descriptor: releases whatever
     * was watched before (a no-op when dispatch already consumed the listener),
     * then registers {@link #onUnblock} for the new descriptor.
     */
    public void establish(@Nullable UUID owner, @Nullable QIOResourceDescriptor descriptor, CloudworksPlayerDataManager manager) {
        release(owner, manager);
        if (owner == null || descriptor == null) return;
        try {
            manager.registerTypeUnblockListener(owner, descriptor, onUnblock);
        } catch (RuntimeException unavailable) {
            return;   // manager not loaded / owner failed: clear watch, retried on the next evaluateResume
        }
        watched = descriptor;
    }

    /**
     * P1-6: capture-before-clear — the unregistration uses the locals, so there
     * is no window where the fields are already null while the manager still
     * holds the callback.
     */
    public void release(@Nullable UUID owner, CloudworksPlayerDataManager manager) {
        QIOResourceDescriptor wasWatched = watched;
        watched = null;
        if (owner == null || wasWatched == null) return;
        try {
            manager.unregisterTypeUnblockListener(owner, wasWatched, onUnblock);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * The paused port's resume evaluation. Resumes (releasing the watch) when
     * the blocked buffer position is out of range (corrupt pause state — resume
     * instead of throwing), empty, unresolvable, or has ANY receiving room
     * again. The {@code room > 0} predicate pairs exactly with the manager's
     * dispatch condition ({@code insertable >= 1}): a dispatch notification
     * always resumes, the resumed cycle uploads whatever fits, and a leftover
     * re-pauses through the normal grace protocol — neither side can spin.
     * While still blocked the watch is (re)established on the CURRENT
     * descriptor; a manager failure keeps any existing watch and waits for the
     * next wake path. Returns true when the port should resume.
     */
    public boolean evaluateResume(UUID owner, List<IInventorySlot> slots, List<CloudworksHybridTank> tanks,
                           int blockedIndex, CloudworksPlayerDataManager manager) {
        if (blockedIndex < 0 || blockedIndex >= slots.size() + tanks.size()) {
            release(owner, manager);   // corrupt pause state: resume defensively
            return true;
        }
        long content = CloudworksPortIo.bufferContent(slots, tanks, blockedIndex);
        if (content <= 0) {
            release(owner, manager);
            return true;
        }
        QIOResourceDescriptor descriptor = CloudworksPortIo.bufferDescriptor(slots, tanks, blockedIndex);
        if (descriptor == null) {
            release(owner, manager);
            return true;
        }
        long room;
        try {
            room = manager.receivingRoom(owner, descriptor);
        } catch (RuntimeException unavailable) {
            // manager down: keep any existing watch, retry on the next wake path
            return false;
        }
        if (room > 0) {
            release(owner, manager);
            return true;
        }
        establish(owner, descriptor, manager);
        return false;
    }
}
