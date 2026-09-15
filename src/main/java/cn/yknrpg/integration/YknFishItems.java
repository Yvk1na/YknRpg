package cn.yknrpg.integration;

import cn.yknrpg.model.IngredientSpec;
import cn.yknrpg.model.ItemDefinition;
import cn.yknrpg.model.ItemDefinition.CatalogKind;
import cn.yknrpg.model.ItemDefinition.ItemSource;
import cn.yknrpg.model.ItemDefinition.Rarity;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** YknFish 使用 MythicMobs 物品及 Bukkit 无序配方，无需连接其私有实现。 */
public final class YknFishItems {
    private final MythicItems mythicItems;
    private final Map<NamespacedKey, ShapelessRecipe> suppressedRecipes = new LinkedHashMap<>();

    public YknFishItems(MythicItems mythicItems) {
        this.mythicItems = mythicItems;
    }

    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("YknFish");
    }

    public List<ItemDefinition> load() {
        if (!available()) return List.of();
        Map<String, List<IngredientSpec>> defaults = new LinkedHashMap<>();
        for (ShapelessRecipe recipe : nativeRecipes().values()) {
            mythicItems.itemId(recipe.getResult()).ifPresent(id -> defaults.putIfAbsent(id, ingredients(recipe)));
        }
        List<ItemDefinition> result = new ArrayList<>();
        for (String id : mythicItems.itemIds().stream().sorted().toList()) {
            if (!mythicItems.group(id).map("YknFish"::equalsIgnoreCase).orElse(false)) continue;
            ItemStack stack = mythicItems.create(id);
            if (stack.getType().isAir()) throw new IllegalStateException("YknFish 物品为空：" + id);
            List<String> lore = stack.lore() == null ? List.of() : stack.lore().stream()
                    .map(PlainTextComponentSerializer.plainText()::serialize).toList();
            result.add(new ItemDefinition(id, ItemSource.MYTHIC_MOBS,
                    defaults.getOrDefault(id, Collections.nCopies(9, IngredientSpec.empty())),
                    kind(stack.getType()), rarity(lore), result.size()));
        }
        return List.copyOf(result);
    }

    public void suppressRecipes(Set<String> overriddenIds) {
        if (!available()) return;
        var suppressed = suppressedRecipes.entrySet().iterator();
        while (suppressed.hasNext()) {
            var entry = suppressed.next();
            String id = mythicItems.itemId(entry.getValue().getResult()).orElse(null);
            if (overriddenIds.contains(id)) continue;
            if (Bukkit.getRecipe(entry.getKey()) == null) Bukkit.addRecipe(entry.getValue());
            suppressed.remove();
        }
        if (overriddenIds.isEmpty()) return;
        for (var entry : nativeRecipes().entrySet()) {
            if (suppressedRecipes.containsKey(entry.getKey())) continue;
            String id = mythicItems.itemId(entry.getValue().getResult()).orElse(null);
            if (id == null || !overriddenIds.contains(id)) continue;
            if (Bukkit.removeRecipe(entry.getKey())) suppressedRecipes.put(entry.getKey(), entry.getValue());
        }
    }

    public void restoreRecipes() {
        if (available()) {
            for (var entry : suppressedRecipes.entrySet()) {
                if (Bukkit.getRecipe(entry.getKey()) == null) Bukkit.addRecipe(entry.getValue());
            }
        }
        suppressedRecipes.clear();
    }

    public void forgetRecipes() {
        // YknFish 重新启用后必须读取它新注册的配方，不得复活上次启用的对象。
        suppressedRecipes.clear();
    }

    private Map<NamespacedKey, ShapelessRecipe> nativeRecipes() {
        Map<NamespacedKey, ShapelessRecipe> result = new LinkedHashMap<>(suppressedRecipes);
        Bukkit.recipeIterator().forEachRemaining(recipe -> {
            if (recipe instanceof ShapelessRecipe shapeless
                    && shapeless.getKey().getNamespace().equals("yknfish")) {
                result.put(shapeless.getKey(), shapeless);
            }
        });
        return result;
    }

    private List<IngredientSpec> ingredients(ShapelessRecipe recipe) {
        List<IngredientSpec> result = new ArrayList<>();
        for (RecipeChoice choice : recipe.getChoiceList()) {
            if (choice instanceof RecipeChoice.ExactChoice exact && exact.getChoices().size() == 1) {
                ItemStack stack = exact.getChoices().getFirst();
                result.add(mythicItems.itemId(stack).map(IngredientSpec::custom)
                        .orElseGet(() -> IngredientSpec.material(stack.getType())));
            } else if (choice instanceof RecipeChoice.MaterialChoice materials && materials.getChoices().size() == 1) {
                result.add(IngredientSpec.material(materials.getChoices().getFirst()));
            } else {
                throw new IllegalStateException("钓鱼配方包含不支持的可选材料：" + recipe.getKey());
            }
        }
        while (result.size() < 9) result.add(IngredientSpec.empty());
        return List.copyOf(result);
    }

    static CatalogKind kind(Material material) {
        if (material == Material.FISHING_ROD) return CatalogKind.FISHING_ROD;
        String name = material.name();
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")) return CatalogKind.FISHING_ARMOR;
        return CatalogKind.FISHING_CATCH;
    }

    static Rarity rarity(List<String> lore) {
        for (String line : lore.reversed()) {
            switch (line.trim()) {
                case "普通" -> { return Rarity.COMMON; }
                case "少见的" -> { return Rarity.UNCOMMON; }
                case "稀有" -> { return Rarity.RARE; }
                case "史诗" -> { return Rarity.EPIC; }
                case "传说" -> { return Rarity.LEGENDARY; }
                case "神话" -> { return Rarity.MYTHIC; }
                default -> { }
            }
        }
        return Rarity.COMMON;
    }
}
