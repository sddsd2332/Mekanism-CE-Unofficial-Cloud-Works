package mekanism.cloudworks.common.recipe;

import javax.annotation.Nullable;
import java.util.Objects;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import java.util.regex.Pattern;

/**
 * One named recipe family of a machine. The Runtime consumes exclusively the
 * frozen recipe views captured through {@link CloudworksAsyncRecipeAdmission};
 * the adapter reference exists only for admission, rejection diagnostics and
 * per-mode adapter selection. The old preview/query plumbing was removed
 * because no production caller remained after the frozen-view migration.
 */
public final class MachineRecipeTypeEntry {
    public static final int MAX_ID_LENGTH = 128;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.:-]+");
    private final String typeId;
    private final int priority;
    private final CloudworksMachineAdapter adapter;
    private final MachineSlotLayout slots;

    public MachineRecipeTypeEntry(String typeId, int priority, CloudworksMachineAdapter adapter) {
        this(typeId, priority, adapter, null);
    }

    public MachineRecipeTypeEntry(String typeId, int priority, CloudworksMachineAdapter adapter,
            MachineSlotLayout slots) {
        this.typeId = Objects.requireNonNull(typeId);
        if (!validId(typeId)) throw new IllegalArgumentException("Invalid recipe type id");
        this.priority = priority;
        this.adapter = Objects.requireNonNull(adapter);
        this.slots = slots == null ? null : slots.frozenCopy();
    }

    public static MachineRecipeTypeEntry forAdapter(String typeId, int priority, CloudworksMachineAdapter adapter) {
        return new MachineRecipeTypeEntry(typeId, priority, adapter, null);
    }

    public static MachineRecipeTypeEntry forAdapter(String typeId, int priority,
            CloudworksMachineAdapter adapter, MachineSlotLayout slots) {
        return new MachineRecipeTypeEntry(typeId, priority, adapter, slots);
    }

    public static boolean validId(String id) {
        return id != null && !id.isEmpty() && id.length() <= MAX_ID_LENGTH && ID.matcher(id).matches();
    }

    @Nullable
    public CloudworksMachineAdapter adapter() {
        return adapter;
    }

    public String typeId() {
        return typeId;
    }

    public int priority() {
        return priority;
    }

    @Nullable
    public MachineSlotLayout slots() {
        return slots;
    }
}
