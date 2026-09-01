package cn.yknitems;

import cn.yknitems.combat.CombatListener;
import cn.yknitems.command.YknItemsCommand;
import cn.yknitems.gui.EquipmentGui;
import cn.yknitems.hook.ProgressionHook;
import cn.yknitems.item.CustomItemFactory;
import cn.yknitems.recipe.RecipeRegistrar;
import cn.yknitems.registry.ItemRegistry;
import cn.yknitems.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class YknItemsPlugin extends JavaPlugin {
    private ItemRegistry registry;
    private CustomItemFactory itemFactory;
    private ProgressionHook progression;
    private RecipeRegistrar recipes;
    private EquipmentGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveBundledResource("equipment.yml");
        saveBundledResource("recipes.yml");

        registry = new ItemRegistry(this);
        registry.reload();
        itemFactory = new CustomItemFactory(this, registry);
        progression = ProgressionHook.create(this);
        recipes = new RecipeRegistrar(this, registry, itemFactory);
        recipes.reload();
        gui = new EquipmentGui(this, registry, itemFactory);

        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(
                new CombatListener(this, registry, itemFactory, progression), this);

        PluginCommand command = getCommand("yknitems");
        if (command == null) {
            throw new IllegalStateException("plugin.yml 缺少 yknitems 指令定义");
        }
        YknItemsCommand handler = new YknItemsCommand(this);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        getLogger().info("已载入 " + registry.items().size() + " 个物品与 " + recipes.recipeCount() + " 个配方。");
        if (!getServer().getPluginManager().isPluginEnabled("CraftEngine")) {
            getLogger().warning("未检测到 CraftEngine；物品功能可用，但需安装随插件构建的 CraftEngine 包才能显示自定义模型。 ");
        }
    }

    @Override
    public void onDisable() {
        if (recipes != null) recipes.unregister();
    }

    public void reloadAll() {
        reloadConfig();
        registry.reload();
        progression = ProgressionHook.create(this);
        recipes.reload();
        getLogger().info("配置已重载：" + registry.items().size() + " 个物品，" + recipes.recipeCount() + " 个配方。");
    }

    public Component message(String path, Object... replacements) {
        return Text.component(raw(path, replacements));
    }

    public String raw(String path, Object... replacements) {
        String value = getConfig().getString(path, path);
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

    private void saveBundledResource(String name) {
        File target = new File(getDataFolder(), name);
        if (!target.isFile()) saveResource(name, false);
    }
}
