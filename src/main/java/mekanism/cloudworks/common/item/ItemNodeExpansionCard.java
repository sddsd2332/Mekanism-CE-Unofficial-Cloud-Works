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
 * 节点扩容卡: right-click consumes one card and permanently raises the owner's
 * per-line machine node ceiling by {@link #getNodeStep()} (owner-level setting,
 * applies to every line up to the configured cap). Requires an enabled cloud;
 * otherwise the card is not consumed and the player gets the standard
 * "not enabled" notice. A short use cooldown prevents double-spend spam.
 */

public class ItemNodeExpansionCard extends ItemMekanism {


    public ItemNodeExpansionCard() {
    }

    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        UUID owner = player.getUniqueID();
        int ceiling = CloudworksPlayerDataManager.INSTANCE.expandNodeLimit(owner, getNodeStep());
        if (ceiling < 0) {
            player.sendMessage(new TextComponentTranslation(ceiling == -2
                    ? "chat.cloudworks.node_limit_max" : "chat.cloudworks.not_enabled"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        stack.shrink(1);
        player.sendMessage(new TextComponentTranslation("chat.cloudworks.nodes_expanded", ceiling));
        return new ActionResult<>(EnumActionResult.SUCCESS, stack);
    }

    public int getNodeStep() {
        return 1;
    }
}


