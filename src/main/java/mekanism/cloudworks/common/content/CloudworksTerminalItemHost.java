package mekanism.cloudworks.common.content;

import mekanism.api.IContentsListener;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.DataHandlerUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import mekanism.common.inventory.container.item.ItemStackSlotAccess;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import mekanism.cloudworks.common.registries.CloudworksItems;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;

/** Portable terminal inventory persisted inside its host item, like the portable QIO dashboard. */
public final class CloudworksTerminalItemHost implements CloudworksTerminalHost {

    private static final String SLOT_TAG = "cloudTerminalSlots";

    private final ItemStackSlotAccess itemAccess;
    private final boolean remote;
    private final EntityPlayer context;
    private final Item hostItem;
    private UUID terminalId;
    private CompletableFuture<Void> playerSave;
    private NBTTagCompound savedIdentity;
    private final IContentsListener persistListener = this::persist;
    private final List<IInventorySlot> slots;

    public CloudworksTerminalItemHost(@Nonnull EntityPlayer context, @Nonnull ItemStackSlotAccess itemAccess) {
        this.itemAccess = Objects.requireNonNull(itemAccess);
        this.context = context;
        this.remote = context.world.isRemote;
        this.hostItem = itemAccess.getStack().getItem();
        slots = Arrays.asList(
                CloudworksTerminalSlots.machineInput(persistListener, 8, 18),
                CloudworksTerminalSlots.machineOutput(persistListener, 26, 18),
                CloudworksTerminalSlots.energyInput(persistListener, 44, 18),
                CloudworksTerminalSlots.upgradeInput(persistListener, 62, 18),
                CloudworksTerminalSlots.upgradeOutput(persistListener, 80, 18));
        ItemStack terminal = itemAccess.getStack();
        if (!remote && !terminal.isEmpty()) {
            NBTTagCompound tag = terminal.getTagCompound();
            if (tag == null) {
                tag = new NBTTagCompound();
                terminal.setTagCompound(tag);
            }
            if (tag.hasKey("cloudTerminalId"))
                terminalId = UUID.fromString(tag.getString("cloudTerminalId"));
            else {
                terminalId = UUID.randomUUID();
                tag.setString("cloudTerminalId", terminalId.toString());
            }
        }
        read();
    }

    @Override
    public UUID terminalId() {
        return Objects.requireNonNull(terminalId, "Server terminal identity");
    }

    @Override
    public boolean available() {
        if (!itemAccess.isOriginalStackPresent()) return false;
        ItemStack stack = itemAccess.getStack();
        if (stack.isEmpty() || stack.getItem() != hostItem) return false;
        if (remote) return true;
        return terminalId != null && stack.hasTagCompound()
                && terminalId.toString().equals(stack.getTagCompound().getString("cloudTerminalId"));
    }

    public ItemStackSlotAccess itemAccess() {
        return itemAccess;
    }

    @Override public boolean allowsOwnerChange() {
        return hostItem == CloudworksItems.TeamTerminal;
    }

    private ItemStack requireHostStack() {
        if (!available()) throw new IllegalStateException("Portable terminal host was replaced");
        return itemAccess.getStack();
    }

    @Override
    public UUID appliedTerminalTransfer() {
        ItemStack stack = requireHostStack();
        return stack.hasTagCompound() && stack.getTagCompound().hasKey("cloudTerminalApplied", 8)
                ? UUID.fromString(stack.getTagCompound().getString("cloudTerminalApplied")) : null;
    }

    @Override
    public void acknowledgeTerminalTransfer(UUID transferId) {
        ItemStack stack = requireHostStack();
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound().setString("cloudTerminalApplied", transferId.toString());
    }

    @Override
    public void persistIdentity() {
        persist();
        NBTTagCompound current = requireHostStack().writeToNBT(new NBTTagCompound());
        if (current.equals(savedIdentity) && playerSave != null && !playerSave.isCompletedExceptionally()) return;
        if (context instanceof EntityPlayerMP player && player.getServer() != null)
            playerSave = CloudworksTerminalPersistence.savePlayer(player);
        savedIdentity = current;
    }

    @Override
    public boolean identityDurable() {
        if (playerSave == null) return true;
        if (!playerSave.isDone()) return false;
        if (playerSave.isCancelled()) {
            persistIdentity();
            return false;
        }
        playerSave.join();
        return true;
    }

    private void read() {
        ItemStack stack = itemAccess.getStack();
        if (stack.isEmpty() || !stack.hasTagCompound()) return;
        NBTTagList list = stack.getTagCompound().getTagList(SLOT_TAG, Constants.NBT.TAG_COMPOUND);
        if (!list.isEmpty()) DataHandlerUtils.readContainers(slots, list);
    }

    @Override
    public List<IInventorySlot> terminalSlots() {
        return slots;
    }

    @Override
    public void persist() {
        if (remote) return;   // client NBT churn is pointless; server sync owns the truth
        ItemStack stack = requireHostStack();
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setTag(SLOT_TAG, DataHandlerUtils.writeContainers(slots));
    }
}
