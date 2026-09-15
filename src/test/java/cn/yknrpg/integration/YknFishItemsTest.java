package cn.yknrpg.integration;

import cn.yknrpg.model.ItemDefinition.CatalogKind;
import cn.yknrpg.model.ItemDefinition.Rarity;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YknFishItemsTest {
    @Test
    void separatesFishingLootRodsAndArmorWithoutMixingExistingCatalogs() {
        assertEquals(CatalogKind.FISHING_ROD, YknFishItems.kind(Material.FISHING_ROD));
        for (Material material : List.of(Material.COD, Material.PRISMARINE_SHARD,
                Material.TURTLE_SCUTE, Material.PLAYER_HEAD)) {
            assertEquals(CatalogKind.FISHING_CATCH, YknFishItems.kind(material));
        }
        for (Material material : List.of(Material.LEATHER_HELMET, Material.CHAINMAIL_CHESTPLATE,
                Material.IRON_LEGGINGS, Material.DIAMOND_BOOTS, Material.NETHERITE_HELMET)) {
            assertEquals(CatalogKind.FISHING_ARMOR, YknFishItems.kind(material));
        }
        for (CatalogKind kind : CatalogKind.values()) {
            if (kind.name().startsWith("FISHING_")) assertTrue(kind.isFishing());
            else assertFalse(kind.isFishing());
        }
    }

    @Test
    void readsAllSixRaritiesFromTheLoreFooterRatherThanFlavorText() {
        List<String> names = List.of("普通", "少见的", "稀有", "史诗", "传说", "神话");
        for (int index = 0; index < names.size(); index++) {
            assertEquals(Rarity.values()[index], YknFishItems.rarity(
                    List.of("传说", "来自神话中的远海", " " + names.get(index) + " ", "")));
        }
        assertEquals(Rarity.COMMON, YknFishItems.rarity(List.of()));
        assertEquals(Rarity.COMMON, YknFishItems.rarity(List.of("传说中的鱼")));
    }
}
