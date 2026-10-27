package mekanism.cloudworks.api.client;

import java.util.*;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import mekanism.cloudworks.api.CloudworksAreaGeometry;
import mekanism.cloudworks.api.module.MachineModuleType;

/** Client projection. Contains no executable Runtime objects. */
@SideOnly(Side.CLIENT)
public final class AreaRenderContext {
    private final MachineModuleType<?> module;
    private final UUID source;
    private final CloudworksAreaGeometry.Rect bounds;
    public AreaRenderContext(MachineModuleType<?> module, UUID source, CloudworksAreaGeometry.Rect bounds) {
        this.module = Objects.requireNonNull(module); this.source = Objects.requireNonNull(source);
        this.bounds = Objects.requireNonNull(bounds);
    }
    public MachineModuleType<?> module() { return module; }
    public UUID sourceNodeId() { return source; }
    public CloudworksAreaGeometry.Rect bounds() { return bounds; }
}
