package mekanism.cloudworks.common.item.itemBlock;

import mekanism.common.item.interfaces.IItemBlockPlacementData;
import mekanism.common.security.ISecurityItem;
import mekanism.common.security.ISecurityTile.SecurityMode;
import mekanism.common.util.ItemDataUtils;
import mekanism.common.util.MekanismPlacementData;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import mekanism.cloudworks.common.block.BlockCloudTeamInput;
import mekanism.cloudworks.common.block.BlockCloudTeamOutput;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextComponentTranslation;

/**
 * QIO-style external storage reference with Mek security and placement data.
 * No resource payload or item energy capability creates a second authority.
 */
public class ItemBlockCloudworksPort extends ItemBlock
        implements ISecurityItem, IItemBlockPlacementData {

    public ItemBlockCloudworksPort(Block block) {
        super(block);
        setMaxStackSize(1);
    }

    @Nonnull
    @Override public EnumActionResult onItemUse(EntityPlayer player,
            World world, BlockPos pos, EnumHand hand, EnumFacing facing,
            float hitX, float hitY, float hitZ) {
        if (!world.isRemote && (block instanceof BlockCloudTeamInput
                || block instanceof BlockCloudTeamOutput)) {
            ItemStack stack = player.getHeldItem(hand);
            UUID team = stack.hasTagCompound() && stack.getTagCompound().hasKey("cwPortId", 8)
                    ? getOwnerUUID(stack) : CloudworksTeamContext.owner(player);
            if (!CloudworksTeamContext.canAccess(player, team)) {
                player.sendMessage(new TextComponentTranslation("cloudworks.error.team_required"));
                return EnumActionResult.FAIL;
            }
        }
        return super.onItemUse(player, world, pos, hand, facing, hitX, hitY, hitZ);
    }

    @Override
    public void restorePlacementData(@Nonnull ItemStack stack, @Nonnull EntityLivingBase placer,
                                     @Nonnull World world, @Nonnull BlockPos pos,
                                     @Nonnull TileEntity tileEntity) {
        MekanismPlacementData.restoreCommon(stack, placer, tileEntity);
    }

    @Nullable
    @Override
    public UUID getOwnerUUID(ItemStack stack) {
        String value = ItemDataUtils.getString(stack, "ownerUUID");
        if (value.isEmpty()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public void setOwnerUUID(ItemStack stack, @Nullable UUID owner) {
        if (owner == null) ItemDataUtils.removeData(stack, "ownerUUID");
        else ItemDataUtils.setString(stack, "ownerUUID", owner.toString());
    }

    @Override
    public boolean hasOwner(ItemStack stack) {
        return true;
    }

    @Override
    public SecurityMode getSecurity(ItemStack stack) {
        int ordinal = ItemDataUtils.getInt(stack, "security");
        return ordinal >= 0 && ordinal < SecurityMode.values().length
                ? SecurityMode.values()[ordinal] : SecurityMode.PUBLIC;
    }

    @Override
    public void setSecurity(ItemStack stack, SecurityMode mode) {
        if (getOwnerUUID(stack) == null || mode == null || mode == SecurityMode.PUBLIC) {
            ItemDataUtils.removeData(stack, "security");
        } else {
            ItemDataUtils.setInt(stack, "security", mode.ordinal());
        }
    }

    @Override
    public boolean hasSecurity(ItemStack stack) {
        return true;
    }
}


