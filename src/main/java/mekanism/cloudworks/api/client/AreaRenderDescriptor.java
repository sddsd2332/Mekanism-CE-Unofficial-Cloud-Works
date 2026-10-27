package mekanism.cloudworks.api.client;

import java.util.*;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import mekanism.cloudworks.api.CloudworksAreaGeometry;
import mekanism.cloudworks.api.module.ModuleArea;

@SideOnly(Side.CLIENT)
public final class AreaRenderDescriptor {
    private final List<CloudworksAreaGeometry.Rect> areas;
    private final ModuleArea style;
    public AreaRenderDescriptor(Collection<CloudworksAreaGeometry.Rect> areas, ModuleArea style) {
        this.areas = Collections.unmodifiableList(new ArrayList<>(areas)); this.style = Objects.requireNonNull(style);
    }
    public List<CloudworksAreaGeometry.Rect> areas() { return areas; }
    public double targetThreshold() { return style.threshold(); }
    public ModuleArea.MergeMode mergeMode() { return style.mergeMode(); }
    public int fillArgb() { return style.fillArgb(); }
    public int borderArgb() { return style.borderArgb(); }
}
