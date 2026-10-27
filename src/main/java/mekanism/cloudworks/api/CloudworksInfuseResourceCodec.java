package mekanism.cloudworks.api;

import java.util.Objects;
import mekanism.api.infuse.InfuseRegistry;
import mekanism.api.infuse.InfuseType;
import mekanism.api.qio.resource.QIOResourceCodec;
import mekanism.api.qio.resource.QIOResourceCodecRegistry;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.common.InfuseStorage;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import java.util.Locale;

/** Public QIO codec for one amount-free Mekanism infusion identity. */
public final class CloudworksInfuseResourceCodec implements QIOResourceCodec<InfuseStorage> {
    public static final ResourceLocation ID = new ResourceLocation("cloudworks", "infuse");
    public static final CloudworksInfuseResourceCodec INSTANCE = new CloudworksInfuseResourceCodec();
    private static boolean registered;

    private CloudworksInfuseResourceCodec() {}

    public static synchronized void register() {
        if (!registered) {
            if (!QIOResourceCodecRegistry.INSTANCE.isRegistered(ID))
                QIOResourceCodecRegistry.INSTANCE.register(INSTANCE);
            registered = true;
        }
    }

    public static QIOResourceDescriptor descriptor(InfuseStorage storage) {
        register();
        return QIOResourceDescriptor.of(INSTANCE, storage);
    }

    public static QIOResourceDescriptor descriptor(String typeName) {
        if (typeName == null || typeName.trim().isEmpty()) throw new IllegalArgumentException("Empty infusion type");
        InfuseType type = InfuseRegistry.get(typeName.toUpperCase(Locale.ROOT));
        if (type == null) throw new IllegalArgumentException("Unknown infusion type: " + typeName);
        return descriptor(new InfuseStorage(type, 1));
    }

    @Override public ResourceLocation getCodecId() { return ID; }
    @Override public String getFamily() { return "infuse"; }
    @Override public Class<InfuseStorage> getValueClass() { return InfuseStorage.class; }

    @Override
    public InfuseStorage normalize(InfuseStorage value) {
        Objects.requireNonNull(value, "Infuse resource cannot be null");
        InfuseType type = value.getType();
        if (type == null || value.getAmount() <= 0) throw new IllegalArgumentException("Infuse resource cannot be empty");
        return new InfuseStorage(type, 1);
    }

    @Override
    public boolean sameType(InfuseStorage first, InfuseStorage second) {
        return normalize(first).getType().name.equals(normalize(second).getType().name);
    }

    @Override public int typeHash(InfuseStorage value) { return normalize(value).getType().name.hashCode(); }

    @Override
    public NBTTagCompound writeTemplate(InfuseStorage value) {
        NBTTagCompound payload = new NBTTagCompound();
        payload.setString("type", normalize(value).getType().name);
        return payload;
    }

    @Override
    public InfuseStorage readTemplate(NBTTagCompound payload, int codecVersion) {
        if (codecVersion != 1) throw new IllegalArgumentException("Unsupported infuse codec version");
        InfuseType type = InfuseRegistry.get(payload.getString("type"));
        if (type == null) throw new IllegalArgumentException("Unknown InfuseType");
        return new InfuseStorage(type, 1);
    }

    @Override public long getStorageUnitsPerUnit() { return 1L; }
}
