package cn.yknrpg.recipe;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.integration.MythicItems;
import cn.yknrpg.integration.MythicProfessionItems;
import cn.yknrpg.item.CustomItemFactory;
import cn.yknrpg.model.IngredientSpec;
import cn.yknrpg.model.ItemDefinition;
import cn.yknrpg.model.ItemDefinition.CatalogKind;
import cn.yknrpg.model.ItemDefinition.ItemSource;
import cn.yknrpg.model.ItemDefinition.Rarity;
import cn.yknrpg.registry.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeRegistrarReloadTest {
    @Test
    void cachedMenuAndUnchangedRecipesDoNotMutateTheServerRecipeBook(@TempDir Path directory) throws ReflectiveOperationException {
        Map<NamespacedKey, Recipe> serverRecipes = new LinkedHashMap<>();
        List<String> mutations = new ArrayList<>();
        AtomicInteger recipeScans = new AtomicInteger();
        Set<String> enabledPlugins = new HashSet<>(Set.of("YknFish", "YknMining"));
        AtomicBoolean miningReady = new AtomicBoolean(true);
        ReadyMiningPlugin miningPlugin = (ReadyMiningPlugin) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ReadyMiningPlugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isIslandReady")) return miningReady.get();
                    throw new AssertionError(method.getName());
                });
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isPluginEnabled")) return enabledPlugins.contains(args[0]);
                    if (method.getName().equals("getPlugin") && args[0].equals("YknMining")) return miningPlugin;
                    throw new AssertionError(method.getName());
                });
        Server server = (Server) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Server.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPluginManager" -> manager;
                    case "getRecipe" -> serverRecipes.get(args[0]);
                    case "recipeIterator" -> {
                        recipeScans.incrementAndGet();
                        yield new ArrayList<>(serverRecipes.values()).iterator();
                    }
                    case "addRecipe" -> {
                        Recipe recipe = (Recipe) args[0];
                        mutations.add("add:" + ((Keyed) recipe).getKey());
                        if (recipe instanceof ShapedRecipe shaped) recipe = serverRoundTrip(shaped);
                        yield serverRecipes.putIfAbsent(((Keyed) recipe).getKey(), recipe) == null;
                    }
                    case "removeRecipe" -> {
                        mutations.add("remove:" + args[0]);
                        yield serverRecipes.remove(args[0]) != null;
                    }
                    default -> throw new AssertionError("Unexpected Bukkit call: " + method.getName());
                });
        Field serverField = field(Bukkit.class, "server");
        Object previousServer = serverField.get(null);
        Field blockTypeField = field(Material.class, "blockType");
        Object previousBlockType = blockTypeField.get(Material.STONE);
        try {
            serverField.set(null, server);
            // isAir() accepts a null block type; avoid requiring Paper's live RegistryAccess service.
            blockTypeField.set(Material.STONE, (Supplier<?>) () -> null);
            // The plugin remains disabled; bypass only JavaPlugin's required live plugin class loader.
            Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            // MaterialChoice's constructor requires the live item registry; seed its documented choices only.
            RecipeChoice.MaterialChoice materialChoice =
                    (RecipeChoice.MaterialChoice) unsafe.allocateInstance(RecipeChoice.MaterialChoice.class);
            Class<?> serverChoiceType = Class.forName("org.bukkit.inventory.ItemTypeRecipeChoiceImpl");
            RecipeChoice.MaterialChoice serverChoice =
                    (RecipeChoice.MaterialChoice) unsafe.allocateInstance(serverChoiceType);
            field(RecipeChoice.MaterialChoice.class, "choices").set(materialChoice, List.of(Material.STONE));
            field(serverChoiceType, "legacyChoices").set(serverChoice, List.of(Material.STONE));
            assertFalse(materialChoice.equals(serverChoice), "Paper's material readback has a different runtime class");
            NamespacedKey materialKey = new NamespacedKey("yknrpg", "material_round_trip");
            ShapedRecipe materialOriginal = new ShapedRecipe(materialKey, new TestStack("MATERIAL_RESULT"))
                    .shape("AA").setIngredient('A', materialChoice);
            ShapedRecipe materialReadback = new ShapedRecipe(materialKey, new TestStack("MATERIAL_RESULT")) {
                @Override public Map<Character, RecipeChoice> getChoiceMap() {
                    return Map.of('a', serverChoice, 'b', serverChoice);
                }
            }.shape("ab");
            assertTrue(RecipeRegistrar.sameRecipe(materialReadback, materialOriginal));

            YknRpgPlugin plugin = (YknRpgPlugin) unsafe.allocateInstance(YknRpgPlugin.class);
            field(JavaPlugin.class, "pluginMeta").set(plugin,
                    new PluginDescriptionFile("YknRpg", "test", YknRpgPlugin.class.getName()));
            field(JavaPlugin.class, "dataFolder").set(plugin, directory.toFile());
            MythicItems mythic = new MythicItems(plugin);
            MythicStub mythicStub = new MythicStub();
            field(MythicItems.class, "itemManager").set(mythic, mythicStub);
            field(MythicItems.class, "getItem").set(mythic, MythicStub.class.getMethod("getItem", String.class));
            field(MythicItems.class, "adaptItemStack").set(mythic,
                    MythicStub.class.getMethod("adapt", ItemStack.class));
            field(MythicItems.class, "isMythicItem").set(mythic,
                    MythicStub.class.getMethod("isMythicItem", ItemStack.class));
            field(MythicItems.class, "getMythicTypeFromItem").set(mythic,
                    MythicStub.class.getMethod("getMythicTypeFromItem", ItemStack.class));
            MythicProfessionItems fishing = MythicProfessionItems.fishing(mythic);
            MythicProfessionItems mining = MythicProfessionItems.mining(mythic);
            ItemRegistry registry = new ItemRegistry(plugin, mythic, null, fishing, mining);
            CustomItemFactory factory = new CustomItemFactory(plugin, registry, mythic, null);
            RecipeRegistrar registrar = new RecipeRegistrar(plugin, registry, factory, fishing, mining);
            @SuppressWarnings("unchecked")
            Map<String, ItemDefinition> items = (Map<String, ItemDefinition>) field(ItemRegistry.class, "items").get(registry);
            @SuppressWarnings("unchecked")
            Map<String, List<IngredientSpec>> overrides =
                    (Map<String, List<IngredientSpec>>) field(ItemRegistry.class, "recipeOverrides").get(registry);
            List<IngredientSpec> original = recipe("MATERIAL_A");
            for (int index = 0; index < 327; index++) {
                String id = "TEST_" + index;
                items.put(id, new ItemDefinition(id, ItemSource.MYTHIC_MOBS, original,
                        CatalogKind.ITEM, Rarity.COMMON, index));
            }
            registrar.reload();
            assertEquals(327, serverRecipes.size());
            mutations.clear();
            registrar.reload();
            assertEquals(List.of(), mutations, "An unchanged catalog must perform zero recipe mutations");

            items.put("TEST_5", items.get("TEST_5").withRecipe(recipe("MATERIAL_B")));
            registrar.reload();
            assertEquals(List.of("remove:yknrpg:craft_test_5", "add:yknrpg:craft_test_5"), mutations);

            NamespacedKey nativeKey = new NamespacedKey("yknfish", "fish");
            ShapelessRecipe nativeRecipe = new ShapelessRecipe(nativeKey, new TestStack("FISH"))
                    .addIngredient(new RecipeChoice.ExactChoice(new TestStack("MATERIAL_A")));
            serverRecipes.put(nativeKey, nativeRecipe);
            items.put("FISH", new ItemDefinition("FISH", ItemSource.MYTHIC_MOBS, original,
                    CatalogKind.FISHING_CATCH, Rarity.COMMON, 0));
            overrides.put("FISH", original);
            registrar.reload();
            mutations.clear();
            registrar.reload();
            assertEquals(List.of(), mutations, "An existing fishing override must not restore and suppress its native recipe again");
            overrides.remove("FISH");
            registrar.reload();
            assertEquals(List.of("remove:yknrpg:craft_fish", "add:yknfish:fish"), mutations);
            assertSame(nativeRecipe, serverRecipes.get(nativeKey));

            NamespacedKey miningKey = new NamespacedKey("yknmining", "material");
            ShapelessRecipe miningRecipe = new ShapelessRecipe(miningKey, new TestStack("MINING"))
                    .addIngredient(new RecipeChoice.ExactChoice(new TestStack("MATERIAL_A")));
            NamespacedKey unrelatedKey = new NamespacedKey("anotherplugin", "same_result");
            ShapelessRecipe unrelatedRecipe = new ShapelessRecipe(unrelatedKey, new TestStack("MINING"))
                    .addIngredient(new RecipeChoice.ExactChoice(new TestStack("MATERIAL_A")));
            serverRecipes.put(miningKey, miningRecipe);
            serverRecipes.put(unrelatedKey, unrelatedRecipe);
            items.put("MINING", new ItemDefinition("MINING", ItemSource.MYTHIC_MOBS, original,
                    CatalogKind.MINING_ITEM, Rarity.RARE, 0));
            @SuppressWarnings("unchecked")
            Map<String, List<IngredientSpec>> baseRecipes =
                    (Map<String, List<IngredientSpec>>) field(ItemRegistry.class, "baseRecipes").get(registry);
            baseRecipes.put("MINING", original);
            mutations.clear();
            registrar.reload();
            assertEquals(List.of(), mutations, "Unmodified mining items keep their provider's native recipes");
            assertSame(miningRecipe, serverRecipes.get(miningKey));

            registry.updateRecipe("MINING", recipe("MATERIAL_B"));
            registrar.reload();
            assertEquals(List.of("add:yknrpg:craft_mining", "remove:yknmining:material"), mutations);
            assertSame(nativeRecipe, serverRecipes.get(nativeKey), "Mining overrides must not suppress fishing recipes");
            assertSame(unrelatedRecipe, serverRecipes.get(unrelatedKey), "Only the provider's namespace is managed");
            mutations.clear();
            registrar.reload();
            assertEquals(List.of(), mutations, "Unchanged mining overrides must perform zero recipe mutations");

            registry.resetRecipe("MINING");
            registrar.reload();
            assertEquals(List.of("remove:yknrpg:craft_mining", "add:yknmining:material"), mutations);
            assertSame(miningRecipe, serverRecipes.get(miningKey));

            mutations.clear();
            registry.updateRecipe("MINING", Collections.nCopies(9, IngredientSpec.empty()));
            registrar.reload();
            assertEquals(List.of("remove:yknmining:material"), mutations, "Saving an empty mining recipe disables native crafting");
            mutations.clear();
            registrar.reload();
            assertEquals(List.of(), mutations);
            ShapelessRecipe updatedMiningRecipe = new ShapelessRecipe(miningKey, new TestStack("MINING"))
                    .addIngredient(new RecipeChoice.ExactChoice(new TestStack("MATERIAL_B")));
            serverRecipes.put(miningKey, updatedMiningRecipe);
            registrar.reload();
            assertEquals(List.of("remove:yknmining:material"), mutations,
                    "A provider's late registration must not bypass a saved disabled recipe");
            mutations.clear();
            registry.resetRecipe("MINING");
            registrar.reload();
            assertEquals(List.of("add:yknmining:material"), mutations);
            assertSame(updatedMiningRecipe, serverRecipes.get(miningKey), "Reset restores the most recently registered native recipe");

            field(YknRpgPlugin.class, "registry").set(plugin, registry);
            field(YknRpgPlugin.class, "fishingItems").set(plugin, fishing);
            field(YknRpgPlugin.class, "miningItems").set(plugin, mining);
            field(YknRpgPlugin.class, "miningCatalogReady").set(plugin, true);
            // recipes and MythicItems#getItemNames deliberately remain null: a full reload is forbidden here.
            AtomicInteger messages = new AtomicInteger();
            Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Player.class}, (proxy, method, args) -> {
                        if (method.getName().equals("sendMessage")) { messages.incrementAndGet(); return null; }
                        throw new AssertionError(method.getName());
                    });
            mutations.clear();
            recipeScans.set(0);
            for (int index = 0; index < 5; index++) {
                assertTrue(plugin.refreshFishingCatalog(player));
                assertTrue(plugin.refreshMiningCatalog(player));
            }
            assertEquals(List.of(), mutations);
            assertEquals(0, recipeScans.get());
            assertEquals(0, messages.get());

            miningReady.set(false);
            assertFalse(mining.ready());
            assertEquals(List.of(), mining.load());
            mining.suppressRecipes(Set.of("MINING"));
            assertEquals(0, recipeScans.get(), "Unready mining providers must not scan incomplete native recipes");
            assertEquals(List.of(), mutations);
            miningReady.set(true);
            field(MythicItems.class, "getItemNames").set(mythic, MythicStub.class.getMethod("getItemNames"));
            mythicStub.ids = List.of("MINING", "MINING_NEW", "FISH", "TEST_5");
            registry.refreshMining();
            assertEquals(List.of("MINING", "MINING_NEW"), registry.items(CatalogKind.MINING_ITEM)
                    .stream().map(ItemDefinition::id).toList());
            assertEquals(Rarity.RARE, registry.item("MINING").orElseThrow().rarity());
            assertEquals(IngredientSpec.custom("MATERIAL_B"), registry.item("MINING").orElseThrow().recipe().getFirst());
            assertEquals(1, registry.items(CatalogKind.FISHING_CATCH).size());
            assertEquals(327, registry.items(CatalogKind.ITEM).size());
            registry.updateRecipe("MINING", recipe("MATERIAL_C"));
            registrar.reload();
            mining.forgetRecipes();
            enabledPlugins.remove("YknMining");
            registry.refreshMining();
            assertTrue(registry.items().stream().noneMatch(item -> item.kind().isMining()));
            assertTrue(registry.hasRecipeOverride("MINING"), "Provider unavailability must not erase stored overrides");
            assertEquals(1, registry.items(CatalogKind.FISHING_CATCH).size());
            assertEquals(327, registry.items(CatalogKind.ITEM).size());
            mutations.clear();
            registrar.reload();
            assertEquals(List.of("remove:yknrpg:craft_mining"), mutations);
            enabledPlugins.add("YknMining");
            mining.restoreRecipes();
            assertFalse(serverRecipes.containsKey(miningKey), "A disabled provider's forgotten recipe must not be resurrected");
            serverRecipes.put(miningKey, updatedMiningRecipe);
            registry.refreshMining();
            assertEquals(recipe("MATERIAL_C"), registry.item("MINING").orElseThrow().recipe());
            registrar.reload();
            registry.resetRecipe("MINING");
            assertEquals(IngredientSpec.custom("MATERIAL_B"), registry.item("MINING").orElseThrow().recipe().getFirst());
            registrar.reload();
            assertSame(updatedMiningRecipe, serverRecipes.get(miningKey));
        } finally {
            serverField.set(null, previousServer);
            blockTypeField.set(Material.STONE, previousBlockType);
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static List<IngredientSpec> recipe(String material) {
        List<IngredientSpec> result = new ArrayList<>(Collections.nCopies(9, IngredientSpec.empty()));
        result.set(0, IngredientSpec.custom(material));
        result.set(1, IngredientSpec.custom(material));
        result.set(4, IngredientSpec.custom("MATERIAL_C"));
        result.set(8, IngredientSpec.custom(material));
        return result;
    }

    private static ShapedRecipe serverRoundTrip(ShapedRecipe original) {
        // Paper reads back per-cell lowercase symbols, including unassigned symbols for empty cells.
        String[] shape = original.getShape();
        String[] serverShape = new String[shape.length];
        for (int row = 0; row < shape.length; row++) {
            StringBuilder line = new StringBuilder();
            for (int column = 0; column < shape[row].length(); column++) line.append((char) ('a' + row * 3 + column));
            serverShape[row] = line.toString();
        }
        ShapedRecipe readback = new ShapedRecipe(original.getKey(), original.getResult()).shape(serverShape);
        Map<Character, RecipeChoice> choices = original.getChoiceMap();
        for (int row = 0; row < shape.length; row++) {
            for (int column = 0; column < shape[row].length(); column++) {
                RecipeChoice choice = choices.get(shape[row].charAt(column));
                if (choice != null) readback.setIngredient(serverShape[row].charAt(column), choice);
            }
        }
        return readback;
    }

    public static class MythicStub {
        private List<String> ids = List.of();
        public List<String> getItemNames() { return ids; }
        public Optional<MythicItemStub> getItem(String id) { return Optional.of(new MythicItemStub(id)); }
        public static ItemStack adapt(ItemStack stack) { return stack; }
        public boolean isMythicItem(ItemStack stack) { return stack instanceof TestStack; }
        public String getMythicTypeFromItem(ItemStack stack) { return ((TestStack) stack).id; }
    }

    public interface ReadyMiningPlugin extends Plugin {
        boolean isIslandReady();
    }

    public record MythicItemStub(String id) {
        public ItemStack generateItemStack(int amount) { return new TestStack(id); }
        public String getGroup() { return id.startsWith("MINING") ? "YknMining" : id.equals("FISH") ? "YknFish" : "YknRpg"; }
    }

    private static final class TestStack extends ItemStack {
        private final String id;
        private int amount = 1;
        private TestStack(String id) { this.id = id; }
        @Override public Material getType() { return Material.STONE; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public boolean isEmpty() { return false; }
        @Override public boolean hasItemMeta() { return false; }
        @Override public List<Component> lore() { return List.of(Component.text("稀有")); }
        @Override public ItemStack clone() { TestStack copy = new TestStack(id); copy.amount = amount; return copy; }
        @Override public boolean isSimilar(ItemStack other) { return other instanceof TestStack stack && id.equals(stack.id); }
        @Override public boolean equals(Object other) { return other instanceof ItemStack stack && isSimilar(stack) && amount == stack.getAmount(); }
        @Override public int hashCode() { return 31 * id.hashCode() + amount; }
    }
}
