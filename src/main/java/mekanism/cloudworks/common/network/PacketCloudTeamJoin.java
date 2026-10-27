package mekanism.cloudworks.common.network;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import mekanism.cloudworks.common.inventory.ContainerCloudTeamJoin;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import mekanism.cloudworks.common.team.CloudworksTeamManager;
import mekanism.cloudworks.common.util.CloudworksMessages;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import mekanism.cloudworks.common.Cloudworks;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.team.CloudworksTeam;
import mekanism.cloudworks.common.team.CloudworksTeamMember;
import mekanism.cloudworks.common.team.CloudworksTeamSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentTranslation;

public final class PacketCloudTeamJoin {
    public static final int LIST = 0, JOIN = 1, REQUEST_JOIN = 2, CREATE = 3;
    private PacketCloudTeamJoin() {}

    public static final class TeamEntry {
        public final UUID id;
        public final UUID leader;
        public final String leaderName;
        public final String name, mode;
        public final int color;
        public final boolean requested;
        public TeamEntry(UUID id, String name, String mode, int color, boolean requested) {
            this(id, null, "", name, mode, color, requested);
        }
        public TeamEntry(UUID id, UUID leader, String leaderName, String name,
                String mode, int color, boolean requested) {
            this.id = id; this.leader = leader; this.leaderName = leaderName == null ? "" : leaderName;
            this.name = name; this.mode = mode; this.color = color; this.requested = requested;
        }
    }

    public static final class Request implements IMessage {
        public int window, action, color;
        public UUID session, team;
        public long sequence;
        public String name = "", mode = "APPROVAL";
        public boolean valid;
        public Request() {}
        public Request(int window, UUID session, long sequence, int action, UUID team,
                String name, int color, String mode) {
            this.window = window; this.session = session; this.sequence = sequence; this.action = action;
            this.team = team; this.name = name == null ? "" : name; this.color = color;
            this.mode = mode == null ? "APPROVAL" : mode; valid = true;
        }
        @Override public void fromBytes(ByteBuf raw) {
            try {
                PacketBuffer b = new PacketBuffer(raw);
                window = b.readInt(); session = new UUID(b.readLong(), b.readLong());
                sequence = b.readLong(); action = b.readUnsignedByte();
                team = b.readBoolean() ? new UUID(b.readLong(), b.readLong()) : null;
                name = b.readString(128); color = b.readInt(); mode = b.readString(32);
                valid = !b.isReadable() && action >= 0 && action <= CREATE && sequence > 0;
            } catch (RuntimeException e) { valid = false; }
        }
        @Override public void toBytes(ByteBuf raw) {
            PacketBuffer b = new PacketBuffer(raw);
            b.writeInt(window); b.writeLong(session.getMostSignificantBits()); b.writeLong(session.getLeastSignificantBits());
            b.writeLong(sequence); b.writeByte(action); b.writeBoolean(team != null);
            if (team != null) { b.writeLong(team.getMostSignificantBits()); b.writeLong(team.getLeastSignificantBits()); }
            b.writeString(name); b.writeInt(color); b.writeString(mode);
        }
    }

    public static final class State implements IMessage {
        public int window; public UUID session; public long sequence; public String status = "";
        public boolean unchanged;
        public boolean valid;
        public List<TeamEntry> teams = Collections.emptyList();
        public State() {}
        State(int window, UUID session, long sequence, String status, List<TeamEntry> teams) {
            this.window = window; this.session = session; this.sequence = sequence; this.status = status;
            this.teams = teams;
            this.valid = true;
        }
        static State statusOnly(int window, UUID session, long sequence, String status) {
            State result = new State(window, session, sequence, status, Collections.emptyList());
            result.unchanged = true;
            return result;
        }
        @Override public void fromBytes(ByteBuf raw) {
            valid = false;
            try {
                PacketBuffer b = new PacketBuffer(raw);
                window = b.readInt(); session = new UUID(b.readLong(), b.readLong()); sequence = b.readLong();
                status = b.readString(128);
                unchanged = b.readBoolean();
                int count = b.readVarInt(); if (count < 0 || count > 128 || unchanged && count != 0) return;
                List<TeamEntry> next = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    UUID id = new UUID(b.readLong(), b.readLong());
                    UUID leader = b.readBoolean() ? new UUID(b.readLong(), b.readLong()) : null;
                    String leaderName = b.readString(64);
                    next.add(new TeamEntry(id, leader, leaderName, b.readString(128),
                            b.readString(32), b.readInt(), b.readBoolean()));
                }
                teams = next;
                valid = !b.isReadable() && window >= 0 && sequence > 0;
            } catch (RuntimeException malformed) { valid = false; }
        }
        @Override public void toBytes(ByteBuf raw) {
            PacketBuffer b = new PacketBuffer(raw);
            b.writeInt(window); b.writeLong(session.getMostSignificantBits()); b.writeLong(session.getLeastSignificantBits());
            b.writeLong(sequence); b.writeString(status); b.writeBoolean(unchanged); b.writeVarInt(teams.size());
            for (TeamEntry entry : teams) {
                b.writeLong(entry.id.getMostSignificantBits()); b.writeLong(entry.id.getLeastSignificantBits());
                b.writeBoolean(entry.leader != null);
                if (entry.leader != null) {
                    b.writeLong(entry.leader.getMostSignificantBits());
                    b.writeLong(entry.leader.getLeastSignificantBits());
                }
                b.writeString(entry.leaderName);
                b.writeString(entry.name); b.writeString(entry.mode); b.writeInt(entry.color); b.writeBoolean(entry.requested);
            }
        }
    }

    public static final class Handler implements IMessageHandler<Request, IMessage> {
        @Override public IMessage onMessage(Request request, MessageContext context) {
            if (!request.valid) return null;
            EntityPlayerMP player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (!(player.openContainer instanceof ContainerCloudTeamJoin container)
                        || !container.accepts(player, request.window, request.session, request.sequence)) return;
                if (request.action == LIST) {
                    if (!container.acceptsListRefresh(player.world.getTotalWorldTime())) {
                        CloudworksNetwork.CHANNEL.sendTo(State.statusOnly(container.windowId, container.session(),
                                request.sequence, "cloudworks.error.busy"), player);
                        return;
                    }
                    sendState(player, container, request.sequence, CloudworksMessages.SAVED);
                    return;
                }
                if (request.team == null && request.action != CREATE) return;
                final UUID actor = player.getUniqueID();
                final String actorName = player.getName();
                final int action = request.action;
                final UUID team = request.team;
                final String name = request.name;
                final int color = request.color;
                final String mode = request.mode;
                boolean queued = CloudworksTeamManager.submit(() -> {
                    if (action == CREATE) {
                        CloudworksTeamContext.registry().create(actor, name, color,
                                CloudworksTeam.JoinMode.valueOf(mode));
                        CloudworksTeamContext.registry().updateMemberName(actor, actorName);
                    } else if (action == JOIN) {
                        CloudworksTeamContext.registry().join(actor, actorName, team);
                    } else if (action == REQUEST_JOIN) {
                        CloudworksTeamContext.registry().requestJoin(actor, actorName, team);
                    } else {
                        throw new IllegalArgumentException("cloudworks.error.not_found");
                    }
                    UUID teamOwner = CloudworksTeamContext.owner(actor);
                    if (teamOwner != null)
                        CloudworksPlayerDataManager.INSTANCE.enable(teamOwner);
                }, status -> {
                    if (player.openContainer != container) return;
                    sendState(player, container, request.sequence, status);
                    if (CloudworksMessages.SAVED.equals(status)
                            && CloudworksTeamContext.owner(actor) != null
                            && player.getHeldItemMainhand().getItem()
                                    == CloudworksItems.TeamTerminal) {
                        try {
                            player.openGui(Cloudworks.instance, 4,
                                    player.world, 0, 0, 0);
                        } catch (RuntimeException failure) {
                            player.sendMessage(new TextComponentTranslation(
                                    "cloudworks.error.storage"));
                        }
                    }
                });
                if (!queued) sendState(player, container, request.sequence, "cloudworks.error.busy");
            });
            return null;
        }

        private static void sendState(EntityPlayerMP player, ContainerCloudTeamJoin container, long sequence, String status) {
            List<TeamEntry> entries = new ArrayList<>();
            UUID actor = player.getUniqueID();
            for (CloudworksTeamSnapshot team :
                    CloudworksTeamContext.registry().joinableTeams(actor)) {
                String leaderName = team.owner() == null ? "" : team.owner().toString();
                for (CloudworksTeamMember member : team.members())
                    if (team.owner() != null && team.owner().equals(member.id())) {
                        leaderName = member.name();
                        break;
                    }
                entries.add(new TeamEntry(team.id(), team.owner(), leaderName, team.name(),
                        team.joinMode.name(), team.color, team.requests().containsKey(actor)));
            }
            if (player.openContainer == container)
                CloudworksNetwork.CHANNEL.sendTo(new State(container.windowId, container.session(),
                        sequence, status, entries), player);
        }
    }

    public static final class StateHandler implements IMessageHandler<State, IMessage> {
        @Override public IMessage onMessage(State state, MessageContext context) {
            if (!state.valid) return null;
            Minecraft.getMinecraft().addScheduledTask(() -> {
                if (Minecraft.getMinecraft().player != null
                        && Minecraft.getMinecraft().player.openContainer instanceof ContainerCloudTeamJoin container)
                    container.apply(state);
            });
            return null;
        }
    }
}
