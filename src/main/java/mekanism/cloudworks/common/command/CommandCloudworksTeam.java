package mekanism.cloudworks.common.command;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import mekanism.cloudworks.common.team.CloudworksTeamManager;
import mekanism.cloudworks.common.team.CloudworksTeamSnapshot;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import java.util.Collections;
import java.util.Objects;
import javax.annotation.Nullable;

/** Membership-only escape hatch, including for members denied access to the team cloud GUI. */
public final class CommandCloudworksTeam extends CommandBase {
    @Override public String getName() { return "cloudworks-team"; }
    @Override public String getUsage(ICommandSender sender) { return "commands.cloudworks.team.usage"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
            String[] args, @Nullable BlockPos pos) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, Arrays.asList("info", "leave", "join"))
                : Collections.emptyList();
    }

    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        String operation = args.length == 0 ? "info" : args[0];
        UUID actor = player.getUniqueID(), current = CloudworksTeamContext.owner(actor);
        String name = player.getName();
        if ("info".equals(operation)) {
            CloudworksTeamSnapshot team = CloudworksTeamContext.snapshot(current);
            player.sendMessage(new TextComponentTranslation("commands.cloudworks.team.info",
                    team.name(), team.id() == null ? "-" : team.id().toString()));
            return;
        }
        if (!"leave".equals(operation) && !"join".equals(operation)) throw new WrongUsageException(getUsage(sender));
        UUID selected;
        try {
            if ("join".equals(operation) && args.length != 2) throw new IllegalArgumentException();
            selected = "join".equals(operation) ? UUID.fromString(args[1]) : null;
        } catch (IllegalArgumentException malformed) { throw new WrongUsageException(getUsage(sender)); }
        if (!CloudworksTeamManager.submit(() -> {
            if (!Objects.equals(current, CloudworksTeamContext.owner(actor)))
                throw new IllegalArgumentException("cloudworks.team.error.changed");
            if ("join".equals(operation)) CloudworksTeamContext.registry().requestJoin(actor, name, selected);
            else CloudworksTeamContext.registry().leave(actor);
        }, status -> {
            if (server.getPlayerList().getPlayerByUUID(actor) != player) return;
            if (player.openContainer instanceof ContainerCloudworks container && !container.canInteractWith(player))
                player.closeScreen();
            player.sendMessage(new TextComponentTranslation(status));
        })) throw new CommandException("cloudworks.error.busy");
    }
}
