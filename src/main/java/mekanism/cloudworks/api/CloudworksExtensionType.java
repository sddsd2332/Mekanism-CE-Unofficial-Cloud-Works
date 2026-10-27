package mekanism.cloudworks.api;

import java.util.Objects;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/**
 * One namespaced, versioned extension payload type. Addons declare how their
 * data is serialised; CloudWorks stores, bounds and round-trips the payload
 * without interpreting it on Runtime workers. Unknown or version-mismatched
 * entries are preserved verbatim, never executed and never silently destroyed.
 *
 * @param <T> addon-side value type; it never leaks into CloudWorks internals
 */
public final class CloudworksExtensionType<T> {
    /** Reads the payload compound into the addon value; must not mutate the tag. */
    public interface Reader<T> {
        T read(NBTTagCompound payload);
    }

    /** Writes the addon value into the payload compound. */
    public interface Writer<T> {
        void write(T value, NBTTagCompound payload);
    }

    private final ResourceLocation id;
    private final int version;
    private final Reader<T> reader;
    private final Writer<T> writer;

    public CloudworksExtensionType(ResourceLocation id, int version, Reader<T> reader, Writer<T> writer) {
        if (version <= 0) throw new IllegalArgumentException("Extension version must be positive");
        this.id = Objects.requireNonNull(id, "id");
        this.version = version;
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public ResourceLocation id() { return id; }
    public int version() { return version; }

    T decode(NBTTagCompound payload) { return reader.read(payload); }

    NBTTagCompound encode(T value) {
        NBTTagCompound payload = new NBTTagCompound();
        writer.write(value, payload);
        return payload;
    }
}
