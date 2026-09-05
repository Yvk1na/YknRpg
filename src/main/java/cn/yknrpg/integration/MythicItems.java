package cn.yknrpg.integration;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MythicMobs 运行时桥接。使用反射避免把付费插件 JAR 打进源码仓库或发行包。
 */
public final class MythicItems {
    private static final NamespacedKey MYTHIC_TYPE_KEY = NamespacedKey.fromString("mythicmobs:type");

    private final JavaPlugin plugin;
    private Object itemManager;
    private Method getItemNames;
    private Method getItem;
    private Method isMythicItem;
    private Method getMythicTypeFromItem;
    private Method adaptItemStack;

    public MythicItems(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void connect() {
        Plugin mythicMobs = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mythicMobs == null || !mythicMobs.isEnabled()) {
            throw new IllegalStateException("MythicMobs 未安装或尚未启用");
        }
        try {
            ClassLoader loader = mythicMobs.getClass().getClassLoader();
            Class<?> mythicBukkitType = Class.forName("io.lumine.mythic.bukkit.MythicBukkit", true, loader);
            Object mythicBukkit = mythicBukkitType.getMethod("inst").invoke(null);
            itemManager = mythicBukkitType.getMethod("getItemManager").invoke(mythicBukkit);

            Class<?> managerType = Class.forName("io.lumine.mythic.api.items.ItemManager", true, loader);
            getItemNames = managerType.getMethod("getItemNames");
            getItem = managerType.getMethod("getItem", String.class);
            isMythicItem = managerType.getMethod("isMythicItem", ItemStack.class);
            getMythicTypeFromItem = managerType.getMethod("getMythicTypeFromItem", ItemStack.class);

            Class<?> abstractStackType = Class.forName(
                    "io.lumine.mythic.api.adapters.AbstractItemStack", true, loader);
            Class<?> adapterType = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, loader);
            adaptItemStack = adapterType.getMethod("adapt", abstractStackType);
        } catch (ReflectiveOperationException exception) {
            throw incompatible(exception);
        }
    }

    public List<String> itemIds() {
        requireConnected();
        try {
            Object result = getItemNames.invoke(itemManager);
            if (!(result instanceof Collection<?> collection)) {
                throw new IllegalStateException("MythicMobs ItemManager 返回了未知物品集合");
            }
            List<String> ids = new ArrayList<>(collection.size());
            for (Object value : collection) {
                if (value instanceof String id) ids.add(id);
            }
            return List.copyOf(ids);
        } catch (ReflectiveOperationException exception) {
            throw incompatible(exception);
        }
    }

    public boolean exists(String itemId) {
        return findItem(itemId).isPresent();
    }

    public Optional<String> group(String itemId) {
        Optional<Object> mythicItem = findItem(itemId);
        if (mythicItem.isEmpty()) return Optional.empty();
        try {
            Object value = mythicItem.get().getClass().getMethod("getGroup").invoke(mythicItem.get());
            return value instanceof String group && !group.isBlank()
                    ? Optional.of(group)
                    : Optional.empty();
        } catch (ReflectiveOperationException exception) {
            throw incompatible(exception);
        }
    }

    public ItemStack create(String itemId) {
        Object mythicItem = findItem(itemId)
                .orElseThrow(() -> new IllegalArgumentException("未知 MythicMobs 物品：" + itemId));
        try {
            Object abstractStack = mythicItem.getClass()
                    .getMethod("generateItemStack", int.class)
                    .invoke(mythicItem, 1);
            Object adapted = adaptItemStack.invoke(null, abstractStack);
            if (!(adapted instanceof ItemStack stack)) {
                throw new IllegalStateException("MythicMobs 未能生成 Bukkit ItemStack：" + itemId);
            }
            ItemStack result = stack.clone();
            result.setAmount(1);
            return result;
        } catch (ReflectiveOperationException exception) {
            throw incompatible(exception);
        }
    }

    public Optional<String> itemId(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return Optional.empty();
        if (MYTHIC_TYPE_KEY != null && stack.hasItemMeta()) {
            String taggedId = stack.getItemMeta().getPersistentDataContainer()
                    .get(MYTHIC_TYPE_KEY, PersistentDataType.STRING);
            if (taggedId != null && !taggedId.isBlank()) return Optional.of(taggedId.trim());
        }
        requireConnected();
        try {
            if (!Boolean.TRUE.equals(isMythicItem.invoke(itemManager, stack))) return Optional.empty();
            Object result = getMythicTypeFromItem.invoke(itemManager, stack);
            return result instanceof String id && !id.isBlank() ? Optional.of(id) : Optional.empty();
        } catch (ReflectiveOperationException exception) {
            throw incompatible(exception);
        }
    }

    public void migrateWisdomLore(ItemStack stack, String itemId) {
        if (stack == null || !stack.hasItemMeta()) return;
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = meta.lore();
        if (lore == null || lore.stream().noneMatch(this::isLegacyIntelligenceLine)) return;

        ItemMeta currentDefinition = create(itemId).getItemMeta();
        List<Component> currentLore = currentDefinition.lore();
        if (currentLore == null) return;
        Component wisdomLine = currentLore.stream()
                .filter(this::isWisdomLine)
                .findFirst()
                .orElse(null);
        if (wisdomLine == null) return;

        List<Component> migrated = new ArrayList<>(lore.size());
        for (Component line : lore) {
            migrated.add(isLegacyIntelligenceLine(line) ? wisdomLine : line);
        }
        meta.lore(migrated);
        stack.setItemMeta(meta);
    }

    private boolean isLegacyIntelligenceLine(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line).contains("智力：");
    }

    private boolean isWisdomLine(Component line) {
        return PlainTextComponentSerializer.plainText().serialize(line).contains("智慧：");
    }

    private Optional<Object> findItem(String itemId) {
        requireConnected();
        try {
            Object result = getItem.invoke(itemManager, itemId);
            if (!(result instanceof Optional<?> optional)) {
                throw new IllegalStateException("MythicMobs ItemManager#getItem 返回值不兼容");
            }
            return optional.map(value -> value);
        } catch (ReflectiveOperationException exception) {
            throw incompatible(exception);
        }
    }

    private void requireConnected() {
        if (itemManager == null) throw new IllegalStateException("尚未连接 MythicMobs ItemManager");
    }

    private IllegalStateException incompatible(ReflectiveOperationException exception) {
        Throwable cause = exception instanceof InvocationTargetException invocation
                && invocation.getTargetException() != null
                ? invocation.getTargetException()
                : exception;
        plugin.getLogger().severe("MythicMobs API 调用失败：" + cause.getMessage());
        return new IllegalStateException("当前 MythicMobs 版本与 YknRpg 不兼容", cause);
    }
}
