package mekanism.cloudworks.common.item;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.cloudworks.common.card.CloudworksCardEffect;
import mekanism.cloudworks.common.card.CloudworksCardEffects;
import mekanism.cloudworks.common.item.prefab.ItemCloudworksCard;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.cloudworks.common.service.CloudworksPlayerDataManager;
import mekanism.cloudworks.common.team.CloudworksTeamContext;
import mekanism.common.util.LangUtils;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import mekanism.cloudworks.api.environment.EnvironmentAxisRegistry;

/** Shared NBT/activation plumbing for one independent environment card family. */
public abstract class ItemEnvironmentCard extends ItemCloudworksCard {
    private static final String AXIS_TAG = "Axis";
    private final ResourceLocation fixedAxis;

    protected ItemEnvironmentCard() {
        fixedAxis = null;
    }

    protected ItemEnvironmentCard(ResourceLocation fixedAxis) {
        this.fixedAxis = Objects.requireNonNull(fixedAxis, "fixedAxis");
    }

    protected abstract List<EnvironmentAxis> axes();
    protected abstract String shellKey();
    protected abstract String axisWord(EnvironmentAxis axis);
    protected abstract boolean matches(EnvironmentAxis axis, World world, EntityPlayer player);

    /** Samples one axis for this card family; specialized families may add a context phase. */
    protected EnvironmentAxis sampleAxis(World world, EntityPlayer player) {
        for (EnvironmentAxis axis : axes()) {
            if (matches(axis, world, player)) return axis;
        }
        return null;
    }

    public static Item cardFor(EnvironmentAxis axis) {
        switch (axis.kind()) {
            case BIOME: return CloudworksItems.BiomeCard;
            case DIMENSION: return CloudworksItems.DimensionCard;
            case STRUCTURE: return CloudworksItems.StructureCard;
            case WEATHER: return CloudworksItems.WeatherCard;
            default: throw new IllegalArgumentException("Unknown environment card family: " + axis.kind());
        }
    }

    public static ItemStack bound(Item card, ResourceLocation axisId) {
        ItemStack stack = new ItemStack(card);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString(AXIS_TAG, Objects.requireNonNull(axisId, "axisId").toString());
        stack.setTagCompound(tag);
        return stack;
    }

    public static ResourceLocation axisIdOf(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTagCompound()) return null;
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey(AXIS_TAG, 8)) return null;
        try { return new ResourceLocation(tag.getString(AXIS_TAG)); }
        catch (RuntimeException invalid) { return null; }
    }

    private static boolean hasAxisTag(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.hasTagCompound()
                && stack.getTagCompound() != null && stack.getTagCompound().hasKey(AXIS_TAG);
    }

    private EnvironmentAxis boundAxis(ItemStack stack) {
        if (hasAxisTag(stack)) {
            ResourceLocation id = axisIdOf(stack);
            return id == null ? null : EnvironmentAxisRegistry.byId(id);
        }
        return fixedAxis == null ? null
                : EnvironmentAxisRegistry.byId(fixedAxis);
    }

    @Override
    public CloudworksCardEffect cardEffect() {
        if (fixedAxis == null)
            throw new IllegalStateException("Blank sampling cards resolve their effect from NBT");
        EnvironmentAxis axis = EnvironmentAxisRegistry.byId(fixedAxis);
        if (axis == null) throw new IllegalStateException("Unknown fixed environment axis: " + fixedAxis);
        return CloudworksCardEffects.get(axis.effectId());
    }

    @Nonnull
    @Override
    public String getItemStackDisplayName(@Nonnull ItemStack stack) {
        EnvironmentAxis axis = boundAxis(stack);
        if (axis == null) return LangUtils.localize(getTranslationKey() + ".name_empty");
        return LangUtils.localizeWithFormat(shellKey(), axisWord(axis));
    }

    @Override
    public void getSubItems(@Nonnull CreativeTabs tab, @Nonnull NonNullList<ItemStack> items) {
        if (!isInCreativeTab(tab)) return;
        items.add(new ItemStack(this));
        for (EnvironmentAxis axis : axes()) items.add(bound(this, axis.id()));
    }

    @Nonnull
    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        if (stack.isEmpty()) return new ActionResult<>(EnumActionResult.FAIL, stack);
        EnvironmentAxis axis = boundAxis(stack);
        if (axis == null) {
            if (hasAxisTag(stack)) {
                player.sendMessage(new TextComponentTranslation("chat.cloudworks.card_corrupt"));
                return new ActionResult<>(EnumActionResult.FAIL, stack);
            }
            return sample(world, player, stack);
        }
        return activate(player, stack, axis);
    }

    private ActionResult<ItemStack> sample(World world, EntityPlayer player, ItemStack stack) {
        EnvironmentAxis axis = sampleAxis(world, player);
        if (axis != null) {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag == null) tag = new NBTTagCompound();
            tag.setString(AXIS_TAG, axis.id().toString());
            stack.setTagCompound(tag);
            player.sendMessage(new TextComponentTranslation("chat.cloudworks.axis_bound",
                    axisWord(axis)));
            return new ActionResult<>(EnumActionResult.SUCCESS, stack);
        }
        player.sendMessage(new TextComponentTranslation("chat.cloudworks.axis_sampling_failed"));
        return new ActionResult<>(EnumActionResult.FAIL, stack);
    }

    private ActionResult<ItemStack> activate(EntityPlayer player, ItemStack stack, EnvironmentAxis axis) {
        UUID owner = player.getUniqueID();
        ItemStack offhand = player.getHeldItemOffhand();
        if (offhand != null && !offhand.isEmpty() && offhand.getItem() == CloudworksItems.TeamTerminal) {
            UUID teamOwner = CloudworksTeamContext.owner(player);
            if (teamOwner == null || !CloudworksTeamContext.canAccess(player, teamOwner)) {
                player.sendMessage(new TextComponentTranslation("cloudworks.error.team_required"));
                return new ActionResult<>(EnumActionResult.FAIL, stack);
            }
            owner = teamOwner;
        }
        CloudworksCardEffect.Result result;
        try {
            result = CloudworksPlayerDataManager.INSTANCE.applyCardEffect(owner,
                    CloudworksCardEffects.get(axis.effectId()));
        } catch (RuntimeException unavailable) {
            player.sendMessage(new TextComponentTranslation("cloudworks.error.storage"));
            return new ActionResult<>(EnumActionResult.FAIL, stack);
        }
        if (result.applied()) stack.shrink(1);
        player.sendMessage(new TextComponentTranslation(result.messageKey(), result.messageArguments()));
        return new ActionResult<>(result.applied() ? EnumActionResult.SUCCESS : EnumActionResult.FAIL, stack);
    }
}
