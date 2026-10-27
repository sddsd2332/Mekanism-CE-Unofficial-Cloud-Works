package mekanism.cloudworks.common.item;

import mekanism.client.gui.GuiUtils;
import mekanism.cloudworks.common.Cloudworks;
import mekanism.cloudworks.common.CloudworksEntry;
import mekanism.common.interfaces.IOverlayRenderAware;
import mekanism.common.item.ItemMekanism;
import mekanism.common.util.MekanismUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;

/** Opens the player's native Cloudworks team, independent of optional team mods. */
public final class ItemCloudworksTeamTerminal extends ItemMekanism implements IOverlayRenderAware {
    public ItemCloudworksTeamTerminal() {
        setMaxStackSize(1);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        if (!world.isRemote) CloudworksEntry.openTeam(player);
        return new ActionResult<>(EnumActionResult.SUCCESS, player.getHeldItem(hand));
    }

    @Override
    public boolean renderItemOverlayIntoGUI(@NotNull ItemStack stack, int xPosition, int yPosition) {
        if (stack.getItem() instanceof ItemCloudworksTeamTerminal) {
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(xPosition, yPosition, 200);
                GlStateManager.scale(0.5F, 0.5F, 1F);
                GlStateManager.disableDepth();
                Minecraft.getMinecraft().renderEngine.bindTexture(
                        MekanismUtils.getResource(Cloudworks.MODID, MekanismUtils.ResourceType.GUI,
                                "amplifier_entity.png"));
                GuiUtils.blit(0, 0, 0, 0, 18, 18, 18, 18);
            } finally {
                GlStateManager.enableDepth();
                GlStateManager.popMatrix();
            }
            return true;
        }
        return false;
    }
}
