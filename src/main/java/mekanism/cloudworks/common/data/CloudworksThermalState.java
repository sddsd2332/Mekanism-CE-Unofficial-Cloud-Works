package mekanism.cloudworks.common.data;

import net.minecraft.nbt.NBTTagCompound;
import java.util.List;

/** Immutable cold projection; all mutable thermal state belongs to the worker store. */
public final class CloudworksThermalState {
    public final double heat, capacity, plasma, fuelTicks, credit, produced, consumed, loss;
    public final boolean burning;

    public CloudworksThermalState(double heat, double capacity, double plasma, double fuelTicks,
            double credit, double produced, double consumed, double loss, boolean burning) {
        for (double value : new double[]{heat, capacity, plasma, fuelTicks, credit, produced, consumed, loss})
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid thermal state");
        if (capacity < 1) throw new IllegalArgumentException("Invalid persisted heat capacity");
        this.heat = heat; this.capacity = capacity; this.plasma = plasma; this.fuelTicks = fuelTicks;
        this.credit = credit; this.produced = produced; this.consumed = consumed; this.loss = loss; this.burning = burning;
    }

    public double temperature() { return heat / capacity; }
    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setDouble("heat", heat); tag.setDouble("capacity", capacity); tag.setDouble("plasma", plasma);
        tag.setDouble("fuelTicks", fuelTicks); tag.setDouble("credit", credit);
        tag.setDouble("produced", produced); tag.setDouble("consumed", consumed); tag.setDouble("loss", loss);
        tag.setBoolean("burning", burning);
        return tag;
    }
    public static CloudworksThermalState read(NBTTagCompound tag) {
        String[] required = {"heat", "capacity", "plasma", "fuelTicks", "credit", "produced", "consumed", "loss", "burning"};
        List<String> missing = new java.util.ArrayList<>();
        for (String key : required) if (!tag.hasKey(key)) missing.add(key);
        if (!missing.isEmpty() && mekanism.common.Mekanism.logger != null)
            mekanism.common.Mekanism.logger.warn("Incomplete persisted Cloudworks thermal state; missing {}", missing);
        CloudworksDataChecks.fields(tag, 6, "heat", "capacity", "plasma", "fuelTicks", "credit", "produced", "consumed", "loss");
        CloudworksDataChecks.fields(tag, 1, "burning");
        return new CloudworksThermalState(tag.getDouble("heat"), tag.getDouble("capacity"), tag.getDouble("plasma"),
                tag.getDouble("fuelTicks"), tag.getDouble("credit"), tag.getDouble("produced"),
                tag.getDouble("consumed"), tag.getDouble("loss"), tag.getBoolean("burning"));
    }
}
