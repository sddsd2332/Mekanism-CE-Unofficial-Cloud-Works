package mekanism.cloudworks.common.item.prefab;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

import mekanism.cloudworks.common.card.CloudworksCardEffect;
import mekanism.cloudworks.common.card.CloudworksCardEffects;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.common.item.ItemMekanism;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

/**
 * Card items share one admission, feedback and consumption path. Activation has a separate capability contract.
 */
public class ItemCloudworksCard extends ItemMekanism {
    private final ResourceLocation effectId;

    protected ItemCloudworksCard() {
        effectId = null;
    }

    public ItemCloudworksCard(ResourceLocation effectId) {
        this.effectId = Objects.requireNonNull(effectId, "effectId");
    }

    public CloudworksCardEffect cardEffect() {
        return CloudworksCardEffects.get(effectId);
    }

    protected UUID domain(EntityPlayer player) {
        return player.getUniqueID();
    }

    protected String domainRequiredMessage() {
        return "cloudworks.error.storage";
    }

    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        if (stack.isEmpty()) return new ActionResult<>(EnumActionResult.FAIL, stack);
        UUID owner = domain(player);
        if (owner == null) {
            player.sendMessage(new TextComponentTranslation(domainRequiredMessage()));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        CloudworksCardEffect.Result result;
        try {
            result = CloudworksPlayerDataManager.INSTANCE.applyCardEffect(owner, cardEffect());
        } catch (RuntimeException unavailable) {
            player.sendMessage(new TextComponentTranslation("cloudworks.error.storage"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        if (result.applied()) stack.shrink(1);
        player.sendMessage(new TextComponentTranslation(result.messageKey(), result.messageArguments()));
        return new ActionResult<>(result.applied() ? EnumActionResult.SUCCESS : EnumActionResult.FAIL, stack);
    }
}
