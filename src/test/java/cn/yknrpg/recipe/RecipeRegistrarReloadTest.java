package cn.yknrpg.recipe;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.integration.MythicItems;
import cn.yknrpg.integration.YknFishItems;
import cn.yknrpg.item.CustomItemFactory;
import cn.yknrpg.model.IngredientSpec;
import cn.yknrpg.model.ItemDefinition;
import cn.yknrpg.model.ItemDefinition.CatalogKind;
import cn.yknrpg.model.ItemDefinition.ItemSource;
import cn.yknrpg.model.ItemDefinition.Rarity;
import cn.yknrpg.registry.ItemRegistry;
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
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeRegistrarReloadTest {
    @Test
    void cachedMenuAndUnchangedRecipesDoNotMutateTheServerRecipeBook() throws ReflectiveOperationException {
        Map<NamespacedKey, Recipe> serverRecipes = new LinkedHashMap<>();
        List<String> mutations = new ArrayList<>();
        AtomicInteger recipeScans = new AtomicInteger();
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("isPluginEnabled")) return true;
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
            MythicItems mythic = new MythicItems(plugin);
            field(MythicItems.class, "itemManager").set(mythic, new MythicStub());
            field(MythicItems.class, "getItem").set(mythic, MythicStub.class.getMethod("getItem", String.class));
            field(MythicItems.class, "adaptItemStack").set(mythic,
                    MythicStub.class.getMethod("adapt", ItemStack.class));
            field(MythicItems.class, "isMythicItem").set(mythic,
                    MythicStub.class.getMethod("isMythicItem", ItemStack.class));
            field(MythicItems.class, "getMythicTypeFromItem").set(mythic,
                    MythicStub.class.getMethod("getMythicTypeFromItem", ItemStack.class));
            YknFishItems fishing = new YknFishItems(mythic);
            ItemRegistry registry = new ItemRegistry(plugin, mythic, null, fishing);
            CustomItemFactory factory = new CustomItemFactory(plugin, registry, mythic, null);
            RecipeRegistrar registrar = new RecipeRegistrar(plugin, registry, factory, fishing);
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

            field(YknRpgPlugin.class, "registry").set(plugin, registry);
            field(YknRpgPlugin.class, "fishingItems").set(plugin, fishing);
            // recipes and MythicItems#getItemNames deliberately remain null: a full reload is forbidden here.
            AtomicInteger messages = new AtomicInteger();
            Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Player.class}, (proxy, method, args) -> {
                        if (method.getName().equals("sendMessage")) { messages.incrementAndGet(); return null; }
                        throw new AssertionError(method.getName());
                    });
            mutations.clear();
            recipeScans.set(0);
            for (int index = 0; index < 5; index++) assertTrue(plugin.refreshFishingCatalog(player));
            assertEquals(List.of(), mutations);
            assertEquals(0, recipeScans.get());
            assertEquals(0, messages.get());
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
        public Optional<MythicItemStub> getItem(String id) { return Optional.of(new MythicItemStub(id)); }
        public static ItemStack adapt(ItemStack stack) { return stack; }
        public boolean isMythicItem(ItemStack stack) { return stack instanceof TestStack; }
        public String getMythicTypeFromItem(ItemStack stack) { return ((TestStack) stack).id; }
    }

    public record MythicItemStub(String id) {
        public ItemStack generateItemStack(int amount) { return new TestStack(id); }
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
        @Override public ItemStack clone() { TestStack copy = new TestStack(id); copy.amount = amount; return copy; }
        @Override public boolean isSimilar(ItemStack other) { return other instanceof TestStack stack && id.equals(stack.id); }
        @Override public boolean equals(Object other) { return other instanceof ItemStack stack && isSimilar(stack) && amount == stack.getAmount(); }
        @Override public int hashCode() { return 31 * id.hashCode() + amount; }
    }
}
