package cn.yknitems.gui;

import cn.yknitems.YknItemsPlugin;
import cn.yknitems.item.CustomItemFactory;
import cn.yknitems.model.IngredientSpec;
import cn.yknitems.model.ItemDefinition;
import cn.yknitems.registry.ItemRegistry;
import cn.yknitems.util.Text;
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
    private static final int PREVIOUS_SLOT = 45;
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

    private final YknItemsPlugin plugin;
    private final ItemRegistry registry;
    private final CustomItemFactory factory;

    public EquipmentGui(YknItemsPlugin plugin, ItemRegistry registry, CustomItemFactory factory) {
        this.plugin = plugin;
        this.registry = registry;
        this.factory = factory;
    }

    public void openCatalog(Player player, int requestedPage) {
        List<ItemDefinition> definitions = registry.items();
        int pageCount = Math.max(1, (definitions.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        CatalogHolder holder = new CatalogHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, CATALOG_SIZE,
                plugin.message("gui.catalog-title", "page", page + 1, "pages", pageCount));
        holder.bind(inventory);
        fill(inventory);

        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(definitions.size(), start + ITEMS_PER_PAGE);
        for (int index = start; index < end; index++) {
            int slot = index - start;
            ItemDefinition definition = definitions.get(index);
            holder.items.put(slot, definition.id());
            inventory.setItem(slot, catalogDisplay(definition));
        }
        if (page > 0) inventory.setItem(PREVIOUS_SLOT, button(Material.ARROW, "gui.previous-page"));
        inventory.setItem(PAGE_SLOT, pageIndicator(page + 1, pageCount));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "gui.close"));
        if (page + 1 < pageCount) inventory.setItem(NEXT_SLOT, button(Material.ARROW, "gui.next-page"));
        player.openInventory(inventory);
    }

    private void openRecipe(Player player, String itemId, int returnPage) {
        ItemDefinition definition = registry.item(itemId).orElse(null);
        if (definition == null) return;
        RecipeHolder holder = new RecipeHolder(itemId, returnPage);
        Inventory inventory = Bukkit.createInventory(holder, CATALOG_SIZE,
                plugin.message("gui.recipe-title", "item", Text.strip(definition.name())));
        holder.bind(inventory);
        fill(inventory);

        List<IngredientSpec> recipe = definition.recipe();
        for (int index = 0; index < Math.min(9, recipe.size()); index++) {
            inventory.setItem(RECIPE_INPUT_SLOTS[index], null);
            IngredientSpec ingredient = recipe.get(index);
            if (!ingredient.isEmpty()) inventory.setItem(RECIPE_INPUT_SLOTS[index], factory.ingredient(ingredient));
        }
        inventory.setItem(RECIPE_TABLE_SLOT, factory.guiIcon(Material.CRAFTING_TABLE,
                "yknitems:gui_crafting_table", plugin.raw("gui.crafting-table")));
        inventory.setItem(RECIPE_ARROW_SLOT, factory.guiIcon(Material.SPECTRAL_ARROW,
                "yknitems:gui_arrow", plugin.raw("gui.recipe-arrow")));
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
        if (!(topHolder instanceof CatalogHolder) && !(topHolder instanceof RecipeHolder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();

        if (topHolder instanceof CatalogHolder catalog) {
            event.setCancelled(true);
            if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
            if (slot == CLOSE_SLOT) {
                player.closeInventory();
                return;
            }
            if (slot == PREVIOUS_SLOT && catalog.page > 0) {
                openCatalog(player, catalog.page - 1);
                return;
            }
            if (slot == NEXT_SLOT) {
                openCatalog(player, catalog.page + 1);
                return;
            }
            String itemId = catalog.items.get(slot);
            if (itemId == null) return;
            if (event.isRightClick()) {
                openRecipe(player, itemId, catalog.page);
            } else if (event.isLeftClick()) {
                give(player, itemId, event.isShiftClick() ? 64 : 1);
            }
            return;
        }

        RecipeHolder recipe = (RecipeHolder) topHolder;
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
            openCatalog(player, recipe.returnPage);
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
            player.sendMessage(plugin.message("messages.recipe-reset", "item", Text.strip(definition.name())));
            openRecipe(player, recipe.itemId, recipe.returnPage);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
        if (holder instanceof CatalogHolder) {
            event.setCancelled(true);
        } else if (holder instanceof RecipeHolder) {
            int topSize = event.getView().getTopInventory().getSize();
            if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
        }
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
        player.sendMessage(plugin.message("messages.recipe-saved", "item", Text.strip(definition.name())));
        openRecipe(player, holder.itemId, holder.returnPage);
    }

    private int recipeIndex(int slot) {
        for (int index = 0; index < RECIPE_INPUT_SLOTS.length; index++) {
            if (RECIPE_INPUT_SLOTS[index] == slot) return index;
        }
        return -1;
    }

    private void give(Player player, String itemId, int amount) {
        ItemDefinition definition = registry.item(itemId).orElseThrow();
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
                "item", Text.strip(definition.name())));
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
        private final int page;
        private final Map<Integer, String> items = new HashMap<>();
        private Inventory inventory;

        private CatalogHolder(int page) {
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

    private static final class RecipeHolder implements InventoryHolder {
        private final String itemId;
        private final int returnPage;
        private Inventory inventory;

        private RecipeHolder(String itemId, int returnPage) {
            this.itemId = itemId;
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
