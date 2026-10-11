package mekanism.cloudworks.common.recipe;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Function;

public final class MachineRecipeTypeEntry {
    private final String typeId;
    private final int priority;
    private final Function<Object, Object> query;

    public MachineRecipeTypeEntry(String typeId, int priority, Function<Object, Object> query) {
        this.typeId = Objects.requireNonNull(typeId);
        this.priority = priority;
        this.query = Objects.requireNonNull(query);
    }

    public String typeId() {
        return typeId;
    }

    public int priority() {
        return priority;
    }

    @Nullable
    public Object query(Object input) {
        return query.apply(input);
    }
}
