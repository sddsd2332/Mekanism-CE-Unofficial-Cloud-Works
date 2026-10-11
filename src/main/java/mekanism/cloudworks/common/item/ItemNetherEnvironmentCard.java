package mekanism.cloudworks.common.item;

import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.common.item.ItemMekanism;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import javax.annotation.Nonnull;

public final class ItemNetherEnvironmentCard extends ItemMekanism {
    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        if (!CloudworksPlayerDataManager.INSTANCE.unlockNetherEnvironment(player.getUniqueID())) {
            boolean enabled = CloudworksPlayerDataManager.INSTANCE.enabledForTransfer(player.getUniqueID());
            player.sendMessage(new TextComponentTranslation(enabled
                    ? "chat.cloudworks.nether_environment_installed" : "chat.cloudworks.not_enabled"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        stack.shrink(1);
        player.sendMessage(new TextComponentTranslation("chat.cloudworks.nether_environment_unlocked"));
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }
}
