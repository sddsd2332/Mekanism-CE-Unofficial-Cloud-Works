package mekanism.cloudworks.common.item;

import javax.annotation.Nonnull;
import mekanism.cloudworks.api.CloudworksEnabled;
import mekanism.cloudworks.common.capability.CloudworksCapabilities;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.common.item.ItemMekanism;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

public final class ItemCloudActivationCard extends ItemMekanism {
    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        CloudworksEnabled capability = player.getCapability(CloudworksCapabilities.ENABLED, null);
        CloudworksPlayerDataManager manager = CloudworksPlayerDataManager.INSTANCE;
        if (capability == null) {
            player.sendMessage(new TextComponentTranslation("cloudworks.error.storage"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        boolean previous = capability.isEnabled();
        try {
            if (previous && manager.recordEnabled(player.getUniqueID())) {
                player.sendMessage(new TextComponentTranslation("chat.cloudworks.already_activated"));
                return new ActionResult<>(EnumActionResult.FAIL, stack);
            }
            // The entrance permit leads the cloud record, as in the existing enable command.
            capability.setEnabled(true);
            manager.setCloudEnabled(player.getUniqueID(), true);
        } catch (RuntimeException failure) {
            capability.setEnabled(previous);
            player.sendMessage(new TextComponentTranslation("cloudworks.error.storage"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        stack.shrink(1);
        player.sendMessage(new TextComponentTranslation("chat.cloudworks.activated"));
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }
}
