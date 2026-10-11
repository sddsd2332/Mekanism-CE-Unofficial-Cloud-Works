package mekanism.cloudworks.common.item;

import mekanism.cloudworks.common.CloudworksEntry;
import mekanism.common.item.ItemMekanism;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;

/**
 * The every-player cloudworks entry item (portable terminal). Extends the
 * same non-energized base as the portable QIO dashboard; right-click opens the
 * holder's own cloudworks GUI. Model assets reuse the portable QIO dashboard's, copied
 * into the cloudworks domain.
 */
public class ItemCloudworksTerminal extends ItemMekanism {
    public ItemCloudworksTerminal() {
        setMaxStackSize(1);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        if (!world.isRemote) CloudworksEntry.open(player);
        return new ActionResult<>(EnumActionResult.SUCCESS, player.getHeldItem(hand));
    }
}

