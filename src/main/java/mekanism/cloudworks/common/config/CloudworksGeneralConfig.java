package mekanism.cloudworks.common.config;

import mekanism.common.config.BaseConfig;
import mekanism.common.config.options.BooleanOption;
import mekanism.common.config.options.DoubleOption;
import mekanism.common.config.options.IntOption;
import mekanism.common.config.options.LongOption;
import mekanism.common.tier.EnergyCubeTier;

public final class CloudworksGeneralConfig extends BaseConfig {
    public final DoubleOption initialReceivingEnergyCapacity = capacity("initialReceivingEnergyCapacity");
    public final DoubleOption initialCloudEnergyCapacity = capacity("initialCloudEnergyCapacity");
    public final DoubleOption initialSendingEnergyCapacity = capacity("initialSendingEnergyCapacity");
    public final IntOption initialLines = integer("initialLines", 1, "Initially unlocked production lines per new player.", 1);
    public final IntOption maxLines = integer("maxLines", 64, "Maximum lines unlockable per player.", 1);
    public final IntOption initialMachineNodes = integer("initialMachineNodes", 64, "Initial machine-node allowance per line.", 1);
    public final IntOption maxMachineNodes = integer("maxMachineNodes", 4096, "Machine nodes per line. Special nodes are not counted.", 1);
    public final IntOption initialWorkRate = integer("initialWorkRate", 1, "Initial work rate of a new cloud.", 1);
    public final IntOption maxWorkRate = integer("maxWorkRate", 1024, "Maximum work rate obtainable with cards.", 1);
    public final DoubleOption maxOutputMultiplier = new DoubleOption(this, "maxOutputMultiplier", 64,
            "Maximum ordinary output multiplier from cards (1-1024, at most three decimal places). "
                    + "Stored player progress is preserved; Runtime uses the configured ceiling.",
            1, 1024).setRequiresWorldRestart();
    public final LongOption maxMachinesPerNode = new LongOption(this, "maxMachinesPerNode", 1024,
            "Maximum merged machine count per machine node. Existing larger nodes are preserved.", 1, Long.MAX_VALUE)
            .setRequiresWorldRestart();
    public final BooleanOption offlineProcessingEnabled = new BooleanOption(this, "offlineProcessingEnabled", true,
            "Server-wide permission for unlocked, individually opted-in lines to run while their owner is offline.")
            .setRequiresWorldRestart();
    public final BooleanOption terminalItemUploadsEnabled = new BooleanOption(this, "terminalItemUploadsEnabled", true,
            "Allow handheld, block and team terminals to upload machine, upgrade and energy-capacity items. "
                    + "Downloads, physical transfer ports and trusted API/script deposits are unaffected.")
            .setRequiresWorldRestart();
    public final BooleanOption ftbAutoCreateTeam = new BooleanOption(this, "ftbAutoCreateTeam", true,
            "Create a Cloudworks team when an FTB team is created.").setRequiresWorldRestart();
    public final BooleanOption ftbOwnerFollows = new BooleanOption(this, "ftbOwnerFollows", true,
            "Follow FTB team owner changes when the new owner is already a Cloudworks team member.").setRequiresWorldRestart();
    public final BooleanOption ftbDissolveFollows = new BooleanOption(this, "ftbDissolveFollows", true,
            "Dissolve the matching Cloudworks team when its bound FTB team is deleted.").setRequiresWorldRestart();
    public final LongOption recycleCapacityPerType = new LongOption(this, "recycleCapacityPerType", Integer.MAX_VALUE,
            "Recycle capacity per resource type. The type limit is fixed at 128. Existing contents are not truncated.",
            1, Long.MAX_VALUE / 128).setRequiresWorldRestart();
    public final LongOption warehouseItemTypeCap = new LongOption(this, "warehouseItemTypeCap", 1_000_000L,
            "Warehouse capacity per item type. Fluid and gas types use 1000 times this value; existing contents are not truncated.",
            1, Long.MAX_VALUE / 1_000L).setRequiresWorldRestart();
    public final IntOption maxWarehouseCapMultiplier = new IntOption(this, "maxWarehouseCapMultiplier", 64,
            "Maximum per-player warehouse capacity multiplier from cards. Base per-type cap multiplied by this value.",
            1, 1024).setRequiresWorldRestart();
    public final IntOption maxLineBufferMultiplier = new IntOption(this, "maxLineBufferMultiplier", 16,
            "Maximum per-line machine input/output buffer multiplier from capacity cards.",
            1, 1024).setRequiresWorldRestart();

    private DoubleOption capacity(String key) {
        return new DoubleOption(this, key, EnergyCubeTier.ULTIMATE.getBaseMaxEnergy(),
                "Initial capacity in Joules for new players; energy items expand all three pools. Stored energy starts at zero.",
                0, Double.MAX_VALUE).setRequiresWorldRestart();
    }

    private IntOption integer(String key, int value, String comment, int minimum) {
        return new IntOption(this, key, value, comment, minimum, Integer.MAX_VALUE).setRequiresWorldRestart();
    }

    @Override public String getCategory() { return "cloudworks"; }
}
