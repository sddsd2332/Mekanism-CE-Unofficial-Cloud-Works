package mekanism.cloudworks.api.work;

import java.util.Objects;
import net.minecraft.util.ResourceLocation;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;

/** Immutable work planning inputs. Inventory contents are intentionally absent. */
public final class PreWorkContext {
    private final String recipeId;
    private final int operationCount;
    private final int baseDuration;
    private final double baseEnergyPerTick;
    private final CloudworksGenerationContext environment;
    private final ResourceLocation costCodec;
    private final QIOResourceDescriptor costResource;

    public PreWorkContext(String recipeId, int operationCount, int baseDuration, double baseEnergyPerTick,
            CloudworksGenerationContext environment, ResourceLocation costCodec) {
        this(recipeId, operationCount, baseDuration, baseEnergyPerTick, environment, costCodec, null);
    }

    public PreWorkContext(String recipeId, int operationCount, int baseDuration, double baseEnergyPerTick,
            CloudworksGenerationContext environment, ResourceLocation costCodec,
            QIOResourceDescriptor costResource) {
        this.recipeId = recipeId == null ? "" : recipeId;
        this.operationCount = operationCount;
        this.baseDuration = baseDuration;
        this.baseEnergyPerTick = baseEnergyPerTick;
        this.environment = Objects.requireNonNull(environment);
        this.costCodec = costCodec;
        this.costResource = costResource;
    }

    public String recipeId() { return recipeId; }
    public int operationCount() { return operationCount; }
    public int baseDuration() { return baseDuration; }
    public double baseEnergyPerTick() { return baseEnergyPerTick; }
    public CloudworksGenerationContext environment() { return environment; }
    public ResourceLocation costCodec() { return costCodec; }
    public QIOResourceDescriptor costResource() { return costResource; }
}
