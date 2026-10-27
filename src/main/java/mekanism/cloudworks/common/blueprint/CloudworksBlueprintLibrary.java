package mekanism.cloudworks.common.blueprint;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import mekanism.common.Mekanism;
import java.nio.channels.FileChannel;
import java.util.zip.GZIPInputStream;
import mekanism.cloudworks.common.network.CloudworksGuiCodec;
import mekanism.cloudworks.common.network.CloudworksLineDraftCodec;

/** Server-wide asset library. All access comes from structural workers, never the tick thread. */
public final class CloudworksBlueprintLibrary {
    public static final CloudworksBlueprintLibrary INSTANCE = new CloudworksBlueprintLibrary();
    public static final int CODE_LENGTH = 36;
    public static final int PAGE_SIZE = 128;
    private Path root;

    public synchronized void configure(Path configurationRoot) {
        root = configurationRoot.resolve("cloudworks").resolve("blueprints").toAbsolutePath().normalize();
    }

    public static final class Entry {
        public final UUID id;
        public final CloudworksBlueprint blueprint;
        public Entry(UUID id, CloudworksBlueprint blueprint) { this.id = id; this.blueprint = blueprint; }
    }

    public static final class Page {
        public final List<Entry> entries;
        public final int nextOffset;
        public final boolean hasMore;
        private Page(List<Entry> entries, int nextOffset, boolean hasMore) {
            this.entries = entries; this.nextOffset = nextOffset; this.hasMore = hasMore;
        }
    }

    public synchronized Page entries(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > PAGE_SIZE) throw CloudworksBlueprint.invalid();
        if (root == null) throw new IllegalStateException("cloudworks.error.unavailable");
        if (!Files.isDirectory(root)) return new Page(Collections.emptyList(), 0, false);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(root, "*.dat")) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                try { UUID.fromString(name.substring(0, name.length() - 4)); files.add(path); }
                catch (RuntimeException ignored) {
                    if (Mekanism.logger != null) Mekanism.logger.debug("Skipping non-UUID blueprint file {}", path, ignored);
                }
            }
        } catch (IOException failure) { throw storage(failure); }
        Map<Path, Long> modified = new HashMap<>();
        for (Path path : files) try { modified.put(path, Files.getLastModifiedTime(path).toMillis()); }
        catch (IOException missing) { modified.put(path, Long.MIN_VALUE); }
        files.sort(Comparator.<Path>comparingLong(path -> modified.get(path)).reversed()
                .thenComparing(path -> path.getFileName().toString()));
        int start = Math.min(offset, files.size());
        int end = Math.min(files.size(), start + limit);
        List<Entry> entries = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) {
            Path path = files.get(index);
            UUID id = UUID.fromString(path.getFileName().toString().replace(".dat", ""));
            try { entries.add(new Entry(id, read(id))); }
            catch (CloudworksLineDraftCodec.UnsupportedBlueprintVersionException ignored) {
                if (Mekanism.logger != null) Mekanism.logger.debug("Skipping legacy Cloudworks blueprint {}", path);
            }
            catch (RuntimeException failure) {
                if (Mekanism.logger != null) Mekanism.logger.warn("Rejected Cloudworks blueprint {}", path, failure);
            }
        }
        return new Page(entries, end, end < files.size());
    }

    public synchronized List<Entry> entries() {
        if (root == null) throw new IllegalStateException("cloudworks.error.unavailable");
        List<Entry> entries = new ArrayList<>();
        if (!Files.isDirectory(root)) return entries;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(root, "*.dat")) {
            for (Path file : files) try {
                UUID id = UUID.fromString(file.getFileName().toString().replace(".dat", ""));
                entries.add(new Entry(id, read(id)));
            } catch (CloudworksLineDraftCodec.UnsupportedBlueprintVersionException ignored) {
                if (Mekanism.logger != null) Mekanism.logger.debug("Skipping legacy Cloudworks blueprint {}", file);
            } catch (RuntimeException failure) {
                if (Mekanism.logger != null) Mekanism.logger.warn("Rejected Cloudworks blueprint {}", file, failure);
            }
        } catch (IOException failure) { throw storage(failure); }
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.blueprint.exportTime).reversed());
        return entries;
    }

    public synchronized CloudworksBlueprint read(UUID id) {
        try (InputStream in = Files.newInputStream(file(id))) {
            if (Files.size(file(id)) > CloudworksGuiCodec.MAX_BYTES) throw CloudworksBlueprint.invalid();
            NBTTagCompound tag = CompressedStreamTools.read(new DataInputStream(new GZIPInputStream(in)),
                    new NBTSizeTracker(CloudworksGuiCodec.MAX_BYTES + 262144));
            if (!id.toString().equals(tag.getString("id"))) throw CloudworksBlueprint.invalid();
            return CloudworksBlueprint.read(tag.getCompoundTag("payload"));
        } catch (NoSuchFileException missing) {
            throw new IllegalArgumentException("cloudworks.error.blueprint_not_found");
        } catch (IOException failure) { throw storage(failure); }
    }

    public synchronized CloudworksBlueprint readCode(String code) {
        return read(parseCode(code));
    }

    public synchronized String code(UUID id) {
        read(id);
        return id.toString();
    }

    public static UUID parseCode(String code) {
        if (code == null || code.length() != CODE_LENGTH) throw CloudworksBlueprint.invalid();
        try {
            UUID id = UUID.fromString(code);
            if (!id.toString().equalsIgnoreCase(code)) throw CloudworksBlueprint.invalid();
            return id;
        } catch (IllegalArgumentException invalid) { throw CloudworksBlueprint.invalid(); }
    }

    public synchronized UUID put(CloudworksBlueprint blueprint) {
        blueprint.validate();
        List<Entry> entries = entries();
        UUID id = null;
        int owned = 0;
        for (Entry entry : entries) {
            if (!entry.blueprint.exporterUUID.equals(blueprint.exporterUUID)) continue;
            owned++;
            if (entry.blueprint.draft().name.equals(blueprint.draft().name)) id = entry.id;
        }
        if (id == null && owned >= 50) throw new IllegalStateException("cloudworks.error.blueprint_library_limit");
        if (id == null) id = UUID.randomUUID();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", id.toString());
        tag.setTag("payload", blueprint.write());
        Path file = file(id), temp = null;
        try {
            Files.createDirectories(root);
            temp = Files.createTempFile(root, "blueprint-", ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
                CompressedStreamTools.writeCompressed(tag, out);
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return id;
        } catch (IOException failure) { throw storage(failure); }
        finally {
            if (temp != null) try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                if (Mekanism.logger != null) Mekanism.logger.debug("Could not remove temporary blueprint file {}", temp, ignored);
            }
        }
    }

    public synchronized void delete(UUID id, UUID actor, boolean operator) {
        CloudworksBlueprint blueprint = read(id);
        if (!operator && !actor.equals(blueprint.exporterUUID))
            throw new IllegalStateException("cloudworks.error.permission");
        try { Files.delete(file(id)); }
        catch (IOException failure) { throw storage(failure); }
    }

    private Path file(UUID id) {
        if (root == null || id == null) throw new IllegalStateException("cloudworks.error.unavailable");
        return root.resolve(id + ".dat");
    }

    private static IllegalStateException storage(Exception cause) {
        return new IllegalStateException("cloudworks.error.storage", cause);
    }
}
