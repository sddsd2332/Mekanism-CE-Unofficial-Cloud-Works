package mekanism.cloudworks.common.config;

import java.io.File;
import mekanism.common.config.BaseConfig;
import mekanism.common.config.options.StringListOption;
import net.minecraftforge.common.config.Configuration;

/** Local creative-card issuance only; never participates in player resource permission checks. */
public final class CloudworksInfiniteResourceConfig extends BaseConfig {
    public static final String FILE_NAME = "cloudworks-infinite-resource-cards.cfg";
    private static final CloudworksInfiniteResourceConfig LOCAL = new CloudworksInfiniteResourceConfig();
    public final StringListOption infiniteResourceCards = new StringListOption(this, "infiniteResourceCards",
            new String[]{"fluid:water"},
            "Prebuilt infinite-resource cards for the creative tab and JEI. Restart the game after editing.\n"
                    + "Examples: fluid:water; gas:oxygen; item:minecraft:coal_ore@2.\n"
                    + "Optional suffix: gas:oxygen|nodeLimit=4,outputAmount=1000.\n"
                    + "Item NBT suffix uses SNBT, for example item:mekanism:machine@5|nbt={recipeTypeName:\"smelting\",recipeType:0,recipeTypeVersion:2}.\n"
                    + "The nbt suffix is only valid for item resources and is preserved in the QIO descriptor.\n"
                    + "nodeLimit enables limited placement. Only nodeLimit/outputAmount positive long values are accepted.\n"
                    + "No suffix means unlimited placement and maximum output. Invalid entries are warned and skipped.\n"
                    + "An empty card is always displayed first. This list does not revoke or restrict already issued cards.")
            .setRequiresGameRestart();

    public static CloudworksInfiniteResourceConfig local() { return LOCAL; }

    public static void loadFile(File file) {
        Configuration configuration = new Configuration(file);
        configuration.load();
        LOCAL.load(configuration);
        if (configuration.hasChanged()) configuration.save();
    }

    public String[] presets() { return infiniteResourceCards.get().clone(); }

    @Override
    public String getCategory() { return "infinite_resource_cards"; }
}
