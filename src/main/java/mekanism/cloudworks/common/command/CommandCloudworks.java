package mekanism.cloudworks.common.command;

import mekanism.cloudworks.api.CloudworksEnabled;
import mekanism.cloudworks.common.capability.CloudworksCapabilities;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.util.CloudworksMessages;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;

import javax.annotation.Nullable;
import java.util.*;

/**
 * OP administration surface (spec CLOUDWORKS_COMMAND_REWORK_AND_BLUEPRINT_SPEC).
 * Player-facing entry is the item GUI only; everything mutating cloud state
 * through commands is admin work and requires permission level 2.
 *
 * <ul>
 *   <li>{@code enable <player> <true|false>} — dual write: cloud record (the
 *       execution authority) first, player capability second; online targets
 *       only, because the capability lives on the entity.</li>
 *   <li>{@code offlineunlock <player> <true|false>} — grant or revoke a
 *       player's offline-processing qualification.</li>
 * </ul>
 */
public final class CommandCloudworks extends CommandBase {
    private static final List<String> SUBCOMMANDS = Collections.unmodifiableList(
            Arrays.asList("enable", "offlineunlock", "runtime"));

    /**
     * Server lifecycle hook; called from the mod's server-starting handler.
     */
    public static void register(net.minecraftforge.fml.common.event.FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandCloudworks());
    }

    private static void require(String[] args, int count) throws CommandException {
        if (args.length < count)
            throw new WrongUsageException("commands.cloudworks.requires_args", args[0], count - 1);
    }

    @Override
    public String getName() {
        return "cloudworks";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.cloudworks.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
        if (args.length == 3 && !"runtime".equalsIgnoreCase(args[0]))
            return getListOfStringsMatchingLastWord(args, "true", "false");
        return Collections.emptyList();
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new WrongUsageException(getUsage(sender));
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "runtime" -> {
                mekanism.cloudworks.common.service.CloudworksServerTickService service =
                        mekanism.cloudworks.common.service.CloudworksServerTickService.INSTANCE;
                mekanism.cloudworks.common.runtime.CloudworksRuntimeService.Stats stats = service.stats();
                sender.sendMessage(new TextComponentTranslation("commands.cloudworks.runtime_stats",
                        stats.pending(), stats.tickDebt(), stats.simulatedTicks(), stats.commits(), stats.conflicts(),
                        stats.workerFailures(), stats.persistenceWaits(), stats.projectedLines(), stats.projectedNodes()));
                if (args.length > 1) {
                    EntityPlayerMP target = getPlayer(server, sender, args[1]);
                    sender.sendMessage(new TextComponentTranslation("commands.cloudworks.runtime_player", target.getName(),
                            new TextComponentTranslation("commands.cloudworks.runtime_phase."
                                    + service.virtualPhase(target.getUniqueID()).name().toLowerCase(Locale.ROOT)),
                            service.runtimeTickDebt(target.getUniqueID())));
                }
            }
            case "enable" -> {
                require(args, 3);
                boolean enabled = parseBoolean(args[2]);
                // Online only: the capability half of the dual write lives on the entity.
                EntityPlayerMP target = getPlayer(server, sender, args[1]);
                // Validate the capability BEFORE touching the record: an unavailable
                // capability must refuse without having written any cloud state
                // (GPT review: no half-committed enable transactions).
                CloudworksEnabled capability = target.getCapability(CloudworksCapabilities.ENABLED, null);
                if (capability == null) throw new CommandException("commands.cloudworks.capability_unavailable");
                // Direction-aware ordering (P1-2, user decision 2026-10-05): the
                // entrance permit LEADS on enable and TRAILS on disable, so a crash
                // between the two writes always fails closed — enable crashes leave
                // "entrance open, engine not started" (GUI opens, data layer
                // refuses), disable crashes leave "engine stopped, GUI opens and
                // refuses". Never a running cloud without an entrance.
                try {
                    if (enabled) capability.setEnabled(true);
                    CloudworksPlayerDataManager.INSTANCE.setCloudEnabled(target.getUniqueID(), enabled);
                    if (!enabled) capability.setEnabled(false);
                } catch (RuntimeException failure) {
                    mekanism.common.Mekanism.logger.warn("Could not update Cloudworks state for {}", target.getName(), failure);
                    throw new CommandException("commands.cloudworks.state_update_failed",
                            new TextComponentTranslation(CloudworksMessages.errorKey(failure)));
                }
                sender.sendMessage(new TextComponentTranslation(enabled
                        ? "commands.cloudworks.enabled" : "commands.cloudworks.disabled", target.getName()));
            }
            case "offlineunlock" -> {
                require(args, 3);
                EntityPlayerMP target = getPlayer(server, sender, args[1]);
                try {
                    CloudworksPlayerDataManager.INSTANCE.setOfflineProcessingUnlocked(
                            target.getUniqueID(), parseBoolean(args[2]));
                } catch (IllegalStateException failure) {
                    throw new CommandException(CloudworksMessages.errorKey(failure));
                }
                sender.sendMessage(new TextComponentTranslation("commands.cloudworks.offlineunlock_updated", target.getName()));
            }
            default -> throw new WrongUsageException(getUsage(sender));
        }
    }
}
