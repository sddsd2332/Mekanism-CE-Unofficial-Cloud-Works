package mekanism.cloudworks.common.service;

import java.util.UUID;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.ProductionLineLink;
import mekanism.cloudworks.common.network.CloudworksNetwork.Operation;
import mekanism.cloudworks.common.util.CloudworksMessages;
import net.minecraft.item.ItemStack;

/**
 * One immutable structural edit captured on the server thread and applied on
 * the {@link CloudworksCommandService} worker (Runtime §3.3).
 *
 * <p>The command carries only manager-level data: ids, strings and values that
 * were captured from main-thread-only sources (held item descriptors, GUI
 * resource choices) BEFORE queueing. Application re-validates the optimistic
 * command revision against the CURRENT state, so a command that lost a race is
 * rejected whole — never merged half. Errors map to the same status strings the
 * synchronous path produced, so the client sees an unchanged protocol.</p>
 */
public final class CloudworksStructuralCommand {
    final UUID owner;
    final Operation operation;
    final UUID line, node;
    final String value;
    final long revision;
    /** Captured FILTER_LINK_CHOICE answer (main-thread container query). */
    final QIOResourceDescriptor choice;
    /** Captured FILTER_LINK_HELD main-hand descriptor. */
    final QIOResourceDescriptor held;
    /** Result of CREATE, for the main-thread container hop. */
    UUID created;

    private CloudworksStructuralCommand(UUID owner, Operation operation, UUID line, UUID node,
                                        String value, long revision, QIOResourceDescriptor choice,
                                        QIOResourceDescriptor held) {
        this.owner = owner;
        this.operation = operation;
        this.line = line;
        this.node = node;
        this.value = value;
        this.revision = revision;
        this.choice = choice;
        this.held = held;
    }

    /**
     * Captures a structural command from a validated GUI action. Returns null
     * when the operation needs a main-thread input that is currently missing
     * (e.g. an empty hand for FILTER_LINK_HELD).
     */
    public static CloudworksStructuralCommand capture(UUID owner, Operation operation, UUID line, UUID node,
                                                      String value, long revision,
                                                      java.util.function.BiFunction<UUID, UUID, QIOResourceDescriptor> choiceLookup,
                                                      ItemStack heldStack) {
        QIOResourceDescriptor choice = null, held = null;
        if (operation == Operation.FILTER_LINK_CHOICE)
            choice = choiceLookup.apply(line, node);
        if (operation == Operation.FILTER_LINK_HELD) {
            if (heldStack.isEmpty()) return null;
            held = mekanism.api.qio.resource.QIOResourceCodecs.item(heldStack);
        }
        return new CloudworksStructuralCommand(owner, operation, line, node, value, revision, choice, held);
    }

    /**
     * Whether the operation is pure manager-level structural work with no
     * live main-thread input, and may therefore be applied asynchronously.
     */
    public static boolean asynchronous(Operation operation) {
        switch (operation) {
            case CREATE: case START: case STOP: case RENAME: case DELETE:
            case LINK: case UNLINK: case REMOVE_NODE: case MOVE_NODE: case TOGGLE_NODE:
            case UPDATE_LINK: case FILTER_LINK_CHOICE: case FILTER_LINK_HELD: case FILTER_LINK_CLEAR:
            case RETURN_CACHE: case CLEAR_NODE_CACHE:
            case INSTALL_UPGRADE: case REMOVE_SPEED: case REMOVE_ENERGY:
            case LINE_FLOW: case LINE_OFFLINE:
                return true;
            default:
                return false;
        }
    }

    /** Line to select in the container after success (CREATE result, or null). */
    public UUID createdLine() {
        return created;
    }

    /**
     * Applies the command under the manager lock on the command worker.
     * Returns the client status string; never throws.
     */
    public String apply(CloudworksPlayerDataManager manager) {
        try {
            synchronized (manager.ownerLock(owner)) {
            if (manager.commandRevision(owner) != revision) return "cloudworks.error.stale";
            boolean applied = true;
            switch (operation) {
                case CREATE -> created = manager.createLine(owner).lineId();
                case START -> applied = manager.toggleLine(owner, line, true);
                case STOP -> applied = manager.toggleLine(owner, line, false);
                case LINE_OFFLINE -> {
                    if (!"1".equals(value) && !"0".equals(value)) throw error();
                    manager.setRunWhileOwnerOffline(owner, line, "1".equals(value));
                }
                case RENAME -> applied = manager.renameLine(owner, line, value);
                case DELETE -> applied = manager.deleteLine(owner, line);
                case LINK -> {
                    LinkSpec link = LinkSpec.parse(value);
                    manager.addLink(owner, line, link.sourceNode, link.sourcePort,
                            link.targetNode, link.targetPort, link.priority, link.ratio);
                }
                case UNLINK -> applied = manager.removeLink(owner, line, UUID.fromString(value.trim()));
                case REMOVE_NODE -> manager.removeNode(owner, line, node);
                case RETURN_CACHE -> manager.returnCache(owner, line, node);
                case CLEAR_NODE_CACHE -> manager.clearNodeCache(owner, line, node);
                case INSTALL_UPGRADE -> {
                    boolean speed;
                    if ("speed".equalsIgnoreCase(value)) speed = true;
                    else if ("energy".equalsIgnoreCase(value)) speed = false;
                    else return "cloudworks.error.editor";
                    manager.installUpgradeFromPool(owner, line, speed);
                }
                case REMOVE_SPEED -> manager.removeUpgradeToPool(owner, line, true);
                case REMOVE_ENERGY -> manager.removeUpgradeToPool(owner, line, false);
                case LINE_FLOW -> manager.edit(owner, line, target -> {
                    // Read-modify-write inside the edit lambda: the flow values
                    // derive from the same locked snapshot that applies them.
                    boolean receiving = target.energyFlowReceivingToLine();
                    boolean sending = target.energyFlowLineToSending();
                    for (String segment : value.split(";")) {
                        String[] pair = segment.split("=", 2);
                        if (pair.length == 2 && "r2l".equals(pair[0])) receiving = "1".equals(pair[1]);
                        if (pair.length == 2 && "l2s".equals(pair[0])) sending = "1".equals(pair[1]);
                    }
                    target.setEnergyFlows(receiving, sending);
                });
                case MOVE_NODE -> manager.edit(owner, line, target -> {
                    String[] xy = value.split(",");
                    int x = coordinate(xy[0]), z = coordinate(xy[1]);
                    MachineNodeData machineNode = machine(target, node);
                    if (machineNode != null) machineNode.setPosition(x, z);
                    else throw error();
                });
                case TOGGLE_NODE -> manager.edit(owner, line, target -> {
                    MachineNodeData machineNode = machine(target, node);
                    if (machineNode == null) throw error();
                    machineNode.setActive(!machineNode.active());
                });
                case FILTER_LINK_CHOICE, FILTER_LINK_HELD, FILTER_LINK_CLEAR -> manager.edit(owner, line, target -> {
                    ProductionLineLink selected = link(target, node);
                    if (selected == null) throw error();
                    if (operation == Operation.FILTER_LINK_CLEAR) selected.setResourceFilters(java.util.Collections.emptyList());
                    else if (operation == Operation.FILTER_LINK_CHOICE) selected.addResourceFilter(choice);
                    else selected.addResourceFilter(held);
                });
                case UPDATE_LINK -> manager.edit(owner, line, target -> {
                    ProductionLineLink selected = link(target, node);
                    if (selected == null) throw error();
                    String[] parts = value.split(",");
                    double ratio = Double.parseDouble(parts[1]);
                    if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1) throw error();
                    selected.setPriority(Integer.parseInt(parts[0]));
                    selected.setRatio(ratio);
                });
                default -> throw error();
            }
            if (!applied) return "cloudworks.error.not_found";
            return CloudworksMessages.SAVED;
            }
        } catch (RuntimeException failure) {
            // Broader than the old synchronous catch: an unexpected storage
            // failure must still answer the client (the per-container sequence
            // gate would otherwise leave the GUI waiting forever).
            return CloudworksMessages.errorKey(failure);
        }
    }

    private static MachineNodeData machine(ProductionLineData line, UUID id) {
        for (MachineNodeData node : line.machines()) if (node.nodeId().equals(id)) return node;
        return null;
    }

    private static ProductionLineLink link(ProductionLineData line, UUID id) {
        for (ProductionLineLink link : line.links()) if (link.linkId().equals(id)) return link;
        return null;
    }

    private static int coordinate(String value) {
        int number = Integer.parseInt(value);
        if (Math.abs((long) number) > 1000000) throw error();
        return number;
    }

    private static IllegalArgumentException error() {
        return new IllegalArgumentException("cloudworks.error.editor");
    }

    private static final class LinkSpec {
        final UUID sourceNode, targetNode;
        final String sourcePort, targetPort;
        final int priority;
        final double ratio;

        LinkSpec(UUID sourceNode, String sourcePort, UUID targetNode, String targetPort,
                 int priority, double ratio) {
            this.sourceNode = sourceNode;
            this.sourcePort = sourcePort;
            this.targetNode = targetNode;
            this.targetPort = targetPort;
            this.priority = priority;
            this.ratio = ratio;
        }

        static LinkSpec parse(String value) {
            String[] parts = value == null ? new String[0] : value.split("\\|", -1);
            if (parts.length < 4) throw new IllegalArgumentException("cloudworks.error.link_format");
            int priority = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
            double ratio = parts.length > 5 ? Double.parseDouble(parts[5]) : 1D;
            if (!Double.isFinite(ratio) || ratio < 0D || ratio > 1D)
                throw new IllegalArgumentException("cloudworks.error.link_format");
            return new LinkSpec(UUID.fromString(parts[0]), parts[1], UUID.fromString(parts[2]), parts[3], priority, ratio);
        }
    }
}
