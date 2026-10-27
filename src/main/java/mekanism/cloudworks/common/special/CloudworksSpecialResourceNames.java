package mekanism.cloudworks.common.special;

import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.service.CloudworksEditorData;
import mekanism.common.util.LangUtils;

public final class CloudworksSpecialResourceNames {
    private CloudworksSpecialResourceNames() {}

    public static String resource(QIOResourceDescriptor descriptor) {
        String type = descriptor.getCodecId().equals(QIOResourceCodecs.ITEM_STACK_ID) ? "resources_item"
                : descriptor.getCodecId().equals(QIOResourceCodecs.FLUID_STACK_ID) ? "resources_fluid"
                : descriptor.getCodecId().equals(QIOResourceCodecs.GAS_STACK_ID) ? "resources_gas" : "resources_other";
        return LangUtils.localizeWithFormat("cloudworks.special.resource_description",
                LangUtils.localize("cloudworks.editor." + type), CloudworksEditorData.resourceName(descriptor));
    }

    public static String node(QIOResourceDescriptor descriptor) {
        return descriptor == null ? LangUtils.localize("cloudworks.special.infinite_resources")
                : LangUtils.localizeWithFormat("cloudworks.special.infinite_resources_selected", resource(descriptor));
    }
}
