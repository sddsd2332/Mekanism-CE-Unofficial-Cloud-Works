package mekanism.cloudworks.client.gui.line;

import java.util.*;
import java.util.function.Supplier;
import mekanism.api.gas.GasRegistry;
import mekanism.api.gas.GasStack;
import mekanism.api.infuse.InfuseRegistry;
import mekanism.api.qio.client.QIOResourceRenderer;
import mekanism.api.qio.client.QIOResourceRendererRegistry;
import mekanism.api.qio.client.QIOResourceSelectionAdapterRegistry;
import mekanism.api.qio.resource.*;
import mekanism.cloudworks.api.CloudworksInfuseResourceCodec;
import mekanism.cloudworks.common.service.CloudworksEditorData;
import mekanism.common.InfuseStorage;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import mekanism.cloudworks.client.gui.element.GuiCloudQIOResourceGrid;
import mekanism.common.Mekanism;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.TextureStitchEvent;

/** Incrementally enumerates local registry templates, including supported recipe-viewer extension ingredients. */
@Mod.EventBusSubscriber(modid = "cloudworks", value = Side.CLIENT)
public final class CloudworksResourceCatalog {
    private static Supplier<Iterator<?>> viewerIngredients = Collections::emptyIterator;
    private static CloudworksResourceCatalog shared;
    private final Set<QIOResourceDescriptor> known = new LinkedHashSet<>();
    private final List<QIOResourceDescriptor> resources = new ArrayList<>();
    private final Map<QIOResourceDescriptor, String> names = new HashMap<>();
    private final Map<QIOResourceDescriptor, GuiCloudQIOResourceGrid.Entry> entries = new HashMap<>();
    private final Iterator<Item> items = Item.REGISTRY.iterator();
    private final Iterator<?> ingredients;
    private boolean complete;

    public static void setViewerIngredients(Supplier<Iterator<?>> supplier) {
        viewerIngredients = supplier;
        shared = null;
    }

    public static CloudworksResourceCatalog shared() {
        if (shared == null) shared = new CloudworksResourceCatalog();
        return shared;
    }

    @SubscribeEvent
    public static void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(() -> shared = null);
    }

    @SubscribeEvent
    public static void texturesReloaded(TextureStitchEvent.Post event) {
        // Resource/language reloads can change names and subtype templates.
        shared = null;
    }

    private CloudworksResourceCatalog() {
        ingredients = viewerIngredients.get();
        FluidRegistry.getRegisteredFluids().values().forEach(fluid -> add(QIOResourceCodecs.fluid(new FluidStack(fluid, 1))));
        GasRegistry.getRegisteredGasses().forEach(gas -> add(QIOResourceCodecs.gas(new GasStack(gas, 1))));
        InfuseRegistry.getInfuseMap().values().forEach(type -> add(CloudworksInfuseResourceCodec.descriptor(new InfuseStorage(type, 1))));
    }

    public void add(QIOResourceDescriptor resource) {
        if (resource != null && resource.isResolved() && known.add(resource)) resources.add(resource);
    }

    /** Work stays on the client thread, where item subtype and ingredient APIs are safe. */
    public void tick() {
        if (complete) return;
        long deadline = System.nanoTime() + 3_000_000;
        int budget = 256;
        while (budget-- > 0 && System.nanoTime() < deadline) {
            if (ingredients.hasNext()) {
                ingredient(ingredients.next());
            } else if (items.hasNext()) {
                Item item = items.next();
                try {
                    NonNullList<ItemStack> variants = NonNullList.create();
                    item.getSubItems(CreativeTabs.SEARCH, variants);
                    if (variants.isEmpty()) variants.add(new ItemStack(item));
                    for (ItemStack stack : variants) ingredient(stack);
                } catch (RuntimeException | LinkageError brokenSubtype) {
                    Mekanism.logger.warn("Cannot enumerate Cloudworks resource subtypes for {}", item.getRegistryName());
                }
            } else {
                complete = true;
                return;
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void ingredient(Object ingredient) {
        if (ingredient == null) return;
        try {
            for (QIOResourceCodec codec : QIOResourceCodecRegistry.INSTANCE.getCodecs().values()) {
                if (codec.getValueClass().isInstance(ingredient)) {
                    try {
                        add(QIOResourceDescriptor.of(codec, ingredient));
                    } catch (RuntimeException invalidTemplate) {
                        // A recipe-viewer ingredient may be empty or incompatible with this codec's normalization.
                    }
                }
            }
            QIOResourceSelectionAdapterRegistry.INSTANCE.getIngredientCandidates(ingredient).forEach(this::add);
        } catch (RuntimeException | LinkageError unsupported) {
            Mekanism.logger.debug("Unsupported Cloudworks catalog ingredient", unsupported);
        }
    }

    public List<QIOResourceDescriptor> resources() { return resources; }
    public boolean complete() { return complete; }

    public String displayName(QIOResourceDescriptor descriptor) {
        return names.computeIfAbsent(descriptor, CloudworksResourceCatalog::name);
    }

    public GuiCloudQIOResourceGrid.Entry entry(QIOResourceDescriptor descriptor) {
        return entries.computeIfAbsent(descriptor,
                value -> new GuiCloudQIOResourceGrid.Entry(value, 1));
    }

    @SuppressWarnings("unchecked")
    public static String name(QIOResourceDescriptor descriptor) {
        Object value = descriptor.resolve();
        if (value instanceof InfuseStorage infuse) return infuse.getType().getLocalizedName();
        QIOResourceRenderer<?> renderer = QIOResourceRendererRegistry.INSTANCE.get(descriptor.getCodecId());
        if (renderer != null && value != null && renderer.getValueClass().isInstance(value)) {
            try {
                return ((QIOResourceRenderer<Object>) renderer).getDisplayName(value);
            } catch (RuntimeException | LinkageError unsupported) {
                // Keep unknown extension resources selectable by their codec identity.
            }
        }
        return CloudworksEditorData.resourceName(descriptor);
    }
}
