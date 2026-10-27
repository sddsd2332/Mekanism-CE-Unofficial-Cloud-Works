package mekanism.cloudworks.common.service;

import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.ProductionLineLink;
import mekanism.cloudworks.common.inventory.ContainerCloudworks;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import java.util.UUID;
import java.util.Collections;
import java.util.Locale;
import mekanism.cloudworks.api.transfer.CloudworksUploadResult;
import mekanism.cloudworks.api.transfer.ICloudworksUploadSource;
import mekanism.cloudworks.common.registries.CloudworksItems;
import mekanism.cloudworks.common.registry.CloudworksRegistries;

public final class CloudworksEditorActions {
    private CloudworksEditorActions() {
    }

    public static boolean apply(ContainerCloudworks container, EntityPlayerMP player, Operation operation, UUID lineId, UUID nodeId, String value) {
        CloudworksPlayerDataManager manager = CloudworksPlayerDataManager.INSTANCE;
        UUID owner = container.ownerId();
        if (operation == Operation.VIEW_LINE) {
            container.selectGraph(lineId);
            return true;
        }
        if (operation == Operation.RESOURCE_CHOICES) {
            String[] parts = value.split("\\|", 2);
            container.loadResources(lineId, nodeId, parts.length == 2 ? parts[1] : "",
                    Integer.parseInt(parts[0]));
            return true;
        }
        if (operation == Operation.RETURN_CACHE) {
            manager.returnCache(owner, lineId, nodeId);
            return true;
        }
        if (operation == Operation.LINE_BUFFER_CAPACITY) {
            ItemStack held = player.getHeldItemMainhand();
            if (held.isEmpty() || held.getItem() != CloudworksItems.LineCapacityCard)
                throw new IllegalArgumentException("cloudworks.error.line_capacity_card");
            int result = manager.increaseLineBufferMultiplier(owner, lineId);
            if (result == -1) throw new IllegalArgumentException("cloudworks.error.not_found");
            if (result == -2) throw new IllegalArgumentException("cloudworks.error.line_capacity_max");
            held.shrink(1);
            return true;
        }
        if (operation == Operation.CLEAR_NODE_CACHE) {
            manager.clearNodeCache(owner, lineId, nodeId);
            return true;
        }
        if (operation == Operation.ADD_INVENTORY) {
            String[] parts = value.split(",");
            int slot = Integer.parseInt(parts[0]);
            if (slot < 0 || slot >= 36 || parts.length != 3) throw error();
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack.isEmpty()) throw error();
            if (!CloudworksRegistries.isRegistered(stack))
                throw new IllegalArgumentException("cloudworks.error.machine_unregistered");
            ICloudworksUploadSource source =
                    new CloudworksPlayerInventorySource(player.inventory.mainInventory,
                    player.getUniqueID(), owner, () -> {
                player.inventory.markDirty();
                player.inventoryContainer.detectAndSendChanges();
            });
            CloudworksUploadResult uploaded = manager.uploadMachines(
                    source, lineId, stack, stack.getCount(), coordinate(parts[1]), coordinate(parts[2]));
            if (!uploaded.success())
                throw new IllegalArgumentException("cloudworks.error." + uploaded.refusal().name().toLowerCase(Locale.ROOT));
            return true;
        }
        if (operation == Operation.MERGE_MACHINE) {
            int slot = Integer.parseInt(value);
            if (slot < 0 || slot >= 36) throw error();
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack.isEmpty()) throw error();
            ICloudworksUploadSource source =
                    new CloudworksPlayerInventorySource(player.inventory.mainInventory,
                    player.getUniqueID(), owner, () -> {
                player.inventory.markDirty();
                player.inventoryContainer.detectAndSendChanges();
            });
            CloudworksUploadResult merged = manager.mergeMachines(
                    source, lineId, nodeId, stack, stack.getCount());
            if (!merged.success())
                throw new IllegalArgumentException("cloudworks.error." + merged.refusal().name().toLowerCase(Locale.ROOT));
            return true;
        }
        if (operation == Operation.INSTALL_UPGRADE || operation == Operation.REMOVE_SPEED || operation == Operation.REMOVE_ENERGY) {
            boolean install = operation == Operation.INSTALL_UPGRADE;
            // Installation is supplied by the cloud upgrade cache. The held
            // item is deliberately not consulted or consumed here; terminal
            // input has already deposited physical upgrade items into the
            // cache. The client sends the selected cache type explicitly.
            // Custom types are node-only and travel through the edit draft.
            if (value != null && value.indexOf(':') >= 0) throw error();
            boolean speed;
            if (install) {
                if ("speed".equalsIgnoreCase(value)) speed = true;
                else if ("energy".equalsIgnoreCase(value)) speed = false;
                else throw error();
            } else speed = operation == Operation.REMOVE_SPEED;
            // P1-4: one durable transaction moves the line count and the cloud
            // pool together; checks (pool/cap/stopped) run inside it.
            if (install) manager.installUpgradeFromPool(owner, lineId, speed);
            else manager.removeUpgradeToPool(owner, lineId, speed);
            return true;
        }
        manager.edit(owner, lineId, line -> {
            switch (operation) {
                case MOVE_NODE -> {
                    String[] xy = value.split(",");
                    int x = coordinate(xy[0]), z = coordinate(xy[1]);
                    MachineNodeData node = machine(line, nodeId);
                    if (node != null) node.setPosition(x, z);
                    else throw error();
                }
                case TOGGLE_NODE -> {
                    MachineNodeData node = machine(line, nodeId);
                    if (node == null) throw error();
                    node.setActive(!node.active());
                }
                case FILTER_LINK_CHOICE, FILTER_LINK_HELD, FILTER_LINK_CLEAR -> {
                    ProductionLineLink selected = null;
                    for (ProductionLineLink link : line.links()) if (link.linkId().equals(nodeId)) selected = link;
                    if (selected == null) throw error();
                    if (operation == Operation.FILTER_LINK_CLEAR) selected.setResourceFilters(Collections.emptyList());
                    else if (operation == Operation.FILTER_LINK_CHOICE)
                        selected.addResourceFilter(container.resourceChoice(lineId, nodeId, Integer.parseInt(value)));
                    else {
                        ItemStack held = player.getHeldItemMainhand();
                        if (held.isEmpty()) throw error();
                        selected.addResourceFilter(QIOResourceCodecs.item(held));
                    }
                }
                case UPDATE_LINK -> {
                    ProductionLineLink selected = null;
                    for (ProductionLineLink link : line.links()) if (link.linkId().equals(nodeId)) selected = link;
                    if (selected == null) throw error();
                    String[] parts = value.split(",");
                    double ratio = Double.parseDouble(parts[1]);
                    if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1) throw error();
                    selected.setPriority(Integer.parseInt(parts[0]));
                    selected.setRatio(ratio);
                }
                default -> throw error();
            }
        });
        return true;
    }

    private static int coordinate(String value) {
        int number = Integer.parseInt(value);
        if (Math.abs((long) number) > 1000000) throw error();
        return number;
    }

    private static MachineNodeData machine(ProductionLineData line, UUID id) {
        for (MachineNodeData node : line.machines()) if (node.nodeId().equals(id)) return node;
        return null;
    }

    private static IllegalArgumentException error() {
        return new IllegalArgumentException("cloudworks.error.editor");
    }
}
