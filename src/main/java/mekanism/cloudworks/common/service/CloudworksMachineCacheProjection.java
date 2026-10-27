package mekanism.cloudworks.common.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mekanism.cloudworks.api.machine.CloudworksMachineCategory;
import mekanism.cloudworks.common.data.CloudworksMachineCache;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Claim;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Machine;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.MachineCache;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import mekanism.cloudworks.common.data.CloudworksAuxiliaryNodeData;
import mekanism.cloudworks.common.registry.CloudworksAuxiliaryNodeRegistry;

/** Shared claim accounting for the GUI and machine withdrawal admission. */
public final class CloudworksMachineCacheProjection {
    private CloudworksMachineCacheProjection() {
    }

    public static Map<Integer, Map<NBTTagCompound, Long>> occupation(
            CloudworksMachineCache cache, List<ProductionLineData> lines) {
        Map<Integer, Map<NBTTagCompound, Long>> occupied = new LinkedHashMap<>();
        List<CloudworksMachineCache.Entry> entries = cache.entries();
        for (ProductionLineData line : lines)
            for (MachineNodeData node : line.machines()) {
                if (node.pooledClaim()) continue;
                ItemStack machine = node.machine();
                int index = cache.typeIndex(machine);
                if (index < 0) continue;
                CloudworksMachineCache.Variant variant = CloudworksMachineCache.findVariant(entries.get(index), machine);
                if (variant != null)
                    occupied.computeIfAbsent(index, ignored -> new LinkedHashMap<>())
                            .merge(variant.tag(), node.machineCount(), Math::addExact);
            }
        for (ProductionLineData line : lines)
            for (CloudworksAuxiliaryNodeData node : line.auxiliaries()) {
                int index = cache.typeIndex(node.type());
                if (index < 0) continue;
                CloudworksMachineCache.Entry entry = entries.get(index);
                CloudworksMachineCache.Variant variant = CloudworksMachineCache.findVariant(entry, node.type());
                if (variant != null)
                    occupied.computeIfAbsent(index, ignored -> new LinkedHashMap<>())
                            .merge(variant.tag(), 1L, Math::addExact);
            }
        // Exact NBT claims take precedence over type-only editor claims.
        for (ProductionLineData line : lines)
            for (MachineNodeData node : line.machines()) {
                if (!node.pooledClaim()) continue;
                int index = cache.typeIndex(node.machine());
                if (index < 0) continue;
                Map<NBTTagCompound, Long> byTag = occupied.computeIfAbsent(index, ignored -> new LinkedHashMap<>());
                long remaining = node.machineCount();
                for (CloudworksMachineCache.Variant variant : entries.get(index).variants()) {
                    NBTTagCompound tag = variant.tag();
                    ItemStack identity = entries.get(index).template();
                    identity.setTagCompound(tag.getKeySet().isEmpty() ? null : tag);
                    if (!CloudworksRegistries.identityKey(identity).equals(
                            CloudworksRegistries.identityKey(node.machine()))) continue;
                    long used = byTag.getOrDefault(tag, 0L);
                    long take = Math.min(remaining, Math.max(0, variant.count() - used));
                    if (take > 0) byTag.put(tag, Math.addExact(used, take));
                    remaining -= take;
                    if (remaining == 0) break;
                }
            }
        return occupied;
    }

    public static MachineCache build(CloudworksMachineCache cache, List<ProductionLineData> lines, int selectedType) {
        if (selectedType < -1) return MachineCache.EMPTY;
        List<CloudworksMachineCache.Entry> entries = cache.entries();
        Map<Integer, Map<NBTTagCompound, Long>> occupied = occupation(cache, lines);
        List<List<Claim>> claims = new ArrayList<>(entries.size());
        for (int type = 0; type < entries.size(); type++) claims.add(new ArrayList<>());
        for (ProductionLineData line : lines) {
            long[] totals = new long[entries.size()];
            for (MachineNodeData node : line.machines()) {
                int type = cache.typeIndex(node.machine());
                if (type >= 0) totals[type] = Math.addExact(totals[type], node.machineCount());
            }
            for (CloudworksAuxiliaryNodeData node : line.auxiliaries()) {
                int type = cache.typeIndex(node.type());
                if (type >= 0) totals[type] = Math.addExact(totals[type], 1L);
            }
            for (int type = 0; type < entries.size(); type++)
                if (totals[type] > 0) claims.get(type).add(new Claim(line.lineId(), line.name(), totals[type]));
        }
        List<Machine> types = new ArrayList<>(entries.size()), variants = new ArrayList<>();
        for (int type = 0; type < entries.size(); type++) {
            CloudworksMachineCache.Entry entry = entries.get(type);
            CloudworksMachineCategory category =
                    CloudworksAuxiliaryNodeRegistry.byItem(entry.template()) != null
                            ? CloudworksMachineCategory.SUPPORT : CloudworksRegistries.category(entry.template());
            Map<NBTTagCompound, Long> byTag = occupied.getOrDefault(type, Collections.emptyMap());
            long claimed = 0;
            for (long count : byTag.values()) claimed = Math.addExact(claimed, count);
            types.add(new Machine(entry.template(), entry.count(), claimed,
                    Math.max(0, entry.count() - claimed), claims.get(type), category.id()));
            if (type == selectedType)
                for (CloudworksMachineCache.Variant variant : entry.variants()) {
                    NBTTagCompound tag = variant.tag();
                    ItemStack machine = entry.template();
                    machine.setTagCompound(tag.getKeySet().isEmpty() ? null : tag);
                    long used = byTag.getOrDefault(tag, 0L);
                    variants.add(new Machine(machine, variant.count(), used,
                            Math.max(0, variant.count() - used), Collections.emptyList(), category.id()));
                }
        }
        return new MachineCache(selectedType, types, variants);
    }
}
