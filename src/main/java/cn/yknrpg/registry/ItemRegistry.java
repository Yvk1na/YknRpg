package cn.yknrpg.registry;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.integration.EcoPetsItems;
import cn.yknrpg.integration.MythicItems;
import cn.yknrpg.model.IngredientSpec;
import cn.yknrpg.model.ItemDefinition;
import cn.yknrpg.model.ItemDefinition.CatalogKind;
import cn.yknrpg.model.ItemDefinition.ItemSource;
import cn.yknrpg.model.ItemDefinition.Rarity;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ItemRegistry {
    private final YknRpgPlugin plugin;
    private final MythicItems mythicItems;
    private final EcoPetsItems ecoPetsItems;
    private final LinkedHashMap<String, ItemDefinition> items = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<IngredientSpec>> baseRecipes = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<IngredientSpec>> recipeOverrides = new LinkedHashMap<>();

    public ItemRegistry(YknRpgPlugin plugin, MythicItems mythicItems, EcoPetsItems ecoPetsItems) {
        this.plugin = plugin;
        this.mythicItems = mythicItems;
        this.ecoPetsItems = ecoPetsItems;
    }

    public void reload() {
        items.clear();
        baseRecipes.clear();
        recipeOverrides.clear();
        mythicItems.connect();
        ecoPetsItems.connect();

        File file = new File(plugin.getDataFolder(), "equipment.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        Set<String> orderedIds = loadCatalog(config);
        ConfigurationSection recipes = config.getConfigurationSection("default-recipes");
        ConfigurationSection metadata = config.getConfigurationSection("catalog-metadata");
        List<ItemDefinition> loaded = new ArrayList<>(orderedIds.size());
        int fallbackOrder = 0;
        for (String itemId : orderedIds) {
            List<IngredientSpec> recipe = recipes == null
                    ? emptyRecipe()
                    : parseRecipe(recipes.getStringList(itemId));
            ConfigurationSection itemMetadata = metadata == null
                    ? null : metadata.getConfigurationSection(itemId);
            CatalogKind kind = CatalogKind.parse(itemMetadata == null
                    ? "OTHER" : itemMetadata.getString("kind", "OTHER"));
            Rarity rarity = Rarity.parse(itemMetadata == null
                    ? "COMMON" : itemMetadata.getString("rarity", "COMMON"));
            int order = itemMetadata == null
                    ? fallbackOrder : itemMetadata.getInt("order", fallbackOrder);
            ItemDefinition definition = new ItemDefinition(
                    itemId, ItemSource.MYTHIC_MOBS, recipe, kind, rarity, order);
            loaded.add(definition);
            items.put(itemId, definition);
            baseRecipes.put(itemId, recipe);
            fallbackOrder++;
        }
        int petOrder = 0;
        for (String itemId : ecoPetsItems.itemIds()) {
            List<IngredientSpec> recipe = emptyRecipe();
            ItemDefinition definition = new ItemDefinition(
                    itemId,
                    ItemSource.ECO_PETS,
                    recipe,
                    CatalogKind.PET,
                    petRarity(ecoPetsItems.configuredName(itemId)),
                    petOrder++
            );
            loaded.add(definition);
            items.put(itemId, definition);
            baseRecipes.put(itemId, recipe);
        }
        loaded.sort(Comparator.comparingInt((ItemDefinition item) -> item.kind().sortRank())
                .thenComparingInt(item -> item.rarity().sortRank())
                .thenComparingInt(ItemDefinition::catalogOrder)
                .thenComparing(ItemDefinition::id));
        items.clear();
        loaded.forEach(item -> items.put(item.id(), item));
        loadRecipeOverrides();
        plugin.getLogger().info("目录已读取 " + orderedIds.size() + " 个 MythicMobs 物品与 "
                + ecoPetsItems.itemIds().size() + " 个 EcoPets 宠物蛋。");
    }

    public List<ItemDefinition> items() {
        return List.copyOf(items.values());
    }

    public List<ItemDefinition> items(CatalogKind kind) {
        return items.values().stream().filter(item -> item.kind() == kind).toList();
    }

    public Optional<ItemDefinition> item(String id) {
        return Optional.ofNullable(items.get(id));
    }

    public void updateRecipe(String itemId, List<IngredientSpec> recipe) {
        if (recipe.size() != 9) throw new IllegalArgumentException("配方必须包含 9 格");
        ItemDefinition definition = items.get(itemId);
        if (definition == null) throw new IllegalArgumentException("未知物品：" + itemId);
        List<IngredientSpec> copy = List.copyOf(recipe);
        recipeOverrides.put(itemId, copy);
        items.put(itemId, definition.withRecipe(copy));
        saveRecipeOverrides();
    }

    public void resetRecipe(String itemId) {
        ItemDefinition definition = items.get(itemId);
        List<IngredientSpec> baseRecipe = baseRecipes.get(itemId);
        if (definition == null || baseRecipe == null) throw new IllegalArgumentException("未知物品：" + itemId);
        recipeOverrides.remove(itemId);
        items.put(itemId, definition.withRecipe(baseRecipe));
        saveRecipeOverrides();
    }

    public boolean hasRecipeOverride(String itemId) {
        return recipeOverrides.containsKey(itemId);
    }

    private Set<String> loadCatalog(YamlConfiguration config) {
        String group = config.getString("mythicmobs.group", "YknRpg");
        String prefix = config.getString("mythicmobs.id-prefix", "YKI_");
        boolean discover = config.getBoolean("mythicmobs.discover-group-items", true);
        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (String itemId : config.getStringList("catalog-order")) {
            if (mythicItems.exists(itemId)) {
                result.add(itemId);
            } else {
                plugin.getLogger().warning("MythicMobs 缺少目录物品：" + itemId);
            }
        }
        if (!discover) return result;

        mythicItems.itemIds().stream()
                .filter(id -> prefix.isBlank() || id.startsWith(prefix))
                .filter(id -> mythicItems.group(id).map(group::equalsIgnoreCase).orElse(false))
                .sorted(Comparator.comparing(id -> id.toLowerCase(Locale.ROOT)))
                .forEach(result::add);
        return result;
    }

    private List<IngredientSpec> parseRecipe(List<String> configured) {
        if (configured == null || configured.isEmpty()) return emptyRecipe();
        if (configured.size() != 9) throw new IllegalStateException("配方必须正好包含 9 格");
        List<IngredientSpec> result = new ArrayList<>(9);
        for (String value : configured) {
            if (value == null || value.isBlank() || value.equalsIgnoreCase("AIR")) {
                result.add(IngredientSpec.empty());
            } else if (value.startsWith("@")) {
                result.add(IngredientSpec.custom(value.substring(1)));
            } else {
                result.add(IngredientSpec.material(material(value)));
            }
        }
        return List.copyOf(result);
    }

    private void loadRecipeOverrides() {
        File file = new File(plugin.getDataFolder(), "recipes.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("recipes");
        if (root == null) return;
        for (String itemId : root.getKeys(false)) {
            ItemDefinition definition = items.get(itemId);
            if (definition == null) {
                plugin.getLogger().warning("recipes.yml 忽略未知目录物品：" + itemId);
                continue;
            }
            try {
                List<IngredientSpec> recipe = parseRecipe(root.getStringList(itemId));
                recipeOverrides.put(itemId, recipe);
                items.put(itemId, definition.withRecipe(recipe));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("recipes.yml 配方无效（" + itemId + "）：" + exception.getMessage());
            }
        }
    }

    private void saveRecipeOverrides() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, List<IngredientSpec>> entry : recipeOverrides.entrySet()) {
            config.set("recipes." + entry.getKey(), entry.getValue().stream()
                    .map(this::serializeIngredient)
                    .toList());
        }
        try {
            config.save(new File(plugin.getDataFolder(), "recipes.yml"));
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存 recipes.yml", exception);
        }
    }

    private String serializeIngredient(IngredientSpec ingredient) {
        if (ingredient.isEmpty()) return "AIR";
        if (ingredient.customItemId() != null) return "@" + ingredient.customItemId();
        return ingredient.material().name();
    }

    private static Rarity petRarity(String configuredName) {
        if (configuredName == null) return Rarity.COMMON;
        String normalized = configuredName.toLowerCase(Locale.ROOT);
        if (normalized.contains("&d") || normalized.contains("§d")) return Rarity.MYTHIC;
        if (normalized.contains("&6") || normalized.contains("§6")) return Rarity.LEGENDARY;
        if (normalized.contains("&5") || normalized.contains("§5")) return Rarity.EPIC;
        if (normalized.contains("&9") || normalized.contains("§9")) return Rarity.RARE;
        if (normalized.contains("&a") || normalized.contains("§a")) return Rarity.UNCOMMON;
        return Rarity.COMMON;
    }

    private static List<IngredientSpec> emptyRecipe() {
        return List.of(
                IngredientSpec.empty(), IngredientSpec.empty(), IngredientSpec.empty(),
                IngredientSpec.empty(), IngredientSpec.empty(), IngredientSpec.empty(),
                IngredientSpec.empty(), IngredientSpec.empty(), IngredientSpec.empty()
        );
    }

    private static Material material(String value) {
        Material material = Material.matchMaterial(value.toUpperCase(Locale.ROOT));
        if (material == null) throw new IllegalStateException("未知 Minecraft 材质：" + value);
        return material;
    }
}
