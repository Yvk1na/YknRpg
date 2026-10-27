package cn.yknrpg.integration;

import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Optional bridge for plugins that expose custom monsters and their native drops.
 * Providers are deliberately small: YknRpg owns the GUI and override storage.
 */
public interface MonsterDropProvider {
    String id();

    Collection<Monster> monsters();

    Optional<String> identify(LivingEntity entity);

    record Monster(String id, String displayName, ItemStack icon, List<Drop> nativeDrops) {
        public Monster {
            nativeDrops = List.copyOf(nativeDrops == null ? List.of() : nativeDrops);
            if (icon != null) icon = icon.clone();
        }
    }

    record Drop(String key, ItemStack item, String description, double chance, int minimum, int maximum) {
        public Drop {
            if (item != null) {
                item = item.clone();
                item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), item.getAmount())));
            }
            chance = Math.max(0.0, Math.min(100.0, chance));
            minimum = Math.max(1, minimum);
            maximum = Math.max(minimum, maximum);
        }

        public boolean itemDrop() {
            return item != null && !item.getType().isAir();
        }

        public Drop withValues(double newChance, int newMinimum, int newMaximum) {
            return new Drop(key, item, description, newChance, newMinimum, newMaximum);
        }
    }
}
