package cn.yknrpg.model;

import java.util.List;
import java.util.Locale;

public record ItemDefinition(String id, ItemSource source, List<IngredientSpec> recipe,
                             CatalogKind kind, Rarity rarity, int catalogOrder) {
    public ItemDefinition {
        recipe = List.copyOf(recipe);
    }

    public ItemDefinition withRecipe(List<IngredientSpec> newRecipe) {
        return new ItemDefinition(id, source, newRecipe, kind, rarity, catalogOrder);
    }

    public enum ItemSource {
        MYTHIC_MOBS,
        ECO_PETS
    }

    public enum CatalogKind {
        ITEM(0), WEAPON(1), ARMOR(2), PET(3),
        FISHING_CATCH(4), FISHING_ROD(5), FISHING_ARMOR(6),
        MINING_ITEM(7), MINING_PICKAXE(8), MINING_ARMOR(9), OTHER(10);

        private final int sortRank;

        CatalogKind(int sortRank) {
            this.sortRank = sortRank;
        }

        public int sortRank() {
            return sortRank;
        }

        public boolean isFishing() {
            return this == FISHING_CATCH || this == FISHING_ROD || this == FISHING_ARMOR;
        }

        public boolean isMining() {
            return this == MINING_ITEM || this == MINING_PICKAXE || this == MINING_ARMOR;
        }

        public boolean isProfession() {
            return isFishing() || isMining();
        }

        public static CatalogKind parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("未知图鉴类型：" + value);
            }
        }
    }

    public enum Rarity {
        COMMON(0), UNCOMMON(1), RARE(2), EPIC(3), LEGENDARY(4), MYTHIC(5);

        private final int sortRank;

        Rarity(int sortRank) {
            this.sortRank = sortRank;
        }

        public int sortRank() {
            return sortRank;
        }

        public static Rarity parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("未知物品稀有度：" + value);
            }
        }
    }
}
