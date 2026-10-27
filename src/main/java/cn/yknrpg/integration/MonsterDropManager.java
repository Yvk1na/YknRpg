package cn.yknrpg.integration;

import cn.yknrpg.YknRpgPlugin;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/** Stores monster drop overrides and applies them at the server's death events. */
public final class MonsterDropManager implements Listener {
    private static final String MYTHIC_PROVIDER = "mythicmobs";
    private static final Random RANDOM = new Random();

    private final JavaPlugin plugin;
    private final Map<String, List<MonsterDropProvider.Drop>> overrides = new LinkedHashMap<>();
    private final Map<String, MonsterDropProvider.Monster> monsters = new LinkedHashMap<>();
    private final Map<UUID, String> eventMonsterIds = new HashMap<>();
    private final List<MonsterDropProvider> providers = new ArrayList<>();
    private final MythicProvider mythicProvider;
    private Object mythicBukkit;
    private Object mythicMobManager;
    private Method mythicGetMobNames;
    private Method mythicGetMob;
    private Method bukkitAdapter;
    private Class<?> mythicItemDropType;
    private boolean mythicEventsRegistered;

    public MonsterDropManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.mythicProvider = new MythicProvider();
        providers.add(mythicProvider);
        connectMythic();
        registerMythicEvents();
    }

    public void registerProvider(MonsterDropProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            throw new IllegalArgumentException("怪物掉落提供者必须有 ID");
        }
        providers.removeIf(existing -> existing.id().equalsIgnoreCase(provider.id()));
        providers.add(provider);
        reload();
    }

    public void reload() {
        discoverProviders();
        loadOverrides();
        monsters.clear();
        for (MonsterDropProvider provider : providers) {
            try {
                for (MonsterDropProvider.Monster monster : provider.monsters()) {
                    monsters.put(ownerKey(provider.id(), monster.id()), monster);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("读取 " + provider.id() + " 怪物目录失败：" + exception.getMessage());
            }
        }
    }

    public List<MonsterView> monsters() {
        return monsters.entrySet().stream()
                .map(entry -> new MonsterView(entry.getKey(), entry.getKey().substring(entry.getKey().indexOf(':') + 1),
                        entry.getValue()))
                .sorted(Comparator.comparing(MonsterView::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(MonsterView::key))
                .toList();
    }

    public Optional<MonsterView> monster(String key) {
        MonsterDropProvider.Monster monster = monsters.get(key);
        if (monster == null) return Optional.empty();
        return Optional.of(new MonsterView(key, key.substring(key.indexOf(':') + 1), monster));
    }

    public List<MonsterDropProvider.Drop> drops(String key) {
        List<MonsterDropProvider.Drop> override = overrides.get(key);
        if (override != null) return cloneDrops(override);
        return monster(key).map(view -> cloneDrops(view.monster().nativeDrops())).orElseGet(List::of);
    }

    public List<MonsterDropProvider.Drop> nativeDrops(String key) {
        return monster(key).map(view -> cloneDrops(view.monster().nativeDrops())).orElseGet(List::of);
    }

    public boolean hasOverride(String key) {
        return overrides.containsKey(key);
    }

    public void save(String key, List<MonsterDropProvider.Drop> drops) {
        if (!monsters.containsKey(key)) throw new IllegalArgumentException("未知怪物：" + key);
        List<MonsterDropProvider.Drop> normalized = normalize(drops);
        overrides.put(key, normalized);
        saveOverrides();
    }

    public List<MonsterDropProvider.Drop> normalize(List<MonsterDropProvider.Drop> drops) {
        List<MonsterDropProvider.Drop> result = new ArrayList<>();
        for (MonsterDropProvider.Drop drop : drops) {
            if (drop == null) continue;
            if (drop.itemDrop()) {
                result.removeIf(previous -> previous.itemDrop() && previous.item().isSimilar(drop.item()));
            }
            result.add(copy(drop));
        }
        return List.copyOf(result);
    }

    public MonsterDropProvider.Drop customDrop(ItemStack item, double chance, int minimum, int maximum) {
        ItemStack sample = item.clone();
        sample.setAmount(1);
        String fingerprint = Integer.toHexString(sample.serialize().toString().hashCode());
        return new MonsterDropProvider.Drop("custom:" + fingerprint, sample, "YknRpg 自定义掉落",
                chance, minimum, maximum);
    }

    public boolean isCustom(MonsterDropProvider.Drop drop) {
        return drop != null && drop.key().startsWith("custom:");
    }

    public void closeAll() {
        eventMonsterIds.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        String key = eventMonsterIds.remove(event.getEntity().getUniqueId());
        if (key == null) key = identify(event.getEntity());
        if (key == null || !overrides.containsKey(key)) return;

        List<ItemStack> result = new ArrayList<>();
        for (MonsterDropProvider.Drop drop : overrides.get(key)) {
            if (!drop.itemDrop() || RANDOM.nextDouble() * 100.0 >= drop.chance()) continue;
            int amount = drop.minimum() == drop.maximum()
                    ? drop.minimum()
                    : drop.minimum() + RANDOM.nextInt(drop.maximum() - drop.minimum() + 1);
            int max = Math.max(1, drop.item().getMaxStackSize());
            int remaining = amount;
            while (remaining > 0) {
                ItemStack stack = drop.item().clone();
                int batch = Math.min(max, remaining);
                stack.setAmount(batch);
                result.add(stack);
                remaining -= batch;
            }
        }
        event.getDrops().clear();
        event.getDrops().addAll(result);
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("MythicMobs")) {
            connectMythic();
            reload();
            registerMythicEvents();
        }
        discoverProviders();
        reload();
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("MythicMobs")) {
            mythicBukkit = null;
            mythicMobManager = null;
            mythicEventsRegistered = false;
            monsters.clear();
        }
    }

    private String identify(LivingEntity entity) {
        for (MonsterDropProvider provider : providers) {
            try {
                Optional<String> id = provider.identify(entity);
                if (id.isPresent()) return ownerKey(provider.id(), id.get());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("识别 " + provider.id() + " 怪物失败：" + exception.getMessage());
            }
        }
        return null;
    }

    private void discoverProviders() {
        for (RegisteredServiceProvider<MonsterDropProvider> registration
                : Bukkit.getServicesManager().getRegistrations(MonsterDropProvider.class)) {
            MonsterDropProvider provider = registration.getProvider();
            if (provider == null || provider.id().equalsIgnoreCase(MYTHIC_PROVIDER)) continue;
            if (providers.stream().noneMatch(existing -> existing.id().equalsIgnoreCase(provider.id()))) {
                providers.add(provider);
            }
        }
    }

    private void connectMythic() {
        try {
            Plugin mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
            if (mythic == null || !mythic.isEnabled()) return;
            ClassLoader loader = mythic.getClass().getClassLoader();
            Class<?> api = Class.forName("io.lumine.mythic.bukkit.MythicBukkit", true, loader);
            mythicBukkit = api.getMethod("inst").invoke(null);
            mythicMobManager = invoke(mythicBukkit, "getMobManager");
            mythicGetMobNames = findMethod(mythicMobManager.getClass(), "getMobNames");
            mythicGetMob = findMethod(mythicMobManager.getClass(), "getMythicMob", String.class);
            bukkitAdapter = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, loader)
                    .getMethod("adapt", Class.forName("io.lumine.mythic.api.adapters.AbstractEntity", true, loader));
            mythicItemDropType = Class.forName("io.lumine.mythic.api.drops.IItemDrop", true, loader);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            mythicBukkit = null;
            mythicMobManager = null;
            plugin.getLogger().warning("MythicMobs 怪物掉落桥接不可用：" + exception.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerMythicEvents() {
        if (mythicBukkit == null || mythicEventsRegistered) return;
        try {
            PluginManagerAccess.register(plugin, "io.lumine.mythic.bukkit.events.MythicMobDeathEvent",
                    (listener, event) -> handleMythicDeath(event));
            PluginManagerAccess.register(plugin, "io.lumine.mythic.bukkit.events.MythicMobLootDropEvent",
                    (listener, event) -> handleMythicLoot(event));
            mythicEventsRegistered = true;
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("无法监听 MythicMobs 掉落事件：" + exception.getMessage());
        }
    }

    private void handleMythicDeath(Event event) {
        Object entity = invokeQuiet(event, "getEntity");
        Object mobType = invokeQuiet(event, "getMobType");
        if (!(entity instanceof Entity bukkitEntity) || mobType == null) return;
        String id = String.valueOf(invokeQuiet(mobType, "getInternalName"));
        String key = ownerKey(MYTHIC_PROVIDER, id);
        eventMonsterIds.put(bukkitEntity.getUniqueId(), key);
    }

    private void handleMythicLoot(Event event) {
        Object entity = invokeQuiet(event, "getEntity");
        Object mobType = invokeQuiet(event, "getMobType");
        if (!(entity instanceof Entity bukkitEntity) || mobType == null) return;
        String key = ownerKey(MYTHIC_PROVIDER, String.valueOf(invokeQuiet(mobType, "getInternalName")));
        List<MonsterDropProvider.Drop> override = overrides.get(key);
        if (override == null) return;
        Object lootBag = invokeQuiet(event, "getDrops");
        if (lootBag == null) return;

        Object rawTable = invokeQuiet(lootBag, "getLootTable");
        if (rawTable instanceof List<?> table) {
            List<Object> kept = new ArrayList<>();
            for (Object nativeDrop : table) {
                if (isItemDrop(nativeDrop)) continue;
                MonsterDropProvider.Drop configured = findNative(override, nativeDrop);
                if (configured == null) continue;
                applyNativeValues(nativeDrop, configured);
                kept.add(nativeDrop);
            }
            invokeQuiet(lootBag, "setLootTable", kept);
        }

        Object rawIntangible = invokeQuiet(lootBag, "getLootTableIntangible");
        if (rawIntangible instanceof Map<?, ?> map) {
            Map<Object, Object> kept = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                MonsterDropProvider.Drop configured = findNative(override, entry.getValue());
                if (configured == null) continue;
                applyNativeValues(entry.getValue(), configured);
                kept.put(entry.getKey(), entry.getValue());
            }
            invokeQuiet(lootBag, "setLootTableIntangible", kept);
        }
    }

    private MonsterDropProvider.Drop findNative(List<MonsterDropProvider.Drop> entries, Object nativeDrop) {
        String key = nativeKey(nativeDrop);
        return entries.stream().filter(entry -> entry.key().equals(key)).findFirst().orElse(null);
    }

    private String identifyActiveMob(Entity entity) {
        if (mythicMobManager == null || bukkitAdapter == null) return null;
        Object activeMobs = invokeQuiet(mythicMobManager, "getActiveMobs");
        if (!(activeMobs instanceof Collection<?> collection)) return null;
        for (Object active : collection) {
            Object abstractEntity = invokeQuiet(active, "getEntity");
            Object converted = invokeMethodQuiet(bukkitAdapter, null, abstractEntity);
            if (converted instanceof Entity candidate && candidate.getUniqueId().equals(entity.getUniqueId())) {
                Object type = invokeQuiet(active, "getType");
                return type == null ? null : ownerKey(MYTHIC_PROVIDER, String.valueOf(invokeQuiet(type, "getInternalName")));
            }
        }
        return null;
    }

    private String nativeKey(Object drop) {
        return "native:" + drop.getClass().getSimpleName() + ":" + String.valueOf(invokeQuiet(drop, "getLine"));
    }

    private boolean isItemDrop(Object drop) {
        return mythicItemDropType != null && mythicItemDropType.isInstance(drop)
                || drop.getClass().getSimpleName().contains("ItemDrop");
    }

    private void applyNativeValues(Object drop, MonsterDropProvider.Drop values) {
        try {
            ClassLoader loader = drop.getClass().getClassLoader();
            Class<?> placeholder = Class.forName("io.lumine.mythic.api.skills.placeholders.PlaceholderDouble",
                    true, loader);
            Method of = placeholder.getMethod("of", String.class);
            Object chance = of.invoke(null, Double.toString(values.chance() / 100.0));
            Object amount = of.invoke(null, values.minimum() == values.maximum()
                    ? Integer.toString(values.minimum())
                    : values.minimum() + "-" + values.maximum());
            invoke(drop, "setDropChance", chance);
            invoke(drop, "setDropAmount", amount);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A provider may expose read-only drops; its adapter can still implement removal.
        }
    }

    private void loadOverrides() {
        overrides.clear();
        File file = new File(plugin.getDataFolder(), "monster-drops.yml");
        if (!file.isFile()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("monsters");
        if (root == null) return;
        for (String encoded : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(encoded);
            if (node == null || !node.getBoolean("override", false)) continue;
            String key = node.getString("id");
            ConfigurationSection drops = node.getConfigurationSection("drops");
            if (key == null || drops == null) continue;
            List<MonsterDropProvider.Drop> loaded = new ArrayList<>();
            for (String index : drops.getKeys(false)) {
                String dropPath = "drops." + index;
                ItemStack item = node.getItemStack(dropPath + ".item");
                loaded.add(new MonsterDropProvider.Drop(
                        node.getString(dropPath + ".key", "custom:" + index),
                        item,
                        node.getString(dropPath + ".description", item == null ? "非物品掉落" : "自定义掉落"),
                        node.getDouble(dropPath + ".chance", 100.0),
                        node.getInt(dropPath + ".minimum", 1),
                        node.getInt(dropPath + ".maximum", 1)));
            }
            overrides.put(key, normalize(loaded));
        }
    }

    private void saveOverrides() {
        YamlConfiguration config = new YamlConfiguration();
        ConfigurationSection root = config.createSection("monsters");
        for (Map.Entry<String, List<MonsterDropProvider.Drop>> entry : overrides.entrySet()) {
            String encoded = Integer.toHexString(entry.getKey().hashCode()) + "_" +
                    Integer.toHexString(entry.getKey().length());
            ConfigurationSection node = root.createSection(encoded);
            node.set("id", entry.getKey());
            node.set("override", true);
            for (int index = 0; index < entry.getValue().size(); index++) {
                MonsterDropProvider.Drop drop = entry.getValue().get(index);
                String path = "drops." + index;
                node.set(path + ".key", drop.key());
                node.set(path + ".description", drop.description());
                node.set(path + ".chance", drop.chance());
                node.set(path + ".minimum", drop.minimum());
                node.set(path + ".maximum", drop.maximum());
                if (drop.itemDrop()) node.set(path + ".item", drop.item());
            }
        }
        try {
            config.save(new File(plugin.getDataFolder(), "monster-drops.yml"));
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存 monster-drops.yml", exception);
        }
    }

    private List<MonsterDropProvider.Drop> cloneDrops(List<MonsterDropProvider.Drop> source) {
        return source.stream().map(MonsterDropManager::copy).toList();
    }

    private static MonsterDropProvider.Drop copy(MonsterDropProvider.Drop drop) {
        return new MonsterDropProvider.Drop(drop.key(), drop.item(), drop.description(), drop.chance(),
                drop.minimum(), drop.maximum());
    }

    private static String ownerKey(String provider, String id) {
        return provider.toLowerCase() + ":" + id;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException exception) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterTypes.length) return method;
            }
            throw new IllegalStateException("缺少 MythicMobs 方法：" + name, exception);
        }
    }

    private static Object invoke(Object target, String name, Object... args) throws ReflectiveOperationException {
        if (target == null) throw new IllegalStateException("反射目标为空：" + name);
        Method selected = null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            boolean compatible = true;
            for (int index = 0; index < args.length; index++) {
                if (args[index] != null && !box(method.getParameterTypes()[index]).isInstance(args[index])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                selected = method;
                break;
            }
        }
        if (selected == null) throw new NoSuchMethodException(name);
        try {
            return selected.invoke(target, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getTargetException();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw exception;
        }
    }

    private static Object invokeQuiet(Object target, String name, Object... args) {
        try {
            return invoke(target, name, args);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static Object invokeMethodQuiet(Method method, Object target, Object... args) {
        if (method == null) return null;
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    public record MonsterView(String key, String id, MonsterDropProvider.Monster monster) {
        public String displayName() {
            return monster.displayName() == null || monster.displayName().isBlank() ? id : monster.displayName();
        }
    }

    private final class MythicProvider implements MonsterDropProvider {
        @Override
        public String id() {
            return MYTHIC_PROVIDER;
        }

        @Override
        public Collection<Monster> monsters() {
            if (mythicMobManager == null || mythicGetMobNames == null) return List.of();
            Object result = invokeQuiet(mythicMobManager, "getMobNames");
            if (!(result instanceof Collection<?> names)) return List.of();
            List<Monster> resultList = new ArrayList<>();
            for (Object rawName : names) {
                String id = String.valueOf(rawName);
                Object type = invokeQuiet(mythicMobManager, "getMythicMob", id);
                Object mob = optionalValue(type);
                if (mob == null || Boolean.TRUE.equals(invokeQuiet(mob, "isHidden"))) continue;
                resultList.add(readMonster(id, mob));
            }
            return resultList;
        }

        @Override
        public Optional<String> identify(LivingEntity entity) {
            String active = identifyActiveMob(entity);
            if (active == null || !active.startsWith(MYTHIC_PROVIDER + ":")) return Optional.empty();
            return Optional.of(active.substring(MYTHIC_PROVIDER.length() + 1));
        }

        private Monster readMonster(String id, Object mob) {
            Object display = invokeQuiet(mob, "getDisplayName");
            String displayName = display == null ? id : String.valueOf(display);
            String entityType = String.valueOf(invokeQuiet(mob, "getEntityTypeString"));
            ItemStack icon = monsterIcon(entityType);
            List<MonsterDropProvider.Drop> drops = readNativeDrops(id, mob);
            return new Monster(id, displayName, icon, drops);
        }

        private List<MonsterDropProvider.Drop> readNativeDrops(String id, Object mob) {
            Object table = invokeQuiet(mob, "getDropTable");
            Object weighted = invokeQuiet(table, "getDrops");
            Object view = invokeQuiet(weighted, "getView");
            if (!(view instanceof List<?> list)) return List.of();
            List<MonsterDropProvider.Drop> result = new ArrayList<>();
            for (Object drop : list) {
                String line = String.valueOf(invokeQuiet(drop, "getLine"));
                ItemStack item = nativeItem(drop);
                double chance = number(invokeQuiet(invokeQuiet(drop, "getDropChance"), "get"), 1.0) * 100.0;
                int[] range = amountRange(drop, line);
                result.add(new MonsterDropProvider.Drop(nativeKey(drop), item,
                        item == null ? drop.getClass().getSimpleName() + "：" + line : "原生物品掉落",
                        chance, range[0], range[1]));
            }
            return result;
        }

        private ItemStack nativeItem(Object drop) {
            try {
                Object item = invokeQuiet(drop, "getItem");
                Object abstractStack = item == null ? invoke(drop, "getDrop", null, 1.0) : invoke(item, "generateItemStack", 1);
                Object bukkit = invokeMethodQuiet(MonsterDropManager.this.bukkitAdapter, null, abstractStack);
                return bukkit instanceof ItemStack stack ? stack.clone() : parseVanillaLine(String.valueOf(invokeQuiet(drop, "getLine")));
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return parseVanillaLine(String.valueOf(invokeQuiet(drop, "getLine")));
            }
        }

        private int[] amountRange(Object drop, String line) {
            Object config = invokeQuiet(drop, "getConfig");
            String configured = config == null ? null : String.valueOf(invokeQuiet(config, "getString", "amount"));
            String value = configured == null || configured.equals("null") ? line : configured;
            var matcher = java.util.regex.Pattern.compile("(?<![A-Za-z])([0-9]+)(?:\\s*[-~]\\s*([0-9]+))?").matcher(value);
            if (!matcher.find()) return new int[]{1, 1};
            int minimum = Integer.parseInt(matcher.group(1));
            int maximum = matcher.group(2) == null ? minimum : Integer.parseInt(matcher.group(2));
            return new int[]{Math.max(1, minimum), Math.max(minimum, maximum)};
        }

        private ItemStack parseVanillaLine(String line) {
            for (String token : line.split("[ :;,|]+")) {
                Material material = Material.matchMaterial(token.toUpperCase());
                if (material != null && material.isItem()) return new ItemStack(material);
            }
            return null;
        }

        private double number(Object value, double fallback) {
            return value instanceof Number number ? number.doubleValue() : fallback;
        }

        private ItemStack monsterIcon(String entityType) {
            String normalized = entityType == null ? "" : entityType.toUpperCase().replace('-', '_');
            Material head = switch (normalized) {
                case "CREEPER" -> Material.CREEPER_HEAD;
                case "SKELETON" -> Material.SKELETON_SKULL;
                case "WITHER_SKELETON" -> Material.WITHER_SKELETON_SKULL;
                case "ZOMBIE" -> Material.ZOMBIE_HEAD;
                case "PIGLIN" -> Material.PIGLIN_HEAD;
                case "ENDER_DRAGON" -> Material.DRAGON_HEAD;
                case "PLAYER" -> Material.PLAYER_HEAD;
                default -> Material.PLAYER_HEAD;
            };
            return new ItemStack(head);
        }
    }

    private static Object optionalValue(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static final class PluginManagerAccess {
        private PluginManagerAccess() {
        }

        @SuppressWarnings("unchecked")
        static void register(JavaPlugin plugin, String eventName, EventExecutor executor)
                throws ReflectiveOperationException {
            Plugin mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
            if (mythic == null) return;
            Class<?> eventClass = Class.forName(eventName, true, mythic.getClass().getClassLoader());
            Listener listener = new Listener() {
            };
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) eventClass, listener,
                    EventPriority.HIGHEST, executor, plugin);
        }
    }
}
