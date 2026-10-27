package mekanism.cloudworks.common.integration.ftb;

import com.feed_the_beast.ftblib.events.team.ForgeTeamDeletedEvent;
import com.feed_the_beast.ftblib.events.team.ForgeTeamCreatedEvent;
import com.feed_the_beast.ftblib.events.team.ForgeTeamOwnerChangedEvent;
import com.feed_the_beast.ftblib.events.team.ForgeTeamPlayerJoinedEvent;
import com.feed_the_beast.ftblib.events.team.ForgeTeamPlayerLeftEvent;
import com.feed_the_beast.ftblib.lib.data.ForgePlayer;
import com.feed_the_beast.ftblib.lib.data.ForgeTeam;
import com.feed_the_beast.ftblib.lib.data.Universe;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mekanism.cloudworks.common.team.CloudworksExternalTeam;
import mekanism.cloudworks.common.team.CloudworksTeamBridge;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import mekanism.cloudworks.common.team.CloudworksTeamManager;
import mekanism.cloudworks.common.team.CloudworksTeamMember;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.util.CloudworksMessages;
import mekanism.common.Mekanism;
import net.minecraft.util.StringUtils;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Optional, manually registered adapter. Live FTB state is captured only on the server thread. */
public final class CloudworksTeamBridgeFtb implements CloudworksTeamBridge {
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private final Consumer<Runnable> dispatch;
    private boolean submitted, closed;

    public CloudworksTeamBridgeFtb() { dispatch = this::queue; }

    CloudworksTeamBridgeFtb(Consumer<Runnable> dispatch) {
        this.dispatch = dispatch;
    }
    @Override
    public void start() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Override public void close() {
        closed = true;
        pending.clear();
        MinecraftForge.EVENT_BUS.unregister(this);
    }

    @Override public CloudworksExternalTeam team(UUID player) {
        if (!Universe.loaded()) return null;
        ForgePlayer member = Universe.get().getPlayer(player);
        return member == null ? null : capture(member.team);
    }

    @SubscribeEvent public void joined(ForgeTeamPlayerJoinedEvent event) {
        ForgePlayer player = event.getPlayer();
        ForgeTeam team = event.getTeam();
        if (team == null) return;
        UUID leader = team.getOwner() == null ? null : team.getOwner().getId();
        if (leader == null) return;
        UUID playerId = player.getId();
        String playerName = player.getName();
        dispatch.accept(() -> {
            // A joined event only adds a member to an already existing
            // Cloudworks team owned by the same leader. Creation belongs to
            // the CreatedEvent path exclusively.
            CloudworksTeamContext.registry().ftbJoin(leader, playerId, playerName);
        });
    }

    @SubscribeEvent public void left(ForgeTeamPlayerLeftEvent event) {
        ForgePlayer player = event.getPlayer();
        ForgeTeam team = event.getTeam();
        if (team == null || team.getOwner() == null) return;
        UUID leader = team.getOwner().getId();
        UUID playerId = player.getId();
        dispatch.accept(() -> CloudworksTeamContext.registry().ftbLeave(leader, playerId));
    }

    @SubscribeEvent public void ownerChanged(ForgeTeamOwnerChangedEvent event) {
        ForgePlayer old = event.getOldOwner();
        ForgePlayer current = event.getTeam().getOwner();
        if (old == null || current == null
                || !CloudworksConfig.settings().ftbOwnerFollows) return;
        String binding = binding(event.getTeam());
        UUID oldId = old.getId();
        UUID newId = current.getId();
        dispatch.accept(() -> CloudworksTeamContext.registry().ftbOwnerChanged(binding, oldId, newId));
    }

    @SubscribeEvent public void deleted(ForgeTeamDeletedEvent event) {
        String binding = binding(event.getTeam());
        boolean dissolve = CloudworksConfig.settings().ftbDissolveFollows;
        dispatch.accept(() -> {
            if (dissolve)
                CloudworksTeamContext.registry().dissolveBinding(binding);
            else {
                CloudworksTeamContext.registry().unbindBinding(binding);
            }
        });
    }

    @SubscribeEvent public void created(ForgeTeamCreatedEvent event) {
        CloudworksExternalTeam snapshot = capture(event.getTeam());
        if (snapshot == null || !CloudworksConfig.settings().ftbAutoCreateTeam)
            return;
        dispatch.accept(() -> CloudworksTeamContext.registry().createFromExternal(snapshot));
    }

    private static CloudworksExternalTeam capture(ForgeTeam team) {
        if (team == null || !team.type.isPlayer || team.getOwner() == null) return null;
        List<CloudworksTeamMember> members = new ArrayList<>();
        for (ForgePlayer member : team.getMembers())
            members.add(new CloudworksTeamMember(member.getId(), member.getName(), member.isOnline()));
        ForgePlayer owner = team.getOwner();
        if (members.stream().noneMatch(member -> owner.getId().equals(member.id())))
            members.add(new CloudworksTeamMember(owner.getId(), owner.getName(), owner.isOnline()));
        String name = StringUtils.stripControlCodes(team.getTitle().getUnformattedText());
        if (name.trim().isEmpty()) name = team.getId();
        if (name.length() > 64) name = name.substring(0, 64);
        return new CloudworksExternalTeam(binding(team), name, owner.getId(), members);
    }

    private static String binding(ForgeTeam team) { return team.getId() + "#" + team.getUID(); }

    private void queue(Runnable mutation) {
        if (closed) return;
        pending.addLast(mutation);
        drain();
    }

    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) drain();
    }

    private void drain() {
        if (closed || submitted || pending.isEmpty()) return;
        // Keep events on the main thread until the bounded executor admits the batch.
        List<Runnable> batch = new ArrayList<>(pending);
        if (CloudworksTeamManager.submit(() -> applyBatch(batch), status -> {
            submitted = false;
            if (closed) return;
            if (!CloudworksMessages.SAVED.equals(status))
                Mekanism.logger.error("FTB membership synchronization failed: {}", status);
            drain();
        })) {
            pending.clear();
            submitted = true;
        }
    }

    /** Each event commits independently; a rejected event must not discard later events. */
    static void applyBatch(List<Runnable> batch) {
        RuntimeException failure = null;
        for (Runnable command : batch) {
            try {
                command.run();
            } catch (RuntimeException rejected) {
                if (failure == null) failure = rejected;
                else if (failure != rejected) failure.addSuppressed(rejected);
            }
        }
        if (failure != null) throw failure;
    }
}
