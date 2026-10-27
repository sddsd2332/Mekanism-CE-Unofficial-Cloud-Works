package mekanism.cloudworks.common.item;

import mekanism.cloudworks.common.config.CloudworksInfiniteResourceConfig;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.special.CloudworksInfiniteResourceCardData;
import mekanism.cloudworks.common.special.CloudworksInfiniteResourcePresets;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeStore;
import mekanism.cloudworks.common.special.CloudworksSpecialResourceNames;
import mekanism.common.item.ItemMekanism;
import mekanism.common.util.LangUtils;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import mekanism.common.Mekanism;

public final class ItemInfiniteResourceCard extends ItemMekanism {
    private List<ItemStack> creativeCards;

    public static boolean isAllowedResource(@Nonnull ItemStack stack) {
        return !stack.isEmpty() && !(stack.getItem() instanceof ItemInfiniteResourceCard);
    }

    public void refreshCreativeCards() {
        creativeCards = CloudworksInfiniteResourcePresets.cards(this,
                CloudworksInfiniteResourceConfig.local().presets(), (entry, failure) -> {
                    if (Mekanism.logger != null)
                        Mekanism.logger.warn(
                                "Skipping invalid Cloudworks infinite-resource card preset '{}': {}",
                                entry, failure.getMessage());
                });
    }

    @Override
    public void getSubItems(@Nonnull CreativeTabs tab, @Nonnull NonNullList<ItemStack> items) {
        if (!isInCreativeTab(tab)) return;
        if (creativeCards == null) refreshCreativeCards();
        for (ItemStack card : creativeCards) items.add(card.copy());
    }

    @Nonnull
    @Override
    public String getItemStackDisplayName(@Nonnull ItemStack stack) {
        try {
            return LangUtils.localizeWithFormat("item.cloud_infinite_resource_card.name",
                    CloudworksSpecialResourceNames.resource(CloudworksInfiniteResourceCardData.parse(stack).descriptor));
        } catch (IllegalArgumentException invalid) {
            return LangUtils.localize("item.cloud_infinite_resource_card.name_empty");
        }
    }

    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        CloudworksInfiniteResourceCardData card;
        try {
            card = CloudworksInfiniteResourceCardData.parse(stack);
        } catch (IllegalArgumentException corrupt) {
            player.sendMessage(new TextComponentTranslation("chat.cloudworks.card_corrupt"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        try {
            UUID targetOwner = player.getUniqueID();
            ItemStack offhand = player.getHeldItemOffhand();
            if (offhand != null && !offhand.isEmpty()
                    && offhand.getItem()
                    == CloudworksItems.TeamTerminal) {
                UUID teamOwner = CloudworksTeamContext.owner(player);
                if (teamOwner == null || !CloudworksTeamContext.canAccess(player, teamOwner)) {
                    player.sendMessage(new TextComponentTranslation("cloudworks.error.team_required"));
                    return new ActionResult<>(EnumActionResult.FAIL, stack);
                }
                targetOwner = teamOwner;
            }
            CloudworksSpecialNodeStore.AddResult result =
                    CloudworksPlayerDataManager.INSTANCE.addInfiniteResourceCard(
                            targetOwner, card);
            if (result == CloudworksSpecialNodeStore.AddResult.DUPLICATE) {
                player.sendMessage(new TextComponentTranslation("chat.cloudworks.resource_not_improved"));
                return new ActionResult<>(EnumActionResult.FAIL, stack);
            }
            stack.shrink(1);
            player.sendMessage(new TextComponentTranslation(result == CloudworksSpecialNodeStore.AddResult.INCREASED
                    ? "chat.cloudworks.resource_upgraded" : "chat.cloudworks.resource_added"));
            return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        } catch (RuntimeException failure) {
            String key = "cloudworks.error.disabled".equals(failure.getMessage())
                    ? "chat.cloudworks.not_enabled" : "cloudworks.error.storage";
            player.sendMessage(new TextComponentTranslation(key));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
    }
}
