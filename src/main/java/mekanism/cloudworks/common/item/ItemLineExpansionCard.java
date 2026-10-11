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
import java.util.UUID;

/**
 * 产线扩容卡: right-click consumes one card and permanently raises the using
 * player's usable production line ceiling by one — unlocked and hard max rise
 * together up to the configured cap, so the new slot is immediately creatable.
 * Requires an enabled cloud; otherwise the card is not consumed and the player
 * gets the standard "not enabled" notice. A short use cooldown prevents
 * double-spend spam while holding a stack.
 */
public class ItemLineExpansionCard extends ItemMekanism {


    public ItemLineExpansionCard() {
    }

    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        UUID owner = player.getUniqueID();
        int unlocked = CloudworksPlayerDataManager.INSTANCE.expandLineLimit(owner);
        if (unlocked < 0) {
            player.sendMessage(new TextComponentTranslation(unlocked == -2
                    ? "chat.cloudworks.line_limit_max" : "chat.cloudworks.not_enabled"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        stack.shrink(1);
        player.sendMessage(new TextComponentTranslation("chat.cloudworks.line_expanded", unlocked));
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }
}


