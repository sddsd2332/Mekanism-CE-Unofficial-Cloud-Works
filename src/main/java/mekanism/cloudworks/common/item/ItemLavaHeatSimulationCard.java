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

public final class ItemLavaHeatSimulationCard extends ItemMekanism {
    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        int cards = CloudworksPlayerDataManager.INSTANCE.expandLavaHeat(player.getUniqueID());
        if (cards < 0) {
            player.sendMessage(new TextComponentTranslation(cards == -2
                    ? "chat.cloudworks.lava_heat_max" : "chat.cloudworks.not_enabled"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        stack.shrink(1);
        player.sendMessage(new TextComponentTranslation("chat.cloudworks.lava_heat_expanded", cards));
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }
}
