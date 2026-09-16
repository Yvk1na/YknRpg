package cn.yknrpg.command;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.model.ItemDefinition.CatalogKind;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class YknRpgCommand implements CommandExecutor, TabCompleter {
    private final YknRpgPlugin plugin;

    public YknRpgCommand(YknRpgPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.message("messages.admin-usage"));
            return true;
        }
        if (!sender.hasPermission("yknrpg.admin")) {
            sender.sendMessage(plugin.message("messages.no-permission"));
            return true;
        }
        if (args[0].equalsIgnoreCase("edit")) {
            if (sender instanceof Player player) plugin.itemEditGui().open(player);
            else sender.sendMessage(plugin.message("messages.player-only"));
            return true;
        }
        if (args[0].equalsIgnoreCase("admin")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.message("messages.player-only"));
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("fishing")) {
                plugin.gui().openFishingMenu(player);
                return true;
            }
            CatalogKind kind = args.length < 2 ? CatalogKind.ITEM : switch (args[1].toLowerCase(Locale.ROOT)) {
                case "items", "item" -> CatalogKind.ITEM;
                case "weapon", "weapons" -> CatalogKind.WEAPON;
                case "armor", "armour" -> CatalogKind.ARMOR;
                case "pet", "pets" -> CatalogKind.PET;
                default -> null;
            };
            if (kind == null) {
                sender.sendMessage(plugin.message("messages.admin-usage"));
                return true;
            }
            if (kind == CatalogKind.PET) {
                plugin.openPetCatalog(player);
            } else {
                plugin.gui().openCatalog(player, kind, 0);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadAll();
            sender.sendMessage(plugin.message("messages.reload-complete",
                    "items", plugin.registry().items().size(),
                    "recipes", plugin.recipes().recipeCount()));
            return true;
        }
        sender.sendMessage(plugin.message("messages.admin-usage"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("yknrpg.admin")) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("admin", "edit", "reload").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("items", "weapon", "armor", "pets", "fishing").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
