package cn.yknitems.command;

import cn.yknitems.YknItemsPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class YknItemsCommand implements CommandExecutor, TabCompleter {
    private final YknItemsPlugin plugin;

    public YknItemsCommand(YknItemsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.message("messages.usage"));
            return true;
        }
        if (!sender.hasPermission("yknitems.admin")) {
            sender.sendMessage(plugin.message("messages.no-permission"));
            return true;
        }
        if (args[0].equalsIgnoreCase("admin")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.message("messages.player-only"));
                return true;
            }
            plugin.gui().openCatalog(player, 0);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadAll();
            sender.sendMessage(plugin.message("messages.reload-complete",
                    "items", plugin.registry().items().size(),
                    "recipes", plugin.recipes().recipeCount()));
            return true;
        }
        sender.sendMessage(plugin.message("messages.usage"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1 || !sender.hasPermission("yknitems.admin")) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("admin", "reload").stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
