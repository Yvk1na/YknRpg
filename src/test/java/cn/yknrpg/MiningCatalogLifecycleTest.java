package cn.yknrpg;

import cn.yknrpg.integration.MythicItems;
import cn.yknrpg.integration.MythicProfessionItems;
import cn.yknrpg.model.ItemDefinition.CatalogKind;
import cn.yknrpg.recipe.RecipeRegistrar;
import cn.yknrpg.registry.ItemRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MiningCatalogLifecycleTest {
    @Test
    void initializationWaitsOnceThenCachesAndRejectsDisabledProvider() throws Exception {
        try (Harness state = new Harness()) {
            for (int attempt = 0; attempt < 5; attempt++) {
                assertFalse(state.plugin.refreshMiningCatalog(state.player));
                state.task().tick();
            }
            assertEquals(1, state.tasks.size(), "Repeated opens must reuse the pending retry");
            assertEquals(0, state.recipeScans);
            assertEquals(0, state.mythic.scans);
            assertEquals(List.of(), state.recipeMutations);

            state.islandReady = true;
            assertFalse(state.plugin.refreshMiningCatalog(state.player), "Only the scheduled sync marks the catalog ready");
            state.task().tick();
            assertTrue(state.task().isCancelled());
            assertTrue((boolean) field(YknRpgPlugin.class, "miningCatalogReady").get(state.plugin));
            assertEquals(1, state.recipeScans);
            assertEquals(1, state.mythic.scans);
            assertEquals(List.of("remove:yknrpg:stale_mining_override"), state.recipeMutations);
            assertEquals(1, state.registry.items(CatalogKind.MINING_ITEM).size());

            for (int attempt = 0; attempt < 5; attempt++) assertTrue(state.plugin.refreshMiningCatalog(state.player));
            assertEquals(1, state.tasks.size());
            assertEquals(1, state.recipeScans, "Cached menus must not enumerate native recipes");
            assertEquals(1, state.mythic.scans);
            assertEquals(1, state.recipeMutations.size(), "Cached menus must not mutate recipes");

            state.miningEnabled = false;
            assertFalse(state.plugin.refreshMiningCatalog(state.player), "A cached catalog cannot authorize a disabled provider");
            state.plugin.onProfessionPluginDisable(new PluginDisableEvent(state.miningPlugin));
            state.task().tick();
            assertFalse((boolean) field(YknRpgPlugin.class, "miningCatalogReady").get(state.plugin));
            assertTrue(state.registry.items(CatalogKind.MINING_ITEM).isEmpty());
            assertEquals(1, state.recipeScans);
        }
    }

    @Test
    void timeoutCancelsAtThreeHundredAttemptsAndNextOpenCanRetry() throws Exception {
        try (Harness state = new Harness()) {
            assertFalse(state.plugin.refreshMiningCatalog(state.player));
            TestTask original = state.task();
            for (int attempt = 0; attempt < 299; attempt++) original.tick();
            assertFalse(original.isCancelled());
            original.tick();
            assertTrue(original.isCancelled());
            assertNull(field(YknRpgPlugin.class, "miningCatalogRetryTask").get(state.plugin));
            assertEquals(0, state.recipeScans);
            assertEquals(0, state.mythic.scans);
            assertEquals(List.of(), state.recipeMutations);

            assertFalse(state.plugin.refreshMiningCatalog(state.player));
            assertEquals(2, state.tasks.size());
            assertFalse(state.task().isCancelled());
            state.islandReady = true;
            state.task().tick();
            assertTrue(state.plugin.refreshMiningCatalog(state.player));
            assertEquals(1, state.recipeScans);
            assertEquals(1, state.mythic.scans);
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Harness implements AutoCloseable {
        private final Object previousServer = field(Bukkit.class, "server").get(null);
        private final Object previousBlockType = field(Material.class, "blockType").get(Material.STONE);
        private final YknRpgPlugin plugin;
        private final ItemRegistry registry;
        private final MythicStub mythic = new MythicStub();
        private final List<TestTask> tasks = new ArrayList<>();
        private final List<String> recipeMutations = new ArrayList<>();
        private final Plugin miningPlugin;
        private final Player player;
        private boolean miningEnabled = true;
        private boolean islandReady;
        private int recipeScans;

        @SuppressWarnings("unchecked")
        private Harness() throws ReflectiveOperationException {
            ClassLoader loader = getClass().getClassLoader();
            miningPlugin = (Plugin) Proxy.newProxyInstance(loader, new Class<?>[]{Plugin.class, IslandStatus.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getName" -> "YknMining";
                        case "isEnabled" -> miningEnabled;
                        case "isIslandReady" -> islandReady;
                        default -> throw new AssertionError(method.getName());
                    });
            PluginManager manager = (PluginManager) Proxy.newProxyInstance(loader, new Class<?>[]{PluginManager.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isPluginEnabled" -> "YknMining".equals(args[0]) && miningEnabled;
                        case "getPlugin" -> "YknMining".equals(args[0]) ? miningPlugin : null;
                        default -> throw new AssertionError(method.getName());
                    });
            BukkitScheduler scheduler = (BukkitScheduler) Proxy.newProxyInstance(loader, new Class<?>[]{BukkitScheduler.class},
                    (proxy, method, args) -> {
                        assertEquals("runTaskTimer", method.getName());
                        assertEquals(1L, args[2]);
                        assertEquals(20L, args[3]);
                        TestTask task = new TestTask((Plugin) args[0], (Runnable) args[1]);
                        tasks.add(task);
                        return task;
                    });
            Server server = (Server) Proxy.newProxyInstance(loader, new Class<?>[]{Server.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getPluginManager" -> manager;
                        case "getScheduler" -> scheduler;
                        case "isPrimaryThread" -> true;
                        case "recipeIterator" -> {
                            recipeScans++;
                            yield Collections.<Recipe>emptyIterator();
                        }
                        case "removeRecipe" -> { recipeMutations.add("remove:" + args[0]); yield true; }
                        case "addRecipe" -> { recipeMutations.add("add:" + args[0]); yield true; }
                        default -> throw new AssertionError(method.getName());
                    });
            player = (Player) Proxy.newProxyInstance(loader, new Class<?>[]{Player.class}, (proxy, method, args) -> {
                if (method.getName().equals("sendMessage")) return null;
                throw new AssertionError(method.getName());
            });
            // Only bypass JavaPlugin's live class loader requirement, not its lifecycle methods under test.
            Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
            plugin = (YknRpgPlugin) unsafe.allocateInstance(YknRpgPlugin.class);
            field(JavaPlugin.class, "isEnabled").set(plugin, true);
            field(JavaPlugin.class, "server").set(plugin, server);
            field(JavaPlugin.class, "logger").set(plugin, Logger.getAnonymousLogger());
            YamlConfiguration config = new YamlConfiguration();
            config.set("messages.mining-loading", "loading");
            config.set("messages.mining-unavailable", "unavailable");
            field(JavaPlugin.class, "newConfig").set(plugin, config);
            MythicItems items = new MythicItems(plugin);
            field(MythicItems.class, "itemManager").set(items, mythic);
            field(MythicItems.class, "getItemNames").set(items, MythicStub.class.getMethod("getItemNames"));
            field(MythicItems.class, "getItem").set(items, MythicStub.class.getMethod("getItem", String.class));
            field(MythicItems.class, "adaptItemStack").set(items, MythicStub.class.getMethod("adapt", ItemStack.class));
            MythicProfessionItems mining = MythicProfessionItems.mining(items);
            MythicProfessionItems fishing = MythicProfessionItems.fishing(items);
            registry = new ItemRegistry(plugin, items, null, fishing, mining);
            RecipeRegistrar recipes = new RecipeRegistrar(plugin, registry, null, fishing, mining);
            ((List<NamespacedKey>) field(RecipeRegistrar.class, "keys").get(recipes))
                    .add(new NamespacedKey("yknrpg", "stale_mining_override"));
            field(YknRpgPlugin.class, "registry").set(plugin, registry);
            field(YknRpgPlugin.class, "recipes").set(plugin, recipes);
            field(YknRpgPlugin.class, "miningItems").set(plugin, mining);
            field(Bukkit.class, "server").set(null, server);
            field(Material.class, "blockType").set(Material.STONE, (Supplier<?>) () -> null);
        }

        private TestTask task() { return tasks.getLast(); }

        @Override
        public void close() throws ReflectiveOperationException {
            field(Bukkit.class, "server").set(null, previousServer);
            field(Material.class, "blockType").set(Material.STONE, previousBlockType);
        }
    }

    public interface IslandStatus { boolean isIslandReady(); }

    private static final class TestTask implements BukkitTask {
        private final Plugin owner;
        private final Runnable action;
        private boolean cancelled;
        private TestTask(Plugin owner, Runnable action) { this.owner = owner; this.action = action; }
        private void tick() { assertFalse(cancelled); action.run(); }
        @Override public int getTaskId() { return 1; }
        @Override public Plugin getOwner() { return owner; }
        @Override public boolean isSync() { return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void cancel() { cancelled = true; }
    }

    public static final class MythicStub {
        private int scans;
        public List<String> getItemNames() { scans++; return List.of("YKM_TEST_ITEM"); }
        public Optional<MythicItemStub> getItem(String id) { return Optional.of(new MythicItemStub()); }
        public static ItemStack adapt(ItemStack stack) { return stack; }
    }

    public static final class MythicItemStub {
        public String getGroup() { return "YknMining"; }
        public ItemStack generateItemStack(int amount) { return new TestStack(); }
    }

    private static final class TestStack extends ItemStack {
        @Override public Material getType() { return Material.STONE; }
        @Override public void setAmount(int amount) { }
        @Override public ItemStack clone() { return new TestStack(); }
        @Override public List<Component> lore() { return null; }
    }
}
