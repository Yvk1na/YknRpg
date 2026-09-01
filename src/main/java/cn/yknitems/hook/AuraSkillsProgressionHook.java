package cn.yknitems.hook;

import cn.yknitems.YknItemsPlugin;
import cn.yknitems.model.ItemDefinition;
import cn.yknitems.model.WeaponType;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.skill.Skills;
import dev.aurelium.auraskills.api.user.SkillsUser;
import org.bukkit.entity.Player;

final class AuraSkillsProgressionHook implements ProgressionHook {
    private final YknItemsPlugin plugin;
    private final AuraSkillsApi api;

    AuraSkillsProgressionHook(YknItemsPlugin plugin) {
        this.plugin = plugin;
        this.api = AuraSkillsApi.get();
    }

    @Override
    public boolean meetsRequirement(Player player, ItemDefinition definition, boolean notify) {
        if (!plugin.getConfig().getBoolean("auraskills.enforce-level-requirements", true)) return true;
        Skills skill = skillFor(definition.weaponType());
        SkillsUser user = api.getUser(player.getUniqueId());
        int current = user.isLoaded() ? user.getSkillLevel(skill) : 0;
        if (current >= definition.requiredLevel()) return true;
        if (notify) {
            String skillKey = skill.name().toLowerCase(java.util.Locale.ROOT);
            String skillName = plugin.getConfig().getString("auraskills.skill-names." + skillKey, skillKey);
            player.sendMessage(plugin.message("messages.level-required",
                    "skill", skillName,
                    "level", definition.requiredLevel(),
                    "current", current));
        }
        return false;
    }

    @Override
    public boolean meetsDefenseRequirement(Player player, int requiredLevel) {
        if (!plugin.getConfig().getBoolean("auraskills.enforce-level-requirements", true)) return true;
        SkillsUser user = api.getUser(player.getUniqueId());
        return user.isLoaded() && user.getSkillLevel(Skills.DEFENSE) >= requiredLevel;
    }

    @Override
    public boolean consumeStaffMana(Player player, double amount) {
        if (!plugin.getConfig().getBoolean("auraskills.consume-staff-mana", true) || amount <= 0.0) return true;
        SkillsUser user = api.getUser(player.getUniqueId());
        if (user.isLoaded() && user.consumeMana(amount)) return true;
        player.sendMessage(plugin.message("messages.mana-required", "mana", cn.yknitems.util.Text.number(amount)));
        return false;
    }

    private Skills skillFor(WeaponType type) {
        if (type == WeaponType.BOW) return Skills.ARCHERY;
        if (type == WeaponType.STAFF) return Skills.SORCERY;
        return Skills.FIGHTING;
    }
}
