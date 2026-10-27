package mekanism.cloudworks.client.gui.element;

import java.util.Collections;
import java.util.List;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.api.recipe.RecipeContribution;
import mekanism.cloudworks.common.upgrade.CloudworksMachineModuleRegistry;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * Client-side rich view of one custom upgrade type, assembled entirely from the
 * frozen local registries (capability is a client fact). Only the pool count is
 * a server fact and stays on the wire; display key, item, description keys and
 * mechanism metadata all resolve here. Unknown ids (pool counts without a
 * declaration) resolve to an empty shell: bare id, no icon, no description.
 */
public final class CustomUpgradeInfo {
    public final String id;
    public final String displayKey;
    public final ItemStack item;
    public final List<String> descriptionKeys;
    public final MachineSlotLayoutView mechanism;

    private CustomUpgradeInfo(String id, String displayKey, ItemStack item,
            List<String> descriptionKeys, MachineSlotLayoutView mechanism) {
        this.id = id;
        this.displayKey = displayKey;
        this.item = item;
        this.descriptionKeys = descriptionKeys;
        this.mechanism = mechanism;
    }

    /** Package-private test fixture: same-package tests shape registry-like infos. */
    static CustomUpgradeInfo create(String id, String displayKey, ItemStack item,
            List<String> descriptionKeys, MachineSlotLayoutView mechanism) {
        return new CustomUpgradeInfo(id, displayKey, item, descriptionKeys, mechanism);
    }

    public static CustomUpgradeInfo fromRegistry(String id) {
        MachineModuleType<?> type = null;
        try {
            type = CloudworksMachineModuleRegistry.type(new ResourceLocation(id));
        } catch (RuntimeException invalid) {
            type = null;
        }
        RecipeContribution definition = CloudworksNodeMechanisms.definition(new ResourceLocation(id));
        MachineSlotLayoutView mechanism = definition == null ? null : new MachineSlotLayoutView(
                definition.thermal() == null ? definition.ports()
                        : CloudworksThermalPlan.ports(definition.ports()),
                definition.required(),
                definition.installation() == RecipeContribution.Installation.PER_MACHINE,
                definition.mode() == RecipeContribution.RecipeMode.REPLACE,
                definition.mode() == RecipeContribution.RecipeMode.PARALLEL,
                definition.exclusiveGroup(),
                definition.mode() != RecipeContribution.RecipeMode.PARALLEL
                        && definition.overridesThermal() && definition.thermal() == null);
        return new CustomUpgradeInfo(id,
                type == null ? "" : type.displayKey(),
                type == null ? ItemStack.EMPTY : type.item(),
                type == null ? Collections.<String>emptyList() : type.descriptionKeys(),
                mechanism);
    }

    /** Port layout plus the activation flags the editor renders; null = no mechanism. */
    public static final class MachineSlotLayoutView {
        public final mekanism.cloudworks.api.machine.MachineSlotLayout ports;
        public final int required;
        public final boolean perMachine, replacesRecipes, parallel, clearsThermal;
        public final String exclusiveGroup;

        MachineSlotLayoutView(mekanism.cloudworks.api.machine.MachineSlotLayout ports, int required,
                boolean perMachine, boolean replacesRecipes, boolean parallel,
                String exclusiveGroup, boolean clearsThermal) {
            this.ports = ports;
            this.required = required;
            this.perMachine = perMachine;
            this.replacesRecipes = replacesRecipes;
            this.parallel = parallel;
            this.exclusiveGroup = exclusiveGroup == null ? "" : exclusiveGroup;
            this.clearsThermal = clearsThermal;
        }
    }
}
