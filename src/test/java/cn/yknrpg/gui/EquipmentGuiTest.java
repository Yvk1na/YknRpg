package cn.yknrpg.gui;

import cn.yknrpg.model.ItemDefinition.CatalogKind;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentGuiTest {
    @Test
    void miningAndFishingMenusUseIndependentCategoriesAndArmorPagination() {
        List<Integer> slots = List.of(11, 13, 15);
        assertEquals(List.of(CatalogKind.MINING_ITEM, CatalogKind.MINING_PICKAXE, CatalogKind.MINING_ARMOR),
                slots.stream().map(slot -> EquipmentGui.professionKind(true, slot)).toList());
        assertEquals(List.of(CatalogKind.FISHING_CATCH, CatalogKind.FISHING_ROD, CatalogKind.FISHING_ARMOR),
                slots.stream().map(slot -> EquipmentGui.professionKind(false, slot)).toList());
        for (int slot = -1; slot < 64; slot++) {
            if (!slots.contains(slot)) {
                assertNull(EquipmentGui.professionKind(true, slot));
                assertNull(EquipmentGui.professionKind(false, slot));
            }
        }
        for (CatalogKind kind : CatalogKind.values()) {
            assertEquals(kind.name().startsWith("MINING_"), kind.isMining());
            assertEquals(kind.name().startsWith("FISHING_"), kind.isFishing());
            assertEquals(kind.isFishing() || kind.isMining(), kind.isProfession());
            assertEquals(kind == CatalogKind.ARMOR || kind == CatalogKind.FISHING_ARMOR
                    || kind == CatalogKind.MINING_ARMOR ? 44 : 45, EquipmentGui.itemsPerPage(kind));
        }
    }

    @Test
    void allCatalogTitlesAndMiningButtonsHaveDefaultMessages() {
        var resource = getClass().getResourceAsStream("/config.yml");
        assertNotNull(resource);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new InputStreamReader(resource, StandardCharsets.UTF_8));
        for (CatalogKind kind : CatalogKind.values()) {
            assertNotNull(config.getString(EquipmentGui.catalogTitlePath(kind)), kind.name());
        }
        assertEquals("&b物品", config.getString("gui.mining-buttons.items"));
        assertEquals("&a矿镐", config.getString("gui.mining-buttons.pickaxe"));
        assertEquals("&6装备", config.getString("gui.mining-buttons.armor"));
        for (String path : List.of("gui.mining-title", "gui.mining-back", "messages.mining-loading",
                "messages.mining-unavailable")) assertNotNull(config.getString(path), path);
        assertTrue(config.getString("messages.admin-usage").contains("mining"));
    }
}
