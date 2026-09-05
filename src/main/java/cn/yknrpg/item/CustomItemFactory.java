package cn.yknrpg.item;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.integration.EcoPetsItems;
import cn.yknrpg.integration.MythicItems;
import cn.yknrpg.model.IngredientSpec;
import cn.yknrpg.model.ItemDefinition;
import cn.yknrpg.registry.ItemRegistry;
import cn.yknrpg.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Optional;

public final class CustomItemFactory {
    private final YknRpgPlugin plugin;
    private final ItemRegistry registry;
    private final MythicItems mythicItems;
    private final EcoPetsItems ecoPetsItems;

    public CustomItemFactory(YknRpgPlugin plugin, ItemRegistry registry,
                             MythicItems mythicItems, EcoPetsItems ecoPetsItems) {
        this.plugin = plugin;
        this.registry = registry;
        this.mythicItems = mythicItems;
        this.ecoPetsItems = ecoPetsItems;
    }

    public ItemStack create(String itemId) {
        ItemDefinition definition = registry.item(itemId)
                .orElseThrow(() -> new IllegalArgumentException("物品不在 YknRpg 目录中：" + itemId));
        return create(definition);
    }

    public ItemStack create(ItemDefinition definition) {
        return switch (definition.source()) {
            case MYTHIC_MOBS -> mythicItems.create(definition.id());
            case ECO_PETS -> ecoPetsItems.create(definition.id());
        };
    }

    public ItemStack ingredient(IngredientSpec ingredient) {
        if (ingredient.customItemId() != null) {
            ItemDefinition definition = registry.item(ingredient.customItemId()).orElse(null);
            return definition == null
                    ? mythicItems.create(ingredient.customItemId())
                    : create(definition);
        }
        if (ingredient.material() != null) return new ItemStack(ingredient.material());
        return new ItemStack(Material.AIR);
    }

    public Optional<String> itemId(ItemStack stack) {
        return ecoPetsItems.itemId(stack).or(() -> mythicItems.itemId(stack));
    }

    public Optional<ItemDefinition> definition(ItemStack stack) {
        return itemId(stack).flatMap(registry::item);
    }

    public String displayName(ItemDefinition definition) {
        ItemStack stack = create(definition);
        ItemMeta meta = stack.getItemMeta();
        Component displayName = meta.displayName();
        if (displayName == null) return definition.id();
        String plain = PlainTextComponentSerializer.plainText().serialize(displayName);
        return plain.isBlank() ? definition.id() : plain;
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
}
