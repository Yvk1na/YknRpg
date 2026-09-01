package cn.yknitems.model;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlotGroup;

public enum ArmorSlot {
    HELMET(Material.LEATHER_HELMET, EquipmentSlotGroup.HEAD),
    CHESTPLATE(Material.LEATHER_CHESTPLATE, EquipmentSlotGroup.CHEST),
    LEGGINGS(Material.LEATHER_LEGGINGS, EquipmentSlotGroup.LEGS),
    BOOTS(Material.LEATHER_BOOTS, EquipmentSlotGroup.FEET);

    private final Material material;
    private final EquipmentSlotGroup slotGroup;

    ArmorSlot(Material material, EquipmentSlotGroup slotGroup) {
        this.material = material;
        this.slotGroup = slotGroup;
    }

    public Material material() {
        return material;
    }

    public EquipmentSlotGroup slotGroup() {
        return slotGroup;
    }

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
