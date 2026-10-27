package cn.yknrpg.integration;

import cn.yknrpg.model.ItemDefinition.CatalogKind;
import cn.yknrpg.model.ItemDefinition.Rarity;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MythicProfessionItemsTest {
    @Test
    void separatesFishingLootRodsAndArmorWithoutMixingExistingCatalogs() {
        MythicProfessionItems fishing = MythicProfessionItems.fishing(null);
        assertEquals(CatalogKind.FISHING_ROD, fishing.kind(Material.FISHING_ROD));
        for (Material material : List.of(Material.COD, Material.PRISMARINE_SHARD,
                Material.TURTLE_SCUTE, Material.PLAYER_HEAD)) {
            assertEquals(CatalogKind.FISHING_CATCH, fishing.kind(material));
        }
        for (Material material : List.of(Material.LEATHER_HELMET, Material.CHAINMAIL_CHESTPLATE,
                Material.IRON_LEGGINGS, Material.DIAMOND_BOOTS, Material.NETHERITE_HELMET)) {
            assertEquals(CatalogKind.FISHING_ARMOR, fishing.kind(material));
        }
        for (CatalogKind kind : CatalogKind.values()) {
            if (kind.name().startsWith("FISHING_")) assertTrue(kind.isFishing());
            else assertFalse(kind.isFishing());
        }
    }

    @Test
    void separatesMiningMaterialsPickaxesAndArmorWithoutMixingFishingOrRpgCatalogs() {
        MythicProfessionItems mining = MythicProfessionItems.mining(null);
        for (Material material : List.of(Material.WOODEN_PICKAXE, Material.STONE_PICKAXE,
                Material.IRON_PICKAXE, Material.GOLDEN_PICKAXE, Material.DIAMOND_PICKAXE,
                Material.NETHERITE_PICKAXE)) {
            assertEquals(CatalogKind.MINING_PICKAXE, mining.kind(material));
        }
        for (Material material : List.of(Material.RAW_COPPER, Material.AMETHYST_SHARD,
                Material.IRON_INGOT, Material.FIREWORK_STAR, Material.PLAYER_HEAD)) {
            assertEquals(CatalogKind.MINING_ITEM, mining.kind(material));
        }
        for (Material material : List.of(Material.LEATHER_HELMET, Material.CHAINMAIL_CHESTPLATE,
                Material.IRON_LEGGINGS, Material.DIAMOND_BOOTS, Material.NETHERITE_HELMET)) {
            assertEquals(CatalogKind.MINING_ARMOR, mining.kind(material));
        }
        for (CatalogKind kind : CatalogKind.values()) {
            assertEquals(kind.name().startsWith("MINING_"), kind.isMining());
            assertEquals(kind.isFishing() || kind.isMining(), kind.isProfession());
        }
    }

    @Test
    void readsAllSixRaritiesFromTheLoreFooterRatherThanFlavorText() {
        List<String> names = List.of("普通", "少见的", "稀有", "史诗", "传说", "神话");
        for (int index = 0; index < names.size(); index++) {
            assertEquals(Rarity.values()[index], MythicProfessionItems.rarity(
                    List.of("传说", "来自神话中的远海", " " + names.get(index) + " ", "")));
        }
        assertEquals(Rarity.COMMON, MythicProfessionItems.rarity(List.of()));
        assertEquals(Rarity.COMMON, MythicProfessionItems.rarity(List.of("传说中的鱼")));
    }
}
