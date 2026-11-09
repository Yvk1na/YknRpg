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
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    @Test
    void forgeServiceUsesInstanceBaseDisablesSkillsAndRewritesLegacyLoreIdempotently()
            throws Exception {
        Field serverField = field(org.bukkit.Bukkit.class, "server");
        Object oldServer = serverField.get(null);
        var active =
                new java.util.concurrent.atomic.AtomicReference<cn.yknrpg.api.ForgeBonusProvider>();
        var disabled = new java.util.concurrent.atomic.AtomicBoolean();
        var phaseTwo = new java.util.concurrent.atomic.AtomicBoolean();
        var provider =
                new cn.yknrpg.api.ForgeBonusProvider() {
                    public double multiplier(ItemStack stack, String stat) {
                        return stat.equals("damage") ? 1.25 : 1;
                    }

                    public boolean disabled(ItemStack stack) {
                        return disabled.get();
                    }

                    public List<Component> loreLines(ItemStack stack) {
                        return List.of(Text.component("&6锻造 +7"), Text.component("&b祝福 2/5"));
                    }

                    public double flat(ItemStack stack, String stat) {
                        return phaseTwo.get()
                                ? (stat.equals("damage") ? 3 : stat.equals("crit_chance") ? 2 : 0)
                                : 0;
                    }

                    public Component decorateName(ItemStack stack, Component baseName) {
                        return phaseTwo.get() ? baseName.append(Text.component("&6 +7")) : baseName;
                    }
                };
        var services =
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {org.bukkit.plugin.ServicesManager.class},
                        (proxy, method, args) ->
                                method.getName().equals("load") ? active.get() : null);
        Object server =
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {org.bukkit.Server.class},
                        (proxy, method, args) ->
                                method.getName().equals("getServicesManager") ? services : null);
        // Initialize Material before installing the deliberately narrow server double.
        Field blockType = field(Material.class, "blockType");
        Object originalType = blockType.get(Material.DIAMOND_SWORD);
        blockType.set(Material.DIAMOND_SWORD, (Supplier<?>) () -> null);
        try {
            MythicItems mythic = new MythicItems(null);
            RpgRegistry registry = new RpgRegistry(null, mythic);
            YamlConfiguration config = new YamlConfiguration();
            config.set("damage", 55);
            config.set("strength", 10);
            Method parseStats =
                    RpgRegistry.class.getDeclaredMethod("parseStats", ConfigurationSection.class);
            parseStats.setAccessible(true);
            Object stats = parseStats.invoke(registry, config);
            var ability =
                    new RpgRegistry.Ability(
                            "test",
                            "技能",
                            RpgRegistry.Trigger.RIGHT_CLICK,
                            0,
                            0,
                            0,
                            1,
                            1,
                            16,
                            true,
                            true,
                            false,
                            false,
                            100,
                            null,
                            List.of());
            var constructor =
                    Class.forName("cn.yknrpg.rpg.RpgRegistry$ItemPower")
                            .getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> templates =
                    (Map<String, Object>) field(RpgRegistry.class, "items").get(registry);
            templates.put(
                    "YKI_TEST",
                    constructor.newInstance(
                            "YKI_TEST", RpgRegistry.Slot.MAIN_HAND, stats, List.of(ability)));
            TestStack stack = new TestStack(Material.DIAMOND_SWORD);
            Component originalName =
                    Text.component("&d原有武器名").clickEvent(ClickEvent.suggestCommand("/name"));
            stack.meta.name = originalName;
            stack.meta.data.put(new NamespacedKey("mythicmobs", "type"), "YKI_TEST");
            Component description =
                    Component.text("技能说明").clickEvent(ClickEvent.suggestCommand("/retained"));
            stack.meta.lore = List.of(Text.component("&7伤害：&c+55 | &7力量：&c+10"), description);
            ItemStatOverrides.apply(stack, EditableStat.DAMAGE, 100);
            Map<String, ItemStack> slots = new HashMap<>();
            slots.put("getItemInMainHand", stack);
            serverField.set(null, server);
            active.set(
                    new cn.yknrpg.api.ForgeBonusProvider() {
                        public double multiplier(ItemStack item, String stat) {
                            return 1;
                        }

                        public boolean disabled(ItemStack item) {
                            return false;
                        }

                        public List<Component> loreLines(ItemStack item) {
                            return List.of();
                        }
                    });
            List<Component> untouched = List.copyOf(stack.meta.lore);
            List<TestStack> equipped = new ArrayList<>();
            equipped.add(stack);
            for (String slot :
                    List.of(
                            "getItemInOffHand",
                            "getHelmet",
                            "getChestplate",
                            "getLeggings",
                            "getBoots")) {
                TestStack copy = stack.clone();
                equipped.add(copy);
                slots.put(slot, copy);
            }
            int writesBefore = stack.metaWrites;
            for (int i = 0; i < 3; i++) {
                registry.profile(player(slots));
            }
            for (TestStack item : equipped) {
                assertEquals(
                        untouched,
                        item.meta.lore,
                        "Unforged equipment keeps its exact styles and component tree");
                assertFalse(item.meta.data.containsKey(new NamespacedKey("yknrpg", "forge_lore")));
                assertSame(
                        originalName,
                        item.meta.name,
                        "Unforged names keep the exact component tree");
            }
            assertEquals(writesBefore, stack.metaWrites, "Collect must not write unforged lore");
            slots.keySet().removeIf(slot -> !slot.equals("getItemInMainHand"));
            var untouchedBase = registry.baseItemStats(stack);
            int readsBefore = stack.metaReads;
            ForgeSupport.rewrite(stack, untouchedBase.values());
            assertEquals(
                    readsBefore,
                    stack.metaReads,
                    "Forge rewrite must not clone ItemMeta for unforged items");
            assertEquals(untouched, stack.meta.lore);
            active.set(provider);
            assertEquals(125, registry.itemStats(stack).get(Stat.DAMAGE));
            assertEquals(10, registry.itemStats(stack).get(Stat.STRENGTH));
            assertEquals(100, registry.baseItemStats(stack).get(Stat.DAMAGE));
            registry.rewriteForgeLore(stack);
            List<String> once = stack.meta.lore.stream().map(PLAIN::serialize).toList();
            assertEquals("伤害：+125 (+25) | 力量：+10", once.getFirst());
            assertEquals(List.of("锻造 +7", "祝福 2/5"), once.subList(2, 4));
            assertSame(description, stack.meta.lore.get(1));
            List<Component> components = List.copyOf(stack.meta.lore);
            registry.rewriteForgeLore(stack);
            assertEquals(once, stack.meta.lore.stream().map(PLAIN::serialize).toList());
            assertEquals(
                    components, stack.meta.lore, "Repeated rewrite keeps the same component tree");
            assertEquals(1, registry.profile(player(slots)).abilities().size());
            disabled.set(true);
            assertTrue(registry.itemStats(stack).values().isEmpty());
            assertTrue(registry.profile(player(slots)).abilities().isEmpty());
            assertEquals("伤害：+0 | 力量：+0", PLAIN.serialize(stack.meta.lore.getFirst()));
            disabled.set(false);
            active.set(null);
            registry.rewriteForgeLore(stack);
            assertEquals(
                    List.of("伤害：+100 | 力量：+10", "技能说明"),
                    stack.meta.lore.stream().map(PLAIN::serialize).toList());
            // Re-registering a provider is observed immediately; no stale service cache.
            active.set(provider);
            assertEquals(125, registry.itemStats(stack).get(Stat.DAMAGE));
            registry.rewriteForgeLore(stack);
            ItemStatOverrides.apply(stack, EditableStat.DAMAGE, 80);
            registry.rewriteForgeLore(stack);
            assertEquals("伤害：+100 (+20) | 力量：+10", PLAIN.serialize(stack.meta.lore.getFirst()));
            var withOtherLore = new ArrayList<>(stack.meta.lore);
            withOtherLore.add(Component.text("另一个插件的说明"));
            stack.meta.lore = withOtherLore;
            registry.rewriteForgeLore(stack);
            assertEquals(
                    1,
                    stack.meta.lore.stream().map(PLAIN::serialize).filter("锻造 +7"::equals).count());
            assertTrue(stack.meta.lore.stream().map(PLAIN::serialize).anyMatch("另一个插件的说明"::equals));
            phaseTwo.set(true);
            assertEquals(
                    103,
                    registry.itemStats(stack).get(Stat.DAMAGE),
                    "flat is added after multiplication");
            assertEquals(
                    2,
                    registry.itemStats(stack).get(Stat.CRIT_CHANCE),
                    "flat can add a missing base stat");
            registry.rewriteForgeLore(stack);
            assertEquals("原有武器名 +7", PLAIN.serialize(stack.meta.name));
            assertEquals(originalName, ForgeSupport.baseName(stack));
            var decorated = stack.meta.name;
            var withFlat = List.copyOf(stack.meta.lore);
            registry.rewriteForgeLore(stack);
            assertEquals(decorated, stack.meta.name);
            assertEquals(withFlat, stack.meta.lore);
            assertEquals(
                    1,
                    stack.meta.lore.stream()
                            .map(PLAIN::serialize)
                            .filter("暴击率：+2% (+2%)"::equals)
                            .count());
            active.set(null);
            registry.rewriteForgeLore(stack);
            assertEquals(originalName, stack.meta.name);
            assertFalse(
                    stack.meta.lore.stream()
                            .map(PLAIN::serialize)
                            .anyMatch(line -> line.contains("暴击率")));
        } finally {
            serverField.set(null, oldServer);
            blockType.set(Material.DIAMOND_SWORD, originalType);
        }
    }

    @Test
    void forgeLoreDoesNotMistakeCritOrSkillDamageForDamage() {
        Component original = Text.component("&9暴击伤害：+50% | &d技能伤害：+15%");
        assertEquals(
                List.of(original),
                ForgeSupport.rewriteLines(List.of(original), Map.of(Stat.DAMAGE, 100.0), s -> 1.5));
    }

    @Test
    void neutralForgeMultipliersKeepUnforgedLoreComponentsExactly() {
        Component line =
                Text.component("&d伤害：&c+55 &8| &a力量：&6+10")
                        .clickEvent(ClickEvent.suggestCommand("/unchanged"));
        List<Component> original = List.of(line, Component.text("原有技能说明"));
        List<Component> result =
                ForgeSupport.rewriteLines(
                        original, Map.of(Stat.DAMAGE, 55.0, Stat.STRENGTH, 10.0), stat -> 1);
        assertEquals(original, result);
        assertSame(line, result.getFirst());
    }

    @Test
    void validatesNumbersAndUsesPercentagePointsRatherThanFractions() {
        assertEquals(12.5, ItemStatOverrides.parseValue(" 12.5% "));
        assertEquals(0.5, ItemStatOverrides.parseValue("+.5"));
        assertEquals(1_000_000, ItemStatOverrides.parseValue("1000000"));
        assertEquals("12.5%", ItemStatOverrides.formatValue(12.5, true));
        assertEquals("0", ItemStatOverrides.formatValue(0, false));
        for (String invalid :
                List.of("", "-1", "NaN", "Infinity", "1000001", "1e8", "0x10", "2%%", "2 3")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ItemStatOverrides.parseValue(invalid),
                    invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> ItemStatOverrides.parseValue(null));
        assertEquals(
                List.of(
                        EditableStat.CRIT_CHANCE,
                        EditableStat.CRIT_DAMAGE,
                        EditableStat.ATTACK_SPEED,
                        EditableStat.ABILITY_DAMAGE,
                        EditableStat.MENDING),
                List.of(EditableStat.values()).stream().filter(EditableStat::percent).toList());
    }

    @Test
    void removesOnlyRetiredAttributesAndPreservesMixedLoreComponents() {
        Component description = Text.component("&7技能说明：活力与凶猛不是此行的属性。");
        Component other =
                Component.text("治疗增幅：", NamedTextColor.GRAY)
                        .append(Component.text("+5", NamedTextColor.GREEN))
                        .clickEvent(ClickEvent.suggestCommand("/unchanged"));
        Component mixed =
                Text.component("&7活力：&a+7.5 &8| ")
                        .append(other)
                        .append(Text.component(" &8| &7凶猛：&c+20"));
        List<Component> cleaned =
                ItemStatOverrides.rewriteLore(List.of(description, mixed), null, 0);
        assertEquals(2, cleaned.size());
        assertSame(description, cleaned.getFirst());
        assertEquals("治疗增幅：+5", PLAIN.serialize(cleaned.get(1)));
        assertTrue(
                containsComponent(cleaned.get(1), other),
                "Retained mixed attribute keeps its styling and event");
        assertEquals(
                List.of(),
                ItemStatOverrides.rewriteLore(List.of(Text.component("&a凶猛：+10")), null, 0));

        Component original = Text.component("&7暴击伤害：&9+20% | &7伤害：&c+55 | &7防御：&a+10");
        List<Component> edited =
                ItemStatOverrides.rewriteLore(
                        List.of(original, description), EditableStat.DAMAGE, 80.5);
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
        for (Material material :
                List.of(
                        Material.DIAMOND_SWORD,
                        Material.DIAMOND_HELMET,
                        Material.IRON_CHESTPLATE,
                        Material.IRON_LEGGINGS,
                        Material.IRON_BOOTS,
                        Material.STICK)) {
            oldBlockTypes.put(material, blockType.get(material));
            blockType.set(material, (Supplier<?>) () -> null);
        }
        try {
            MythicItems mythic = new MythicItems(null);
            field(MythicItems.class, "itemManager").set(mythic, new MythicStub());
            field(MythicItems.class, "isMythicItem")
                    .set(mythic, MythicStub.class.getMethod("isMythicItem", ItemStack.class));
            RpgRegistry registry = new RpgRegistry(null, mythic);
            YamlConfiguration config = new YamlConfiguration();
            config.set("damage", 55);
            config.set("strength", 10);
            config.set("vitality", 99);
            config.set("ferocity", 80);
            Method parseStats =
                    RpgRegistry.class.getDeclaredMethod("parseStats", ConfigurationSection.class);
            parseStats.setAccessible(true);
            RpgRegistry.Stats templateStats =
                    (RpgRegistry.Stats) parseStats.invoke(registry, config);
            assertEquals(0, templateStats.get(Stat.VITALITY));
            assertEquals(0, templateStats.get(Stat.FEROCITY));
            RpgRegistry.Ability ability =
                    new RpgRegistry.Ability(
                            "test",
                            "技能",
                            RpgRegistry.Trigger.RIGHT_CLICK,
                            5,
                            10,
                            0,
                            1,
                            1,
                            16,
                            true,
                            true,
                            false,
                            false,
                            100,
                            null,
                            List.of());
            Class<?> powerClass = Class.forName("cn.yknrpg.rpg.RpgRegistry$ItemPower");
            Constructor<?> constructor = powerClass.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> templates =
                    (Map<String, Object>) field(RpgRegistry.class, "items").get(registry);
            templates.put(
                    "YKI_TEST",
                    constructor.newInstance(
                            "YKI_TEST",
                            RpgRegistry.Slot.MAIN_HAND,
                            templateStats,
                            List.of(ability)));

            TestStack original = new TestStack(Material.DIAMOND_SWORD);
            original.meta.data.put(new NamespacedKey("mythicmobs", "type"), "YKI_TEST");
            original.meta.data.put(new NamespacedKey("other", "marker"), "retained");
            original.meta.name = Component.text("原来的名字");
            original.meta.model = 901;
            original.meta.lore =
                    List.of(
                            Text.component("&7伤害：&c+55 | &7力量：&c+10"),
                            Text.component("&7活力：&a+99 | &7凶猛：&c+80"),
                            Component.text("技能说明"));
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
            assertEquals(
                    List.of(ability),
                    registry.profile(player).abilities().stream()
                            .map(RpgRegistry.AbilitySource::ability)
                            .toList());

            TestStack ordinary = new TestStack(Material.STICK);
            ItemStatOverrides.apply(ordinary, EditableStat.STRENGTH, 30);
            slots.put("getItemInMainHand", ordinary);
            slots.put("getItemInOffHand", ordinary.clone());
            assertEquals(30, registry.profile(player).stats().get(Stat.STRENGTH));
            assertEquals(0, registry.profile(player).abilities().size());
            for (var armor :
                    Map.of(
                                    Material.DIAMOND_HELMET,
                                    "getHelmet",
                                    Material.IRON_CHESTPLATE,
                                    "getChestplate",
                                    Material.IRON_LEGGINGS,
                                    "getLeggings",
                                    Material.IRON_BOOTS,
                                    "getBoots")
                            .entrySet()) {
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
            assertEquals(
                    2,
                    original.meta.lore.size(),
                    "Equipped legacy item loses obsolete lore without a reissue");
            assertFalse(
                    original.meta.lore.stream()
                            .map(PLAIN::serialize)
                            .anyMatch(s -> s.contains("凶猛")));
            ordinary.amount = 2;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ItemStatOverrides.apply(ordinary, EditableStat.DAMAGE, 2));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ItemStatOverrides.apply(edited, EditableStat.DAMAGE, Double.NaN));
            assertEquals(100, registry.itemStats(edited).get(Stat.DAMAGE));
        } finally {
            for (var entry : oldBlockTypes.entrySet()) {
                blockType.set(entry.getKey(), entry.getValue());
            }
        }
    }

    private static boolean containsComponent(Component parent, Component wanted) {
        return parent.equals(wanted)
                || parent.children().stream().anyMatch(child -> containsComponent(child, wanted));
    }

    private static Player player(Map<String, ItemStack> slots) {
        PlayerInventory inventory =
                (PlayerInventory)
                        Proxy.newProxyInstance(
                                ItemStatOverridesTest.class.getClassLoader(),
                                new Class<?>[] {PlayerInventory.class},
                                (proxy, method, args) -> slots.get(method.getName()));
        return (Player)
                Proxy.newProxyInstance(
                        ItemStatOverridesTest.class.getClassLoader(),
                        new Class<?>[] {Player.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getInventory")) {
                                return inventory;
                            }
                            throw new AssertionError(method.getName());
                        });
    }

    public static final class MythicStub {
        public boolean isMythicItem(ItemStack stack) {
            return false;
        }
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
        private int metaReads;
        private int metaWrites;

        private TestStack(Material material) {
            this.material = material;
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public boolean hasItemMeta() {
            return true;
        }

        @Override
        public ItemMeta getItemMeta() {
            metaReads++;
            return meta.copy().proxy();
        }

        @Override
        public io.papermc.paper.persistence.PersistentDataContainerView
                getPersistentDataContainer() {
            return meta.container();
        }

        @Override
        public boolean setItemMeta(ItemMeta value) {
            metaWrites++;
            meta = ((MetaState) Proxy.getInvocationHandler(value)).copy();
            return true;
        }

        @Override
        public TestStack clone() {
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
            return (ItemMeta)
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(), new Class<?>[] {ItemMeta.class}, this);
        }

        private PersistentDataContainer container() {
            return (PersistentDataContainer)
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {PersistentDataContainer.class},
                            (container, operation, values) -> {
                                if (operation.getName().equals("get")
                                        || operation.getName().equals("has")) {
                                    Object value = data.get(values[0]);
                                    boolean valid =
                                            ((PersistentDataType<?, ?>) values[1])
                                                    .getComplexType()
                                                    .isInstance(value);
                                    return operation.getName().equals("has")
                                            ? valid
                                            : valid ? value : null;
                                }
                                if (operation.getName().equals("set")) {
                                    data.put((NamespacedKey) values[0], values[2]);
                                    return null;
                                }
                                throw new AssertionError(operation.getName());
                            });
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("displayName")) {
                if (args == null || args.length == 0) {
                    return name;
                }
                name = (Component) args[0];
                return null;
            }
            if (method.getName().equals("lore")) {
                if (args == null || args.length == 0) {
                    return lore;
                }
                @SuppressWarnings("unchecked")
                List<Component> value = (List<Component>) args[0];
                lore = value;
                return null;
            }
            if (method.getName().equals("getPersistentDataContainer")) {
                return container();
            }
            throw new AssertionError(method.getName());
        }
    }
}
