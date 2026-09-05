package cn.yknrpg.item;

import cn.yknrpg.model.ItemDefinition.CatalogKind;
import io.papermc.paper.event.entity.EntityCompostItemEvent;
import org.bukkit.block.Crafter;
import org.bukkit.Material;
import org.bukkit.entity.Piglin;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.Set;

/** Prevents crafting materials from being consumed by their vanilla right-click behavior. */
public final class MaterialUseGuard implements Listener {
    private static final Set<String> VANILLA_CONSUMER_INVENTORIES = Set.of(
            "ANVIL", "BEACON", "BLAST_FURNACE", "BREWING", "CARTOGRAPHY", "ENCHANTING",
            "FURNACE", "GRINDSTONE", "LOOM", "MERCHANT", "SMITHING", "SMOKER", "STONECUTTER"
    );

    private final CustomItemFactory itemFactory;

    public MaterialUseGuard(CustomItemFactory itemFactory) {
        this.itemFactory = itemFactory;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (isCraftingMaterial(event.getItem())) {
            event.setUseItemInHand(Event.Result.DENY);
            if (event.getClickedBlock() != null
                    && event.getClickedBlock().getType() == Material.COMPOSTER) {
                event.setUseInteractedBlock(Event.Result.DENY);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event instanceof PlayerInteractAtEntityEvent) return;
        if (isCraftingMaterial(heldItem(event.getPlayer().getInventory().getItemInMainHand(),
                event.getPlayer().getInventory().getItemInOffHand(), event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (isCraftingMaterial(heldItem(event.getPlayer().getInventory().getItemInMainHand(),
                event.getPlayer().getInventory().getItemInOffHand(), event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (containsCraftingMaterial(event.getInventory().getMatrix()) && !isYknRpgRecipe(event.getRecipe())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCraft(CraftItemEvent event) {
        if (containsCraftingMaterial(event.getInventory().getMatrix()) && !isYknRpgRecipe(event.getRecipe())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof Crafter crafter
                && containsCraftingMaterial(crafter.getInventory().getContents())
                && !isYknRpgRecipe(event.getRecipe())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityCompost(EntityCompostItemEvent event) {
        if (isCraftingMaterial(event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (!isCraftingMaterial(event.getItem())) return;
        var destination = event.getDestination().getLocation();
        if (destination != null && destination.getBlock().getType() == Material.COMPOSTER) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPiglinPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Piglin
                && isCraftingMaterial(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPiglinBarter(PiglinBarterEvent event) {
        if (isCraftingMaterial(event.getInput())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFurnaceFuel(FurnaceBurnEvent event) {
        if (isCraftingMaterial(event.getFuel())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFurnaceSmelt(FurnaceSmeltEvent event) {
        if (isCraftingMaterial(event.getSource())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBrewingFuel(BrewingStandFuelEvent event) {
        if (isCraftingMaterial(event.getFuel())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBrew(BrewEvent event) {
        if (containsCraftingMaterial(event.getContents().getContents())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepareEnchant(PrepareItemEnchantEvent event) {
        if (isCraftingMaterial(event.getInventory().getItem(1))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEnchant(EnchantItemEvent event) {
        if (isCraftingMaterial(event.getInventory().getItem(1))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (containsCraftingMaterial(event.getInventory().getContents())) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        if (containsCraftingMaterial(event.getInventory().getContents())) event.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsumerInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!VANILLA_CONSUMER_INVENTORIES.contains(top.getType().name())) return;

        if (event.isShiftClick() && event.getClickedInventory() == event.getView().getBottomInventory()
                && isCraftingMaterial(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;

        ItemStack incoming = event.getCursor();
        if (event.getClick() == ClickType.NUMBER_KEY && event.getWhoClicked() instanceof Player player
                && event.getHotbarButton() >= 0) {
            incoming = player.getInventory().getItem(event.getHotbarButton());
        } else if (event.getClick() == ClickType.SWAP_OFFHAND
                && event.getWhoClicked() instanceof Player player) {
            incoming = player.getInventory().getItemInOffHand();
        }
        if (isCraftingMaterial(incoming)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsumerInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!VANILLA_CONSUMER_INVENTORIES.contains(top.getType().name())) return;
        if (isCraftingMaterial(event.getOldCursor())
                && event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())) {
            event.setCancelled(true);
        }
    }

    private boolean isCraftingMaterial(ItemStack stack) {
        return itemFactory.definition(stack)
                .map(definition -> definition.kind() == CatalogKind.ITEM)
                .orElse(false);
    }

    private boolean containsCraftingMaterial(ItemStack[] contents) {
        for (ItemStack stack : contents) {
            if (isCraftingMaterial(stack)) return true;
        }
        return false;
    }

    private boolean isYknRpgRecipe(Recipe recipe) {
        return recipe != null && itemFactory.definition(recipe.getResult()).isPresent();
    }

    private static ItemStack heldItem(ItemStack mainHand, ItemStack offHand, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND ? offHand : mainHand;
    }
}
