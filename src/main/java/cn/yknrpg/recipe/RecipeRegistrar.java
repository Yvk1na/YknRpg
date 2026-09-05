package cn.yknrpg.recipe;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.item.CustomItemFactory;
import cn.yknrpg.model.IngredientSpec;
import cn.yknrpg.model.ItemDefinition;
import cn.yknrpg.registry.ItemRegistry;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RecipeRegistrar {
    private final YknRpgPlugin plugin;
    private final ItemRegistry registry;
    private final CustomItemFactory factory;
    private final List<NamespacedKey> keys = new ArrayList<>();

    public RecipeRegistrar(YknRpgPlugin plugin, ItemRegistry registry, CustomItemFactory factory) {
        this.plugin = plugin;
        this.registry = registry;
        this.factory = factory;
    }

    public void reload() {
        unregister();
        for (ItemDefinition definition : registry.items()) register(definition);
    }

    public int recipeCount() {
        return keys.size();
    }

    public void unregister() {
        for (NamespacedKey key : keys) Bukkit.removeRecipe(key);
        keys.clear();
    }

    private void register(ItemDefinition definition) {
        if (definition.recipe().size() != 9 || definition.recipe().stream().allMatch(IngredientSpec::isEmpty)) return;
        String recipeId = ("craft_" + definition.id().toLowerCase(Locale.ROOT))
                .replaceAll("[^a-z0-9/._-]", "_");
        NamespacedKey key = new NamespacedKey(plugin, recipeId);
        ShapedRecipe recipe = new ShapedRecipe(key, factory.create(definition));

        Map<String, Character> symbols = new LinkedHashMap<>();
        Map<Character, IngredientSpec> ingredients = new HashMap<>();
        char next = 'A';
        char[][] full = new char[3][3];
        int minRow = 3;
        int maxRow = -1;
        int minColumn = 3;
        int maxColumn = -1;
        for (int index = 0; index < 9; index++) {
            IngredientSpec ingredient = definition.recipe().get(index);
            int row = index / 3;
            int column = index % 3;
            if (ingredient.isEmpty()) {
                full[row][column] = ' ';
                continue;
            }
            Character symbol = symbols.get(ingredient.identity());
            if (symbol == null) {
                symbol = next++;
                symbols.put(ingredient.identity(), symbol);
                ingredients.put(symbol, ingredient);
            }
            full[row][column] = symbol;
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            minColumn = Math.min(minColumn, column);
            maxColumn = Math.max(maxColumn, column);
        }
        if (maxRow < 0) return;

        List<String> shape = new ArrayList<>();
        for (int row = minRow; row <= maxRow; row++) {
            shape.add(new String(full[row], minColumn, maxColumn - minColumn + 1));
        }
        recipe.shape(shape.toArray(String[]::new));
        for (Map.Entry<Character, IngredientSpec> entry : ingredients.entrySet()) {
            IngredientSpec ingredient = entry.getValue();
            RecipeChoice choice = ingredient.customItemId() != null
                    ? new RecipeChoice.ExactChoice(factory.ingredient(ingredient))
                    : new RecipeChoice.MaterialChoice(ingredient.material());
            recipe.setIngredient(entry.getKey(), choice);
        }
        if (!Bukkit.addRecipe(recipe)) {
            plugin.getLogger().warning("配方注册失败：" + definition.id());
            return;
        }
        keys.add(key);
    }
}
