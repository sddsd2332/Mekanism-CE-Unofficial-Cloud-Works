package mekanism.cloudworks.common.data;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import mekanism.api.qio.resource.QIOResourceDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class ProductionLineLink {
    private final UUID linkId;
    private final UUID sourceNode;
    private final String sourcePort;
    private final UUID targetNode;
    private final String targetPort;
    private boolean enabled = true;
    private int priority;
    private double ratio = 1D;
    private final List<QIOResourceDescriptor> resourceFilters = new ArrayList<>();
    /**
     * Fractional transfer credit retained between ticks for ratios below one.
     */
    private double ratioRemainder;

    public ProductionLineLink(UUID linkId, UUID sourceNode, String sourcePort, UUID targetNode, String targetPort) {
        this.linkId = linkId;
        this.sourceNode = sourceNode;
        this.sourcePort = sourcePort;
        this.targetNode = targetNode;
        this.targetPort = targetPort;
    }

    public ProductionLineLink copyForRuntime() {
        ProductionLineLink copy = new ProductionLineLink(linkId, sourceNode, sourcePort, targetNode, targetPort);
        copy.enabled = enabled;
        copy.priority = priority;
        copy.ratio = ratio;
        copy.resourceFilters.addAll(resourceFilters);
        copy.ratioRemainder = ratioRemainder;
        return copy;
    }

    public static ProductionLineLink read(NBTTagCompound tag) {
        CloudworksDataChecks.fields(tag, 8, "linkId", "sourceNode", "sourcePort", "targetNode", "targetPort");
        CloudworksDataChecks.fields(tag, 1, "enabled");
        CloudworksDataChecks.fields(tag, 3, "priority");
        CloudworksDataChecks.fields(tag, 6, "ratioRemainder");

        CloudworksDataChecks.schema(tag, 1);
        ProductionLineLink link = new ProductionLineLink(UUID.fromString(tag.getString("linkId")),
                UUID.fromString(tag.getString("sourceNode")), tag.getString("sourcePort"),
                UUID.fromString(tag.getString("targetNode")), tag.getString("targetPort"));
        link.enabled = tag.getBoolean("enabled");
        link.priority = tag.getInteger("priority");
        CloudworksDataChecks.field(tag, "ratio", 6);
        link.setRatio(tag.getDouble("ratio"));
        if (tag.hasKey("resourceFilters")) {
            NBTTagList filters = CloudworksDataChecks.list(tag, "resourceFilters", 10);
            List<QIOResourceDescriptor> resources = new ArrayList<>();
            for (int index = 0; index < filters.tagCount(); index++) {
                NBTTagCompound entry = filters.getCompoundTagAt(index);
                CloudworksDataChecks.field(entry, "descriptor", 10);
                resources.add(CloudworksDataChecks.descriptor(entry.getCompoundTag("descriptor")));
            }
            link.setResourceFilters(resources);
        } else if (tag.hasKey("resourceFilter")) {
            CloudworksDataChecks.field(tag, "resourceFilter", 10);
            link.addResourceFilter(CloudworksDataChecks.descriptor(tag.getCompoundTag("resourceFilter")));
        }
        if (tag.hasKey("ratioRemainder")) {
            double remainder = tag.getDouble("ratioRemainder");
            if ((!Double.isFinite(remainder) || remainder < 0D || remainder >= 1D)
                    || (link.ratio <= 0D || link.ratio >= 1D) && remainder != 0D)
                throw new IllegalArgumentException("Invalid persisted ratio remainder");
            link.ratioRemainder = remainder;
        }
        return link;
    }

    public UUID linkId() {
        return linkId;
    }

    public UUID sourceNode() {
        return sourceNode;
    }

    public String sourcePort() {
        return sourcePort;
    }

    public UUID targetNode() {
        return targetNode;
    }

    public String targetPort() {
        return targetPort;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int priority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public double ratio() {
        return ratio;
    }

    public List<QIOResourceDescriptor> resourceFilters() {
        return Collections.unmodifiableList(resourceFilters);
    }

    public void setResourceFilters(Collection<QIOResourceDescriptor> filters) {
        List<QIOResourceDescriptor> checked = new ArrayList<>();
        Set<QIOResourceDescriptor> unique = new HashSet<>();
        for (QIOResourceDescriptor filter : filters) {
            if (filter == null || !filter.isResolved() || !unique.add(filter))
                throw new IllegalArgumentException("Invalid or duplicate link filter");
            checked.add(filter);
        }
        resourceFilters.clear();
        resourceFilters.addAll(checked);
    }

    public void addResourceFilter(QIOResourceDescriptor filter) {
        if (filter == null || !filter.isResolved()) throw new IllegalArgumentException("Invalid link filter");
        if (!resourceFilters.contains(filter)) resourceFilters.add(filter);
    }

    public boolean accepts(QIOResourceDescriptor resource) {
        return resourceFilters.isEmpty() || resourceFilters.contains(resource);
    }

    public void setRatio(double ratio) {
        if (!Double.isFinite(ratio) || ratio < 0D || ratio > 1D)
            throw new IllegalArgumentException("Invalid link ratio");
        this.ratio = ratio;
        if (this.ratio >= 1D || this.ratio <= 0D) ratioRemainder = 0D;
    }

    /**
     * Calculates this link's integer transfer allowance without turning a
     * fractional ratio into an unconditional one-item transfer. The fractional
     * part is retained so a stream of single items still progresses over time.
     */
    public long ratioAllowance(long available) {
        if (available <= 0 || ratio <= 0D) return 0L;
        if (ratio >= 1D) {
            ratioRemainder = 0D;
            return available;
        }
        double quota = available * ratio + ratioRemainder;
        if (!Double.isFinite(quota) || quota <= 0D) return 0L;
        long allowance = (long) Math.min((double) available, Math.floor(quota));
        ratioRemainder = quota - Math.floor(quota);
        return Math.max(0L, Math.min(available, allowance));
    }

    public double ratioRemainder() {
        return ratioRemainder;
    }

    public void setRatioRemainder(double value) {
        if (!Double.isFinite(value) || value < 0D || value >= 1D)
            throw new IllegalArgumentException("Invalid link ratio remainder");
        ratioRemainder = ratio > 0D && ratio < 1D ? value : 0D;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", 1);
        tag.setString("linkId", linkId.toString());
        tag.setString("sourceNode", sourceNode.toString());
        tag.setString("sourcePort", sourcePort);
        tag.setString("targetNode", targetNode.toString());
        tag.setString("targetPort", targetPort);
        tag.setBoolean("enabled", enabled);
        tag.setInteger("priority", priority);
        tag.setDouble("ratio", ratio);
        NBTTagList filters = new NBTTagList();
        for (QIOResourceDescriptor filter : resourceFilters) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setTag("descriptor", filter.write());
            filters.appendTag(entry);
        }
        tag.setTag("resourceFilters", filters);
        tag.setDouble("ratioRemainder", ratioRemainder);
        return tag;
    }
}
