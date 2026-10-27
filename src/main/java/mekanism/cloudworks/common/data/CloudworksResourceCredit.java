package mekanism.cloudworks.common.data;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import net.minecraft.nbt.NBTTagCompound;

/** A prepaid resource balance; stored independently from unreserved physical inputs. */
public final class CloudworksResourceCredit {
    public final QIOResourceDescriptor resource;
    public final double units;
    public CloudworksResourceCredit(QIOResourceDescriptor resource, double units) {
        if (resource == null || !resource.isResolved() || !Double.isFinite(units) || units < 0 || units >= Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid processing resource credit");
        this.resource = resource; this.units = units;
    }
    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound(); tag.setTag("descriptor", resource.write()); tag.setDouble("units", units); return tag;
    }
    public static CloudworksResourceCredit read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 10, "descriptor"); CloudworksDataChecks.fields(tag, 6, "units");
        return new CloudworksResourceCredit(mekanism.cloudworks.common.service.CloudworksNativeContracts.isWorker()
                ? mekanism.cloudworks.common.service.CloudworksNativeContracts.resource(tag.getCompoundTag("descriptor"))
                : QIOResourceDescriptor.read(tag.getCompoundTag("descriptor")), tag.getDouble("units"));
    }
}
