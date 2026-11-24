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

    @Test
    void resolvesMultiVariantExactChoiceWithSameCustomItemId() throws Exception {
        var currentCopper = new StubItemStack(org.bukkit.Material.RAW_COPPER, "YKM_RED_COPPER");
        var legacyCopper = new StubItemStack(org.bukkit.Material.COPPER_ORE, "YKM_RED_COPPER");
        var miningKey = new org.bukkit.NamespacedKey("yknmining", "material_upgrade/red_copper");
        assertEquals(
                cn.yknrpg.model.IngredientSpec.custom("YKM_RED_COPPER"),
                MythicProfessionItems.resolveIngredient(
                        exactChoice(currentCopper, legacyCopper),
                        miningKey,
                        "YknMining",
                        MythicProfessionItemsTest::lookupStubId));

        var currentTooth = new StubItemStack(org.bukkit.Material.PRISMARINE_CRYSTALS, "YKF_POLISHED_TOOTH");
        var legacyTooth = new StubItemStack(org.bukkit.Material.PRISMARINE_CRYSTALS, "YKF_POLISHED_TOOTH");
        var fishKey = new org.bukkit.NamespacedKey("yknfish", "material_upgrade/polished_tooth");
        assertEquals(
                cn.yknrpg.model.IngredientSpec.custom("YKF_POLISHED_TOOTH"),
                MythicProfessionItems.resolveIngredient(
                        exactChoice(currentTooth, legacyTooth),
                        fishKey,
                        "YknFish",
                        MythicProfessionItemsTest::lookupStubId));
    }

    @Test
    void resolvesMultiVariantExactChoiceWithSameVanillaMaterial() throws Exception {
        var diamondA = new StubItemStack(org.bukkit.Material.DIAMOND, null);
        var diamondB = new StubItemStack(org.bukkit.Material.DIAMOND, null);
        var key = new org.bukkit.NamespacedKey("yknmining", "vanilla_variants");
        assertEquals(
                cn.yknrpg.model.IngredientSpec.material(org.bukkit.Material.DIAMOND),
                MythicProfessionItems.resolveIngredient(
                        exactChoice(diamondA, diamondB),
                        key,
                        "YknMining",
                        MythicProfessionItemsTest::lookupStubId));
    }

    @Test
    void rejectsMixedExactChoiceAndMultiMaterialChoiceWhileAllowingSingleMaterial() throws Exception {
        var copper = new StubItemStack(org.bukkit.Material.RAW_COPPER, "YKM_RED_COPPER");
        var silver = new StubItemStack(org.bukkit.Material.RAW_IRON, "YKM_RAW_SILVER");
        var vanillaCopper = new StubItemStack(org.bukkit.Material.RAW_COPPER, null);
        var diamond = new StubItemStack(org.bukkit.Material.DIAMOND, null);
        var emerald = new StubItemStack(org.bukkit.Material.EMERALD, null);

        var mixedIds = new org.bukkit.NamespacedKey("yknmining", "mixed_ids");
        var mixedIdsChoice = exactChoice(copper, silver);
        var ex1 = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> MythicProfessionItems.resolveIngredient(
                        mixedIdsChoice,
                        mixedIds,
                        "YknMining",
                        MythicProfessionItemsTest::lookupStubId));
        org.junit.jupiter.api.Assertions.assertTrue(ex1.getMessage().contains("YknMining 配方包含不支持的可选材料：" + mixedIds));

        var mixedCustomAndVanilla = new org.bukkit.NamespacedKey("yknmining", "mixed_custom_vanilla");
        var mixedCustomVanillaChoice = exactChoice(copper, vanillaCopper);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> MythicProfessionItems.resolveIngredient(
                        mixedCustomVanillaChoice,
                        mixedCustomAndVanilla,
                        "YknMining",
                        MythicProfessionItemsTest::lookupStubId));

        var mixedVanilla = new org.bukkit.NamespacedKey("yknmining", "mixed_vanilla");
        var mixedVanillaChoice = exactChoice(diamond, emerald);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> MythicProfessionItems.resolveIngredient(
                        mixedVanillaChoice,
                        mixedVanilla,
                        "YknMining",
                        MythicProfessionItemsTest::lookupStubId));

        var emptyKey = new org.bukkit.NamespacedKey("yknmining", "empty_exact");
        var emptyChoice = exactChoice();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> MythicProfessionItems.resolveIngredient(
                        emptyChoice,
                        emptyKey,
                        "YknMining",
                        MythicProfessionItemsTest::lookupStubId));

        var singleMatKey = new org.bukkit.NamespacedKey("yknmining", "single_mat");
        assertEquals(
                cn.yknrpg.model.IngredientSpec.material(org.bukkit.Material.COPPER_INGOT),
                MythicProfessionItems.resolveIngredient(
                        materialChoice(org.bukkit.Material.COPPER_INGOT),
                        singleMatKey,
                        "YknMining",
                        MythicProfessionItemsTest::lookupStubId));

        var multiMatKey = new org.bukkit.NamespacedKey("yknmining", "multi_mat");
        var multiMatChoice = materialChoice(org.bukkit.Material.COPPER_INGOT, org.bukkit.Material.IRON_INGOT);
        var ex4 = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> MythicProfessionItems.resolveIngredient(
                        multiMatChoice,
                        multiMatKey,
                        "YknMining",
                        MythicProfessionItemsTest::lookupStubId));
        org.junit.jupiter.api.Assertions.assertTrue(ex4.getMessage().contains("YknMining 配方包含不支持的可选材料：" + multiMatKey));
    }

    private static org.bukkit.inventory.RecipeChoice.ExactChoice exactChoice(org.bukkit.inventory.ItemStack... stacks)
            throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var choice = (org.bukkit.inventory.RecipeChoice.ExactChoice) unsafe.allocateInstance(
                org.bukkit.inventory.RecipeChoice.ExactChoice.class);
        var choicesField = org.bukkit.inventory.RecipeChoice.ExactChoice.class.getDeclaredField("choices");
        choicesField.setAccessible(true);
        choicesField.set(choice, java.util.List.of(stacks));
        return choice;
    }

    private static org.bukkit.inventory.RecipeChoice.MaterialChoice materialChoice(org.bukkit.Material... materials)
            throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var choice = (org.bukkit.inventory.RecipeChoice.MaterialChoice) unsafe.allocateInstance(
                org.bukkit.inventory.RecipeChoice.MaterialChoice.class);
        var choicesField = org.bukkit.inventory.RecipeChoice.MaterialChoice.class.getDeclaredField("choices");
        choicesField.setAccessible(true);
        choicesField.set(choice, java.util.List.of(materials));
        return choice;
    }

    private static java.util.Optional<String> lookupStubId(org.bukkit.inventory.ItemStack stack) {
        if (stack instanceof StubItemStack stub) {
            return java.util.Optional.ofNullable(stub.customId);
        }
        return java.util.Optional.empty();
    }

    private static final class StubItemStack extends org.bukkit.inventory.ItemStack {
        private final org.bukkit.Material material;
        private final String customId;

        private StubItemStack(org.bukkit.Material material, String customId) {
            this.material = material;
            this.customId = customId;
        }

        @Override
        public org.bukkit.Material getType() {
            return material;
        }

        @Override
        public org.bukkit.inventory.ItemStack clone() {
            return new StubItemStack(material, customId);
        }
    }
}
