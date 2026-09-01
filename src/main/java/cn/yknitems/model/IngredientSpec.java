package cn.yknitems.model;

import org.bukkit.Material;

public record IngredientSpec(Material material, String customItemId) {
    public static IngredientSpec empty() {
        return new IngredientSpec(null, null);
    }

    public static IngredientSpec material(Material material) {
        return new IngredientSpec(material, null);
    }

    public static IngredientSpec custom(String itemId) {
        return new IngredientSpec(null, itemId);
    }

    public boolean isEmpty() {
        return material == null && customItemId == null;
    }

    public String identity() {
        if (customItemId != null) return "custom:" + customItemId;
        return material == null ? "empty" : "material:" + material.name();
    }
}
