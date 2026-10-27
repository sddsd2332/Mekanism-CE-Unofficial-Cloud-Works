package mekanism.cloudworks.api.card;

import mekanism.common.item.ItemMekanism;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import java.util.Objects;
import mekanism.cloudworks.api.CloudworksAPI;

/** Optional shared right-click item implementation for a registered card type. */
public class CloudworksCardItem extends ItemMekanism {
    private final ResourceLocation card;
    public CloudworksCardItem(ResourceLocation card) { this.card = Objects.requireNonNull(card); }
    public final ResourceLocation cardId() { return card; }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        CloudworksCardResult result = CloudworksAPI.useCard(player, hand, card);
        player.sendMessage(new TextComponentTranslation(result.messageKey, result.messageArguments()));
        return new ActionResult<>(result.applied() ? EnumActionResult.SUCCESS : EnumActionResult.FAIL, stack);
    }
}
