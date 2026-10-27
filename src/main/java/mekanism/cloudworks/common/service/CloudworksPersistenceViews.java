package mekanism.cloudworks.common.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Arrays;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.minecraft.nbt.NBTTagCompound;

/** Bounded committed read-only views. Cold/strict recovery always bypasses this cache. */
final class CloudworksPersistenceViews {
    private final int entryLimit;
    private final long byteLimit;
    private long bytes;
    private final Map<Path, Entry> entries = new LinkedHashMap<>(16, .75F, true);

    CloudworksPersistenceViews(int entryLimit, long byteLimit) {
        this.entryLimit = entryLimit; this.byteLimit = byteLimit;
    }

    synchronized Entry get(Path path) {
        Entry entry = entries.get(path);
        if (entry == null) return null;
        try {
            if (entry.matches(Files.readAttributes(path, BasicFileAttributes.class))) {
                CloudworksPlayerDataManager.DEBUG_CACHE_VERIFICATION_READS.incrementAndGet();
                if (Arrays.equals(entry.fileDigest, digest(path))) return entry;
            }
        } catch (IOException unavailable) { /* The ordinary read reports the real error. */ }
        remove(path);
        return null;
    }

    synchronized void put(Path path, NBTTagCompound frozenTag, byte[] fingerprint) {
        put(path, frozenTag, fingerprint, null);
    }

    synchronized void put(Path path, NBTTagCompound frozenTag, byte[] fingerprint, byte[] fileDigest) {
        remove(path);
        if (!path.getFileName().toString().endsWith(".dat")
                || path.getFileName().toString().endsWith(".pending.dat")
                || path.getParent().getFileName().toString().equals("port-journal")) return;
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile() || attributes.size() > byteLimit) return;
            entries.put(path, new Entry(frozenTag, fingerprint, fileDigest == null ? digest(path) : fileDigest, attributes));
            bytes += attributes.size();
            while (entries.size() > entryLimit || bytes > byteLimit)
                remove(entries.keySet().iterator().next());
        } catch (IOException unavailable) { /* A cache miss never changes durability. */ }
    }

    synchronized void clear() { entries.clear(); bytes = 0; }
    synchronized int size() { return entries.size(); }

    static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] digest(Path path) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return digest.digest();
    }

    private void remove(Path path) {
        Entry old = entries.remove(path);
        if (old != null) bytes -= old.attributes.size();
    }

    static final class Entry {
        final NBTTagCompound tag;
        final byte[] fingerprint;
        final byte[] fileDigest;
        final BasicFileAttributes attributes;
        Entry(NBTTagCompound tag, byte[] fingerprint, byte[] fileDigest, BasicFileAttributes attributes) {
            this.tag = tag;
            this.fingerprint = fingerprint == null ? null : fingerprint.clone();
            this.fileDigest = fileDigest.clone();
            this.attributes = attributes;
        }
        boolean matches(BasicFileAttributes next) {
            return next.isRegularFile() && attributes.size() == next.size()
                    && attributes.lastModifiedTime().equals(next.lastModifiedTime())
                    && attributes.creationTime().equals(next.creationTime())
                    && Objects.equals(attributes.fileKey(), next.fileKey());
        }
    }
}
