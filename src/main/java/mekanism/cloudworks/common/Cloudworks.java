package mekanism.cloudworks.common;

import io.netty.buffer.ByteBuf;
import mekanism.cloudworks.api.CloudworksEnabled;
import mekanism.cloudworks.common.capability.CloudworksCapabilities;
import mekanism.cloudworks.common.capability.DefaultCloudworksEnabled;
import mekanism.cloudworks.common.command.CommandCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork;
import mekanism.cloudworks.api.CloudworksInfuseResourceCodec;
import mekanism.cloudworks.common.registries.CloudworksBlocks;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.service.CloudworksServerTickService;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.team.CloudworksTeamBridges;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import mekanism.common.Mekanism;
import mekanism.common.Version;
import mekanism.common.base.IModule;
import mekanism.common.config.MekanismConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import java.util.UUID;
import java.io.File;
import mekanism.cloudworks.api.module.CloudworksUpgradeItems;
import mekanism.cloudworks.common.blueprint.CloudworksBlueprintLibrary;
import mekanism.cloudworks.common.config.CloudworksInfiniteResourceConfig;
import mekanism.cloudworks.common.content.CloudworksTerminalPersistence;
import mekanism.cloudworks.common.team.CloudworksTeamManager;
import mekanism.cloudworks.common.tile.TileEntityCloudDashboard;
import mekanism.cloudworks.common.tile.TileEntityCloudInput;
import mekanism.cloudworks.common.tile.TileEntityCloudOutput;
import mekanism.cloudworks.common.tile.TileEntityCloudworksTeamRecovery;
import mekanism.mekanism.Tags;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;

@Mod(modid = Cloudworks.MODID, useMetadata = true, acceptedMinecraftVersions = "[1.12,1.13)",
        dependencies = "required-after:mekanism;after:mekanismgenerators;after:mekanismmultiblockmachine;after:mekceumoremachine;after:crafttweaker;after:ftblib")
@Mod.EventBusSubscriber(modid = Cloudworks.MODID)
public final class Cloudworks implements IModule {
    public static final String MODID = "cloudworks";
    public static final String NAME = "Cloudworks";
    public static final Version VERSION = Version.get(Tags.VERSION);
    public static final CreativeTabCloudworks tabCloudworks = new CreativeTabCloudworks();

    @Mod.Instance(MODID)
    public static Cloudworks instance;

    @SidedProxy(clientSide = "mekanism.cloudworks.client.CloudworksClientProxy", serverSide = "mekanism.cloudworks.common.CloudworksCommonProxy")
    public static CloudworksCommonProxy proxy;

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        CloudworksBlocks.registerBlocks(event.getRegistry());
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        CloudworksItems.registerItems(event.getRegistry());
        CloudworksBlocks.registerItemBlocks(event.getRegistry());
    }

    @SubscribeEvent
    public static void attachCapability(AttachCapabilitiesEvent<Entity> event) {
        // Forge dispatches entity attachment events with Entity.class, including players.
        if (!(event.getObject() instanceof EntityPlayer)) return;
        event.addCapability(new ResourceLocation(MODID, "enabled"),
                new ICapabilitySerializable<NBTTagCompound>() {
                    private final CloudworksEnabled value = new DefaultCloudworksEnabled();

                    @Override
                    public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
                        return capability == CloudworksCapabilities.ENABLED ? (T) value : null;
                    }

                    @Override
                    public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
                        return capability == CloudworksCapabilities.ENABLED;
                    }

                    @Override
                    public NBTTagCompound serializeNBT() {
                        return value.serializeNBT();
                    }

                    @Override
                    public void deserializeNBT(NBTTagCompound nbt) {
                        value.deserializeNBT(nbt);
                    }
                });
    }

    @SubscribeEvent
    public static void cloneCapability(PlayerEvent.Clone event) {
        CloudworksEnabled oldValue = event.getOriginal().getCapability(CloudworksCapabilities.ENABLED, null);
        CloudworksEnabled newValue = event.getEntityPlayer().getCapability(CloudworksCapabilities.ENABLED, null);
        if (oldValue != null && newValue != null) newValue.setEnabled(oldValue.isEnabled());
    }

    @SubscribeEvent
    public static void playerLoggedIn(PlayerLoggedInEvent event) {
        EntityPlayer player = event.player;
        if (player.world.isRemote) return;
        CloudworksTeamContext.playerLoggedIn(player.getUniqueID(), player.getName());
        CloudworksTerminalPersistence.playerLoggedIn(player.getUniqueID());
        CloudworksEnabled capability = player.getCapability(CloudworksCapabilities.ENABLED, null);
        if (capability != null && capability.isEnabled()) {
            CloudworksPlayerDataManager.INSTANCE.getOrCreate(player.getUniqueID());
        } else if (CloudworksPlayerDataManager.INSTANCE.recordEnabled(player.getUniqueID())) {
            // P1-2 fail-closed reconciliation (user decision 2026-10-05): record ON
            // + entrance OFF is the enable-direction crash window — heal to stopped
            // (data preserved; lines pause while disabled, exactly like /disable).
            CloudworksPlayerDataManager.INSTANCE.setCloudEnabled(player.getUniqueID(), false);
        }
        // Cross-restart upload recovery (GPT review 4): committed uploads whose
        // physical deduction never happened are settled here, exactly once.
        if (player instanceof EntityPlayerMP serverPlayer)
            CloudworksPlayerDataManager.INSTANCE.applySourceDebts(serverPlayer);
        CloudworksServerTickService.INSTANCE.playerLoggedIn(player.getUniqueID());
        CloudworksTeamManager.refreshPresence();
        if (player instanceof EntityPlayerMP serverPlayer)
            CloudworksTeamManager.deliverNotices(serverPlayer);
        if (CloudworksTeamContext.owner(player) != null) {
            UUID actor = player.getUniqueID();
            String name = player.getName();
            CloudworksTeamManager.submit(
                    () -> CloudworksTeamContext.registry().updateMemberName(actor, name), null);
        }
    }

    @SubscribeEvent
    public static void playerLoggedOut(PlayerLoggedOutEvent event) {
        if (!event.player.world.isRemote) {
            UUID teamOwner = CloudworksTeamContext.owner(event.player.getUniqueID());
            CloudworksTeamContext.playerLoggedOut(event.player.getUniqueID());
            CloudworksTerminalPersistence.flushPlayer(event.player.getUniqueID());
            CloudworksServerTickService.INSTANCE.playerLoggedOut(event.player.getUniqueID(), teamOwner);
            CloudworksTeamManager.refreshPresence();
        }
    }

    @SubscribeEvent
    public static void playerSaved(PlayerEvent.SaveToFile event) {
        if (!event.getEntityPlayer().world.isRemote)
            CloudworksTerminalPersistence.playerSaved(
                    event.getEntityPlayer().getUniqueID());
    }

    @Mod.EventHandler
    public static void serverStopping(FMLServerStoppingEvent event) {
        try {
            CloudworksTeamBridges.close();
            CloudworksServerTickService.INSTANCE.onServerStopping();
        } finally {
            try {
                CloudworksPlayerDataManager.INSTANCE.close();
            } finally {
                try {
                    CloudworksTeamManager.close();
                    CloudworksTeamContext.close();
                }
                finally { CloudworksConfig.serverStopped(); }
            }
        }
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        CloudworksConfig.load(event.getSuggestedConfigurationFile());
        CloudworksInfiniteResourceConfig.loadFile(new File(
                event.getModConfigurationDirectory(),
                CloudworksInfiniteResourceConfig.FILE_NAME));
        CloudworksBlueprintLibrary.INSTANCE.configure(
                event.getModConfigurationDirectory().toPath());
        proxy.preInit();
        CloudworksCapabilities.register();
        CloudworksRegistries.bootstrap();
        CloudworksInfuseResourceCodec.register();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        // Mekanism ItemBlocks are guaranteed to be present after registry events;
        // built-in virtual machine keys must be created after that boundary.
        CloudworksBootstrap.initBuiltins();
        Mekanism.modulesLoaded.add(this);
        CloudworksPlayerDataManager.INSTANCE.register();
        CloudworksNetwork.init();
        NetworkRegistry.INSTANCE.registerGuiHandler(this, proxy);
        MinecraftForge.EVENT_BUS.register(CloudworksServerTickService.INSTANCE);
        // Placeholder tiles for the port blocks: the save format and block-tile
        // binding are stable from now on; the transfer logic is the port batch.
        GameRegistry.registerTileEntity(
                TileEntityCloudInput.class,
                new ResourceLocation(MODID, "cloud_input"));
        GameRegistry.registerTileEntity(
                TileEntityCloudOutput.class,
                new ResourceLocation(MODID, "cloud_output"));
        GameRegistry.registerTileEntity(
                TileEntityCloudDashboard.class,
                new ResourceLocation(MODID, "cloudworks_dashboard"));
        GameRegistry.registerTileEntity(
                TileEntityCloudworksTeamRecovery.class,
                new ResourceLocation(MODID, "team_cloudworks_recovery"));
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        World world = server == null ? null : server.getEntityWorld();
        ISaveHandler saveHandler = world == null ? null : world.getSaveHandler();
        File worldDirectory = saveHandler == null ? null : saveHandler.getWorldDirectory();
        if (worldDirectory == null) {
            throw new IllegalStateException("Cloudworks cannot start without a valid world save directory");
        }
        CloudworksConfig.serverStarting();
        CloudworksPlayerDataManager.INSTANCE.load(worldDirectory);
        CloudworksTeamContext.load(worldDirectory.toPath().toAbsolutePath().normalize().resolve("cloudworks"));
        CloudworksServerTickService.INSTANCE.onServerStarting(
                world instanceof WorldServer
                        ? (WorldServer) world : null);
        CommandCloudworks.register(event);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        CloudworksTeamBridges.install();
        if (!CloudworksTeamManager.submit(() -> {}, null))
            throw new IllegalStateException("Could not queue Cloudworks team recovery");
    }

    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        CloudworksBootstrap.loadComplete();
    }

    @Override
    public Version getVersion() {
        return VERSION;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void writeConfig(ByteBuf dataStream, MekanismConfig config) {
        CloudworksConfig.writeServer(dataStream);
        CloudworksUpgradeItems.writeServer(dataStream);
    }

    @Override
    public void readConfig(ByteBuf dataStream, MekanismConfig destConfig) {
        CloudworksConfig.readServer(dataStream);
        CloudworksUpgradeItems.readServer(dataStream);
    }

    @Override
    public void resetClient() {
        CloudworksConfig.setSyncedConfig(null);
        CloudworksUpgradeItems.resetClient();
    }

    @SubscribeEvent
    public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (MODID.equals(event.getModID())) CloudworksConfig.reload();
    }
}
