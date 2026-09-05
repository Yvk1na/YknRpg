package cn.yknrpg;

import cn.yknrpg.command.YknRpgCommand;
import cn.yknrpg.gui.EquipmentGui;
import cn.yknrpg.integration.AuraSkillsMana;
import cn.yknrpg.integration.EcoPetsItems;
import cn.yknrpg.integration.MythicItems;
import cn.yknrpg.integration.ProtocolLibInputBridge;
import cn.yknrpg.item.CustomItemFactory;
import cn.yknrpg.item.MaterialUseGuard;
import cn.yknrpg.recipe.RecipeRegistrar;
import cn.yknrpg.registry.ItemRegistry;
import cn.yknrpg.rpg.RpgEngine;
import cn.yknrpg.rpg.RpgRegistry;
import cn.yknrpg.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class YknRpgPlugin extends JavaPlugin {
    private static final String LEGACY_DATA_DIRECTORY = "YknItems";

    private ItemRegistry registry;
    private CustomItemFactory itemFactory;
    private RecipeRegistrar recipes;
    private EquipmentGui gui;
    private RpgRegistry rpgRegistry;
    private RpgEngine rpgEngine;
    private ProtocolLibInputBridge protocolLibInputBridge;
    private YamlConfiguration bundledConfigDefaults;
    private final Set<String> warnedMissingMessages = new HashSet<>();

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        mergeBundledConfigDefaults();
        migrateLegacyEquipmentGroup();
        boolean catalogReset = syncVersionedResource("equipment.yml", "content-revision");
        if (catalogReset) {
            resetBundledResource("recipes.yml");
        } else {
            saveBundledResource("recipes.yml");
        }
        syncVersionedResource("rpg.yml", "content-revision");

        MythicItems mythicItems = new MythicItems(this);
        EcoPetsItems ecoPetsItems = new EcoPetsItems(this);
        registry = new ItemRegistry(this, mythicItems, ecoPetsItems);
        registry.reload();
        rpgRegistry = new RpgRegistry(this, mythicItems);
        rpgRegistry.reload();
        itemFactory = new CustomItemFactory(this, registry, mythicItems, ecoPetsItems);
        recipes = new RecipeRegistrar(this, registry, itemFactory);
        recipes.reload();
        gui = new EquipmentGui(this, registry, itemFactory);
        rpgEngine = new RpgEngine(this, rpgRegistry, new AuraSkillsMana());

        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(new MaterialUseGuard(itemFactory), this);
        getServer().getPluginManager().registerEvents(rpgEngine, this);
        rpgEngine.start();
        enableProtocolLibInputBridge();

        PluginCommand command = getCommand("yknrpg");
        if (command == null) {
            throw new IllegalStateException("plugin.yml 缺少 yknrpg 指令定义");
        }
        YknRpgCommand handler = new YknRpgCommand(this);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        getLogger().info("已载入 " + registry.items().size() + " 个目录物品与 "
                + recipes.recipeCount() + " 个配方。");
        getLogger().info("SkyBlock 引擎已启用：" + rpgRegistry.itemCount() + " 个能力物品，"
                + rpgRegistry.setCount() + " 个套装。");
        getLogger().info("技能法力已接入 AuraSkills，YknRpg 不维护独立法力值。");
        if (!getServer().getPluginManager().isPluginEnabled("CraftEngine")) {
            getLogger().warning("未检测到 CraftEngine；物品和配方可用，但配方 GUI 将不显示工作台与箭头贴图。");
        }
    }

    @Override
    public void onDisable() {
        if (protocolLibInputBridge != null) protocolLibInputBridge.stop();
        if (rpgEngine != null) rpgEngine.stop();
        if (recipes != null) recipes.unregister();
    }

    public void reloadAll() {
        if (rpgEngine != null) rpgEngine.stop();
        reloadConfig();
        mergeBundledConfigDefaults();
        registry.reload();
        rpgRegistry.reload();
        recipes.reload();
        rpgEngine.start();
        getLogger().info("配置已重载：" + registry.items().size() + " 个物品，" + recipes.recipeCount() + " 个配方。");
    }

    public Component message(String path, Object... replacements) {
        return Text.component(raw(path, replacements));
    }

    public String raw(String path, Object... replacements) {
        String value = getConfig().getString(path);
        if (value == null && bundledConfigDefaults != null) {
            value = bundledConfigDefaults.getString(path);
        }
        if (value == null) {
            if (warnedMissingMessages.add(path)) {
                getLogger().warning("缺少文本配置：" + path);
            }
            value = "";
        }
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            value = value.replace("{" + replacements[index] + "}", String.valueOf(replacements[index + 1]));
        }
        return value;
    }

    public ItemRegistry registry() {
        return registry;
    }

    public CustomItemFactory itemFactory() {
        return itemFactory;
    }

    public RecipeRegistrar recipes() {
        return recipes;
    }

    public EquipmentGui gui() {
        return gui;
    }

    private void migrateLegacyDataFolder() {
        File current = getDataFolder();
        File legacy = new File(current.getParentFile(), LEGACY_DATA_DIRECTORY);
        if (!legacy.isDirectory()) return;
        if (current.exists()) {
            getLogger().warning("同时检测到 YknItems 与 YknRpg 数据目录；为避免覆盖，已保留并使用 YknRpg。"
                    + " 请手动核对旧目录。");
            return;
        }
        try {
            try {
                Files.move(legacy.toPath(), current.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(legacy.toPath(), current.toPath());
            }
            getLogger().info("已将旧版 plugins/YknItems 数据目录迁移为 plugins/YknRpg。");
        } catch (IOException exception) {
            throw new IllegalStateException("无法迁移旧版 YknItems 数据目录", exception);
        }
    }

    private void saveBundledResource(String name) {
        File target = new File(getDataFolder(), name);
        if (!target.isFile()) saveResource(name, false);
    }

    private void migrateLegacyEquipmentGroup() {
        File target = new File(getDataFolder(), "equipment.yml");
        if (!target.isFile()) return;
        YamlConfiguration equipment = YamlConfiguration.loadConfiguration(target);
        if (!"YknItems".equals(equipment.getString("mythicmobs.group"))) return;
        backupResource(target);
        equipment.set("mythicmobs.group", "YknRpg");
        try {
            equipment.save(target);
        } catch (IOException exception) {
            throw new IllegalStateException("无法迁移 MythicMobs 物品组名称", exception);
        }
        getLogger().info("已将 equipment.yml 的 MythicMobs 物品组从 YknItems 迁移为 YknRpg。");
    }

    private boolean syncVersionedResource(String name, String revisionPath) {
        File target = new File(getDataFolder(), name);
        if (!target.isFile()) {
            saveResource(name, false);
            return false;
        }

        YamlConfiguration bundled = loadBundledYaml(name);
        YamlConfiguration installed = YamlConfiguration.loadConfiguration(target);
        int bundledRevision = bundled.getInt(revisionPath, 0);
        int installedRevision = installed.getInt(revisionPath, 0);
        if (bundledRevision <= installedRevision) return false;

        backupResource(target);
        saveResource(name, true);
        getLogger().info("已将 " + name + " 内容版本从 " + installedRevision
                + " 升级至 " + bundledRevision + "，旧文件已备份。");
        return true;
    }

    private void resetBundledResource(String name) {
        File target = new File(getDataFolder(), name);
        if (target.isFile()) backupResource(target);
        saveResource(name, true);
        getLogger().info("已随物品目录更新重置 " + name + "，旧文件已备份。");
    }

    private void backupResource(File source) {
        File directory = new File(getDataFolder(), "backups");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建配置备份目录：" + directory);
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        File backup = new File(directory, source.getName() + "." + timestamp + ".bak");
        try {
            Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException exception) {
            throw new IllegalStateException("无法备份 " + source.getName(), exception);
        }
    }

    private YamlConfiguration loadBundledYaml(String name) {
        try (InputStream input = getResource(name)) {
            if (input == null) throw new IllegalStateException("插件包缺少 " + name);
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取插件内置 " + name, exception);
        }
    }

    private void mergeBundledConfigDefaults() {
        bundledConfigDefaults = loadBundledYaml("config.yml");

        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration persisted = YamlConfiguration.loadConfiguration(configFile);
        int added = 0;
        for (String path : bundledConfigDefaults.getKeys(true)) {
            if (bundledConfigDefaults.isConfigurationSection(path) || persisted.contains(path, true)) {
                continue;
            }
            persisted.set(path, bundledConfigDefaults.get(path));
            added++;
        }
        Map<String, String> legacyBrandDefaults = Map.of(
                "gui.catalog-title", "&0YknItems {category}图鉴 &8({page}/{pages})",
                "gui.catalog-titles.other", "&0YknItems 其他图鉴 &8({page}/{pages})",
                "messages.reload-complete", "&aYknItems 已重载，共读取 {items} 个物品和 {recipes} 个配方。",
                "messages.usage", "&e用法：/yknitems admin <items|weapon|armor> 或 /yknitems reload",
                "messages.admin-usage", "&e用法：/yknitems admin <items|weapon|armor> 或 /yknitems reload"
        );
        for (Map.Entry<String, String> entry : legacyBrandDefaults.entrySet()) {
            if (!entry.getValue().equals(persisted.getString(entry.getKey()))) continue;
            persisted.set(entry.getKey(), bundledConfigDefaults.get(entry.getKey()));
            added++;
        }
        Map<String, String> previousUsageDefaults = Map.of(
                "messages.usage", "&e用法：/yknrpg admin <items|weapon|armor> 或 /yknrpg reload",
                "messages.admin-usage", "&e用法：/yknrpg admin <items|weapon|armor> 或 /yknrpg reload"
        );
        for (Map.Entry<String, String> entry : previousUsageDefaults.entrySet()) {
            if (!entry.getValue().equals(persisted.getString(entry.getKey()))) continue;
            persisted.set(entry.getKey(), bundledConfigDefaults.get(entry.getKey()));
            added++;
        }
        for (String path : List.of(
                "gui.catalog-titles.items",
                "gui.catalog-titles.weapon",
                "gui.catalog-titles.armor",
                "gui.catalog-titles.pets")) {
            String current = persisted.getString(path);
            if (current == null) continue;
            String migrated = current;
            for (String brand : List.of("YknItems", "YknRpg")) {
                if (migrated.startsWith("&0" + brand + " ")) {
                    migrated = "&0" + migrated.substring(("&0" + brand + " ").length());
                } else if (migrated.startsWith(brand + " ")) {
                    migrated = migrated.substring((brand + " ").length());
                }
            }
            if (!migrated.equals(current)) {
                persisted.set(path, migrated);
                added++;
            }
        }

        if (added > 0) {
            try {
                persisted.save(configFile);
            } catch (IOException exception) {
                throw new IllegalStateException("无法补齐 config.yml 默认配置", exception);
            }
            reloadConfig();
            getLogger().info("已为旧版 config.yml 补充或迁移 " + added + " 个配置项。");
        }
        getConfig().setDefaults(bundledConfigDefaults);
        warnedMissingMessages.clear();
    }

    private void enableProtocolLibInputBridge() {
        if (!getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            getLogger().warning("未检测到 ProtocolLib；右键技能仅使用 Paper 事件监听。");
            return;
        }
        try {
            protocolLibInputBridge = new ProtocolLibInputBridge(this, rpgEngine);
            protocolLibInputBridge.start();
            getLogger().info("右键技能已启用 ProtocolLib 原始数据包兜底监听。");
        } catch (RuntimeException | LinkageError exception) {
            protocolLibInputBridge = null;
            getLogger().warning("ProtocolLib 右键兜底监听启用失败，将继续使用 Paper 事件："
                    + exception.getMessage());
        }
    }
}
