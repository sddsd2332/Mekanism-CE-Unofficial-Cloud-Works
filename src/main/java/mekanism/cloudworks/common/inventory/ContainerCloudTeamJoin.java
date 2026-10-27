package mekanism.cloudworks.common.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import mekanism.cloudworks.common.network.CloudworksNetwork;
import mekanism.cloudworks.common.network.PacketCloudTeamJoin;
import mekanism.cloudworks.common.registries.CloudworksItems;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import net.minecraft.entity.player.InventoryPlayer;

/** Slotless join/create chooser. It is intentionally not attached to a cloud owner. */
public final class ContainerCloudTeamJoin extends Container {
    private final EntityPlayer player;
    private UUID session = UUID.randomUUID();
    private boolean sessionEstablished;
    private long sequence;
    private long receivedSequence;
    private long lastListTick = Long.MIN_VALUE;
    private long viewVersion;
    private String status = "";
    private List<PacketCloudTeamJoin.TeamEntry> teams = Collections.emptyList();

    public ContainerCloudTeamJoin(InventoryPlayer inventory) {
        this.player = inventory.player;
    }

    @Override public boolean canInteractWith(EntityPlayer candidate) {
        if (candidate != player || candidate.isDead
                || candidate.getHeldItemMainhand().getItem() != CloudworksItems.TeamTerminal) return false;
        // Keep the chooser alive between the worker publishing membership and
        // its main-thread callback opening the team terminal.
        return true;
    }

    public EntityPlayer player() { return player; }
    public long viewVersion() { return viewVersion; }
    public String status() { return status; }
    public List<PacketCloudTeamJoin.TeamEntry> teams() { return teams; }

    public void requestList() { send(PacketCloudTeamJoin.LIST, null, "", 0xFF4F7FFF, "APPROVAL"); }
    public void join(UUID team, boolean request) {
        send(request ? PacketCloudTeamJoin.REQUEST_JOIN : PacketCloudTeamJoin.JOIN, team, "", 0, "");
    }
    public void create(String name, int color, String mode) {
        send(PacketCloudTeamJoin.CREATE, null, name, color, mode);
    }

    private void send(int action, UUID team, String name, int color, String mode) {
        CloudworksNetwork.CHANNEL.sendToServer(new PacketCloudTeamJoin.Request(
                windowId, session, ++sequence, action, team, name, color, mode));
    }

    public boolean accepts(EntityPlayer candidate, int window, UUID token, long requestSequence) {
        if (candidate != player || candidate.openContainer != this || windowId != window
                || token == null || requestSequence <= receivedSequence) return false;
        if (!sessionEstablished) {
            session = token;
            sessionEstablished = true;
        } else if (!session.equals(token)) {
            return false;
        }
        receivedSequence = requestSequence;
        return canInteractWith(candidate)
                && CloudworksTeamContext.owner(candidate) == null;
    }

    public UUID session() { return session; }

    /** Full public-team snapshots are low-frequency; status-only replies release the GUI's waiting state. */
    public boolean acceptsListRefresh(long tick) {
        if (lastListTick != Long.MIN_VALUE && tick >= lastListTick && tick - lastListTick < 10) return false;
        lastListTick = tick;
        return true;
    }

    public void apply(PacketCloudTeamJoin.State state) {
        if (state.window != windowId || state.session == null || state.sequence < viewVersion) return;
        if (viewVersion > 0 && !session.equals(state.session)) return;
        session = state.session;
        sessionEstablished = true;
        if (!state.unchanged) teams = Collections.unmodifiableList(new ArrayList<>(state.teams));
        status = state.status;
        viewVersion = state.sequence;
    }

    @Override public void detectAndSendChanges() {
        if (player.isDead || player.getHeldItemMainhand().getItem() != CloudworksItems.TeamTerminal) {
            player.closeScreen();
            return;
        }
        super.detectAndSendChanges();
    }
}
