package mekanism.cloudworks.common.service;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.nbt.NBTTagString;

/** Canonical projection identity, independent of compound key insertion order. */
final class CloudworksNbtFingerprint {
    static byte[] of(NBTTagCompound tag) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DataOutputStream output = new DataOutputStream(new DigestOutputStream(new OutputStream() {
                @Override public void write(int value) {}
                @Override public void write(byte[] bytes, int offset, int length) {}
            }, digest));
            append(output, tag);
            return digest.digest();
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("cloudworks.error.storage", failure);
        }
    }

    private static void append(DataOutputStream output, NBTBase value) throws IOException {
        output.writeByte(value.getId());
        if (value instanceof NBTTagCompound) {
            NBTTagCompound compound = (NBTTagCompound) value;
            ArrayList<String> keys = new ArrayList<>(compound.getKeySet());
            Collections.sort(keys);
            output.writeInt(keys.size());
            for (String key : keys) {
                output.writeUTF(key);
                append(output, compound.getTag(key));
            }
        } else if (value instanceof NBTTagList) {
            NBTTagList list = (NBTTagList) value;
            output.writeInt(list.tagCount());
            for (int index = 0; index < list.tagCount(); index++) append(output, list.get(index));
        } else if (value instanceof NBTTagString) {
            output.writeUTF(((NBTTagString) value).getString());
        } else if (value instanceof NBTTagByteArray) {
            byte[] bytes = ((NBTTagByteArray) value).getByteArray();
            output.writeInt(bytes.length);
            output.write(bytes);
        } else if (value instanceof NBTTagIntArray) {
            int[] values = ((NBTTagIntArray) value).getIntArray();
            output.writeInt(values.length);
            for (int number : values) output.writeInt(number);
        } else if (value instanceof NBTTagLongArray) {
            NBTTagCompound array = new NBTTagCompound();
            array.setTag("v", value);
            CompressedStreamTools.write(array, output);
        } else if (value instanceof NBTPrimitive) {
            NBTPrimitive number = (NBTPrimitive) value;
            if (value.getId() == 5) output.writeFloat(number.getFloat());
            else if (value.getId() == 6) output.writeDouble(number.getDouble());
            else output.writeLong(number.getLong());
        } else {
            throw new IllegalArgumentException("Unsupported NBT fingerprint type");
        }
    }
}
