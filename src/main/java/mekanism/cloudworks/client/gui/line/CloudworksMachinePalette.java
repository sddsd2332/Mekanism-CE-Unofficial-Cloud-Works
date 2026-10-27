package mekanism.cloudworks.client.gui.line;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import mekanism.cloudworks.api.machine.CloudworksMachineCategory;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.network.data.CloudworksGuiData.Machine;
import net.minecraft.util.ResourceLocation;

/** Pure projection from server cache identities to machine-page slot indices. */
public final class CloudworksMachinePalette {

    public static final String ALL = "cloudworks:all";
    public final Map<String, String> categories;
    public final String selectedCategory;
    public final List<Integer> indices;

    private CloudworksMachinePalette(Map<String, String> categories, String selectedCategory,
            List<Integer> indices) {
        this.categories = Collections.unmodifiableMap(categories);
        this.selectedCategory = selectedCategory;
        this.indices = Collections.unmodifiableList(indices);
    }

    public static CloudworksMachinePalette project(List<Machine> machines, String selectedCategory) {
        return project(machines, selectedCategory, "");
    }

    public static CloudworksMachinePalette project(List<Machine> machines, String selectedCategory, String query) {
        Map<String, String> pending = new TreeMap<>();
        for (Machine machine : machines) {
            // Capability: the translation key resolves from the frozen local registry.
            CloudworksMachineCategory category = CloudworksMachineCategory.SUPPORT.id().equals(machine.categoryId)
                    ? CloudworksMachineCategory.SUPPORT : CloudworksRegistries.category(machine.stack);
            pending.put(machine.categoryId, category == null || !category.id().equals(machine.categoryId)
                    ? "" : category.translationKey());
        }
        Map<String, String> categories = new LinkedHashMap<>();
        categories.put(ALL, "cloudworks.editor.palette_all");
        for (CloudworksMachineCategory category : Arrays.asList(CloudworksMachineCategory.PROCESSING,
                CloudworksMachineCategory.GENERATION, CloudworksMachineCategory.OTHER)) {
            pending.remove(category.id());
            categories.put(category.id(), category.translationKey());
        }
        categories.putAll(pending);
        if (!categories.containsKey(selectedCategory))
            selectedCategory = ALL;
        String search = normalizeQuery(query);
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < machines.size(); index++) {
            Machine machine = machines.get(index);
            if ((ALL.equals(selectedCategory) || Objects.equals(selectedCategory, machine.categoryId))
                    && matches(machine, search)) indices.add(index);
        }
        return new CloudworksMachinePalette(categories, selectedCategory, indices);
    }

    public static String normalizeQuery(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean matchesSearch(String normalizedQuery, String name, String id) {
        return normalizedQuery.isEmpty() || name != null && name.toLowerCase(Locale.ROOT).contains(normalizedQuery)
                || id != null && id.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private static boolean matches(Machine machine, String query) {
        if (query.isEmpty()) return true;
        ResourceLocation registry = machine.stack.getItem().getRegistryName();
        return matchesSearch(query, machine.stack.getDisplayName(), registry == null ? "" : registry.toString());
    }
}
