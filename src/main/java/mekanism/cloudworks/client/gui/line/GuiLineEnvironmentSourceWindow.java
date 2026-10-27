package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiInnerScreen;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.service.CloudworksNodeEnvironment;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import mekanism.cloudworks.common.network.data.CloudworksGuiData;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetype;
import mekanism.cloudworks.common.special.CloudworksSpecialNodeArchetypes;

/** Read-only description and coverage count for a special environment source. */
public final class GuiLineEnvironmentSourceWindow extends GuiLineWindow {
    public GuiLineEnvironmentSourceWindow(IGuiWrapper gui, ContainerCloudworks container,
            CloudworksLineEditorModel model, UUID line, UUID node, String archetype, boolean editing) {
        super(gui, 35, 30, 224, editing ? 124 : 102, title(archetype), type("channel"));
        boolean nether = CloudworksNodeEnvironment.netherSource(archetype);
        addChild(new GuiInnerScreen(gui, relativeX + 8, relativeY + 23, 208, 70, () -> {
            CloudworksLineDraft draft = editing ? model.draft : container.guiData().graph.configuration;
            List<ITextComponent> text = new ArrayList<>();
            text.add(new TextComponentTranslation(nether
                    ? "cloudworks.editor.nether_area" : "cloudworks.editor.environment_area",
                    CloudworksNodeEnvironment.SOURCE_SIZE, CloudworksNodeEnvironment.SOURCE_SIZE));
            text.add(new TextComponentTranslation(nether
                    ? "cloudworks.editor.nether_no_stack" : "cloudworks.editor.environment_no_stack"));
            int hits = 0;
            if (editing) {
                if (draft != null) hits = CloudworksNodeEnvironment.hits(draft, node);
            } else if (line.equals(container.guiData().graph.id)) {
                for (mekanism.cloudworks.common.network.data.node.Node projected
                        : container.guiData().graph.nodes) {
                    if (node.equals(projected.id)
                            && projected instanceof mekanism.cloudworks.common.network.data.node.ChannelNode) {
                        hits = ((mekanism.cloudworks.common.network.data.node.ChannelNode) projected).areaHits;
                        break;
                    }
                }
            }
            text.add(new TextComponentTranslation(nether
                    ? "cloudworks.editor.nether_hits" : "cloudworks.editor.environment_hits", hits));
            return text;
        }).clearFormat().padding(4).spacing(2).textScale(.8F));
        if (editing) addChild(new MekanismButton(gui, relativeX + 8, relativeY + 100, 208, 18,
                new TextComponentString(tr(nether ? "toggle_node" : "toggle_environment_node")), () -> {
                    if (model.draft != null) for (CloudworksLineDraft.Channel source : model.draft.channels)
                        if (source.id.equals(node)) { model.setChannelActive(node, !source.active); break; }
                }, null));
    }

    private static String title(String archetype) {
        CloudworksSpecialNodeArchetype declaration =
                CloudworksSpecialNodeArchetypes.get(archetype);
        return declaration == null ? "cloudworks.editor.special_node_invalid" : declaration.translationKey();
    }
}
