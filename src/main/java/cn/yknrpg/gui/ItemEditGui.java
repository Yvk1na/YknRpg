package cn.yknrpg.gui;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.item.ItemStatOverrides;
import cn.yknrpg.item.ItemStatOverrides.EditableStat;
import cn.yknrpg.rpg.RpgRegistry;
import cn.yknrpg.util.Text;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Holds the actual single item; templates and recipe configuration are never written. */
public final class ItemEditGui implements Listener {
    static final int SIZE = 45;
    static final int ITEM_SLOT = 13;
    static final List<Integer> BUTTON_SLOTS = List.of(20, 21, 22, 23, 24, 29, 30, 31, 32, 33);
    private static final List<Material> ICONS = List.of(Material.IRON_SWORD, Material.BLAZE_POWDER,
            Material.FLINT, Material.NETHERITE_SWORD, Material.LAPIS_LAZULI, Material.IRON_CHESTPLATE,
            Material.FEATHER, Material.ENDER_PEARL, Material.GHAST_TEAR, Material.SUGAR);

    private final YknRpgPlugin plugin;
    private final RpgRegistry registry;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public ItemEditGui(YknRpgPlugin plugin, RpgRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public void open(Player player) {
        if (!permitted(player)) return;
        Session previous = sessions.get(player.getUniqueId());
        if (previous != null) finish(previous);
        Session session = new Session(player);
        session.inventory = Bukkit.createInventory(session, SIZE, plugin.message("gui.item-edit-title"));
        ItemStack filler = icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            if (slot != ITEM_SLOT) session.inventory.setItem(slot, filler);
        }
        sessions.put(player.getUniqueId(), session);
        refreshButtons(session);
        player.openInventory(session.inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Session session)) return;
        boolean wasCancelled = event.isCancelled();
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !current(session)
                || player != session.player || !permitted(player) || session.pendingStat != null) return;
        int raw = event.getRawSlot();
        if (raw < 0) return;
        if (raw >= SIZE) {
            if (event.isShiftClick()) {
                ItemStack source = event.getCurrentItem();
                if (empty(session.item()) && !empty(source)) {
                    ItemStack one = source.clone();
                    one.setAmount(1);
                    ItemStatOverrides.stripRetiredLore(one);
                    session.inventory.setItem(ITEM_SLOT, one);
                    ItemStack remainder = source.clone();
                    remainder.setAmount(source.getAmount() - 1);
                    event.setCurrentItem(empty(remainder) ? null : remainder);
                    refreshButtons(session);
                }
            } else if (!wasCancelled && event.getAction() != InventoryAction.COLLECT_TO_CURSOR
                    && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)) {
                // Ordinary bottom-inventory clicks cannot collect GUI decorations or move items upward.
                event.setCancelled(false);
            }
            return;
        }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT
                && event.getClick() != ClickType.SHIFT_LEFT && event.getClick() != ClickType.SHIFT_RIGHT) return;
        if (raw == ITEM_SLOT) {
            ItemStack stored = session.item();
            if (event.isShiftClick()) {
                session.inventory.setItem(ITEM_SLOT, null);
                giveBack(player, stored);
            } else {
                ItemStack cursor = event.getCursor();
                if (empty(cursor)) {
                    session.inventory.setItem(ITEM_SLOT, null);
                    event.getView().setCursor(stored);
                } else if (cursor.getAmount() == 1 || empty(stored)) {
                    ItemStack one = cursor.clone();
                    one.setAmount(1);
                    ItemStatOverrides.stripRetiredLore(one);
                    session.inventory.setItem(ITEM_SLOT, one);
                    if (cursor.getAmount() == 1) event.getView().setCursor(stored);
                    else {
                        ItemStack remainder = cursor.clone();
                        remainder.setAmount(cursor.getAmount() - 1);
                        event.getView().setCursor(remainder);
                    }
                } else player.sendMessage(plugin.message("messages.item-edit-single"));
            }
            refreshButtons(session);
            return;
        }
        int index = BUTTON_SLOTS.indexOf(raw);
        if (index < 0) return;
        if (empty(session.item())) {
            player.sendMessage(plugin.message("messages.item-edit-empty"));
            return;
        }
        if (session.item().getAmount() != 1) {
            player.sendMessage(plugin.message("messages.item-edit-single"));
            return;
        }
        session.pendingStat = EditableStat.values()[index];
        Bukkit.getScheduler().runTask(plugin, () -> openSign(session));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Session)) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot < SIZE)) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof Session session
                && current(session) && session.pendingStat == null) finish(session);
    }

    private void openSign(Session session) {
        if (!current(session)) return;
        Player player = session.player;
        if (!player.isOnline() || !permitted(player) || empty(session.item())) {
            finish(session);
            return;
        }
        try {
            // Marking pending before closing is essential: the close listener must not return this item yet.
            player.closeInventory();
            Location location = player.getLocation().getBlock().getLocation();
            location.setY(Math.clamp(location.getY() - 4,
                    player.getWorld().getMinHeight(), player.getWorld().getMaxHeight() - 1));
            session.signLocation = location;
            double value = registry.itemStats(session.item()).get(session.pendingStat.stat());
            player.sendBlockChange(location, Material.OAK_SIGN.createBlockData());
            player.sendSignChange(location, List.of(
                    Component.text(ItemStatOverrides.formatValue(value, session.pendingStat.percent())),
                    Component.text("↑ 输入新数值"), Component.text(session.pendingStat.label()),
                    Component.text("留空取消")));
            player.openVirtualSign(Position.block(location), Side.FRONT);
            session.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (current(session)) {
                    finish(session);
                    if (player.isOnline()) player.sendMessage(plugin.message("messages.item-edit-returned"));
                }
            }, 2400L);
        } catch (RuntimeException exception) {
            endSign(session);
            plugin.getLogger().warning("无法打开属性编辑告示牌：" + exception.getMessage());
            player.sendMessage(plugin.message("messages.item-edit-input-failed"));
            reopen(session);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSignInput(UncheckedSignChangeEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.pendingStat == null || session.signLocation == null
                || event.getSide() != Side.FRONT
                || !session.signLocation.getWorld().equals(event.getPlayer().getWorld())
                || !Position.block(session.signLocation).equals(event.getEditedBlockPosition())) return;
        event.setCancelled(true);
        EditableStat stat = session.pendingStat;
        endSign(session);
        if (!permitted(session.player) || empty(session.item()) || session.item().getAmount() != 1) {
            finish(session);
            return;
        }
        String input = PlainTextComponentSerializer.plainText().serialize(event.lines().getFirst()).trim();
        if (!input.isEmpty()) {
            try {
                double value = ItemStatOverrides.parseValue(input);
                ItemStack edited = session.item().clone();
                ItemStatOverrides.apply(edited, stat, value);
                session.inventory.setItem(ITEM_SLOT, edited);
                session.player.sendMessage(plugin.message("messages.item-edit-saved", "stat", stat.label(),
                        "value", ItemStatOverrides.formatValue(value, stat.percent())));
            } catch (IllegalArgumentException exception) {
                session.player.sendMessage(plugin.message("messages.item-edit-invalid"));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("属性编辑未保存，原物品已保留：" + exception.getMessage());
                session.player.sendMessage(plugin.message("messages.item-edit-input-failed"));
            }
        }
        reopen(session);
    }

    private void reopen(Session session) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!current(session)) return;
            if (!session.player.isOnline() || !permitted(session.player)) {
                finish(session);
                return;
            }
            refreshButtons(session);
            session.player.openInventory(session.inventory);
        });
    }

    private void refreshButtons(Session session) {
        RpgRegistry.Stats stats = registry.itemStats(session.item());
        EditableStat[] choices = EditableStat.values();
        for (int index = 0; index < choices.length; index++) {
            EditableStat stat = choices[index];
            session.inventory.setItem(BUTTON_SLOTS.get(index), icon(ICONS.get(index),
                    Text.component("&e" + stat.label()), List.of(
                            plugin.message("gui.item-edit-value", "value",
                                    ItemStatOverrides.formatValue(stats.get(stat.stat()), stat.percent())),
                            plugin.message("gui.item-edit-hint"))));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) finish(session);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) finish(session);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        if (!event.getKeepInventory() && !empty(session.item())) {
            event.getDrops().add(session.item());
            session.inventory.setItem(ITEM_SLOT, null);
        }
        finish(session);
    }

    public void closeAll() {
        for (Session session : List.copyOf(sessions.values())) finish(session);
    }

    private void finish(Session session) {
        if (!sessions.remove(session.player.getUniqueId(), session)) return;
        endSign(session);
        ItemStack item = session.item();
        session.inventory.setItem(ITEM_SLOT, null);
        giveBack(session.player, item);
        if (session.player.getOpenInventory().getTopInventory().getHolder(false) == session) {
            session.player.closeInventory();
        }
    }

    private void endSign(Session session) {
        session.pendingStat = null;
        if (session.timeout != null) session.timeout.cancel();
        session.timeout = null;
        Location location = session.signLocation;
        session.signLocation = null;
        if (location == null || !session.player.isOnline() || !location.getWorld().equals(session.player.getWorld())
                || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        try {
            BlockState state = location.getBlock().getState();
            session.player.sendBlockChange(location, state.getBlockData());
            if (state instanceof TileState tile) session.player.sendBlockUpdate(location, tile);
        } catch (RuntimeException exception) {
            // Client-only restoration must never prevent the real item from being returned.
            plugin.getLogger().warning("恢复虚拟告示牌显示失败：" + exception.getMessage());
        }
    }

    private boolean current(Session session) {
        return sessions.get(session.player.getUniqueId()) == session;
    }

    private boolean permitted(Player player) {
        if (player.hasPermission("yknrpg.admin")) return true;
        player.sendMessage(plugin.message("messages.no-permission"));
        return false;
    }

    private void giveBack(Player player, ItemStack item) {
        if (empty(item)) return;
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final class Session implements InventoryHolder {
        final Player player;
        Inventory inventory;
        EditableStat pendingStat;
        Location signLocation;
        BukkitTask timeout;

        Session(Player player) { this.player = player; }
        ItemStack item() { return inventory.getItem(ITEM_SLOT); }
        @Override public Inventory getInventory() { return inventory; }
    }
}
