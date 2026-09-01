package cn.yknitems.hook;

import cn.yknitems.YknItemsPlugin;
import cn.yknitems.model.ItemDefinition;
import org.bukkit.entity.Player;

public interface ProgressionHook {
    boolean meetsRequirement(Player player, ItemDefinition definition, boolean notify);

    boolean meetsDefenseRequirement(Player player, int requiredLevel);

    boolean consumeStaffMana(Player player, double amount);

    static ProgressionHook create(YknItemsPlugin plugin) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("AuraSkills")) {
            plugin.getLogger().warning("未检测到 AuraSkills：等级限制和法力消耗已自动停用。");
            return permissive();
        }
        try {
            return new AuraSkillsProgressionHook(plugin);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning("AuraSkills 接入失败，已使用兼容模式：" + exception.getMessage());
            return permissive();
        }
    }

    static ProgressionHook permissive() {
        return new ProgressionHook() {
            @Override
            public boolean meetsRequirement(Player player, ItemDefinition definition, boolean notify) {
                return true;
            }

            @Override
            public boolean meetsDefenseRequirement(Player player, int requiredLevel) {
                return true;
            }

            @Override
            public boolean consumeStaffMana(Player player, double amount) {
                return true;
            }
        };
    }
}
