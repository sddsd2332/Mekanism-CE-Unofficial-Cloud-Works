package mekanism.cloudworks.common.recipe;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksOperation;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.service.CloudworksResourceCapacity;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import net.minecraft.util.ResourceLocation;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import mekanism.cloudworks.common.service.CloudworksQuantities;

/**
 * Captures a native-free planning job; only the caller applies its result on the owner thread.
 */
public final class CloudworksFrozenRecipePlanner {
    private CloudworksFrozenRecipePlanner() {
    }

    public static Request capture(MachineNodeData node, CloudworksFrozenRecipeView view,
                                  int operations, int fallbackTicks) {
        Objects.requireNonNull(node);
        Objects.requireNonNull(view);
        if (operations < 1 || fallbackTicks < 1 || node.operation() != null)
            throw new IllegalArgumentException("Invalid planning request");
        MachineSlotLayout layout = node.capabilities().slots();
        if (layout == null) throw new IllegalArgumentException("Machine has no registered slot layout");
        Map<QIOResourceDescriptor, Long> stock = stock(node);
        Map<ResourceLocation, Long> room = new TreeMap<>();
        for (MachineSlotLayout.Slot slot : layout.slots())
            if (slot.role() == MachineSlotLayout.PortRole.OUTPUT)
                room.merge(slot.codecId(), CloudworksQuantities.multiplyLimit(slot.capacity(), node.machineCount()), CloudworksQuantities::addLimit);
        for (CloudworksResourceStack stack : node.outputs())
            room.merge(stack.descriptor().getCodecId(), -stack.amount(), Math::addExact);
        return new Request(node.nodeId(), node.machineCount(), machineIdentity(node),
                CloudworksRegistries.version(), view, operations, fallbackTicks, stock, room);
    }

    private static String machineIdentity(MachineNodeData node) {
        return node.capabilities().keyString();
    }

    private static Map<QIOResourceDescriptor, Long> stock(MachineNodeData node) {
        Map<QIOResourceDescriptor, Long> result = new TreeMap<>();
        for (CloudworksResourceStack stack : node.inputs())
            if (stack.available() > 0)
                result.merge(stack.descriptor(), stack.available(), Math::addExact);
        return result;
    }

    public static final class Request {
        private final UUID nodeId;
        private final long machineCount;
        private final String machineIdentity;
        private final long registryVersion;
        private final CloudworksFrozenRecipeView view;
        private final int operations;
        private final int fallbackTicks;
        private final Map<QIOResourceDescriptor, Long> stock;
        private final Map<ResourceLocation, Long> room;

        private Request(UUID nodeId, long machineCount, String machineIdentity, long registryVersion,
                        CloudworksFrozenRecipeView view,
                        int operations, int fallbackTicks, Map<QIOResourceDescriptor, Long> stock,
                        Map<ResourceLocation, Long> room) {
            this.nodeId = nodeId;
            this.machineCount = machineCount;
            this.machineIdentity = machineIdentity;
            this.registryVersion = registryVersion;
            this.view = view;
            this.operations = operations;
            this.fallbackTicks = fallbackTicks;
            this.stock = Collections.unmodifiableSortedMap(new TreeMap<>(stock));
            this.room = Collections.unmodifiableSortedMap(new TreeMap<>(room));
        }

        public UUID nodeId() {
            return nodeId;
        }

        /**
         * No Minecraft, Forge, registry or live recipe calls on this path.
         */
        public Result plan() {
            for (CloudworksFrozenRecipeView.Entry entry : view.entries()) {
                Map<QIOResourceDescriptor, Long> allocation = entry.allocate(stock, operations);
                if (allocation == null) continue;
                List<CloudworksResourceStack> outputs = entry.outputs(operations);
                Map<ResourceLocation, Long> needed = new TreeMap<>();
                for (CloudworksResourceStack output : outputs)
                    needed.merge(output.descriptor().getCodecId(), output.amount(), Math::addExact);
                boolean fits = true;
                for (Map.Entry<ResourceLocation, Long> demand : needed.entrySet())
                    if (demand.getValue() > room.getOrDefault(demand.getKey(), 0L)) {
                        fits = false;
                        break;
                    }
                if (fits) return new Result(this, allocation, outputs, entry.duration(fallbackTicks));
            }
            return null;
        }
    }

    public static final class Result {
        private final Request request;
        private final Map<QIOResourceDescriptor, Long> allocation;
        private final List<CloudworksResourceStack> outputs;
        private final int duration;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private Result(Request request, Map<QIOResourceDescriptor, Long> allocation,
                       List<CloudworksResourceStack> outputs, int duration) {
            this.request = request;
            this.allocation = Collections.unmodifiableSortedMap(new TreeMap<>(allocation));
            this.outputs = new ArrayList<>(outputs);
            this.duration = duration;
        }

        /**
         * Apply only on the owner thread. A changed recipe generation or node cache rejects
         * the whole result; the caller can capture and plan again without partial debit.
         */
        public boolean start(MachineNodeData node) {
            if (!consumed.compareAndSet(false, true)
                    || !CloudworksAsyncRecipeAdmission.current(request.view)
                    || !request.nodeId.equals(node.nodeId()) || !node.active()
                    || request.machineCount != node.machineCount() || node.operation() != null
                    || request.registryVersion != CloudworksRegistries.version()
                    || !request.machineIdentity.equals(machineIdentity(node))
                    || !request.stock.equals(stock(node))
                    || !CloudworksResourceCapacity.canOutput(node, outputs)) return false;
            List<CloudworksResourceStack> inputs = new ArrayList<>();
            allocation.forEach((resource, amount) -> inputs.add(new CloudworksResourceStack(resource, amount)));
            return node.beginOperation(new CloudworksOperation(request.operations, duration, inputs, outputs));
        }

        public int duration() {
            return duration;
        }

        public int operations() {
            return request.operations;
        }
    }
}
