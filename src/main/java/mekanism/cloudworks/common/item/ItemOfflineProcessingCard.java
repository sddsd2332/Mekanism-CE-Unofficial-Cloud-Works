package mekanism.cloudworks.common.item;

import javax.annotation.Nonnull;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.common.item.ItemMekanism;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

public class ItemOfflineProcessingCard extends ItemMekanism {
    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        int result;
        try {
            result = CloudworksPlayerDataManager.INSTANCE.unlockOfflineProcessing(player.getUniqueID());
        } catch (RuntimeException unavailable) {
            player.sendMessage(new TextComponentTranslation("cloudworks.error.storage"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        if (result < 0) {
            player.sendMessage(new TextComponentTranslation(result == -3 ? "cloudworks.error.offline_disabled"
                    : result == -2 ? "chat.cloudworks.offline_already_unlocked" : "chat.cloudworks.not_enabled"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        stack.shrink(1);
        player.sendMessage(new TextComponentTranslation("chat.cloudworks.offline_unlocked"));
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }
}
