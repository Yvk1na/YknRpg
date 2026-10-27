package cn.yknrpg.gui;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.integration.MonsterDropManager;
import cn.yknrpg.integration.MonsterDropProvider;
import cn.yknrpg.util.Text;
import io.papermc.paper.event.packet.UncheckedSignChangeEvent;
import io.papermc.paper.math.Position;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The paged monster and drop editor. All mutations stay in a session until Save. */
public final class MonsterDropGui implements Listener {
    private static final int SIZE = 54;
    private static final int CONTENT = 45;
    private static final int PREVIOUS = 45;
    private static final int BACK = 46;
    private static final int SYNC = 47;
    private static final int PAGE = 48;
    private static final int SAVE = 49;
    private static final int DISCARD = 50;
    private static final int NEXT = 53;
    private static final int EDIT_ITEM_SLOT = 4;
    private static final long SESSION_TIMEOUT = 2400L;

    private final YknRpgPlugin plugin;
    private final MonsterDropManager manager;
    private final Map<UUID, DropSession> sessions = new HashMap<>();

    public MonsterDropGui(YknRpgPlugin plugin, MonsterDropManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void openMonsters(Player player, int requestedPage) {
        if (!permitted(player)) return;
        List<MonsterDropManager.MonsterView> monsters = manager.monsters();
        int pages = Math.max(1, (monsters.size() + CONTENT - 1) / CONTENT);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        MonsterListHolder holder = new MonsterListHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.message("gui.monsters-title", "page", page + 1, "pages", pages));
        holder.inventory = inventory;
        fill(inventory);
        int start = page * CONTENT;
        for (int index = start; index < Math.min(monsters.size(), start + CONTENT); index++) {
            MonsterDropManager.MonsterView view = monsters.get(index);
            holder.keys.put(index - start, view.key());
            inventory.setItem(index - start, monsterIcon(view));
        }
        if (page > 0) inventory.setItem(PREVIOUS, button(Material.ARROW, "gui.monsters-previous"));
        inventory.setItem(PAGE, pageIndicator(page + 1, pages));
        inventory.setItem(50, button(Material.BARRIER, "gui.close"));
        if (page + 1 < pages) inventory.setItem(NEXT, button(Material.ARROW, "gui.monsters-next"));
        player.openInventory(inventory);
    }

    public void openDrops(Player player, String monsterKey, int requestedPage) {
        if (!permitted(player)) return;
        MonsterDropManager.MonsterView monster = manager.monster(monsterKey).orElse(null);
        if (monster == null) return;
        DropSession session = sessions.computeIfAbsent(player.getUniqueId(), ignored ->
                new DropSession(player, monsterKey, manager.drops(monsterKey)));
        if (!session.monsterKey.equals(monsterKey)) {
            finishSession(session, false);
            session = new DropSession(player, monsterKey, manager.drops(monsterKey));
            sessions.put(player.getUniqueId(), session);
        }
        session.page = requestedPage;
        scheduleTimeout(session);
        List<MonsterDropProvider.Drop> drops = session.working;
        int pages = Math.max(1, (drops.size() + CONTENT - 1) / CONTENT);
        session.page = Math.max(0, Math.min(session.page, pages - 1));
        DropListHolder holder = new DropListHolder(session);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                plugin.message("gui.monster-drops-title", "monster", monster.displayName(),
                        "page", session.page + 1, "pages", pages));
        holder.inventory = inventory;
        fill(inventory);
        int start = session.page * CONTENT;
        for (int index = start; index < Math.min(drops.size(), start + CONTENT); index++) {
            holder.indexes.put(index - start, index);
            inventory.setItem(index - start, dropIcon(drops.get(index)));
        }
        if (session.page > 0) inventory.setItem(PREVIOUS, button(Material.ARROW, "gui.monsters-previous"));
        inventory.setItem(BACK, button(Material.ARROW, "gui.monster-drops-back"));
        inventory.setItem(SYNC, button(Material.COMPASS, "gui.monster-drops-sync"));
        inventory.setItem(PAGE, pageIndicator(session.page + 1, pages));
        inventory.setItem(SAVE, button(Material.EMERALD, "gui.monster-drops-save"));
        inventory.setItem(DISCARD, button(Material.RED_DYE, "gui.monster-drops-discard"));
        inventory.setItem(51, button(Material.CHEST, "gui.monster-drops-add"));
        if (session.page + 1 < pages) inventory.setItem(NEXT, button(Material.ARROW, "gui.monsters-next"));
        player.openInventory(inventory);
    }

    private void openEditor(Player player, DropSession session, int index, boolean newEntry) {
        MonsterDropProvider.Drop source = newEntry
                ? new MonsterDropProvider.Drop("new", null, "YknRpg 自定义掉落", 100.0, 1, 1)
                : session.working.get(index);
        DropEditorHolder holder = new DropEditorHolder(session, index, newEntry, source);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                plugin.message("gui.monster-drop-editor-title"));
        holder.inventory = inventory;
        fill(inventory);
        if (source.itemDrop()) inventory.setItem(EDIT_ITEM_SLOT, source.item().clone());
        inventory.setItem(10, button(Material.PAPER, "gui.monster-drop-chance"));
        inventory.setItem(12, button(source.itemDrop() ? Material.CHEST : Material.BARRIER,
                source.itemDrop() ? "gui.monster-drop-range" : "gui.monster-drop-range-disabled"));
        inventory.setItem(14, button(Material.EMERALD, "gui.monster-drop-save"));
        inventory.setItem(16, button(Material.RED_DYE, "gui.monster-drop-discard"));
        if (!newEntry) inventory.setItem(22, button(Material.LAVA_BUCKET, "gui.monster-drop-delete"));
        holder.item = source.itemDrop() ? source.item().clone() : null;
        session.editor = holder;
        scheduleTimeout(session);
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (holder instanceof MonsterListHolder list) {
            event.setCancelled(true);
            if (!permitted(player)) return;
            int slot = event.getRawSlot();
            if (slot == PREVIOUS && list.page > 0) openMonsters(player, list.page - 1);
            else if (slot == NEXT) openMonsters(player, list.page + 1);
            else if (slot == 50) player.closeInventory();
            else if (slot >= 0 && slot < CONTENT) {
                String key = list.keys.get(slot);
                if (key != null) openDrops(player, key, 0);
            }
            return;
        }
        if (holder instanceof DropListHolder list) {
            event.setCancelled(true);
            if (!permitted(player)) return;
            DropSession session = list.session;
            int slot = event.getRawSlot();
            list.navigating = true;
            if (slot == PREVIOUS && session.page > 0) openDrops(player, session.monsterKey, session.page - 1);
            else if (slot == NEXT) openDrops(player, session.monsterKey, session.page + 1);
            else if (slot == BACK || slot == DISCARD) {
                finishSession(session, false);
                openMonsters(player, 0);
            } else if (slot == SYNC) {
                syncNative(session);
                openDrops(player, session.monsterKey, session.page);
            } else if (slot == SAVE) {
                manager.save(session.monsterKey, session.working);
                player.sendMessage(plugin.message("messages.monster-drops-saved"));
                finishSession(session, false);
                openDrops(player, list.session.monsterKey, 0);
            } else if (slot == 51) {
                openEditor(player, session, -1, true);
            } else if (slot >= 0 && slot < CONTENT) {
                Integer index = list.indexes.get(slot);
                if (index != null) openEditor(player, session, index, false);
            }
            return;
        }
        if (!(holder instanceof DropEditorHolder editor)) return;
        event.setCancelled(true);
        if (!permitted(player)) return;
        int slot = event.getRawSlot();
        if (slot >= event.getView().getTopInventory().getSize()) {
            if (editor.newEntry && event.isShiftClick() && !empty(editor.item)) {
                player.sendMessage(plugin.message("messages.monster-drop-item-filled"));
            }
            return;
        }
        if (slot == EDIT_ITEM_SLOT && editor.newEntry) {
            moveInputItem(event, editor);
            return;
        }
        if (slot == 10) {
            editor.pending = Pending.CHANCE;
            Bukkit.getScheduler().runTask(plugin, () -> openSign(editor));
        } else if (slot == 12 && editor.item != null) {
            editor.pending = Pending.UPPER;
            Bukkit.getScheduler().runTask(plugin, () -> openSign(editor));
        } else if (slot == 14) {
            saveEditor(player, editor);
        } else if (slot == 16) {
            discardEditor(player, editor);
        } else if (slot == 22 && !editor.newEntry) {
            editor.session.working.remove(editor.index);
            finishEditor(editor);
            openDrops(player, editor.session.monsterKey, editor.session.page);
        }
    }

    private void moveInputItem(InventoryClickEvent event, DropEditorHolder editor) {
        ItemStack cursor = event.getCursor();
        ItemStack current = editor.inventory.getItem(EDIT_ITEM_SLOT);
        if (empty(cursor)) {
            editor.inventory.setItem(EDIT_ITEM_SLOT, null);
            editor.item = null;
            event.getView().setCursor(current);
        } else {
            ItemStack one = cursor.clone();
            one.setAmount(1);
            editor.inventory.setItem(EDIT_ITEM_SLOT, one);
            editor.item = one.clone();
            if (cursor.getAmount() == 1) event.getView().setCursor(current);
            else {
                ItemStack remainder = cursor.clone();
                remainder.setAmount(cursor.getAmount() - 1);
                event.getView().setCursor(remainder);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (!(holder instanceof MonsterListHolder) && !(holder instanceof DropListHolder)
                && !(holder instanceof DropEditorHolder)) return;
        if (holder instanceof DropEditorHolder editor && editor.newEntry
                && event.getRawSlots().stream().allMatch(slot -> slot == EDIT_ITEM_SLOT)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSign(UncheckedSignChangeEvent event) {
        DropEditorHolder editor = findEditor(event.getPlayer());
        if (editor == null || editor.pending == null || editor.signLocation == null
                || event.getSide() != Side.FRONT
                || !editor.signLocation.getWorld().equals(event.getPlayer().getWorld())
                || !Position.block(editor.signLocation).equals(event.getEditedBlockPosition())) return;
        event.setCancelled(true);
        Pending pending = editor.pending;
        endSign(editor);
        String input = PlainTextComponentSerializer.plainText().serialize(event.lines().getFirst()).trim();
        try {
            if (pending == Pending.CHANCE) {
                double chance = Double.parseDouble(input.replace("%", ""));
                if (!Double.isFinite(chance) || chance < 0 || chance > 100) throw new IllegalArgumentException();
                editor.chance = chance;
                reopenEditor(editor);
            } else if (pending == Pending.UPPER) {
                editor.upper = parseAmount(input);
                editor.pending = Pending.LOWER;
                Bukkit.getScheduler().runTask(plugin, () -> openSign(editor));
            } else {
                int lower = parseAmount(input);
                if (lower > editor.upper) throw new IllegalArgumentException();
                editor.minimum = lower;
                editor.maximum = editor.upper;
                reopenEditor(editor);
            }
        } catch (RuntimeException exception) {
            editor.pending = null;
            event.getPlayer().sendMessage(plugin.message("messages.monster-drop-invalid"));
            reopenEditor(editor);
        }
    }

    private int parseAmount(String input) {
        int value = Integer.parseInt(input);
        if (value < 1 || value > 1_000_000) throw new IllegalArgumentException();
        return value;
    }

    private DropEditorHolder findEditor(Player player) {
        DropSession session = sessions.get(player.getUniqueId());
        return session == null ? null : session.editor;
    }

    private void openSign(DropEditorHolder editor) {
        if (!current(editor.session) || !editor.player().isOnline()) return;
        try {
            Player player = editor.player();
            player.closeInventory();
            Location location = player.getLocation().getBlock().getLocation();
            location.setY(Math.clamp(location.getY() - 4,
                    player.getWorld().getMinHeight(), player.getWorld().getMaxHeight() - 1));
            editor.signLocation = location;
            player.sendBlockChange(location, Material.OAK_SIGN.createBlockData());
            String prompt = editor.pending == Pending.CHANCE ? "输入 0-100 的概率"
                    : editor.pending == Pending.UPPER ? "输入数量上限" : "输入数量下限";
            player.sendSignChange(location, List.of(Component.text("当前：" + Text.number(editor.currentValue())),
                    Component.text(prompt), Component.text("留空取消"), Component.text("完成后确认")));
            player.openVirtualSign(Position.block(location), Side.FRONT);
            editor.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (current(editor.session)) {
                    editor.pending = null;
                    finishSession(editor.session, true);
                    if (player.isOnline()) player.sendMessage(plugin.message("messages.monster-drop-timeout"));
                }
            }, SESSION_TIMEOUT);
        } catch (RuntimeException exception) {
            endSign(editor);
            editor.pending = null;
            editor.player().sendMessage(plugin.message("messages.monster-drop-input-failed"));
            reopenEditor(editor);
        }
    }

    private void saveEditor(Player player, DropEditorHolder editor) {
        if (editor.newEntry && empty(editor.item)) {
            player.sendMessage(plugin.message("messages.monster-drop-empty"));
            return;
        }
        MonsterDropProvider.Drop old = editor.newEntry ? null : editor.session.working.get(editor.index);
        String key = old == null ? "new" : old.key();
        String description = old == null ? "YknRpg 自定义掉落" : old.description();
        MonsterDropProvider.Drop updated = new MonsterDropProvider.Drop(key, editor.item, description,
                editor.chance, editor.minimum, editor.maximum);
        if (editor.newEntry) editor.session.working.add(manager.customDrop(updated.item(), updated.chance(),
                updated.minimum(), updated.maximum()));
        else editor.session.working.set(editor.index, updated);
        editor.session.working = new ArrayList<>(manager.normalize(editor.session.working));
        finishEditor(editor);
        openDrops(player, editor.session.monsterKey, editor.session.page);
    }

    private void discardEditor(Player player, DropEditorHolder editor) {
        if (editor.newEntry && !empty(editor.item)) giveBack(player, editor.item);
        finishEditor(editor);
        openDrops(player, editor.session.monsterKey, editor.session.page);
    }

    private void reopenEditor(DropEditorHolder editor) {
        editor.pending = null;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!current(editor.session)) return;
            editor.session.editor = editor;
            editor.player().openInventory(editor.inventory);
        });
    }

    private void syncNative(DropSession session) {
        List<MonsterDropProvider.Drop> custom = session.working.stream().filter(manager::isCustom).toList();
        List<MonsterDropProvider.Drop> result = new ArrayList<>(manager.nativeDrops(session.monsterKey));
        result.addAll(custom);
        session.working = new ArrayList<>(manager.normalize(result));
    }

    private void scheduleTimeout(DropSession session) {
        if (session.timeout != null) session.timeout.cancel();
        session.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!current(session)) return;
            DropEditorHolder editor = session.editor;
            if (editor != null && editor.newEntry && !empty(editor.item)) giveBack(session.player, editor.item);
            finishSession(session, true);
            if (session.player.isOnline()) session.player.sendMessage(plugin.message("messages.monster-drop-timeout"));
        }, SESSION_TIMEOUT);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder instanceof DropEditorHolder editor) {
            if (editor.navigating || editor.pending != null) return;
            discardEditor(player, editor);
        } else if (holder instanceof DropListHolder list && !list.navigating) {
            finishSession(list.session, false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        DropSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) finishSession(session, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        DropSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) finishSession(session, true);
    }

    public void closeAll() {
        for (DropSession session : List.copyOf(sessions.values())) finishSession(session, true);
    }

    private void finishEditor(DropEditorHolder editor) {
        editor.navigating = true;
        editor.pending = null;
        endSign(editor);
        editor.session.editor = null;
    }

    private void finishSession(DropSession session, boolean close) {
        if (!sessions.remove(session.player.getUniqueId(), session)) return;
        if (session.timeout != null) session.timeout.cancel();
        if (session.editor != null) {
            DropEditorHolder editor = session.editor;
            editor.navigating = true;
            endSign(editor);
            if (editor.newEntry && !empty(editor.item)) giveBack(session.player, editor.item);
        }
        if (close && session.player.isOnline()) session.player.closeInventory();
    }

    private boolean current(DropSession session) {
        return sessions.get(session.player.getUniqueId()) == session;
    }

    private void endSign(DropEditorHolder editor) {
        if (editor.timeout != null) editor.timeout.cancel();
        editor.timeout = null;
        Location location = editor.signLocation;
        editor.signLocation = null;
        if (location == null || !editor.player().isOnline()
                || !location.getWorld().equals(editor.player().getWorld())) return;
        try {
            BlockState state = location.getBlock().getState();
            editor.player().sendBlockChange(location, state.getBlockData());
            if (state instanceof TileState tile) editor.player().sendBlockUpdate(location, tile);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean permitted(Player player) {
        if (player.hasPermission("yknrpg.admin.monsters") || player.hasPermission("yknrpg.admin")) return true;
        player.sendMessage(plugin.message("messages.no-permission"));
        return false;
    }

    private MonsterDropProvider.Drop dropAt(DropSession session, int index) {
        return index >= 0 && index < session.working.size() ? session.working.get(index) : null;
    }

    private ItemStack monsterIcon(MonsterDropManager.MonsterView view) {
        ItemStack icon = view.monster().icon() == null ? new ItemStack(Material.PLAYER_HEAD) : view.monster().icon().clone();
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(Text.component("&b" + view.displayName()));
        meta.lore(List.of(Text.component("&7ID：&f" + view.id()),
                Text.component("&7掉落物：&f" + manager.drops(view.key()).size()),
                Text.component("&e点击查看掉落物")));
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack dropIcon(MonsterDropProvider.Drop drop) {
        ItemStack icon = drop.itemDrop() ? drop.item().clone() : new ItemStack(Material.PAPER);
        icon.setAmount(1);
        ItemMeta meta = icon.getItemMeta();
        String name = drop.itemDrop() && meta.displayName() != null ?
                PlainTextComponentSerializer.plainText().serialize(meta.displayName()) : drop.description();
        meta.displayName(Text.component("&f" + name));
        List<Component> lore = new ArrayList<>();
        lore.add(Text.component("&7来源：&f" + (manager.isCustom(drop) ? "YknRpg 自定义" : "原生掉落")));
        lore.add(Text.component("&7概率：&f" + Text.number(drop.chance()) + "%"));
        if (drop.itemDrop()) lore.add(Text.component("&7数量：&f" + drop.minimum() + " - " + drop.maximum()));
        else lore.add(Text.component("&8非物品掉落，可编辑概率或删除"));
        lore.add(Text.component("&e点击编辑"));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack button(Material material, String path) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.message(path).decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack pageIndicator(int page, int pages) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.message("gui.page-indicator", "page", page, "pages", pages)
                .decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }

    private void fill(Inventory inventory) {
        ItemStack filler = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.setHideTooltip(true);
        filler.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
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

    private final class DropSession {
        private final Player player;
        private final String monsterKey;
        private final List<MonsterDropProvider.Drop> original;
        private List<MonsterDropProvider.Drop> working;
        private int page;
        private BukkitTask timeout;
        private DropEditorHolder editor;

        private DropSession(Player player, String monsterKey, List<MonsterDropProvider.Drop> drops) {
            this.player = player;
            this.monsterKey = monsterKey;
            this.original = new ArrayList<>(manager.normalize(drops));
            this.working = new ArrayList<>(manager.normalize(drops));
        }
    }

    private final class MonsterListHolder implements InventoryHolder {
        private final int page;
        private final Map<Integer, String> keys = new HashMap<>();
        private Inventory inventory;

        private MonsterListHolder(int page) { this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final class DropListHolder implements InventoryHolder {
        private final DropSession session;
        private final Map<Integer, Integer> indexes = new HashMap<>();
        private Inventory inventory;
        private boolean navigating;

        private DropListHolder(DropSession session) { this.session = session; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final class DropEditorHolder implements InventoryHolder {
        private final DropSession session;
        private final int index;
        private final boolean newEntry;
        private final String originalKey;
        private Inventory inventory;
        private ItemStack item;
        private double chance;
        private int minimum;
        private int maximum;
        private int upper;
        private Pending pending;
        private Location signLocation;
        private BukkitTask timeout;
        private boolean navigating;

        private DropEditorHolder(DropSession session, int index, boolean newEntry, MonsterDropProvider.Drop source) {
            this.session = session;
            this.index = index;
            this.newEntry = newEntry;
            this.originalKey = source.key();
            this.chance = source.chance();
            this.minimum = source.minimum();
            this.maximum = source.maximum();
            this.upper = source.maximum();
        }

        private Player player() { return session.player; }
        private double currentValue() {
            return pending == Pending.CHANCE ? chance : pending == Pending.UPPER ? maximum : minimum;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private enum Pending { CHANCE, UPPER, LOWER }
}
