package mekanism.cloudworks.common.content;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import mekanism.api.Action;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.common.content.qio.QIOFrequency;

/** QIO exchanges use the same planned local buffers as cloud transfers. */
public final class CloudworksPortQIO {
    private int nextFilter;

    public void download(QIOFrequency frequency, List<QIOResourceDescriptor> filters,
            List<IInventorySlot> slots, List<CloudworksHybridTank> tanks) {
        if (filters.isEmpty()) return;
        int start = nextFilter % filters.size();
        nextFilter = (start + 1) % filters.size();
        for (int index = 0; index < filters.size(); index++) {
            QIOResourceDescriptor resource = filters.get((start + index) % filters.size());
            // One buffer cell per type per pass keeps an abundant first filter from starving the rest.
            long limit = QIOResourceCodecs.ITEM_FAMILY.equals(resource.getFamily()) ? CloudworksPortInputSlot.LIMIT
                    : QIOResourceCodecs.FLUID_FAMILY.equals(resource.getFamily())
                    ? CloudworksHybridTank.FLUID_CAPACITY : CloudworksHybridTank.GAS_CAPACITY;
            Map<QIOResourceDescriptor, Long> request = CloudworksPortIo.buildRequest(
                    Collections.singletonMap(resource, frequency.massExtract(resource, limit, Action.SIMULATE)),
                    slots, tanks);
            long amount = request.getOrDefault(resource, 0L);
            if (amount <= 0) continue;
            long extracted = frequency.massExtract(resource, amount, Action.EXECUTE);
            CloudworksPortIo.insertPlanned(slots, tanks, resource, extracted);
        }
    }

    public void upload(QIOFrequency frequency, List<IInventorySlot> slots, List<CloudworksHybridTank> tanks) {
        for (int index = 0; index < slots.size() + tanks.size(); index++) {
            QIOResourceDescriptor resource = CloudworksPortIo.bufferDescriptor(slots, tanks, index);
            long amount = CloudworksPortIo.bufferContent(slots, tanks, index);
            if (resource == null || amount <= 0) continue;
            long accepted = frequency.massInsert(resource, amount, Action.SIMULATE);
            if (accepted <= 0) continue;
            long inserted = frequency.massInsert(resource, Math.min(amount, accepted), Action.EXECUTE);
            if (inserted <= 0) continue;
            if (index < slots.size()) {
                slots.get(index).shrinkStack((int) inserted, Action.EXECUTE);
            } else if (QIOResourceCodecs.FLUID_FAMILY.equals(resource.getFamily())) {
                tanks.get(index - slots.size()).shrinkFluid((int) inserted);
            } else {
                tanks.get(index - slots.size()).shrinkGas((int) inserted);
            }
        }
    }
}
