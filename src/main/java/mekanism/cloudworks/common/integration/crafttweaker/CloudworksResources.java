package mekanism.cloudworks.common.integration.crafttweaker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.data.IData;
import crafttweaker.api.item.IIngredient;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.liquid.ILiquidStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import mekanism.api.infuse.InfuseRegistry;
import mekanism.api.infuse.InfuseType;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.recipe.CloudworksInfuseResourceCodec;
import mekanism.common.InfuseStorage;
import mekanism.common.integration.crafttweaker.gas.IGasStack;
import mekanism.common.integration.crafttweaker.helpers.GasHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.util.NonNullList;
import net.minecraftforge.oredict.OreDictionary;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

@ZenClass("mods.cloudworks.Resources")
@ZenRegister
public final class CloudworksResources {
    final List<QIOResourceDescriptor> alternatives;
    final long amount;

    private CloudworksResources(List<QIOResourceDescriptor> alternatives, long amount) {
        if (alternatives.isEmpty() || amount <= 0) throw new IllegalArgumentException("Resource cannot be empty");
        for (QIOResourceDescriptor descriptor : alternatives)
            if (!descriptor.isResolved()) throw new IllegalArgumentException("Resource codec is unavailable");
        this.alternatives = Collections.unmodifiableList(new ArrayList<>(alternatives));
        this.amount = amount;
    }

    private static CloudworksResources of(QIOResourceDescriptor resource, long amount) {
        return new CloudworksResources(Collections.singletonList(resource), amount);
    }

    QIOResourceDescriptor single() {
        if (alternatives.size() != 1) throw new IllegalArgumentException("Outputs and templates require one exact resource");
        return alternatives.get(0);
    }

    @ZenMethod
    public static CloudworksResources item(IItemStack item) {
        return of(QIOResourceCodecs.item(CloudworksMachines.stack(item)), item.getAmount());
    }

    @ZenMethod
    public static CloudworksResources fluid(ILiquidStack liquid) {
        java.util.Objects.requireNonNull(liquid);
        return of(QIOResourceCodecs.fluid(CraftTweakerMC.getLiquidStack(liquid)), liquid.getAmount());
    }

    @ZenMethod
    public static CloudworksResources gas(IGasStack gas) {
        java.util.Objects.requireNonNull(gas);
        return of(QIOResourceCodecs.gas(java.util.Objects.requireNonNull(GasHelper.toGas(gas))), gas.getAmount());
    }

    @ZenMethod
    public static CloudworksResources infuse(String name, long amount) {
        InfuseType type = InfuseRegistry.get(name.toUpperCase(java.util.Locale.ROOT));
        if (type == null) throw new IllegalArgumentException("Unknown infusion type: " + name);
        return of(CloudworksInfuseResourceCodec.descriptor(new InfuseStorage(type, 1)), amount);
    }

    @ZenMethod
    public static CloudworksResources ingredient(IIngredient ingredient, long amount) {
        if (ingredient == null || ingredient.hasTransformers() || ingredient.hasNewTransformers())
            throw new IllegalArgumentException("Script ingredient callbacks cannot enter Runtime");
        List<QIOResourceDescriptor> resources = new ArrayList<>();
        for (ItemStack candidate : CraftTweakerMC.getIngredient(ingredient).getMatchingStacks()) {
            if (candidate.isEmpty()) continue;
            if (candidate.getMetadata() == OreDictionary.WILDCARD_VALUE) {
                NonNullList<ItemStack> variants = NonNullList.create();
                candidate.getItem().getSubItems(CreativeTabs.SEARCH, variants);
                for (ItemStack variant : variants) {
                    if (variant.isEmpty() || variant.getItem() != candidate.getItem()
                            || variant.getMetadata() == OreDictionary.WILDCARD_VALUE) continue;
                    ItemStack concrete = variant.copy();
                    if (candidate.hasTagCompound()) concrete.setTagCompound(candidate.getTagCompound().copy());
                    addIngredient(resources, concrete, ingredient);
                }
            } else addIngredient(resources, candidate, ingredient);
        }
        return new CloudworksResources(resources, amount);
    }

    private static void addIngredient(List<QIOResourceDescriptor> resources, ItemStack item, IIngredient ingredient) {
        if (!ingredient.matches(CraftTweakerMC.getIItemStack(item))) return;
        QIOResourceDescriptor descriptor = QIOResourceCodecs.item(item);
        if (!resources.contains(descriptor)) resources.add(descriptor);
    }

    @ZenMethod
    public static CloudworksResources descriptor(IData encoded, long amount) {
        NBTTagCompound tag = CraftTweakerMC.getNBTCompound(encoded);
        return of(QIOResourceDescriptor.read(tag), amount);
    }

    @ZenMethod
    public CloudworksResources withAmount(long amount) { return new CloudworksResources(alternatives, amount); }

    @ZenGetter("amount")
    public long getAmount() { return amount; }

    @ZenGetter("codec")
    public String getCodec() { return alternatives.get(0).getCodecId().toString(); }
}
