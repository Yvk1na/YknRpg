package cn.yknrpg.item;

import cn.yknrpg.integration.MythicItems;
import cn.yknrpg.item.ItemStatOverrides.EditableStat;
import cn.yknrpg.rpg.RpgRegistry;
import cn.yknrpg.rpg.RpgRegistry.Stat;
import cn.yknrpg.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ItemStatOverridesTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void validatesNumbersAndUsesPercentagePointsRatherThanFractions() {
        assertEquals(12.5, ItemStatOverrides.parseValue(" 12.5% "));
        assertEquals(0.5, ItemStatOverrides.parseValue("+.5"));
        assertEquals(1_000_000, ItemStatOverrides.parseValue("1000000"));
        assertEquals("12.5%", ItemStatOverrides.formatValue(12.5, true));
        assertEquals("0", ItemStatOverrides.formatValue(0, false));
        for (String invalid : List.of("", "-1", "NaN", "Infinity", "1000001", "1e8", "0x10", "2%%", "2 3")) {
            assertThrows(IllegalArgumentException.class, () -> ItemStatOverrides.parseValue(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> ItemStatOverrides.parseValue(null));
        assertEquals(List.of(EditableStat.CRIT_CHANCE, EditableStat.CRIT_DAMAGE, EditableStat.ATTACK_SPEED,
                        EditableStat.ABILITY_DAMAGE, EditableStat.MENDING),
                List.of(EditableStat.values()).stream().filter(EditableStat::percent).toList());
    }

    @Test
    void removesOnlyRetiredAttributesAndPreservesMixedLoreComponents() {
        Component description = Text.component("&7技能说明：活力与凶猛不是此行的属性。");
        Component other = Component.text("治疗增幅：", NamedTextColor.GRAY)
                .append(Component.text("+5", NamedTextColor.GREEN))
                .clickEvent(ClickEvent.suggestCommand("/unchanged"));
        Component mixed = Text.component("&7活力：&a+7.5 &8| ").append(other)
                .append(Text.component(" &8| &7凶猛：&c+20"));
        List<Component> cleaned = ItemStatOverrides.rewriteLore(List.of(description, mixed), null, 0);
        assertEquals(2, cleaned.size());
        assertSame(description, cleaned.getFirst());
        assertEquals("治疗增幅：+5", PLAIN.serialize(cleaned.get(1)));
        assertTrue(containsComponent(cleaned.get(1), other), "Retained mixed attribute keeps its styling and event");
        assertEquals(List.of(), ItemStatOverrides.rewriteLore(List.of(Text.component("&a凶猛：+10")), null, 0));

        Component original = Text.component("&7暴击伤害：&9+20% | &7伤害：&c+55 | &7防御：&a+10");
        List<Component> edited = ItemStatOverrides.rewriteLore(List.of(original, description), EditableStat.DAMAGE, 80.5);
        assertEquals("暴击伤害：+20% | 伤害：+80.5 | 防御：+10", PLAIN.serialize(edited.getFirst()));
        assertSame(description, edited.get(1));
        List<Component> added = ItemStatOverrides.rewriteLore(edited, EditableStat.MENDING, 3);
        assertEquals("治疗增幅：+3%", PLAIN.serialize(added.get(1)));
        assertSame(description, added.get(2));
    }

    @Test
    void instanceOverridesDoNotMutateTemplatesAndRespectEquipmentSlots() throws Exception {
        Field blockType = field(Material.class, "blockType");
        Map<Material, Object> oldBlockTypes = new HashMap<>();
        for (Material material : List.of(Material.DIAMOND_SWORD, Material.DIAMOND_HELMET,
                Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS, Material.STICK)) {
            oldBlockTypes.put(material, blockType.get(material));
            blockType.set(material, (Supplier<?>) () -> null);
        }
        try {
            MythicItems mythic = new MythicItems(null);
            field(MythicItems.class, "itemManager").set(mythic, new MythicStub());
            field(MythicItems.class, "isMythicItem").set(mythic,
                    MythicStub.class.getMethod("isMythicItem", ItemStack.class));
            RpgRegistry registry = new RpgRegistry(null, mythic);
            YamlConfiguration config = new YamlConfiguration();
            config.set("damage", 55);
            config.set("strength", 10);
            config.set("vitality", 99);
            config.set("ferocity", 80);
            Method parseStats = RpgRegistry.class.getDeclaredMethod("parseStats", ConfigurationSection.class);
            parseStats.setAccessible(true);
            RpgRegistry.Stats templateStats = (RpgRegistry.Stats) parseStats.invoke(registry, config);
            assertEquals(0, templateStats.get(Stat.VITALITY));
            assertEquals(0, templateStats.get(Stat.FEROCITY));
            RpgRegistry.Ability ability = new RpgRegistry.Ability("test", "技能", RpgRegistry.Trigger.RIGHT_CLICK,
                    5, 10, 0, 1, 1, 16, true, true, false, false, 100, null, List.of());
            Class<?> powerClass = Class.forName("cn.yknrpg.rpg.RpgRegistry$ItemPower");
            Constructor<?> constructor = powerClass.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String, Object> templates =
                    (Map<String, Object>) field(RpgRegistry.class, "items").get(registry);
            templates.put("YKI_TEST", constructor.newInstance("YKI_TEST", RpgRegistry.Slot.MAIN_HAND,
                    templateStats, List.of(ability)));

            TestStack original = new TestStack(Material.DIAMOND_SWORD);
            original.meta.data.put(new NamespacedKey("mythicmobs", "type"), "YKI_TEST");
            original.meta.data.put(new NamespacedKey("other", "marker"), "retained");
            original.meta.name = Component.text("原来的名字");
            original.meta.model = 901;
            original.meta.lore = List.of(Text.component("&7伤害：&c+55 | &7力量：&c+10"),
                    Text.component("&7活力：&a+99 | &7凶猛：&c+80"), Component.text("技能说明"));
            TestStack edited = original.clone();
            ItemStatOverrides.apply(edited, EditableStat.DAMAGE, 100);
            ItemStatOverrides.apply(edited, EditableStat.STRENGTH, 0);
            assertEquals(100, registry.itemStats(edited).get(Stat.DAMAGE));
            assertEquals(0, registry.itemStats(edited).get(Stat.STRENGTH));
            assertEquals(55, registry.itemStats(original).get(Stat.DAMAGE));
            assertEquals(10, registry.itemStats(original).get(Stat.STRENGTH));
            assertEquals(55, templateStats.get(Stat.DAMAGE));
            assertEquals(original.meta.name, edited.meta.name);
            assertEquals(901, edited.meta.model);
            assertEquals("retained", edited.meta.data.get(new NamespacedKey("other", "marker")));
            assertFalse(PLAIN.serialize(edited.meta.lore.getFirst()).contains("活力"));
            assertFalse(ItemStatOverrides.hasOverrides(original));
            TestStack persisted = edited.clone();
            assertEquals(100, registry.itemStats(persisted).get(Stat.DAMAGE));

            Map<String, ItemStack> slots = new HashMap<>();
            slots.put("getItemInMainHand", persisted);
            slots.put("getItemInOffHand", persisted.clone());
            Player player = player(slots);
            assertEquals(100, registry.profile(player).stats().get(Stat.DAMAGE));
            assertEquals(List.of(ability), registry.profile(player).abilities().stream()
                    .map(RpgRegistry.AbilitySource::ability).toList());

            TestStack ordinary = new TestStack(Material.STICK);
            ItemStatOverrides.apply(ordinary, EditableStat.STRENGTH, 30);
            slots.put("getItemInMainHand", ordinary);
            slots.put("getItemInOffHand", ordinary.clone());
            assertEquals(30, registry.profile(player).stats().get(Stat.STRENGTH));
            assertEquals(0, registry.profile(player).abilities().size());
            for (var armor : Map.of(Material.DIAMOND_HELMET, "getHelmet", Material.IRON_CHESTPLATE,
                    "getChestplate", Material.IRON_LEGGINGS, "getLeggings", Material.IRON_BOOTS, "getBoots").entrySet()) {
                TestStack piece = new TestStack(armor.getKey());
                ItemStatOverrides.apply(piece, EditableStat.DEFENSE, 20);
                slots.put("getItemInMainHand", piece);
                slots.put("getItemInOffHand", piece.clone());
                assertEquals(0, registry.profile(player).stats().get(Stat.DEFENSE));
                slots.put(armor.getValue(), piece);
                assertEquals(20, registry.profile(player).stats().get(Stat.DEFENSE));
                slots.remove(armor.getValue());
            }
            slots.put("getItemInMainHand", original);
            registry.profile(player);
            assertEquals(2, original.meta.lore.size(), "Equipped legacy item loses obsolete lore without a reissue");
            assertFalse(original.meta.lore.stream().map(PLAIN::serialize).anyMatch(s -> s.contains("凶猛")));
            ordinary.amount = 2;
            assertThrows(IllegalArgumentException.class, () -> ItemStatOverrides.apply(ordinary, EditableStat.DAMAGE, 2));
            assertThrows(IllegalArgumentException.class, () -> ItemStatOverrides.apply(edited, EditableStat.DAMAGE, Double.NaN));
            assertEquals(100, registry.itemStats(edited).get(Stat.DAMAGE));
        } finally {
            for (var entry : oldBlockTypes.entrySet()) blockType.set(entry.getKey(), entry.getValue());
        }
    }

    private static boolean containsComponent(Component parent, Component wanted) {
        return parent.equals(wanted) || parent.children().stream().anyMatch(child -> containsComponent(child, wanted));
    }

    private static Player player(Map<String, ItemStack> slots) {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(ItemStatOverridesTest.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> slots.get(method.getName()));
        return (Player) Proxy.newProxyInstance(ItemStatOverridesTest.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getInventory")) return inventory;
                    throw new AssertionError(method.getName());
                });
    }

    public static final class MythicStub {
        public boolean isMythicItem(ItemStack stack) { return false; }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class TestStack extends ItemStack {
        private final Material material;
        private MetaState meta = new MetaState();
        private int amount = 1;
        private TestStack(Material material) { this.material = material; }
        @Override public Material getType() { return material; }
        @Override public int getAmount() { return amount; }
        @Override public boolean hasItemMeta() { return true; }
        @Override public ItemMeta getItemMeta() { return meta.copy().proxy(); }
        @Override public boolean setItemMeta(ItemMeta value) {
            meta = ((MetaState) Proxy.getInvocationHandler(value)).copy();
            return true;
        }
        @Override public TestStack clone() {
            TestStack copy = new TestStack(material);
            copy.meta = meta.copy();
            copy.amount = amount;
            return copy;
        }
    }

    private static final class MetaState implements java.lang.reflect.InvocationHandler {
        private final Map<NamespacedKey, Object> data = new HashMap<>();
        private List<Component> lore;
        private Component name;
        private int model;

        private MetaState copy() {
            MetaState result = new MetaState();
            result.data.putAll(data);
            result.lore = lore == null ? null : new ArrayList<>(lore);
            result.name = name;
            result.model = model;
            return result;
        }

        private ItemMeta proxy() {
            return (ItemMeta) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ItemMeta.class}, this);
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("lore")) {
                if (args == null || args.length == 0) return lore;
                @SuppressWarnings("unchecked") List<Component> value = (List<Component>) args[0];
                lore = value;
                return null;
            }
            if (method.getName().equals("getPersistentDataContainer")) {
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PersistentDataContainer.class},
                        (container, operation, values) -> {
                            if (operation.getName().equals("get")) {
                                Object value = data.get(values[0]);
                                return ((PersistentDataType<?, ?>) values[1]).getComplexType().isInstance(value) ? value : null;
                            }
                            if (operation.getName().equals("set")) { data.put((NamespacedKey) values[0], values[2]); return null; }
                            throw new AssertionError(operation.getName());
                        });
            }
            throw new AssertionError(method.getName());
        }
    }
}
