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

public class ItemWorkRateUpgradeCard extends ItemMekanism {
    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        int rate;
        try {
            rate = CloudworksPlayerDataManager.INSTANCE.increaseWorkRate(player.getUniqueID());
        } catch (RuntimeException unavailable) {
            player.sendMessage(new TextComponentTranslation("cloudworks.error.storage"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        if (rate < 0) {
            player.sendMessage(new TextComponentTranslation(rate == -2
                    ? "chat.cloudworks.work_rate_max" : "chat.cloudworks.not_enabled"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        stack.shrink(1);
        player.sendMessage(new TextComponentTranslation("chat.cloudworks.work_rate_upgraded", rate));
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }
}
