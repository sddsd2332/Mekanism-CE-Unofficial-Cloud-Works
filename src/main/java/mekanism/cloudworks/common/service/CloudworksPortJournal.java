package mekanism.cloudworks.common.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksInventory;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import mekanism.cloudworks.common.data.CloudworksData;
import mekanism.cloudworks.common.data.CloudworksPortRecord;

/**
 * Small durable port records. Full projections are compacted once before a
 * checkpoint or a cold load, not once per resource handed across a port.
 */
final class CloudworksPortJournal {
    private static final String WATERMARK = "cwPortSequence";
    private final Path root;
    private final Storage storage;
    private final Function<UUID, Path> ownerDirectory;
    private final Map<UUID, Long> sequences = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pendingRecords = new ConcurrentHashMap<>();
    private final Map<UUID, Object> ownerLocks = new ConcurrentHashMap<>();

    CloudworksPortJournal(Path root, Storage storage) {
        this(root, storage, owner -> root.resolve(owner.toString()));
    }

    CloudworksPortJournal(Path root, Storage storage, Function<UUID, Path> ownerDirectory) {
        this.root = root;
        this.storage = storage;
        this.ownerDirectory = ownerDirectory;
    }

    void prepare(UUID owner, NBTTagCompound intent) {
        synchronized (ownerLocks.computeIfAbsent(owner, ignored -> new Object())) {
        long sequence = sequences.computeIfAbsent(owner, ignored -> {
            long last = 0;
            for (Path file : records(owner)) last = Math.max(last, sequence(file));
            for (String name : new String[]{"cloud", "receiving", "sending", "port-watermark"}) {
                Path file = ownerDirectory.apply(owner).resolve(name + ".dat");
                if (Files.exists(file)) last = Math.max(last, storage.read(file).getLong(WATERMARK));
            }
            return last;
        });
        sequence = Math.addExact(sequence, 1);
        sequences.put(owner, sequence);
        intent.setLong("portSequence", sequence);
        }
    }

    void append(UUID owner, NBTTagCompound intent) {
        synchronized (ownerLocks.computeIfAbsent(owner, ignored -> new Object())) {
        long sequence = intent.getLong("portSequence");
        if (sequence <= 0 || intent.getInteger("schema") != 2
                || !owner.toString().equals(intent.getString("owner")))
            throw new IllegalArgumentException("Invalid port journal");
        Path file = directory(owner).resolve(String.format(Locale.ROOT, "%020d.dat", sequence));
        if (Files.exists(file)) {
            if (!storage.read(file).equals(intent)) throw new IllegalStateException("Conflicting port journal");
        } else {
            storage.write(file, intent);
            pendingRecords.merge(owner, 1, Integer::sum);
        }
        sequences.merge(owner, sequence, Math::max);
        }
    }

    void compact(UUID owner) {
        synchronized (ownerLocks.computeIfAbsent(owner, ignored -> new Object())) {
        List<Path> records = records(owner);
        if (records.isEmpty()) return;
        Path ownerRoot = ownerDirectory.apply(owner);
        Map<String, NBTTagCompound> projections = new LinkedHashMap<>();
        Map<String, CloudworksInventory> inventories = new HashMap<>();
        Map<UUID, NBTTagCompound> ports = new LinkedHashMap<>();
        long last = 0;
        for (Path record : records) {
            NBTTagCompound intent = storage.read(record);
            long sequence = sequence(record);
            if (sequence != intent.getLong("portSequence") || intent.getInteger("schema") != 2
                    || !owner.toString().equals(intent.getString("owner")))
                throw new IllegalStateException("Invalid port record identity");
            last = sequence;
            NBTTagList operations = intent.getTagList("operations", 10);
            for (int index = 0; index < operations.tagCount(); index++) {
                NBTTagCompound operation = operations.getCompoundTagAt(index);
                if (operation.hasKey("port", 10)) {
                    CloudworksPortRecord port =
                            CloudworksPortRecord.read(operation.getCompoundTag("port"));
                    if (!port.owner.equals(owner)) throw new IllegalStateException("Invalid port owner");
                    ports.put(port.id, port.write());
                }
                if (operation.hasKey("warehouse", 8)) {
                    String name = operation.getString("warehouse");
                    if (!name.equals("receiving") && !name.equals("sending"))
                        throw new IllegalStateException("Invalid port warehouse");
                    NBTTagCompound projection = projections.computeIfAbsent(name, ignored -> {
                        Path file = ownerRoot.resolve(name + ".dat");
                        if (Files.exists(file)) return storage.read(file);
                        CloudworksDataEmpty empty = new CloudworksDataEmpty(owner);
                        return empty.warehouse(name);
                    });
                    if (sequence > projection.getLong(WATERMARK)) {
                        CloudworksInventory inventory = inventories.computeIfAbsent(name,
                                ignored -> CloudworksInventory.read(projection.getCompoundTag(name)));
                        QIOResourceDescriptor descriptor = QIOResourceDescriptor.read(operation.getCompoundTag("descriptor"));
                        long target = operation.getLong("amount");
                        if (!descriptor.isResolved() || target < 0) throw new IllegalStateException("Invalid port amount");
                        long current = inventory.amount(descriptor);
                        if (current > target) inventory.extract(descriptor, current - target);
                        else if (target > current
                                && inventory.insertIgnoringTypeCap(descriptor, target - current) != target - current)
                            throw new IllegalStateException("Port journal inventory overflow");
                    }
                }
                if (operation.hasKey("energyKey", 8)) {
                    String key = operation.getString("energyKey");
                    double energy = operation.getDouble("energy");
                    if (!key.equals("receivingEnergy") && !key.equals("sendingEnergy")
                            || !Double.isFinite(energy) || energy < 0)
                        throw new IllegalStateException("Invalid port energy");
                    NBTTagCompound core = projections.computeIfAbsent("cloud", ignored -> {
                        Path file = ownerRoot.resolve("cloud.dat");
                        if (Files.exists(file)) return storage.read(file);
                        return new CloudworksDataEmpty(owner).cloud.writeCore();
                    });
                    if (sequence > core.getLong(WATERMARK)) core.setDouble(key, energy);
                }
            }
        }
        for (Map.Entry<String, NBTTagCompound> projection : projections.entrySet()) {
            CloudworksInventory inventory = inventories.get(projection.getKey());
            if (inventory != null) projection.getValue().setTag(projection.getKey(), inventory.write());
            projection.getValue().setLong(WATERMARK, last);
            storage.write(ownerRoot.resolve(projection.getKey() + ".dat"), projection.getValue());
        }
        for (Map.Entry<UUID, NBTTagCompound> port : ports.entrySet()) {
            port.getValue().setLong(WATERMARK, last);
            storage.write(ownerRoot.resolve("ports").resolve(port.getKey() + ".dat"), port.getValue());
        }
        NBTTagCompound watermark = new NBTTagCompound();
        watermark.setLong(WATERMARK, last);
        storage.write(ownerRoot.resolve("port-watermark.dat"), watermark);
        storage.flush();
        for (Path file : records) {
            try { Files.delete(file); }
            catch (IOException failure) { throw new IllegalStateException("cloudworks.error.storage", failure); }
        }
        pendingRecords.remove(owner);
        }
    }

    private Path directory(UUID owner) { return ownerDirectory.apply(owner).resolve("port-journal"); }

    private List<Path> records(UUID owner) {
        List<Path> files = new ArrayList<>();
        Path directory = directory(owner);
        if (!Files.isDirectory(directory)) return files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.dat")) {
            for (Path file : stream) files.add(file);
        } catch (IOException failure) { throw new IllegalStateException("cloudworks.error.storage", failure); }
        files.sort(Comparator.comparingLong(CloudworksPortJournal::sequence));
        return files;
    }

    private static long sequence(Path file) {
        String name = file.getFileName().toString();
        return Long.parseLong(name.substring(0, name.length() - 4));
    }

    interface Storage {
        NBTTagCompound read(Path file);
        void write(Path file, NBTTagCompound tag);
        default void flush() {}
    }

    private static final class CloudworksDataEmpty {
        final CloudworksData cloud;
        CloudworksDataEmpty(UUID owner) {
            cloud = CloudworksData.create(owner);
        }
        NBTTagCompound warehouse(String name) {
            return "receiving".equals(name) ? cloud.writeReceiving() : cloud.writeSending();
        }
    }
}
