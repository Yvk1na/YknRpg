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
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 生活职业插件使用 MythicMobs 物品及 Bukkit 无序配方，无需连接其私有实现。 */
public final class MythicProfessionItems {
    private final MythicItems mythicItems;
    private final String pluginName;
    private final String namespace;
    private final boolean mining;
    private final Map<NamespacedKey, ShapelessRecipe> suppressedRecipes = new LinkedHashMap<>();

    private MythicProfessionItems(MythicItems mythicItems, String pluginName, String namespace, boolean mining) {
        this.mythicItems = mythicItems;
        this.pluginName = pluginName;
        this.namespace = namespace;
        this.mining = mining;
    }

    public static MythicProfessionItems fishing(MythicItems mythicItems) {
        return new MythicProfessionItems(mythicItems, "YknFish", "yknfish", false);
    }

    public static MythicProfessionItems mining(MythicItems mythicItems) {
        return new MythicProfessionItems(mythicItems, "YknMining", "yknmining", true);
    }

    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled(pluginName);
    }

    public boolean ready() {
        if (!available()) return false;
        if (!mining) return true;
        Plugin provider = Bukkit.getPluginManager().getPlugin(pluginName);
        if (provider == null) return false;
        try {
            // YknMining 异步启动；矿区就绪时才能确认装备与材料配方已注册。
            return Boolean.TRUE.equals(provider.getClass().getMethod("isIslandReady").invoke(provider));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("无法读取 YknMining 矿区就绪状态", exception);
        }
    }

    public List<ItemDefinition> load() {
        if (!ready()) return List.of();
        Map<String, List<IngredientSpec>> defaults = new LinkedHashMap<>();
        for (ShapelessRecipe recipe : nativeRecipes().values()) {
            mythicItems.itemId(recipe.getResult()).ifPresent(id -> defaults.putIfAbsent(id, ingredients(recipe)));
        }
        List<ItemDefinition> result = new ArrayList<>();
        for (String id : mythicItems.itemIds().stream().sorted().toList()) {
            if (!mythicItems.group(id).map(pluginName::equalsIgnoreCase).orElse(false)) continue;
            ItemStack stack = mythicItems.create(id);
            if (stack.getType().isAir()) throw new IllegalStateException(pluginName + " 物品为空：" + id);
            List<String> lore = stack.lore() == null ? List.of() : stack.lore().stream()
                    .map(PlainTextComponentSerializer.plainText()::serialize).toList();
            result.add(new ItemDefinition(id, ItemSource.MYTHIC_MOBS,
                    defaults.getOrDefault(id, Collections.nCopies(9, IngredientSpec.empty())),
                    kind(stack.getType()), rarity(lore), result.size()));
        }
        return List.copyOf(result);
    }

    public void suppressRecipes(Set<String> overriddenIds) {
        if (!ready()) return;
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
            String id = mythicItems.itemId(entry.getValue().getResult()).orElse(null);
            if (id == null || !overriddenIds.contains(id)) continue;
            // 依赖插件可能延迟注册或重新注册同名配方；覆盖仍生效，并记住最新的原生配方。
            if (Bukkit.getRecipe(entry.getKey()) == null) continue;
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
        // 依赖重新启用后必须读取它新注册的配方，不得复活上次启用的对象。
        suppressedRecipes.clear();
    }

    private Map<NamespacedKey, ShapelessRecipe> nativeRecipes() {
        Map<NamespacedKey, ShapelessRecipe> result = new LinkedHashMap<>(suppressedRecipes);
        Bukkit.recipeIterator().forEachRemaining(recipe -> {
            if (recipe instanceof ShapelessRecipe shapeless
                    && shapeless.getKey().getNamespace().equals(namespace)) {
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
                throw new IllegalStateException(pluginName + " 配方包含不支持的可选材料：" + recipe.getKey());
            }
        }
        while (result.size() < 9) result.add(IngredientSpec.empty());
        return List.copyOf(result);
    }

    CatalogKind kind(Material material) {
        String name = material.name();
        if (mining && name.endsWith("_PICKAXE")) return CatalogKind.MINING_PICKAXE;
        if (!mining && material == Material.FISHING_ROD) return CatalogKind.FISHING_ROD;
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")) {
            return mining ? CatalogKind.MINING_ARMOR : CatalogKind.FISHING_ARMOR;
        }
        return mining ? CatalogKind.MINING_ITEM : CatalogKind.FISHING_CATCH;
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
