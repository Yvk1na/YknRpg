package cn.yknitems.registry;

import cn.yknitems.YknItemsPlugin;
import cn.yknitems.model.ArmorSetDefinition;
import cn.yknitems.model.ArmorSlot;
import cn.yknitems.model.IngredientSpec;
import cn.yknitems.model.ItemCategory;
import cn.yknitems.model.ItemDefinition;
import cn.yknitems.model.Stage;
import cn.yknitems.model.WeaponType;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ItemRegistry {
    private final YknItemsPlugin plugin;
    private final LinkedHashMap<String, ItemDefinition> items = new LinkedHashMap<>();
    private final LinkedHashMap<String, ArmorSetDefinition> armorSets = new LinkedHashMap<>();
    private final EnumMap<Stage, String> catalysts = new EnumMap<>(Stage.class);
    private final LinkedHashMap<String, List<IngredientSpec>> baseRecipes = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<IngredientSpec>> recipeOverrides = new LinkedHashMap<>();

    public ItemRegistry(YknItemsPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        items.clear();
        armorSets.clear();
        catalysts.clear();
        baseRecipes.clear();
        recipeOverrides.clear();
        File file = new File(plugin.getDataFolder(), "equipment.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        loadStages(config);
        loadMaterials(config);
        loadWeapons(config);
        loadArmorSets(config);
        for (ItemDefinition definition : items.values()) {
            baseRecipes.put(definition.id(), definition.recipe());
        }
        loadRecipeOverrides();
        plugin.getLogger().info("已读取 " + items.size() + " 个自定义物品和 " + armorSets.size() + " 套护甲。");
    }

    public List<ItemDefinition> items() {
        return List.copyOf(items.values());
    }

    public Optional<ItemDefinition> item(String id) {
        return Optional.ofNullable(items.get(id));
    }

    public Optional<ArmorSetDefinition> armorSet(String id) {
        return Optional.ofNullable(armorSets.get(id));
    }

    public int armorSetCount() {
        return armorSets.size();
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

    private void loadStages(YamlConfiguration config) {
        ConfigurationSection section = requireSection(config, "stages");
        for (Stage stage : Stage.values()) {
            String catalyst = section.getString(stage.key() + ".catalyst");
            if (catalyst == null || catalyst.isBlank()) {
                throw new IllegalStateException("equipment.yml 缺少 stages." + stage.key() + ".catalyst");
            }
            catalysts.put(stage, catalyst);
        }
    }

    private void loadMaterials(YamlConfiguration config) {
        ConfigurationSection root = requireSection(config, "materials");
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = requireSection(root, id);
            Stage stage = Stage.fromKey(section.getString("stage", "early"));
            Material material = material(section.getString("material", "PRISMARINE_SHARD"));
            ItemDefinition definition = new ItemDefinition(
                    id,
                    section.getString("name", id),
                    ItemCategory.MATERIAL,
                    stage,
                    material,
                    section.getString("item-model", "yknitems:" + id),
                    0,
                    null,
                    0.0,
                    0.0,
                    "",
                    "",
                    "",
                    Map.of(),
                    null,
                    null,
                    section.getInt("color", 0),
                    0.0,
                    0.0,
                    0.0,
                    parseRecipe(section.getStringList("recipe")),
                    section.getStringList("description")
            );
            put(definition);
        }
    }

    private void loadWeapons(YamlConfiguration config) {
        ConfigurationSection root = requireSection(config, "weapons");
        for (Stage stage : Stage.values()) {
            ConfigurationSection stageSection = requireSection(root, stage.key());
            int requiredLevel = stageSection.getInt("required-level");
            ConfigurationSection entries = requireSection(stageSection, "items");
            for (String id : entries.getKeys(false)) {
                ConfigurationSection section = requireSection(entries, id);
                WeaponType type = WeaponType.fromKey(section.getString("type", "sword"));
                Material material = material(section.getString("material"));
                ConfigurationSection args = section.getConfigurationSection("ability-args");
                Map<String, Double> abilityArgs = new LinkedHashMap<>();
                if (args != null) {
                    for (String key : args.getKeys(false)) abilityArgs.put(key, args.getDouble(key));
                }
                ItemDefinition definition = new ItemDefinition(
                        id,
                        section.getString("name", id),
                        ItemCategory.WEAPON,
                        stage,
                        material,
                        "",
                        requiredLevel,
                        type,
                        section.getDouble("damage"),
                        section.getDouble("attack-speed", 1.0),
                        section.getString("ability", ""),
                        section.getString("ability-name", ""),
                        section.getString("ability-description", ""),
                        Collections.unmodifiableMap(abilityArgs),
                        null,
                        null,
                        0,
                        0.0,
                        0.0,
                        0.0,
                        weaponRecipe(type, catalysts.get(stage), material(section.getString("focus-material"))),
                        section.getStringList("description")
                );
                put(definition);
            }
        }
    }

    private void loadArmorSets(YamlConfiguration config) {
        ConfigurationSection root = requireSection(config, "armor-sets");
        for (String setId : root.getKeys(false)) {
            ConfigurationSection section = requireSection(root, setId);
            Stage stage = Stage.fromKey(section.getString("stage", "early"));
            int requiredLevel = section.getInt("required-level");
            Material binding = material(section.getString("binding-material"));
            int color = parseColor(section.getString("color", "#808080"));
            List<Double> armorValues = numberList(section, "stats.armor");
            List<Double> toughnessValues = numberList(section, "stats.toughness");
            List<Double> healthValues = numberList(section, "stats.max-health");
            EnumMap<ArmorSlot, String> pieces = new EnumMap<>(ArmorSlot.class);
            int index = 0;
            for (ArmorSlot slot : ArmorSlot.values()) {
                String id = setId + "_" + slot.key();
                pieces.put(slot, id);
                ItemDefinition definition = new ItemDefinition(
                        id,
                        section.getString("pieces." + slot.key(), id),
                        ItemCategory.ARMOR,
                        stage,
                        slot.material(),
                        "",
                        requiredLevel,
                        null,
                        0.0,
                        0.0,
                        "",
                        "",
                        "",
                        Map.of(),
                        slot,
                        setId,
                        color,
                        armorValues.get(index),
                        toughnessValues.get(index),
                        healthValues.get(index),
                        armorRecipe(slot, catalysts.get(stage), binding),
                        section.getStringList("description")
                );
                put(definition);
                index++;
            }
            ArmorSetDefinition set = new ArmorSetDefinition(
                    setId,
                    section.getString("name", setId),
                    stage,
                    requiredLevel,
                    Collections.unmodifiableMap(pieces),
                    section.getDouble("bonuses.outgoing-damage", 1.0),
                    section.getDouble("bonuses.incoming-damage", 1.0),
                    section.getDouble("bonuses.staff-damage", 1.0),
                    section.getDouble("bonuses.staff-cooldown", 1.0),
                    section.getString("bonus-description", "")
            );
            armorSets.put(setId, set);
        }
    }

    private List<Double> numberList(ConfigurationSection section, String path) {
        List<?> raw = section.getList(path, List.of());
        if (raw.size() != ArmorSlot.values().length) {
            throw new IllegalStateException(section.getCurrentPath() + "." + path + " 必须包含 4 个数值");
        }
        List<Double> values = new ArrayList<>();
        for (Object value : raw) {
            if (!(value instanceof Number number)) throw new IllegalStateException(path + " 只能包含数字");
            values.add(number.doubleValue());
        }
        return values;
    }

    private List<IngredientSpec> weaponRecipe(WeaponType type, String catalyst, Material focus) {
        String[] pattern = switch (type) {
            case SWORD -> new String[] {" C ", " C ", " F "};
            case GREATSWORD -> new String[] {"CCC", "CCC", " F "};
            case DAGGER -> new String[] {" C ", " F ", "   "};
            case BOW -> new String[] {"FC ", "F C", "FC "};
            case SPEAR -> new String[] {"  C", " F ", "F  "};
            case STAFF -> new String[] {" C ", " F ", " F "};
        };
        return pattern(pattern, Map.of(
                'C', IngredientSpec.custom(catalyst),
                'F', IngredientSpec.material(focus)
        ));
    }

    private List<IngredientSpec> armorRecipe(ArmorSlot slot, String catalyst, Material binding) {
        String[] pattern = switch (slot) {
            case HELMET -> new String[] {"CBC", "C C", "   "};
            case CHESTPLATE -> new String[] {"C C", "BCB", "CBC"};
            case LEGGINGS -> new String[] {"CBC", "C C", "B B"};
            case BOOTS -> new String[] {"   ", "C C", "B B"};
        };
        return pattern(pattern, Map.of(
                'C', IngredientSpec.custom(catalyst),
                'B', IngredientSpec.material(binding)
        ));
    }

    private List<IngredientSpec> pattern(String[] rows, Map<Character, IngredientSpec> values) {
        List<IngredientSpec> result = new ArrayList<>(9);
        for (String row : rows) {
            for (int column = 0; column < 3; column++) {
                result.add(values.getOrDefault(row.charAt(column), IngredientSpec.empty()));
            }
        }
        return List.copyOf(result);
    }

    private List<IngredientSpec> parseRecipe(List<String> configured) {
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
                plugin.getLogger().warning("recipes.yml 忽略未知物品：" + itemId);
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
            List<String> serialized = entry.getValue().stream().map(this::serializeIngredient).toList();
            config.set("recipes." + entry.getKey(), serialized);
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

    private void put(ItemDefinition definition) {
        if (items.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalStateException("重复物品 ID：" + definition.id());
        }
    }

    private static ConfigurationSection requireSection(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) throw new IllegalStateException("缺少配置段：" + path);
        return section;
    }

    private static Material material(String value) {
        if (value == null) throw new IllegalStateException("物品材质不能为空");
        Material material = Material.matchMaterial(value.toUpperCase(Locale.ROOT));
        if (material == null) throw new IllegalStateException("未知 Minecraft 材质：" + value);
        return material;
    }

    private static int parseColor(String value) {
        String normalized = value.startsWith("#") ? value.substring(1) : value;
        return Integer.parseInt(normalized, 16);
    }
}
