package cn.yknrpg.gui;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.item.CustomItemFactory;
import cn.yknrpg.model.IngredientSpec;
import cn.yknrpg.model.ItemDefinition;
import cn.yknrpg.model.ItemDefinition.CatalogKind;
import cn.yknrpg.registry.ItemRegistry;
import cn.yknrpg.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EquipmentGui implements Listener {
    private static final int CATALOG_SIZE = 54;
    private static final int ITEMS_PER_PAGE = 45;
    private static final int ARMOR_ITEMS_PER_PAGE = 44;
    private static final int PREVIOUS_SLOT = 45;
    private static final int FISHING_BACK_SLOT = 46;
    private static final int PAGE_SLOT = 48;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final int RECIPE_BACK_SLOT = 45;
    private static final int RECIPE_CLEAR_SLOT = 46;
    private static final int RECIPE_SAVE_SLOT = 47;
    private static final int RECIPE_HELP_SLOT = 48;
    private static final int RECIPE_CLOSE_SLOT = 49;
    private static final int RECIPE_RESET_SLOT = 53;
    private static final int RECIPE_TABLE_SLOT = 14;
    private static final int RECIPE_ARROW_SLOT = 23;
    private static final int RECIPE_OUTPUT_SLOT = 25;
    private static final int[] RECIPE_INPUT_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};

    private final YknRpgPlugin plugin;
    private final ItemRegistry registry;
    private final CustomItemFactory factory;

    public EquipmentGui(YknRpgPlugin plugin, ItemRegistry registry, CustomItemFactory factory) {
        this.plugin = plugin;
        this.registry = registry;
        this.factory = factory;
    }

    public void openCatalog(Player player, int requestedPage) {
        openCatalog(player, CatalogKind.ITEM, requestedPage);
    }

    public void openCatalog(Player player, CatalogKind kind, int requestedPage) {
        if (!checkPermission(player)) return;
        if (kind.isFishing() && !plugin.refreshFishingCatalog(player)) return;
        List<ItemDefinition> definitions = registry.items(kind);
        int itemsPerPage = kind == CatalogKind.ARMOR || kind == CatalogKind.FISHING_ARMOR
                ? ARMOR_ITEMS_PER_PAGE : ITEMS_PER_PAGE;
        int pageCount = Math.max(1, (definitions.size() + itemsPerPage - 1) / itemsPerPage);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        CatalogHolder holder = new CatalogHolder(kind, page);
        Component title = plugin.message(catalogTitlePath(kind),
                "page", page + 1, "pages", pageCount);
        if (kind != CatalogKind.OTHER) {
            title = title.append(Component.space()).append(plugin.message("gui.catalog-credit"));
        }
        Inventory inventory = Bukkit.createInventory(holder, CATALOG_SIZE, title);
        holder.bind(inventory);
        fill(inventory);

        int start = page * itemsPerPage;
        int end = Math.min(definitions.size(), start + itemsPerPage);
        for (int index = start; index < end; index++) {
            int slot = index - start;
            ItemDefinition definition = definitions.get(index);
            holder.items.put(slot, definition.id());
            inventory.setItem(slot, catalogDisplay(definition));
        }
        if (page > 0) inventory.setItem(PREVIOUS_SLOT, button(Material.ARROW, "gui.previous-page"));
        if (kind.isFishing()) inventory.setItem(FISHING_BACK_SLOT, button(Material.ARROW, "gui.fishing-back"));
        inventory.setItem(PAGE_SLOT, pageIndicator(page + 1, pageCount));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "gui.close"));
        if (page + 1 < pageCount) inventory.setItem(NEXT_SLOT, button(Material.ARROW, "gui.next-page"));
        player.openInventory(inventory);
    }

    public void openFishingMenu(Player player) {
        if (!checkPermission(player) || !plugin.refreshFishingCatalog(player)) return;
        FishingMenuHolder holder = new FishingMenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, 27, plugin.message("gui.fishing-title"));
        holder.inventory = inventory;
        fill(inventory);
        inventory.setItem(11, button(Material.COD, "gui.fishing-buttons.catch"));
        inventory.setItem(13, button(Material.FISHING_ROD, "gui.fishing-buttons.rod"));
        inventory.setItem(15, button(Material.LEATHER_CHESTPLATE, "gui.fishing-buttons.armor"));
        player.openInventory(inventory);
    }

    private void openRecipe(Player player, String itemId, CatalogKind returnKind, int returnPage) {
        ItemDefinition definition = registry.item(itemId).orElse(null);
        if (definition == null) return;
        RecipeHolder holder = new RecipeHolder(itemId, returnKind, returnPage);
        Inventory inventory = Bukkit.createInventory(holder, CATALOG_SIZE,
                plugin.message("gui.recipe-title", "item", factory.displayName(definition)));
        holder.bind(inventory);
        fill(inventory);

        List<IngredientSpec> recipe = definition.recipe();
        for (int index = 0; index < Math.min(9, recipe.size()); index++) {
            inventory.setItem(RECIPE_INPUT_SLOTS[index], null);
            IngredientSpec ingredient = recipe.get(index);
            if (!ingredient.isEmpty()) inventory.setItem(RECIPE_INPUT_SLOTS[index], factory.ingredient(ingredient));
        }
        inventory.setItem(RECIPE_TABLE_SLOT, factory.guiIcon(Material.CRAFTING_TABLE,
                "yknrpg:gui_crafting_table", plugin.raw("gui.crafting-table")));
        inventory.setItem(RECIPE_ARROW_SLOT, factory.guiIcon(Material.SPECTRAL_ARROW,
                "yknrpg:gui_arrow", plugin.raw("gui.recipe-arrow")));
        inventory.setItem(RECIPE_OUTPUT_SLOT, factory.create(definition));
        inventory.setItem(RECIPE_BACK_SLOT, button(Material.ARROW, "gui.back"));
        inventory.setItem(RECIPE_CLEAR_SLOT, button(Material.RED_DYE, "gui.recipe-clear"));
        inventory.setItem(RECIPE_SAVE_SLOT, button(Material.EMERALD, "gui.recipe-save"));
        inventory.setItem(RECIPE_HELP_SLOT, helpButton());
        inventory.setItem(RECIPE_CLOSE_SLOT, button(Material.BARRIER, "gui.close"));
        inventory.setItem(RECIPE_RESET_SLOT, button(Material.RECOVERY_COMPASS, "gui.recipe-reset"));
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder topHolder = event.getView().getTopInventory().getHolder(false);
        if (!(topHolder instanceof CatalogHolder) && !(topHolder instanceof RecipeHolder)
                && !(topHolder instanceof FishingMenuHolder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!checkPermission(player)) {
            event.setCancelled(true);
            return;
        }
        int slot = event.getRawSlot();

        if (topHolder instanceof FishingMenuHolder) {
            event.setCancelled(true);
            CatalogKind kind = switch (slot) {
                case 11 -> CatalogKind.FISHING_CATCH;
                case 13 -> CatalogKind.FISHING_ROD;
                case 15 -> CatalogKind.FISHING_ARMOR;
                default -> null;
            };
            if (kind != null) openCatalog(player, kind, 0);
            return;
        }

        if (topHolder instanceof CatalogHolder catalog) {
            event.setCancelled(true);
            if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
            if (slot == CLOSE_SLOT) {
                player.closeInventory();
                return;
            }
            if (slot == FISHING_BACK_SLOT && catalog.kind.isFishing()) {
                openFishingMenu(player);
                return;
            }
            if (slot == PREVIOUS_SLOT && catalog.page > 0) {
                openCatalog(player, catalog.kind, catalog.page - 1);
                return;
            }
            if (slot == NEXT_SLOT) {
                openCatalog(player, catalog.kind, catalog.page + 1);
                return;
            }
            String itemId = catalog.items.get(slot);
            if (itemId == null) return;
            if (catalog.kind.isFishing() && !checkFishingItem(player, itemId)) return;
            if (event.isRightClick()) {
                openRecipe(player, itemId, catalog.kind, catalog.page);
            } else if (event.isLeftClick()) {
                give(player, itemId, event.isShiftClick());
            }
            return;
        }

        RecipeHolder recipe = (RecipeHolder) topHolder;
        if (recipe.returnKind.isFishing() && !checkFishingItem(player, recipe.itemId)) {
            event.setCancelled(true);
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (slot < 0) return;
        if (slot >= topSize) {
            if (event.isShiftClick() || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        int recipeIndex = recipeIndex(slot);
        if (recipeIndex >= 0) {
            ItemStack cursor = event.getCursor();
            if (event.isRightClick() || cursor.getType().isAir()) {
                event.getView().getTopInventory().setItem(slot, null);
            } else {
                ItemStack ghost = cursor.clone();
                ghost.setAmount(1);
                event.getView().getTopInventory().setItem(slot, ghost);
            }
            return;
        }
        if (slot == RECIPE_CLOSE_SLOT) {
            player.closeInventory();
        } else if (slot == RECIPE_BACK_SLOT) {
            openCatalog(player, recipe.returnKind, recipe.returnPage);
        } else if (slot == RECIPE_CLEAR_SLOT) {
            for (int inputSlot : RECIPE_INPUT_SLOTS) {
                event.getView().getTopInventory().setItem(inputSlot, null);
            }
        } else if (slot == RECIPE_SAVE_SLOT) {
            saveRecipe(player, recipe, event.getView().getTopInventory());
        } else if (slot == RECIPE_RESET_SLOT) {
            registry.resetRecipe(recipe.itemId);
            plugin.recipes().reload();
            ItemDefinition definition = registry.item(recipe.itemId).orElseThrow();
            player.sendMessage(plugin.message("messages.recipe-reset", "item", factory.displayName(definition)));
            openRecipe(player, recipe.itemId, recipe.returnKind, recipe.returnPage);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (!(holder instanceof CatalogHolder) && !(holder instanceof RecipeHolder)
                && !(holder instanceof FishingMenuHolder)) return;
        if (event.getWhoClicked() instanceof Player player && !checkPermission(player)) {
            event.setCancelled(true);
            return;
        }
        if (holder instanceof CatalogHolder || holder instanceof FishingMenuHolder) {
            event.setCancelled(true);
        } else if (holder instanceof RecipeHolder) {
            int topSize = event.getView().getTopInventory().getSize();
            if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
        }
    }

    private boolean checkPermission(Player player) {
        if (player.hasPermission("yknrpg.admin")) return true;
        player.closeInventory();
        player.sendMessage(plugin.message("messages.no-permission"));
        return false;
    }

    private boolean checkFishingItem(Player player, String itemId) {
        if (registry.item(itemId).isPresent()) return true;
        player.closeInventory();
        player.sendMessage(plugin.message("messages.fishing-unavailable"));
        return false;
    }

    private void saveRecipe(Player player, RecipeHolder holder, Inventory inventory) {
        List<IngredientSpec> recipe = new ArrayList<>(9);
        for (int slot : RECIPE_INPUT_SLOTS) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                recipe.add(IngredientSpec.empty());
                continue;
            }
            String customItemId = factory.itemId(stack).orElse(null);
            recipe.add(customItemId == null
                    ? IngredientSpec.material(stack.getType())
                    : IngredientSpec.custom(customItemId));
        }
        registry.updateRecipe(holder.itemId, recipe);
        plugin.recipes().reload();
        ItemDefinition definition = registry.item(holder.itemId).orElseThrow();
        player.sendMessage(plugin.message("messages.recipe-saved", "item", factory.displayName(definition)));
        openRecipe(player, holder.itemId, holder.returnKind, holder.returnPage);
    }

    private int recipeIndex(int slot) {
        for (int index = 0; index < RECIPE_INPUT_SLOTS.length; index++) {
            if (RECIPE_INPUT_SLOTS[index] == slot) return index;
        }
        return -1;
    }

    private void give(Player player, String itemId, boolean fullStack) {
        ItemDefinition definition = registry.item(itemId).orElseThrow();
        ItemStack sample = factory.create(definition);
        int amount = fullStack ? sample.getMaxStackSize() : 1;
        int remaining = amount;
        boolean overflow = false;
        while (remaining > 0) {
            ItemStack stack = factory.create(definition);
            int batch = Math.min(stack.getMaxStackSize(), remaining);
            stack.setAmount(batch);
            Map<Integer, ItemStack> excess = player.getInventory().addItem(stack);
            for (ItemStack dropped : excess.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), dropped);
                overflow = true;
            }
            remaining -= batch;
        }
        player.sendMessage(plugin.message("messages.item-received",
                "amount", amount,
                "item", factory.displayName(definition)));
        if (overflow) player.sendMessage(plugin.message("messages.inventory-overflow"));
    }

    private ItemStack catalogDisplay(ItemDefinition definition) {
        ItemStack stack = factory.create(definition);
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) lore.addAll(meta.lore());
        for (String line : plugin.getConfig().getStringList("gui.catalog-hint")) {
            lore.add(Text.component(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
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

    private String catalogTitlePath(CatalogKind kind) {
        return switch (kind) {
            case ITEM -> "gui.catalog-titles.items";
            case WEAPON -> "gui.catalog-titles.weapon";
            case ARMOR -> "gui.catalog-titles.armor";
            case PET -> "gui.catalog-titles.pets";
            case FISHING_CATCH -> "gui.catalog-titles.fishing-catch";
            case FISHING_ROD -> "gui.catalog-titles.fishing-rod";
            case FISHING_ARMOR -> "gui.catalog-titles.fishing-armor";
            case OTHER -> "gui.catalog-titles.other";
        };
    }

    private ItemStack button(Material material, String path) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.message(path).decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack helpButton() {
        ItemStack stack = button(Material.KNOWLEDGE_BOOK, "gui.recipe-help");
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = plugin.getConfig().getStringList("gui.recipe-help-lore").stream()
                .map(Text::component)
                .map(line -> line.decoration(TextDecoration.ITALIC, false))
                .toList();
        meta.lore(lore);
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

    private static final class CatalogHolder implements InventoryHolder {
        private final CatalogKind kind;
        private final int page;
        private final Map<Integer, String> items = new HashMap<>();
        private Inventory inventory;

        private CatalogHolder(CatalogKind kind, int page) {
            this.kind = kind;
            this.page = page;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class FishingMenuHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class RecipeHolder implements InventoryHolder {
        private final String itemId;
        private final CatalogKind returnKind;
        private final int returnPage;
        private Inventory inventory;

        private RecipeHolder(String itemId, CatalogKind returnKind, int returnPage) {
            this.itemId = itemId;
            this.returnKind = returnKind;
            this.returnPage = returnPage;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
