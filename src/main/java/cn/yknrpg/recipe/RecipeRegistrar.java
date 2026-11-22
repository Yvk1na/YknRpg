package cn.yknrpg.recipe;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.item.CustomItemFactory;
import cn.yknrpg.integration.MythicProfessionItems;
import cn.yknrpg.model.IngredientSpec;
import cn.yknrpg.model.ItemDefinition;
import cn.yknrpg.registry.ItemRegistry;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RecipeRegistrar {
    private final YknRpgPlugin plugin;
    private final ItemRegistry registry;
    private final CustomItemFactory factory;
    private final MythicProfessionItems fishingItems;
    private final MythicProfessionItems miningItems;
    private final List<NamespacedKey> keys = new ArrayList<>();

    public RecipeRegistrar(YknRpgPlugin plugin, ItemRegistry registry, CustomItemFactory factory,
                           MythicProfessionItems fishingItems, MythicProfessionItems miningItems) {
        this.plugin = plugin;
        this.registry = registry;
        this.factory = factory;
        this.fishingItems = fishingItems;
        this.miningItems = miningItems;
    }

    public void reload() {
        // 先生成候选，再改服务端：无效配方不能破坏上一份仍可用的配方。
        Map<ItemDefinition, ShapedRecipe> desired = new LinkedHashMap<>();
        Set<NamespacedKey> desiredKeys = new HashSet<>();
        Set<String> replacedProfessionItems = new HashSet<>();
        for (ItemDefinition definition : registry.items()) {
            if (definition.kind().isProfession() && !registry.hasRecipeOverride(definition.id())) continue;
            if (definition.recipe().size() != 9) continue;
            ShapedRecipe recipe = createRecipe(definition);
            if (recipe != null) {
                desired.put(definition, recipe);
                desiredKeys.add(recipe.getKey());
            } else if (definition.kind().isProfession()) {
                replacedProfessionItems.add(definition.id()); // 明确保存空配方，禁用原生合成。
            }
        }
        for (NamespacedKey key : List.copyOf(keys)) {
            if (desiredKeys.contains(key)) continue;
            Bukkit.removeRecipe(key);
            keys.remove(key);
        }
        for (var entry : desired.entrySet()) {
            ShapedRecipe recipe = entry.getValue();
            Recipe current = Bukkit.getRecipe(recipe.getKey());
            // 当前服务端每次增删都会刷新玩家数据；不变的配方必须完全跳过。
            if (!sameRecipe(current, recipe)) {
                if (current != null) Bukkit.removeRecipe(recipe.getKey());
                if (!Bukkit.addRecipe(recipe)) {
                    keys.remove(recipe.getKey());
                    plugin.getLogger().warning("配方注册失败：" + entry.getKey().id());
                    continue;
                }
            }
            if (!keys.contains(recipe.getKey())) keys.add(recipe.getKey());
            if (entry.getKey().kind().isProfession()) replacedProfessionItems.add(entry.getKey().id());
        }
        fishingItems.suppressRecipes(replacedProfessionItems);
        miningItems.suppressRecipes(replacedProfessionItems);
    }

    public int recipeCount() {
        return keys.size();
    }

    public void unregister() {
        for (NamespacedKey key : keys) Bukkit.removeRecipe(key);
        keys.clear();
        fishingItems.restoreRecipes();
        miningItems.restoreRecipes();
    }

    static boolean sameRecipe(Recipe current, ShapedRecipe desired) {
        if (!(current instanceof ShapedRecipe shaped) || !shaped.getResult().equals(desired.getResult())) return false;
        String[] actualShape = shaped.getShape();
        String[] desiredShape = desired.getShape();
        if (actualShape.length != desiredShape.length) return false;
        Map<Character, RecipeChoice> actualChoices = shaped.getChoiceMap();
        Map<Character, RecipeChoice> desiredChoices = desired.getChoiceMap();
        // Paper 往返转换会重写形状字母，必须比较每格材料而非字母本身。
        for (int row = 0; row < actualShape.length; row++) {
            if (actualShape[row].length() != desiredShape[row].length()) return false;
            for (int column = 0; column < actualShape[row].length(); column++) {
                if (!sameChoice(actualChoices.get(actualShape[row].charAt(column)),
                        desiredChoices.get(desiredShape[row].charAt(column)))) return false;
            }
        }
        return true;
    }

    private static boolean sameChoice(RecipeChoice actual, RecipeChoice desired) {
        // 新版 Paper 的 ItemTypeRecipeChoiceImpl 继承 MaterialChoice，但覆写了 equals。
        if (actual instanceof RecipeChoice.MaterialChoice left && desired instanceof RecipeChoice.MaterialChoice right) {
            return new HashSet<>(left.getChoices()).equals(new HashSet<>(right.getChoices()));
        }
        return Objects.equals(actual, desired);
    }

    private ShapedRecipe createRecipe(ItemDefinition definition) {
        if (definition.recipe().stream().allMatch(IngredientSpec::isEmpty)) return null;
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
        if (maxRow < 0) return null;

        List<String> shape = new ArrayList<>();
        for (int row = minRow; row <= maxRow; row++) {
            shape.add(new String(full[row], minColumn, maxColumn - minColumn + 1));
        }
        recipe.shape(shape.toArray(String[]::new));
        for (Map.Entry<Character, IngredientSpec> entry : ingredients.entrySet()) {
            IngredientSpec ingredient = entry.getValue();
            RecipeChoice choice = ingredient.customItemId() != null
                    ? exactIngredient(ingredient)
                    : new RecipeChoice.MaterialChoice(ingredient.material());
            recipe.setIngredient(entry.getKey(), choice);
        }
        return recipe;
    }

    private RecipeChoice.ExactChoice exactIngredient(IngredientSpec ingredient) {
        var current = factory.ingredient(ingredient);
        var id = ingredient.customItemId();
        String namespace = id.startsWith("YKF_") ? "yknfish" : id.startsWith("YKM_") ? "yknmining" : null;
        boolean catalogItem = id.startsWith("YKI_");
        if (namespace == null && !catalogItem) return new RecipeChoice.ExactChoice(current);
        var meta = current.getItemMeta();
        var model = meta == null ? null : meta.getItemModel();
        if (model == null || !model.getKey().equals(id.substring(4).toLowerCase(Locale.ROOT))) {
            return new RecipeChoice.ExactChoice(current);
        }
        boolean matchingNamespace = catalogItem
                ? model.getNamespace().equals("yknrpg_items") || model.getNamespace().equals("yknrpg_bone")
                : namespace.equals(model.getNamespace());
        if (!matchingNamespace) {
            return new RecipeChoice.ExactChoice(current);
        }
        var legacy = current.clone();
        meta.setItemModel(null);
        legacy.setItemMeta(meta);
        return new RecipeChoice.ExactChoice(current, legacy);
    }
}
