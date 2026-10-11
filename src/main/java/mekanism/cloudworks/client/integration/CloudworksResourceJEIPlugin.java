package mekanism.cloudworks.client.integration;

import java.util.*;
import mekanism.cloudworks.client.gui.line.CloudworksResourceCatalog;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.recipe.IIngredientType;

/** Optional JEI/HEI catalog source; no dependency on its internal ingredient-list implementation. */
@JEIPlugin
public final class CloudworksResourceJEIPlugin implements IModPlugin {
    @Override
    public void register(IModRegistry registry) {
        IIngredientRegistry ingredients = registry.getIngredientRegistry();
        CloudworksResourceCatalog.setViewerIngredients(() -> iterator(ingredients));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Iterator<?> iterator(IIngredientRegistry registry) {
        Iterator<IIngredientType> types = new ArrayList<IIngredientType>(registry.getRegisteredIngredientTypes()).iterator();
        return new Iterator<Object>() {
            private Iterator<?> current = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!current.hasNext() && types.hasNext())
                    current = registry.getAllIngredients(types.next()).iterator();
                return current.hasNext();
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return current.next();
            }
        };
    }
}
