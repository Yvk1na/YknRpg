package cn.yknrpg.command;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YknRpgCommandTest {
    @Test
    void miningTabCompletionUsesTheExistingAdminPermissionAndAlias() {
        YknRpgCommand command = new YknRpgCommand(null);
        assertEquals(List.of("mining"), command.onTabComplete(sender(true), null, "yknrpg",
                new String[]{"admin", "mi"}));
        assertEquals(List.of("mining"), command.onTabComplete(sender(true), null, "yknitems",
                new String[]{"ADMIN", "MI"}));
        assertEquals(List.of("items", "weapon", "armor", "pets", "fishing", "mining"),
                command.onTabComplete(sender(true), null, "yknrpg", new String[]{"admin", ""}));
        assertEquals(List.of(), command.onTabComplete(sender(false), null, "yknrpg",
                new String[]{"admin", "mi"}));
        assertEquals(List.of(), command.onTabComplete(sender(true), null, "yknrpg",
                new String[]{"admin", "mining", ""}));
    }

    private CommandSender sender(boolean admin) {
        return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission") && args[0].equals("yknrpg.admin")) return admin;
                    throw new AssertionError(method.getName());
                });
    }
}
