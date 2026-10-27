package mekanism.cloudworks.common.item.prefab;

import java.util.List;
import mekanism.common.item.ItemMekanism;
import mekanism.common.util.LangUtils;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Physical module binding lives in the upgrade API; using the held item never activates a cloud effect. */
public class ItemCloudworksNodeUpgradeCard extends ItemMekanism {
    private final String tooltip;
    protected ItemCloudworksNodeUpgradeCard(String tooltip) { this.tooltip = tooltip; }
    @Override
    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, World world, List<String> lines, ITooltipFlag flag) {
        super.addInformation(stack, world, lines, flag);
        lines.add(LangUtils.localize(tooltip));
        lines.add(LangUtils.localize("tooltip.cloudworks.node_module_upload"));
    }
}
