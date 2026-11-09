package cn.yknrpg.api;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.item.ItemStatOverrides.EditableStat;
import cn.yknrpg.model.ItemDefinition.CatalogKind;
import cn.yknrpg.model.ItemDefinition.ItemSource;
import cn.yknrpg.rpg.RpgRegistry;
import cn.yknrpg.item.CustomItemFactory;
import cn.yknrpg.model.ItemDefinition;
import cn.yknrpg.registry.ItemRegistry;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Stable, small read/refresh boundary for forge consumers, including obfuscated releases. */
public final class ForgeItemAccess {
    private final CustomItemFactory factory;
    private final ItemRegistry catalog;
    private final RpgRegistry registry;

    public ForgeItemAccess(YknRpgPlugin plugin, RpgRegistry registry) {
        this(plugin.itemFactory(), plugin.registry(), registry);
    }

    ForgeItemAccess(CustomItemFactory factory, ItemRegistry catalog, RpgRegistry registry) {
        this.factory = factory;
        this.catalog = catalog;
        this.registry = registry;
    }

    public record Attribute(String id, String label, boolean percent, double base) {}

    public record Weapon(String id, String name, String rarity, List<Attribute> attributes) {}

    public enum Kind {
        WEAPON,
        ARMOR
    }

    public record Item(
            String id, String name, String rarity, Kind kind, List<Attribute> attributes) {}

    private Optional<ItemDefinition> definition(ItemStack stack) {
        return factory.definition(stack)
                .filter(
                        d ->
                                d.source() == ItemSource.MYTHIC_MOBS
                                        && (d.kind() == CatalogKind.WEAPON
                                                || d.kind() == CatalogKind.ARMOR)
                                        && d.id().startsWith("YKI_"));
    }

    /** Lightweight classification for per-stat queries; does not clone ItemMeta for the name. */
    public Optional<Kind> kind(ItemStack stack) {
        return definition(stack).map(d -> d.kind() == CatalogKind.ARMOR ? Kind.ARMOR : Kind.WEAPON);
    }

    public boolean forgeable(ItemStack stack) {
        return kind(stack).isPresent();
    }

    public boolean isWeapon(ItemStack stack) {
        return kind(stack).filter(k -> k == Kind.WEAPON).isPresent();
    }

    public Optional<Weapon> weapon(ItemStack stack) {
        return item(stack)
                .filter(i -> i.kind() == Kind.WEAPON)
                .map(i -> new Weapon(i.id(), i.name(), i.rarity(), i.attributes()));
    }

    public Optional<Item> item(ItemStack stack) {
        return definition(stack)
                .map(
                        d -> {
                            var base = registry.baseItemStats(stack);
                            List<Attribute> attributes = new ArrayList<>();
                            for (EditableStat s : EditableStat.values()) {
                                if (base.values().containsKey(s.stat())) {
                                    attributes.add(
                                            new Attribute(
                                                    s.stat().name().toLowerCase(Locale.ROOT),
                                                    s.label(),
                                                    s.percent(),
                                                    base.get(s.stat())));
                                }
                            }
                            if (base.values().containsKey(RpgRegistry.Stat.HEALTH)) {
                                attributes.add(
                                        new Attribute(
                                                "health",
                                                "生命值",
                                                false,
                                                base.get(RpgRegistry.Stat.HEALTH)));
                            }
                            var name = cn.yknrpg.item.ForgeSupport.baseName(stack);
                            return new Item(
                                    d.id(),
                                    name == null
                                            ? d.id()
                                            : PlainTextComponentSerializer.plainText()
                                                    .serialize(name),
                                    d.rarity().name(),
                                    d.kind() == CatalogKind.ARMOR ? Kind.ARMOR : Kind.WEAPON,
                                    List.copyOf(attributes));
                        });
    }

    public Optional<String> itemId(ItemStack stack) {
        return factory.itemId(stack);
    }

    public String materialName(String id) {
        return catalog.item(id)
                .map(factory::displayName)
                .orElseThrow(() -> new IllegalArgumentException("未知 YknRpg 材料：" + id));
    }

    public void refreshLore(ItemStack stack) {
        registry.rewriteForgeLore(stack);
    }
}
