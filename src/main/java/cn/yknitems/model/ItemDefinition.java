package cn.yknitems.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record ItemDefinition(
        String id,
        String name,
        ItemCategory category,
        Stage stage,
        Material material,
        String itemModel,
        int requiredLevel,
        WeaponType weaponType,
        double damage,
        double attackSpeed,
        String ability,
        String abilityName,
        String abilityDescription,
        Map<String, Double> abilityArgs,
        ArmorSlot armorSlot,
        String armorSet,
        int leatherColor,
        double armor,
        double toughness,
        double maxHealth,
        List<IngredientSpec> recipe,
        List<String> description
) {
    public int maximumStackSize() {
        return category == ItemCategory.MATERIAL ? 64 : 1;
    }

    public double abilityArg(String key, double fallback) {
        return abilityArgs.getOrDefault(key, fallback);
    }

    public ItemDefinition withRecipe(List<IngredientSpec> newRecipe) {
        return new ItemDefinition(
                id, name, category, stage, material, itemModel, requiredLevel, weaponType,
                damage, attackSpeed, ability, abilityName, abilityDescription, abilityArgs,
                armorSlot, armorSet, leatherColor, armor, toughness, maxHealth,
                List.copyOf(newRecipe), description
        );
    }
}
