package mekanism.cloudworks.common.data;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.nbt.NBTTagCompound;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;
import net.minecraft.nbt.NBTTagList;

final class CloudworksDataChecks {
    private CloudworksDataChecks() {}

    static void schema(NBTTagCompound tag, int version) {
        field(tag, "schema", 3);
        if (tag.getInteger("schema") != version) throw new IllegalArgumentException("Unsupported cloudworks schema");
    }

    static void field(NBTTagCompound tag, String name, int type) {
        if (!tag.hasKey(name, type)) throw new IllegalArgumentException("Missing or invalid cloudworks field: " + name);
    }

    static NBTTagList list(NBTTagCompound tag, String name, int elementType) {
        field(tag, name, 9);
        NBTTagList list = (NBTTagList) tag.getTag(name);
        if (list.tagCount() != 0 && list.getTagType() != elementType)
            throw new IllegalArgumentException("Invalid cloudworks list element type: " + name);
        return list;
    }

    static void fields(NBTTagCompound tag, int type, String... names) {
        for (String name : names) field(tag, name, type);
    }

    static QIOResourceDescriptor descriptor(NBTTagCompound tag) {
        if (CloudworksNativeContracts.isWorker())
            return CloudworksNativeContracts.resource(tag);
        QIOResourceDescriptor result = QIOResourceDescriptor.read(tag);
        if (!result.isResolved()) throw new IllegalArgumentException("Unresolved cloudworks resource");
        return result;
    }
}
