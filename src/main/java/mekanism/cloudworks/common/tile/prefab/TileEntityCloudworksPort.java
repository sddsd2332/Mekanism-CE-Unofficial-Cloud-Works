package mekanism.cloudworks.common.tile.prefab;

import mekanism.api.IConfigCardAccess;
import mekanism.api.IContentsListener;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.api.transmitters.TransmissionType;
import mekanism.cloudworks.common.content.*;
import mekanism.cloudworks.common.upgrade.CloudworksPortUpgrades;
import mekanism.common.Upgrade;
import mekanism.common.base.ISideConfiguration;
import mekanism.common.base.ISustainedData;
import mekanism.common.base.IUpgradeTile;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.capabilities.holder.energy.IEnergyContainerHolder;
import mekanism.common.capabilities.holder.fluid.FluidTankHelper;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.gas.GasTankHelper;
import mekanism.common.capabilities.holder.gas.IGasTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import mekanism.common.capabilities.holder.slot.InventorySlotHelper;
import mekanism.common.content.qio.IQIOFrequencyHolder;
import mekanism.common.content.qio.QIOFrequency;
import mekanism.common.frequency.FrequencyType;
import mekanism.common.frequency.IFrequencyHandler;
import mekanism.common.security.ISecurityTile;
import mekanism.common.tier.InductionCellTier;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.TileComponentSecurity;
import mekanism.common.tile.component.TileComponentUpgrade;
import mekanism.common.tile.prefab.TileEntityElectricBlock;
import mekanism.common.util.ItemDataUtils;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.SecurityUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import io.netty.buffer.ByteBuf;
import mekanism.api.Action;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.gas.Gas;
import mekanism.api.gas.GasRegistry;
import mekanism.api.gas.GasStack;
import mekanism.api.gas.IExtendedGasTank;
import mekanism.api.TileNetworkList;
import mekanism.cloudworks.common.block.BlockCloudTeamInput;
import mekanism.cloudworks.common.block.BlockCloudTeamOutput;
import mekanism.cloudworks.common.Cloudworks;
import mekanism.cloudworks.common.config.CloudworksConfig;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import mekanism.common.base.IGuiProvider;
import mekanism.common.Mekanism;
import mekanism.common.PacketHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * Mek containers stay the live interface; block NBT stores only identity and configuration.
 */
public abstract class TileEntityCloudworksPort extends TileEntityElectricBlock
        implements CloudworksOwnerPort, ISecurityTile, ISustainedData, ISideConfiguration, IConfigCardAccess,
        IUpgradeTile, IFrequencyHandler, IQIOFrequencyHolder {
    public static final int ITEM_SLOTS = 121, TANK_SLOTS = 36;
    public static final double MAX_ENERGY = InductionCellTier.ULTIMATE.getBaseMaxEnergy();
    protected final CloudworksPortBinding binding = new CloudworksPortBinding();
    private final List<CloudworksHybridTank> tanks = new ArrayList<>();
    private final boolean input;
    public final TileComponentSecurity securityComponent;
    public final TileComponentConfig configComponent;
    public final TileComponentEjector ejectorComponent;
    public final TileComponentUpgrade upgradeComponent;
    private final List<QIOResourceDescriptor> qioFilters = new ArrayList<>();
    private final CloudworksPortQIO qioTransfer = new CloudworksPortQIO();
    private int qioDelay;
    private List<QIOResourceDescriptor> qioChoices = Collections.emptyList();
    private int qioChoicesOffset, qioChoicesTotal, qioChoicesVersion;
    protected int cycleTicks;
    protected boolean paused;

    protected TileEntityCloudworksPort(String name, boolean input) {
        super(name, MAX_ENERGY);
        this.input = input;
        securityComponent = new TileComponentSecurity(this);
        upgradeComponent = new TileComponentUpgrade(this, Upgrade.ENERGY);
        upgradeComponent.setSupported(input ? CloudworksPortUpgrades.QIO_DOWNLOAD : CloudworksPortUpgrades.QIO_UPLOAD);
        frequencyComponent.track(FrequencyType.QIO, true, true, false);
        configComponent = new TileComponentConfig(this, TransmissionType.ITEM, TransmissionType.ENERGY,
                TransmissionType.FLUID, TransmissionType.GAS);
        ejectorComponent = new TileComponentEjector(this);
        initializeInventorySlots();
    }

    private IContentsListener wake(IContentsListener listener) {
        return () -> {
            if (listener != null) listener.onContentsChanged();
            changed();
        };
    }

    private void changed() {
        if (binding != null) binding.changed();
        if (world != null && !world.isRemote) bufferChanged();
    }

    protected void bufferChanged() {
    }

    protected void detachWatch() {
    }

    protected abstract void serverTick();

    @Override
    protected IInventorySlotHolder getInitialInventory(IContentsListener listener) {
        InventorySlotHelper builder = createInventorySlotHelper();
        for (int index = 0; index < ITEM_SLOTS; index++) {
            int x = 8 + index % 11 * 18, y = 18 + index / 11 * 18;
            if (input) {
                CloudworksPortInputSlot slot = CloudworksPortInputSlot.at(this::teamTransferAllowed, wake(listener), x, y);
                builder.addSlot(slot);
            } else {
                CloudworksPortOutputSlot slot = CloudworksPortOutputSlot.at(this::teamTransferAllowed, wake(listener), x, y);
                builder.addSlot(slot);
            }
        }
        return builder.build();
    }

    private void ensureTanks(IContentsListener listener) {
        if (tanks.isEmpty())
            for (int index = 0; index < TANK_SLOTS; index++) tanks.add(new CloudworksHybridTank(wake(listener)));
    }

    @Override
    protected IFluidTankHolder getInitialFluidTanks(IContentsListener listener) {
        ensureTanks(listener);
        FluidTankHelper builder = createFluidTankHelper();
        tanks.forEach(tank -> builder.addTank(tank.fluidView()));
        return builder.build();
    }

    @Override
    protected IGasTankHolder getInitialGasTanks(IContentsListener listener) {
        ensureTanks(listener);
        GasTankHelper builder = createGasTankHelper();
        tanks.forEach(tank -> builder.addTank(tank.gasView()));
        return builder.build();
    }

    @Override
    protected IEnergyContainerHolder getInitialEnergyContainers(IContentsListener listener) {
        return super.getInitialEnergyContainers(wake(listener));
    }

    @Override
    public void setEnergy(double amount) {
        double previous = getEnergy();
        super.setEnergy(amount);
        if (previous != getEnergy()) changed();
    }

    @Override
    public boolean canInsertFluid(@Nullable EnumFacing side) {
        return input && teamTransferAllowed() && super.canInsertFluid(side);
    }

    @Override
    public boolean canInsertGas(@Nullable EnumFacing side) {
        return input && teamTransferAllowed() && super.canInsertGas(side);
    }

    @Override
    public boolean canExtractFluid(@Nullable EnumFacing side) {
        return !input && teamTransferAllowed() && super.canExtractFluid(side);
    }

    @Override
    public boolean canExtractGas(@Nullable EnumFacing side) {
        return !input && teamTransferAllowed() && super.canExtractGas(side);
    }

    @Override
    protected boolean canInsertFluid(@Nonnull IExtendedFluidTank tank, @Nullable EnumFacing side) {
        return input && teamTransferAllowed() && super.canInsertFluid(tank, side);
    }

    @Override
    protected boolean canInsertGas(@Nonnull IExtendedGasTank tank, @Nullable EnumFacing side) {
        return input && teamTransferAllowed() && super.canInsertGas(tank, side);
    }

    @Override
    public boolean canInsertEnergy(@Nullable EnumFacing side) {
        return input && teamTransferAllowed() && super.canInsertEnergy(side);
    }

    @Override
    public boolean canExtractEnergy(@Nullable EnumFacing side) {
        return !input && teamTransferAllowed() && super.canExtractEnergy(side);
    }

    @Override
    protected boolean canExtractFluid(@Nonnull IExtendedFluidTank tank, @Nullable EnumFacing side) {
        return !input && teamTransferAllowed() && super.canExtractFluid(tank, side);
    }

    @Override
    protected boolean canExtractGas(@Nonnull IExtendedGasTank tank, @Nullable EnumFacing side) {
        return !input && teamTransferAllowed() && super.canExtractGas(tank, side);
    }

    @Override
    public double insertEnergy(double amount, @Nullable EnumFacing side, Action action) {
        return !input || !teamTransferAllowed() ? amount : super.insertEnergy(amount, side, action);
    }

    @Override
    public double insertEnergy(int container, double amount, @Nullable EnumFacing side, Action action) {
        return !input || !teamTransferAllowed() ? amount : super.insertEnergy(container, amount, side, action);
    }

    @Override
    public double extractEnergy(double amount, @Nullable EnumFacing side, Action action) {
        return input || !teamTransferAllowed() ? 0 : super.extractEnergy(amount, side, action);
    }

    @Override
    public double extractEnergy(int container, double amount, @Nullable EnumFacing side, Action action) {
        return input || !teamTransferAllowed() ? 0 : super.extractEnergy(container, amount, side, action);
    }

    @Override
    public double acceptEnergy(@Nullable EnumFacing side, double amount, boolean simulate) {
        return !input || !teamTransferAllowed() ? 0 : super.acceptEnergy(side, amount, simulate);
    }

    @Override
    public double pullEnergy(@Nullable EnumFacing side, double amount, boolean simulate) {
        return input || !teamTransferAllowed() ? 0 : super.pullEnergy(side, amount, simulate);
    }

    @Override
    public void onUpdate() {
        if (world != null && !world.isRemote && !teamTransferAllowed()) {
            binding.refresh(getInventorySlots(null), tanks, this::getEnergy);
            return;
        }
        if (world != null && !world.isRemote && !binding.active()) {
            if (!binding.mount(owner(), input, getInventorySlots(null), tanks, this::getEnergy, this::setEnergy))
                return;
            markDirty();
            invalidateCapability(null, null);
        }
        super.onUpdate();
        if (!world.isRemote) {
            tickQIO();
            serverTick();
            binding.refresh(getInventorySlots(null), tanks, this::getEnergy);
            try {
                binding.save(getInventorySlots(null), tanks, this::getEnergy, world.getTotalWorldTime());
            } catch (RuntimeException busy) { /* Dirty buffers retry on a later tick. */ }
        }
    }

    @Override
    protected void onUpdateServer() {
    }

    private void tickQIO() {
        if (!hasQIOUpgrade() || !binding.active()) return;
        if (qioDelay > 0) {
            qioDelay--;
            return;
        }
        qioDelay = 10;
        QIOFrequency frequency = getQIOFrequencyForTransfer();
        if (frequency == null) return;
        if (input) qioTransfer.download(frequency, qioFilters, getInventorySlots(null), tanks);
        else qioTransfer.upload(frequency, getInventorySlots(null), tanks);
    }

    public boolean isInputPort() {
        return input;
    }

    public boolean isDownloadPaused() {
        return paused;
    }

    public boolean isTeamPort() {
        return world != null && (getBlockType() instanceof BlockCloudTeamInput
                || getBlockType() instanceof BlockCloudTeamOutput);
    }

    protected boolean teamTransferAllowed() {
        return world == null || world.isRemote || !isTeamPort()
                || CloudworksTeamContext.registry().isActive(owner());
    }

    public boolean canPlayerAccess(EntityPlayer player) {
        return player.world.isRemote || (isTeamPort()
                ? CloudworksTeamContext.canAccess(player, owner())
                : SecurityUtils.canAccess(player, this));
    }

    public boolean hasQIOUpgrade() {
        return isUpgradeInstalled(input ? CloudworksPortUpgrades.QIO_DOWNLOAD : CloudworksPortUpgrades.QIO_UPLOAD);
    }

    @Override
    public TileComponentUpgrade getComponent() {
        return upgradeComponent;
    }

    @Override
    public QIOFrequency getQIOFrequency() {
        return getFrequency(FrequencyType.QIO);
    }

    @Nullable
    public QIOFrequency getQIOFrequencyForTransfer() {
        QIOFrequency frequency = getQIOFrequency();
        return frequency != null && frequency.isValid() && !frequency.isRemoved() && owner() != null
                && SecurityUtils.canAccess(frequency.getSecurity(), owner(), frequency.getOwner()) ? frequency : null;
    }

    public List<QIOResourceDescriptor> getQIOFilters() {
        return Collections.unmodifiableList(qioFilters);
    }

    public boolean addQIOFilter(QIOResourceDescriptor resource) {
        if (!input || !hasQIOUpgrade() || !CloudworksPortIo.isSupportedDownloadResource(resource)
                || qioFilters.contains(resource)) return false;
        qioFilters.add(resource);
        qioDelay = 0;
        markDirty();
        return true;
    }

    public boolean removeQIOFilter(QIOResourceDescriptor resource) {
        if (!input || !hasQIOUpgrade() || !qioFilters.remove(resource)) return false;
        markDirty();
        return true;
    }

    public void applyQIOState(List<QIOResourceDescriptor> filters, List<QIOResourceDescriptor> choices,
                              int offset, int total) {
        qioFilters.clear();
        qioFilters.addAll(filters);
        if (offset >= 0) {
            qioChoices = Collections.unmodifiableList(new ArrayList<>(choices));
            qioChoicesOffset = offset;
            qioChoicesTotal = total;
            qioChoicesVersion++;
        }
    }

    public List<QIOResourceDescriptor> getQIOChoices() {
        return qioChoices;
    }

    public int getQIOChoicesOffset() {
        return qioChoicesOffset;
    }

    public int getQIOChoicesTotal() {
        return qioChoicesTotal;
    }

    public int getQIOChoicesVersion() {
        return qioChoicesVersion;
    }

    @Override
    public void recalculateUpgradables(Upgrade upgrade) {
        super.recalculateUpgradables(upgrade);
        if (upgrade == Upgrade.ENERGY) {
            maxEnergy = MekanismUtils.getMaxEnergy(this, BASE_MAX_ENERGY);
            setEnergy(Math.min(getMaxEnergy(), getEnergy()));
        }
        qioDelay = 0;
        if (world != null && !world.isRemote) bufferChanged();
    }

    public void prepareRemoval() {
        detach();
    }

    private void detach() {
        if (world != null && !world.isRemote) {
            try {
                detachWatch();
                binding.detach(getInventorySlots(null), tanks, this::getEnergy);
            } catch (RuntimeException unavailable) {
                Mekanism.logger.error("Cloudworks port detach could not persist its external record", unavailable);
            } finally {
                invalidateCapability(null, null);
            }
        }
    }

    @Override
    public void invalidate() {
        detach();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        detach();
        super.onChunkUnload();
        if (world != null && !world.isRemote) frequencyComponent.invalidate();
    }

    public List<CloudworksHybridTank> portTanks() {
        return tanks;
    }

    protected int transferIntervalTicks() {
        return CloudworksConfig.currentSettings().portTransferIntervalTicks;
    }

    public double getCycleProgress() {
        return cycleTicks / (double) transferIntervalTicks();
    }

    public UUID owner() {
        return securityComponent.getOwnerUUID();
    }

    @Override
    public void bindOwner(UUID owner) {
        if (securityComponent.getOwnerUUID() == null) {
            securityComponent.setOwnerUUID(owner);
            markDirty();
        }
    }

    @Override
    public TileComponentSecurity getSecurity() {
        return securityComponent;
    }

    @Override
    public TileComponentConfig getConfig() {
        return configComponent;
    }

    @Override
    public EnumFacing getOrientation() {
        return facing;
    }

    @Override
    public TileComponentEjector getEjector() {
        return ejectorComponent;
    }

    @Override
    public IGuiProvider guiProvider() {
        return Cloudworks.proxy;
    }

    @Override
    public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing side) {
        return !isCapabilityDisabled(capability, side)
                && (capability == Capabilities.CONFIG_CARD_CAPABILITY || super.hasCapability(capability, side));
    }

    @Override
    public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing side) {
        if (isCapabilityDisabled(capability, side)) return null;
        if (capability == Capabilities.CONFIG_CARD_CAPABILITY) return Capabilities.CONFIG_CARD_CAPABILITY.cast(this);
        return super.getCapability(capability, side);
    }

    @Override
    public boolean persistInventory() {
        return false;
    }

    @Override
    protected boolean persistFluidTanks() {
        return false;
    }

    @Override
    protected boolean persistGasTanks() {
        return false;
    }

    @Override
    public void readCustomNBT(NBTTagCompound tag) {
        super.readCustomNBT(tag);
        electricityStored.set(0D);
        binding.read(tag);
        readQIOFilters(tag);
        paused = false;
        cycleTicks = 0;
    }

    @Override
    public void writeCustomNBT(NBTTagCompound tag) {
        super.writeCustomNBT(tag);
        tag.removeTag("electricityStored");
        tag.removeTag("items");
        tag.removeTag("fluidTanks");
        tag.removeTag("gasTanks");
        tag.removeTag("cwCycle");
        tag.removeTag("cwPaused");
        tag.removeTag("cwBlocked");
        binding.write(tag);
        writeQIOFilters(tag);
    }

    @Override
    public void writeSustainedData(ItemStack stack) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        binding.write(stack.getTagCompound());
        NBTTagCompound settings = new NBTTagCompound();
        configComponent.write(settings);
        ejectorComponent.write(settings);
        frequencyComponent.write(settings);
        writeQIOFilters(settings);
        stack.getTagCompound().setTag("cwPortConfig", settings);
        writeUpgrades(ItemDataUtils.getDataMap(stack));
    }

    @Override
    public void readSustainedData(ItemStack stack) {
        if (stack.hasTagCompound()) {
            binding.read(stack.getTagCompound());
            NBTTagCompound settings = stack.getTagCompound().getCompoundTag("cwPortConfig");
            if (!settings.isEmpty()) {
                configComponent.read(settings);
                ejectorComponent.read(settings);
                frequencyComponent.read(settings);
                readQIOFilters(settings);
            }
        }
        cycleTicks = 0;
        paused = false;
    }

    private void writeQIOFilters(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (QIOResourceDescriptor resource : qioFilters) list.appendTag(resource.write());
        tag.setTag("cwQIOFilters", list);
    }

    private void readQIOFilters(NBTTagCompound tag) {
        qioFilters.clear();
        NBTTagList list = tag.getTagList("cwQIOFilters", 10);
        for (int index = 0; index < list.tagCount(); index++) {
            QIOResourceDescriptor resource = QIOResourceDescriptor.read(list.getCompoundTagAt(index));
            if (CloudworksPortIo.isSupportedDownloadResource(resource) && !qioFilters.contains(resource))
                qioFilters.add(resource);
        }
    }

    protected void readPortPacket(ByteBuf data) {
    }

    protected void writePortPacket(TileNetworkList data) {
    }

    @Override
    public void handlePacketData(ByteBuf data) {
        super.handlePacketData(data);
        if (!world.isRemote) return;
        cycleTicks = data.readInt();
        paused = data.readBoolean();
        maxEnergy = data.readDouble();
        readPortPacket(data);
        for (CloudworksHybridTank tank : tanks) tank.drainAll();
        for (CloudworksHybridTank tank : tanks) {
            int mode = data.readByte();
            if (mode == 0) continue;
            int amount = data.readInt();
            String name = PacketHandler.readString(data);
            if (mode == 1) {
                Fluid fluid = FluidRegistry.getFluid(name);
                if (fluid != null) tank.setFluid(new FluidStack(fluid, amount));
            } else {
                Gas gas = GasRegistry.getGas(name);
                if (gas != null) tank.setGas(new GasStack(gas, amount));
            }
        }
    }

    @Override
    public TileNetworkList getNetworkedData(TileNetworkList data) {
        super.getNetworkedData(data);
        data.add(cycleTicks);
        data.add(paused);
        data.add(getMaxEnergy());
        writePortPacket(data);
        for (CloudworksHybridTank tank : tanks) {
            if (tank.getFluid() != null) {
                data.add((byte) 1);
                data.add(tank.getFluidAmount());
                data.add(FluidRegistry.getFluidName(tank.getFluid().getFluid()));
            } else if (tank.getGas() != null) {
                data.add((byte) 2);
                data.add(tank.getGasAmount());
                data.add(tank.getGas().getGas().getName());
            } else data.add((byte) 0);
        }
        return data;
    }
}
