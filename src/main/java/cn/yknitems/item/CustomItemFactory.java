package cn.yknitems.item;

import cn.yknitems.YknItemsPlugin;
import cn.yknitems.model.ArmorSetDefinition;
import cn.yknitems.model.IngredientSpec;
import cn.yknitems.model.ItemCategory;
import cn.yknitems.model.ItemDefinition;
import cn.yknitems.model.Stage;
import cn.yknitems.registry.ItemRegistry;
import cn.yknitems.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CustomItemFactory {
    private final YknItemsPlugin plugin;
    private final ItemRegistry registry;
    private final NamespacedKey itemIdKey;

    public CustomItemFactory(YknItemsPlugin plugin, ItemRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.itemIdKey = new NamespacedKey(plugin, "item_id");
    }

    public ItemStack create(String itemId) {
        ItemDefinition definition = registry.item(itemId)
                .orElseThrow(() -> new IllegalArgumentException("未知物品：" + itemId));
        return create(definition);
    }

    public ItemStack create(ItemDefinition definition) {
        ItemStack stack = new ItemStack(definition.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Text.component(definition.name()).decoration(TextDecoration.ITALIC, false));
        meta.lore(buildLore(definition));
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, definition.id());
        if (!definition.itemModel().isBlank()) {
            NamespacedKey model = NamespacedKey.fromString(definition.itemModel());
            if (model != null) meta.setItemModel(model);
        }
        if (definition.category() != ItemCategory.MATERIAL) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        }
        if (definition.stage() == Stage.LATE) meta.setEnchantmentGlintOverride(true);

        if (definition.category() == ItemCategory.WEAPON) applyWeaponAttributes(meta, definition);
        if (definition.category() == ItemCategory.ARMOR) applyArmorAttributes(meta, definition);
        if (meta instanceof LeatherArmorMeta leather && definition.leatherColor() != 0) {
            leather.setColor(Color.fromRGB(definition.leatherColor()));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack ingredient(IngredientSpec ingredient) {
        if (ingredient.customItemId() != null) return create(ingredient.customItemId());
        if (ingredient.material() != null) return new ItemStack(ingredient.material());
        return new ItemStack(Material.AIR);
    }

    public Optional<String> itemId(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return Optional.empty();
        return Optional.ofNullable(stack.getItemMeta().getPersistentDataContainer()
                .get(itemIdKey, PersistentDataType.STRING));
    }

    public Optional<ItemDefinition> definition(ItemStack stack) {
        return itemId(stack).flatMap(registry::item);
    }

    public ItemStack guiIcon(Material material, String modelKey, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Text.component(name).decoration(TextDecoration.ITALIC, false));
        NamespacedKey model = NamespacedKey.fromString(modelKey);
        if (model != null) meta.setItemModel(model);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    private List<Component> buildLore(ItemDefinition definition) {
        List<Component> lore = new ArrayList<>();
        for (String line : definition.description()) addLore(lore, line);
        if (!definition.description().isEmpty()) addLore(lore, "");
        if (definition.category() == ItemCategory.WEAPON) {
            addLore(lore, "&7基础伤害：&c" + Text.number(definition.damage()));
            if (definition.weaponType() != null && definition.weaponType().name().equals("BOW")) {
                addLore(lore, "&7伤害类型：&e箭矢");
            } else {
                addLore(lore, "&7攻击速度：&e" + Text.number(definition.attackSpeed()));
            }
            addLore(lore, "&7等级需求：&f" + definition.requiredLevel());
            addLore(lore, "");
            addLore(lore, tierColor(definition.stage()) + "&l" + definition.abilityName());
            addLore(lore, "&7" + definition.abilityDescription());
        } else if (definition.category() == ItemCategory.ARMOR) {
            addLore(lore, "&7护甲：&b" + Text.number(definition.armor()));
            addLore(lore, "&7韧性：&b" + Text.number(definition.toughness()));
            addLore(lore, "&7最大生命：&c+" + Text.number(definition.maxHealth()));
            addLore(lore, "&7防御等级需求：&f" + definition.requiredLevel());
            ArmorSetDefinition set = registry.armorSet(definition.armorSet()).orElse(null);
            if (set != null) {
                addLore(lore, "");
                addLore(lore, tierColor(definition.stage()) + "&l" + Text.strip(set.name()));
                addLore(lore, "&7" + set.bonusDescription());
            }
        }
        addLore(lore, "");
        addLore(lore, tierColor(definition.stage()) + "&l" + tierName(definition.stage()));
        return List.copyOf(lore);
    }

    private void applyWeaponAttributes(ItemMeta meta, ItemDefinition definition) {
        if (definition.weaponType() == null || definition.weaponType().name().equals("BOW")) return;
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, modifier(
                definition.id() + "_damage", definition.damage() - 1.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, modifier(
                definition.id() + "_speed", definition.attackSpeed() - 4.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
    }

    private void applyArmorAttributes(ItemMeta meta, ItemDefinition definition) {
        EquipmentSlotGroup slot = definition.armorSlot().slotGroup();
        meta.addAttributeModifier(Attribute.ARMOR, modifier(
                definition.id() + "_armor", definition.armor(), AttributeModifier.Operation.ADD_NUMBER, slot));
        meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, modifier(
                definition.id() + "_toughness", definition.toughness(), AttributeModifier.Operation.ADD_NUMBER, slot));
        meta.addAttributeModifier(Attribute.MAX_HEALTH, modifier(
                definition.id() + "_health", definition.maxHealth(), AttributeModifier.Operation.ADD_NUMBER, slot));
    }

    private AttributeModifier modifier(String key, double amount, AttributeModifier.Operation operation,
                                       EquipmentSlotGroup slot) {
        return new AttributeModifier(new NamespacedKey(plugin, key), amount, operation, slot);
    }

    private static void addLore(List<Component> lore, String line) {
        lore.add(Text.component(line).decoration(TextDecoration.ITALIC, false));
    }

    private static String tierColor(Stage stage) {
        return switch (stage) {
            case EARLY -> "&a";
            case MID -> "&d";
            case LATE -> "&5";
        };
    }

    private static String tierName(Stage stage) {
        return switch (stage) {
            case EARLY -> "精良";
            case MID -> "史诗";
            case LATE -> "传说";
        };
    }
}
