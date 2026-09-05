package cn.yknrpg.rpg;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.integration.MythicItems;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RpgRegistry {
    private final YknRpgPlugin plugin;
    private final MythicItems mythicItems;
    private final Map<String, ItemPower> items = new LinkedHashMap<>();
    private final Map<String, SetPower> sets = new LinkedHashMap<>();

    public RpgRegistry(YknRpgPlugin plugin, MythicItems mythicItems) {
        this.plugin = plugin;
        this.mythicItems = mythicItems;
    }

    public void reload() {
        items.clear();
        sets.clear();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "rpg.yml"));

        ConfigurationSection itemRoot = config.getConfigurationSection("items");
        if (itemRoot != null) {
            for (String itemId : itemRoot.getKeys(false)) {
                ConfigurationSection section = itemRoot.getConfigurationSection(itemId);
                if (section == null) continue;
                try {
                    ItemPower definition = new ItemPower(
                            itemId,
                            Slot.parse(section.getString("slot", "ANY")),
                            parseStats(section.getConfigurationSection("stats")),
                            parseAbilities(itemId, section.getConfigurationSection("abilities")));
                    items.put(normalizeItemId(itemId), definition);
                    if (!mythicItems.exists(itemId)) {
                        plugin.getLogger().warning("rpg.yml 引用了不存在的 MythicMobs 物品：" + itemId);
                    }
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("rpg.yml 物品定义无效（" + itemId + "）："
                            + exception.getMessage(), exception);
                }
            }
        }

        ConfigurationSection setRoot = config.getConfigurationSection("sets");
        if (setRoot != null) {
            for (String setId : setRoot.getKeys(false)) {
                ConfigurationSection section = setRoot.getConfigurationSection(setId);
                if (section == null) continue;
                try {
                    Set<String> pieces = new LinkedHashSet<>();
                    section.getStringList("pieces").stream()
                            .map(RpgRegistry::normalizeItemId)
                            .forEach(pieces::add);
                    if (pieces.isEmpty()) throw new IllegalArgumentException("pieces 不能为空");
                    int required = section.getInt("required-pieces", pieces.size());
                    if (required < 1 || required > pieces.size()) {
                        throw new IllegalArgumentException("required-pieces 必须介于 1 和套装部件数之间");
                    }
                    sets.put(setId, new SetPower(
                            setId,
                            Set.copyOf(pieces),
                            required,
                            parseStats(section.getConfigurationSection("stats")),
                            parseAbilities("set:" + setId, section.getConfigurationSection("abilities"))));
                } catch (RuntimeException exception) {
                    throw new IllegalStateException("rpg.yml 套装定义无效（" + setId + "）："
                            + exception.getMessage(), exception);
                }
            }
        }
        plugin.getLogger().info("SkyBlock 引擎已读取 " + items.size() + " 个物品能力定义与 "
                + sets.size() + " 个套装定义。");
    }

    public Profile profile(Player player) {
        return profile(player, null, null);
    }

    public Profile profile(Player player, ItemStack eventItem, EquipmentSlot eventHand) {
        MutableProfile result = new MutableProfile();
        ItemStack mainHand = eventHand == EquipmentSlot.HAND && eventItem != null
                ? eventItem : player.getInventory().getItemInMainHand();
        ItemStack offHand = eventHand == EquipmentSlot.OFF_HAND && eventItem != null
                ? eventItem : player.getInventory().getItemInOffHand();
        collect(result, mainHand, Slot.MAIN_HAND, false);
        collect(result, offHand, Slot.OFF_HAND, false);
        collect(result, player.getInventory().getHelmet(), Slot.HEAD, true);
        collect(result, player.getInventory().getChestplate(), Slot.CHEST, true);
        collect(result, player.getInventory().getLeggings(), Slot.LEGS, true);
        collect(result, player.getInventory().getBoots(), Slot.FEET, true);

        for (SetPower set : sets.values()) {
            long equipped = set.pieces().stream().filter(result.armorIds::contains).count();
            if (equipped < set.requiredPieces()) continue;
            result.stats.add(set.stats());
            for (Ability ability : set.abilities()) {
                result.abilities.add(new AbilitySource("set:" + set.id(), ability));
            }
        }
        return new Profile(result.stats.freeze(), List.copyOf(result.abilities), Set.copyOf(result.equippedIds));
    }

    public int itemCount() {
        return items.size();
    }

    public int setCount() {
        return sets.size();
    }

    boolean isMainHandItem(ItemStack stack) {
        return mainHandItemId(stack) != null;
    }

    String mainHandItemId(ItemStack stack) {
        Optional<String> itemId = mythicItems.itemId(stack);
        if (itemId.isEmpty()) return null;
        String normalizedId = normalizeItemId(itemId.get());
        ItemPower definition = items.get(normalizedId);
        return definition != null && definition.slot().matches(Slot.MAIN_HAND)
                ? normalizedId : null;
    }

    private void collect(MutableProfile result, ItemStack stack, Slot actualSlot, boolean armorPiece) {
        Optional<String> itemId = mythicItems.itemId(stack);
        if (itemId.isEmpty()) return;
        mythicItems.migrateWisdomLore(stack, itemId.get());
        String normalizedId = normalizeItemId(itemId.get());
        result.equippedIds.add(normalizedId);
        if (armorPiece) result.armorIds.add(normalizedId);
        ItemPower definition = items.get(normalizedId);
        if (definition == null || !definition.slot().matches(actualSlot)) return;
        result.stats.add(definition.stats());
        for (Ability ability : definition.abilities()) {
            result.abilities.add(new AbilitySource("item:" + definition.itemId(), ability));
        }
    }

    private Stats parseStats(ConfigurationSection section) {
        MutableStats stats = new MutableStats();
        if (section == null) return stats.freeze();
        for (String key : section.getKeys(false)) {
            Stat stat = Stat.parse(key);
            stats.values.merge(stat, section.getDouble(key), Double::sum);
        }
        return stats.freeze();
    }

    private List<Ability> parseAbilities(String owner, ConfigurationSection root) {
        if (root == null) return List.of();
        List<Ability> result = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;
            ConfigurationSection conditions = section.getConfigurationSection("conditions");
            List<Effect> effects = new ArrayList<>();
            for (Map<?, ?> raw : section.getMapList("effects")) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    values.put(normalize(String.valueOf(entry.getKey())), entry.getValue());
                }
                String type = string(values, "type", "").toUpperCase(Locale.ROOT);
                if (type.isBlank()) throw new IllegalArgumentException(owner + "." + id + " 的效果缺少 type");
                effects.add(new Effect(type, Collections.unmodifiableMap(values)));
            }
            if (effects.isEmpty()) throw new IllegalArgumentException(owner + "." + id + " 的 effects 不能为空");

            Boolean sneaking = conditions != null && conditions.contains("sneaking")
                    ? conditions.getBoolean("sneaking") : null;
            result.add(new Ability(
                    id,
                    section.getString("name", id),
                    Trigger.parse(section.getString("trigger", "RIGHT_CLICK")),
                    Math.max(0.0, section.getDouble("mana", 0.0)),
                    Math.max(0.0, section.getDouble("cooldown", 0.0)),
                    Math.max(0, section.getInt("charges", 0)),
                    Math.max(0.05, section.getDouble("recharge", 1.0)),
                    clamp(section.getDouble("chance", 1.0), 0.0, 1.0),
                    Math.max(1.0, section.getDouble("target-range", 16.0)),
                    section.getBoolean("announce", section.getString("trigger", "RIGHT_CLICK")
                            .toUpperCase(Locale.ROOT).contains("RIGHT_CLICK")),
                    section.getBoolean("cancel-interaction", true),
                    conditions != null && conditions.getBoolean("target-required", false),
                    conditions != null && conditions.getBoolean("behind-target", false),
                    conditions == null ? 100.0 : clamp(conditions.getDouble("health-below-percent", 100.0), 0, 100),
                    sneaking,
                    List.copyOf(effects)));
        }
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String normalizeItemId(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String string(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(normalize(key));
        return value == null ? fallback : String.valueOf(value);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public enum Stat {
        DAMAGE,
        STRENGTH,
        CRIT_CHANCE,
        CRIT_DAMAGE,
        ATTACK_SPEED,
        HEALTH,
        DEFENSE,
        WISDOM,
        ABILITY_DAMAGE,
        FEROCITY,
        VITALITY,
        MENDING,
        SPEED;

        static Stat parse(String value) {
            try {
                String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
                if (normalized.equals("INTELLIGENCE")) normalized = "WISDOM";
                return valueOf(normalized);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("未知 RPG 属性：" + value);
            }
        }
    }

    public enum Slot {
        ANY,
        MAIN_HAND,
        OFF_HAND,
        HEAD,
        CHEST,
        LEGS,
        FEET;

        static Slot parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("未知装备槽位：" + value);
            }
        }

        boolean matches(Slot actual) {
            return this == ANY || this == actual;
        }
    }

    public enum Trigger {
        RIGHT_CLICK,
        SNEAK_RIGHT_CLICK,
        BOW_SHOT,
        MELEE_HIT,
        PROJECTILE_HIT,
        KILL,
        DAMAGED,
        TICK;

        static Trigger parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("未知技能触发器：" + value);
            }
        }
    }

    public static final class Stats {
        private final Map<Stat, Double> values;

        private Stats(Map<Stat, Double> values) {
            this.values = Collections.unmodifiableMap(new EnumMap<>(values));
        }

        public double get(Stat stat) {
            return values.getOrDefault(stat, 0.0);
        }

        public Map<Stat, Double> values() {
            return values;
        }

        public Stats plus(Stat stat, double amount) {
            EnumMap<Stat, Double> copy = new EnumMap<>(values);
            copy.merge(stat, amount, Double::sum);
            return new Stats(copy);
        }
    }

    public record Effect(String type, Map<String, Object> values) {
        public String text(String key, String fallback) {
            Object value = values.get(normalize(key));
            return value == null ? fallback : String.valueOf(value);
        }

        public double number(String key, double fallback) {
            Object value = values.get(normalize(key));
            if (value instanceof Number number) return number.doubleValue();
            if (value == null) return fallback;
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        public boolean flag(String key, boolean fallback) {
            Object value = values.get(normalize(key));
            return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
        }
    }

    public record Ability(
            String id,
            String name,
            Trigger trigger,
            double manaCost,
            double cooldownSeconds,
            int maxCharges,
            double rechargeSeconds,
            double chance,
            double targetRange,
            boolean announce,
            boolean cancelInteraction,
            boolean targetRequired,
            boolean behindTarget,
            double healthBelowPercent,
            Boolean sneaking,
            List<Effect> effects
    ) {}

    public record AbilitySource(String owner, Ability ability) {
        public String key() {
            return owner + ":" + ability.id();
        }
    }

    public record Profile(Stats stats, List<AbilitySource> abilities, Set<String> equippedItemIds) {}

    private record ItemPower(String itemId, Slot slot, Stats stats, List<Ability> abilities) {}

    private record SetPower(String id, Set<String> pieces, int requiredPieces, Stats stats,
                            List<Ability> abilities) {}

    private static final class MutableStats {
        private final EnumMap<Stat, Double> values = new EnumMap<>(Stat.class);

        void add(Stats stats) {
            stats.values().forEach((stat, value) -> values.merge(stat, value, Double::sum));
        }

        Stats freeze() {
            return new Stats(values);
        }
    }

    private static final class MutableProfile {
        private final MutableStats stats = new MutableStats();
        private final List<AbilitySource> abilities = new ArrayList<>();
        private final Set<String> equippedIds = new LinkedHashSet<>();
        private final Set<String> armorIds = new LinkedHashSet<>();
    }
}
