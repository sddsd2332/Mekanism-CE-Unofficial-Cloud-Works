package mekanism.cloudworks.client.gui.line;

import mekanism.cloudworks.common.network.data.node.MachineNode;
import mekanism.cloudworks.common.network.data.node.Node;

/** Pure client-side progress projection, kept independent from OpenGL GUI classes. */
public final class CloudworksGuiProgress {
    private CloudworksGuiProgress() {
    }

    public static double fraction(Node node, long sampleTick, long worldTick, float partialTicks) {
        if (!(node instanceof MachineNode)) return 0;
        MachineNode machine = (MachineNode) node;
        int duration = Math.max(1, machine.duration);
        double progress = Math.max(0, Math.min(duration, machine.progress));
        if (sampleTick >= 0 && worldTick >= sampleTick && node.active
                && ("RUNNING".equals(node.state) || "DECAYING".equals(node.state))) {
            double fraction = Float.isFinite(partialTicks) ? Math.max(0, Math.min(1, partialTicks)) : 0;
            double elapsed = Math.min(duration, worldTick - sampleTick + fraction);
            progress += Math.min(duration - progress, elapsed);
        }
        return progress / duration;
    }
}
