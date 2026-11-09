package cn.yknrpg.api;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.item.ItemStatOverrides.EditableStat;
import cn.yknrpg.model.ItemDefinition.CatalogKind;
import cn.yknrpg.model.ItemDefinition.ItemSource;
import cn.yknrpg.rpg.RpgRegistry;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Stable, small read/refresh boundary for forge consumers, including obfuscated releases. */
public final class ForgeItemAccess {
    private final YknRpgPlugin plugin;
    private final RpgRegistry registry;

    public ForgeItemAccess(YknRpgPlugin plugin, RpgRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public record Attribute(String id, String label, boolean percent, double base) {}

    public record Weapon(String id, String name, String rarity, List<Attribute> attributes) {}

    public boolean isWeapon(ItemStack stack) {
        return plugin.itemFactory()
                .definition(stack)
                .filter(d ->
                        d.source() == ItemSource.MYTHIC_MOBS
                                && d.kind() == CatalogKind.WEAPON
                                && d.id().startsWith("YKI_"))
                .isPresent();
    }

    public Optional<Weapon> weapon(ItemStack stack) {
        return plugin.itemFactory()
                .definition(stack)
                .filter(d ->
                        d.source() == ItemSource.MYTHIC_MOBS
                                && d.kind() == CatalogKind.WEAPON
                                && d.id().startsWith("YKI_"))
                .map(d -> {
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
                                        "health", "生命值", false, base.get(RpgRegistry.Stat.HEALTH)));
                    }
                    var name = stack.getItemMeta().displayName();
                    return new Weapon(
                            d.id(),
                            name == null
                                    ? d.id()
                                    : PlainTextComponentSerializer.plainText().serialize(name),
                            d.rarity().name(),
                            List.copyOf(attributes));
                });
    }

    public Optional<String> itemId(ItemStack stack) {
        return plugin.itemFactory().itemId(stack);
    }

    public String materialName(String id) {
        return plugin.registry()
                .item(id)
                .map(plugin.itemFactory()::displayName)
                .orElseThrow(() -> new IllegalArgumentException("未知 YknRpg 材料：" + id));
    }

    public void refreshLore(ItemStack stack) {
        registry.rewriteForgeLore(stack);
    }
}
