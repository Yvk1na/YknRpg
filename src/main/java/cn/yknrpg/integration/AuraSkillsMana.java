package cn.yknrpg.integration;

import cn.yknrpg.rpg.RpgRegistry;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.ability.Abilities;
import dev.aurelium.auraskills.api.stat.StatModifier;
import dev.aurelium.auraskills.api.stat.Stats;
import dev.aurelium.auraskills.api.trait.TraitModifier;
import dev.aurelium.auraskills.api.trait.Traits;
import dev.aurelium.auraskills.api.user.SkillsUser;
import dev.aurelium.auraskills.api.util.AuraSkillsModifier;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

import java.util.List;

public final class AuraSkillsMana {
    private static final double EPSILON = 0.000001;
    private static final String AURA_ENCHANTED_STRENGTH_MODIFIER = "AbilityModifier-EnchantedStrength";
    private static final String EQUIPMENT_STRENGTH_MODIFIER = "yknrpg:equipment_strength";
    private static final String EQUIPMENT_HEALTH_MODIFIER = "yknrpg:equipment_health";
    private static final String EQUIPMENT_WISDOM_MODIFIER = "yknrpg:equipment_wisdom";
    private static final String EQUIPMENT_TOUGHNESS_MODIFIER = "yknrpg:equipment_toughness";
    private static final String EQUIPMENT_CRIT_CHANCE_MODIFIER = "yknrpg:equipment_crit_chance";
    private static final String EQUIPMENT_CRIT_DAMAGE_MODIFIER = "yknrpg:equipment_crit_damage";
    private static final String EQUIPMENT_SPEED_MODIFIER = "yknrpg:equipment_speed";
    private static final String DECORATIVE_GLINT_STRENGTH_MODIFIER =
            "yknrpg:decorative_glint_strength_correction";
    private static final String EQUIPMENT_ATTACK_DAMAGE_GUARD =
            "yknrpg:equipment_attack_damage_guard";
    private static final String EQUIPMENT_HEALTH_GUARD = "yknrpg:equipment_health_guard";
    private static final String EQUIPMENT_DAMAGE_REDUCTION_GUARD =
            "yknrpg:equipment_damage_reduction_guard";
    private static final String EQUIPMENT_MOVEMENT_SPEED_GUARD =
            "yknrpg:equipment_movement_speed_guard";
    private static final String DISPLAY_ONLY_EXPERIENCE_BONUS_GUARD =
            "yknrpg:display_only_experience_bonus_guard";
    private static final String DISPLAY_ONLY_ANVIL_DISCOUNT_GUARD =
            "yknrpg:display_only_anvil_discount_guard";
    private static final String DISPLAY_ONLY_MAX_MANA_GUARD =
            "yknrpg:display_only_max_mana_guard";
    private static final String WEAPON_SHIELD_HEALTH_MODIFIER = "yknrpg:weapon_shield_health";
    private static final List<String> EQUIPMENT_STAT_MODIFIERS = List.of(
            EQUIPMENT_STRENGTH_MODIFIER,
            EQUIPMENT_HEALTH_MODIFIER,
            EQUIPMENT_WISDOM_MODIFIER,
            EQUIPMENT_TOUGHNESS_MODIFIER,
            EQUIPMENT_CRIT_CHANCE_MODIFIER,
            EQUIPMENT_CRIT_DAMAGE_MODIFIER,
            EQUIPMENT_SPEED_MODIFIER,
            DECORATIVE_GLINT_STRENGTH_MODIFIER
    );
    private static final List<String> EQUIPMENT_TRAIT_GUARDS = List.of(
            EQUIPMENT_ATTACK_DAMAGE_GUARD,
            EQUIPMENT_HEALTH_GUARD,
            EQUIPMENT_DAMAGE_REDUCTION_GUARD,
            EQUIPMENT_MOVEMENT_SPEED_GUARD,
            DISPLAY_ONLY_EXPERIENCE_BONUS_GUARD,
            DISPLAY_ONLY_ANVIL_DISCOUNT_GUARD,
            DISPLAY_ONLY_MAX_MANA_GUARD
    );

    private final AuraSkillsApi api;
    private long criticalSuppressionSequence;

    public AuraSkillsMana() {
        api = AuraSkillsApi.get();
    }

    public ManaState state(Player player) {
        SkillsUser user = api.getUser(player.getUniqueId());
        if (user == null || !user.isLoaded()) return ManaState.unavailable();
        return new ManaState(true, Math.max(0.0, user.getMana()), Math.max(0.0, user.getMaxMana()));
    }

    public boolean consume(Player player, double amount) {
        if (amount <= 0.0) return true;
        SkillsUser user = api.getUser(player.getUniqueId());
        return user != null && user.isLoaded() && user.consumeMana(amount);
    }

    public void refund(Player player, double amount) {
        if (amount <= 0.0) return;
        SkillsUser user = loadedUser(player);
        if (user == null) return;
        user.setMana(Math.min(user.getMaxMana(), user.getMana() + amount));
    }

    /**
     * Mirrors the standard YknRpg equipment stats into AuraSkills so HUDs and
     * menus that read AuraSkills show the complete equipped profile.
     *
     * <p>YknRpg remains responsible for its combat formula. Trait guards cancel
     * the duplicate AuraSkills effect for stats that YknRpg or Bukkit already
     * applies. Critical traits are neutralized only while AuraSkills builds a
     * damage calculation because the SkyBlock menu reads their effective values.</p>
     */
    public void syncEquipmentStats(Player player, RpgRegistry.Stats equipment,
                                   boolean correctDecorativeGlintStrength,
                                   double displayOnlyWisdom) {
        SkillsUser user = loadedUser(player);
        if (user == null) return;

        double strength = finite(equipment.get(RpgRegistry.Stat.STRENGTH));
        double health = finite(equipment.get(RpgRegistry.Stat.HEALTH));
        double wisdom = finite(equipment.get(RpgRegistry.Stat.WISDOM));
        double toughness = finite(equipment.get(RpgRegistry.Stat.DEFENSE));
        double critChance = finite(equipment.get(RpgRegistry.Stat.CRIT_CHANCE));
        double critDamage = finite(equipment.get(RpgRegistry.Stat.CRIT_DAMAGE));
        double speed = finite(equipment.get(RpgRegistry.Stat.SPEED));

        syncGuardedStatModifier(user, EQUIPMENT_STRENGTH_MODIFIER, Stats.STRENGTH,
                strength, EQUIPMENT_ATTACK_DAMAGE_GUARD, Traits.ATTACK_DAMAGE);
        syncGuardedStatModifier(user, EQUIPMENT_HEALTH_MODIFIER, Stats.HEALTH,
                health, EQUIPMENT_HEALTH_GUARD, Traits.HP);
        syncWisdomModifiers(user, wisdom, finite(displayOnlyWisdom));
        syncGuardedStatModifier(user, EQUIPMENT_TOUGHNESS_MODIFIER, Stats.TOUGHNESS,
                toughness, EQUIPMENT_DAMAGE_REDUCTION_GUARD, Traits.DAMAGE_REDUCTION);
        syncStatModifier(user, EQUIPMENT_CRIT_CHANCE_MODIFIER, Stats.CRIT_CHANCE, critChance);
        syncStatModifier(user, EQUIPMENT_CRIT_DAMAGE_MODIFIER, Stats.CRIT_DAMAGE, critDamage);
        syncGuardedStatModifier(user, EQUIPMENT_SPEED_MODIFIER, Stats.SPEED,
                speed, EQUIPMENT_MOVEMENT_SPEED_GUARD, Traits.MOVEMENT_SPEED);

        double decorativeStrength = correctDecorativeGlintStrength
                ? currentDecorativeGlintStrength(user) : 0.0;
        syncStatModifier(user, DECORATIVE_GLINT_STRENGTH_MODIFIER,
                Stats.STRENGTH, -decorativeStrength);

        clampMana(user);
    }

    public void clearEquipmentStats(Player player) {
        SkillsUser user = loadedUser(player);
        if (user == null) return;
        // Guards go first so removing a positive health stat can never create a
        // transient negative HP delta and clamp the player's current health.
        for (String name : EQUIPMENT_TRAIT_GUARDS) {
            if (user.getTraitModifier(name) != null) user.removeTraitModifier(name);
        }
        for (String name : EQUIPMENT_STAT_MODIFIERS) {
            if (user.getStatModifier(name) != null) user.removeStatModifier(name);
        }
        clampMana(user);
    }

    public SuppressedTraits suppressEquipmentCriticalTraits(Player player) {
        SkillsUser user = loadedUser(player);
        if (user == null) return SuppressedTraits.EMPTY;
        double critChance = modifierAmount(user, EQUIPMENT_CRIT_CHANCE_MODIFIER, Stats.CRIT_CHANCE);
        double critDamage = modifierAmount(user, EQUIPMENT_CRIT_DAMAGE_MODIFIER, Stats.CRIT_DAMAGE);
        if (Math.abs(critChance) <= EPSILON && Math.abs(critDamage) <= EPSILON) {
            return SuppressedTraits.EMPTY;
        }

        String prefix = "yknrpg:damage_critical_guard_" + (++criticalSuppressionSequence);
        String chanceName = prefix + "_chance";
        String damageName = prefix + "_damage";
        addTraitModifier(user, chanceName, Traits.CRIT_CHANCE,
                -critChance * Stats.CRIT_CHANCE.getTraitModifier(Traits.CRIT_CHANCE));
        addTraitModifier(user, damageName, Traits.CRIT_DAMAGE,
                -critDamage * Stats.CRIT_DAMAGE.getTraitModifier(Traits.CRIT_DAMAGE));
        return new SuppressedTraits(List.of(chanceName, damageName));
    }

    public void clearEquipmentCriticalSuppression(Player player, SuppressedTraits suppressed) {
        if (suppressed == null || suppressed.modifierNames().isEmpty()) return;
        SkillsUser user = loadedUser(player);
        if (user == null) return;
        for (String name : suppressed.modifierNames()) {
            if (user.getTraitModifier(name) != null) user.removeTraitModifier(name);
        }
    }

    public boolean syncWeaponShieldHealth(Player player, double amount) {
        SkillsUser user = loadedUser(player);
        if (user == null) return false;

        double remaining = Math.max(0.0, amount);
        TraitModifier current = user.getTraitModifier(WEAPON_SHIELD_HEALTH_MODIFIER);
        if (remaining <= 0.000001) {
            if (current != null) user.removeTraitModifier(WEAPON_SHIELD_HEALTH_MODIFIER);
            return true;
        }

        // HP trait modifiers ultimately become an ADD_NUMBER max-health
        // attribute modifier. Bukkit applies every MULTIPLY_SCALAR_1 modifier
        // (for example YknClass TANK health) after ADD_NUMBER, so store the
        // inverse-scaled raw value to make the configured shield amount equal
        // the player's real max-health increase.
        double totalScale = traitAddScale(user, Traits.HP) * maxHealthAddNumberScale(player);
        if (!Double.isFinite(totalScale) || totalScale <= EPSILON) return false;
        double rawRemaining = remaining / totalScale;

        if (current != null
                && current.trait() == Traits.HP
                && current.operation() == AuraSkillsModifier.Operation.ADD
                && Math.abs(current.value() - rawRemaining) < EPSILON) return true;

        TraitModifier modifier = new TraitModifier(
                WEAPON_SHIELD_HEALTH_MODIFIER,
                Traits.HP,
                rawRemaining,
                AuraSkillsModifier.Operation.ADD
        );
        modifier.setNonPersistent();
        user.addTraitModifier(modifier);
        return true;
    }

    public double weaponShieldHealth(Player player) {
        SkillsUser user = loadedUser(player);
        if (user == null) return 0.0;
        TraitModifier current = user.getTraitModifier(WEAPON_SHIELD_HEALTH_MODIFIER);
        if (current == null
                || current.trait() != Traits.HP
                || current.operation() != AuraSkillsModifier.Operation.ADD) return 0.0;
        double actual = finite(current.value())
                * traitAddScale(user, Traits.HP)
                * maxHealthAddNumberScale(player);
        return Double.isFinite(actual) ? Math.max(0.0, actual) : 0.0;
    }

    public void clearWeaponShieldHealth(Player player) {
        SkillsUser user = loadedUser(player);
        if (user == null) return;
        if (user.getTraitModifier(WEAPON_SHIELD_HEALTH_MODIFIER) != null) {
            user.removeTraitModifier(WEAPON_SHIELD_HEALTH_MODIFIER);
        }
    }

    /**
     * Converts a desired final max-health increase into the raw ADD_NUMBER
     * amount Bukkit needs before class and other scalar modifiers are applied.
     */
    public double rawMaxHealthAddNumber(Player player, double actualAmount) {
        return MaxHealthScaling.rawAddNumber(actualAmount, maxHealthAddNumberScale(player));
    }

    private void syncStatModifier(SkillsUser user, String name,
                                  dev.aurelium.auraskills.api.stat.Stat stat, double amount) {
        double safeAmount = finite(amount);
        StatModifier current = user.getStatModifier(name);
        if (Math.abs(safeAmount) <= EPSILON) {
            if (current != null) user.removeStatModifier(name);
            return;
        }
        if (current != null
                && current.stat().equals(stat)
                && current.operation() == AuraSkillsModifier.Operation.ADD
                && Math.abs(current.value() - safeAmount) <= EPSILON) return;
        StatModifier modifier = new StatModifier(
                name, stat, safeAmount, AuraSkillsModifier.Operation.ADD);
        modifier.setNonPersistent();
        // AuraSkills atomically replaces an existing same-name modifier: its
        // internal removal skips reload and only the final value is reloaded.
        // An explicit remove here would briefly drop HEALTH/WISDOM to zero and
        // could permanently clamp current health or mana.
        user.addStatModifier(modifier);
    }

    private void syncGuardedStatModifier(
            SkillsUser user,
            String statName,
            dev.aurelium.auraskills.api.stat.Stat stat,
            double amount,
            String guardName,
            dev.aurelium.auraskills.api.trait.Trait trait
    ) {
        double safeAmount = finite(amount);
        StatModifier current = user.getStatModifier(statName);
        double currentAmount = current != null
                && current.stat().equals(stat)
                && current.operation() == AuraSkillsModifier.Operation.ADD
                ? current.value() : 0.0;
        double guardAmount = -safeAmount * stat.getTraitModifier(trait);

        // Whichever order can only increase the intermediate effective trait is
        // applied first. This is especially important for HP, where a temporary
        // drop would make Bukkit permanently clamp the player's current health.
        if (safeAmount >= currentAmount) {
            syncStatModifier(user, statName, stat, safeAmount);
            syncTraitModifier(user, guardName, trait, guardAmount);
        } else {
            syncTraitModifier(user, guardName, trait, guardAmount);
            syncStatModifier(user, statName, stat, safeAmount);
        }
    }

    private void syncWisdomModifiers(
            SkillsUser user, double wisdom, double displayOnlyWisdom) {
        double currentWisdom = modifierAmount(
                user, EQUIPMENT_WISDOM_MODIFIER, Stats.WISDOM);
        double wisdomAddScale = statAddScale(user, Stats.WISDOM);
        double effectiveDisplayOnlyWisdom = finite(displayOnlyWisdom * wisdomAddScale);
        double maxManaCoefficient = Stats.WISDOM.getTraitModifier(Traits.MAX_MANA);
        double currentMaxManaGuard = traitModifierAmount(
                user, DISPLAY_ONLY_MAX_MANA_GUARD, Traits.MAX_MANA);
        double targetMaxManaGuard = -effectiveDisplayOnlyWisdom * maxManaCoefficient;

        // Choose the order whose intermediate effective wisdom is higher. In
        // particular, removing a display-only weapon must remove its guards
        // before its stat, otherwise MAX_MANA briefly undershoots and AuraSkills
        // permanently clamps away valid current mana.
        double statFirstIntermediate = wisdom * wisdomAddScale * maxManaCoefficient
                + currentMaxManaGuard;
        double guardsFirstIntermediate = currentWisdom * wisdomAddScale * maxManaCoefficient
                + targetMaxManaGuard;
        if (statFirstIntermediate >= guardsFirstIntermediate) {
            syncStatModifier(user, EQUIPMENT_WISDOM_MODIFIER, Stats.WISDOM, wisdom);
            syncDisplayOnlyWisdomGuards(user, effectiveDisplayOnlyWisdom);
        } else {
            syncDisplayOnlyWisdomGuards(user, effectiveDisplayOnlyWisdom);
            syncStatModifier(user, EQUIPMENT_WISDOM_MODIFIER, Stats.WISDOM, wisdom);
        }
    }

    private void syncDisplayOnlyWisdomGuards(
            SkillsUser user, double displayOnlyWisdom) {
        syncTraitModifier(user, DISPLAY_ONLY_EXPERIENCE_BONUS_GUARD,
                Traits.EXPERIENCE_BONUS,
                -displayOnlyWisdom
                        * Stats.WISDOM.getTraitModifier(Traits.EXPERIENCE_BONUS));
        syncTraitModifier(user, DISPLAY_ONLY_ANVIL_DISCOUNT_GUARD,
                Traits.ANVIL_DISCOUNT,
                -displayOnlyWisdom
                        * Stats.WISDOM.getTraitModifier(Traits.ANVIL_DISCOUNT));
        syncTraitModifier(user, DISPLAY_ONLY_MAX_MANA_GUARD,
                Traits.MAX_MANA,
                -displayOnlyWisdom
                        * Stats.WISDOM.getTraitModifier(Traits.MAX_MANA));
    }

    private void syncTraitModifier(SkillsUser user, String name,
                                   dev.aurelium.auraskills.api.trait.Trait trait, double amount) {
        double safeAmount = finite(amount);
        TraitModifier current = user.getTraitModifier(name);
        if (Math.abs(safeAmount) <= EPSILON) {
            if (current != null) user.removeTraitModifier(name);
            return;
        }
        if (current != null
                && current.trait().equals(trait)
                && current.operation() == AuraSkillsModifier.Operation.ADD
                && Math.abs(current.value() - safeAmount) <= EPSILON) return;
        TraitModifier modifier = new TraitModifier(
                name, trait, safeAmount, AuraSkillsModifier.Operation.ADD);
        modifier.setNonPersistent();
        // See syncStatModifier: same-name replacement is a single AuraSkills
        // reload and avoids an observable guard-free intermediate value.
        user.addTraitModifier(modifier);
    }

    private double statAddScale(
            SkillsUser user, dev.aurelium.auraskills.api.stat.Stat stat) {
        if (stat.hasDirectTrait()) return 1.0;
        double multiply = 1.0;
        double addPercent = 0.0;
        for (StatModifier modifier : user.getStatModifiers().values()) {
            if (!modifier.stat().equals(stat)) continue;
            if (modifier.operation() == AuraSkillsModifier.Operation.MULTIPLY) {
                multiply *= modifier.value();
            } else if (modifier.operation() == AuraSkillsModifier.Operation.ADD_PERCENT) {
                addPercent += modifier.value();
            }
        }
        return finite(multiply * (1.0 + addPercent / 100.0));
    }

    private double maxHealthAddNumberScale(Player player) {
        AttributeInstance maximumHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maximumHealth == null) return 1.0;
        double addScalar = 0.0;
        double multiplyScalar = 1.0;
        for (AttributeModifier modifier : maximumHealth.getModifiers()) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADD_SCALAR) {
                addScalar += modifier.getAmount();
            } else if (modifier.getOperation()
                    == AttributeModifier.Operation.MULTIPLY_SCALAR_1) {
                multiplyScalar *= 1.0 + modifier.getAmount();
            }
        }
        return (1.0 + addScalar) * multiplyScalar;
    }

    private double traitAddScale(
            SkillsUser user, dev.aurelium.auraskills.api.trait.Trait trait) {
        double multiply = 1.0;
        double addPercent = 0.0;
        for (TraitModifier modifier : user.getTraitModifiers().values()) {
            if (!modifier.trait().equals(trait)) continue;
            if (modifier.operation() == AuraSkillsModifier.Operation.MULTIPLY) {
                multiply *= modifier.value();
            } else if (modifier.operation() == AuraSkillsModifier.Operation.ADD_PERCENT) {
                addPercent += modifier.value();
            }
        }
        // AuraSkills moves percentage/multiply modifiers from a direct stat to
        // its trait calculation, so they scale an additive HP shield as well.
        for (StatModifier modifier : user.getStatModifiers().values()) {
            if (!modifier.stat().hasDirectTrait()
                    || modifier.stat().getTraits().isEmpty()
                    || !modifier.stat().getTraits().getFirst().equals(trait)) continue;
            if (modifier.operation() == AuraSkillsModifier.Operation.MULTIPLY) {
                multiply *= modifier.value();
            } else if (modifier.operation() == AuraSkillsModifier.Operation.ADD_PERCENT) {
                addPercent += modifier.value();
            }
        }
        return finite(multiply * (1.0 + addPercent / 100.0));
    }

    private void addTraitModifier(SkillsUser user, String name,
                                  dev.aurelium.auraskills.api.trait.Trait trait, double amount) {
        double safeAmount = finite(amount);
        if (Math.abs(safeAmount) <= EPSILON) return;
        TraitModifier modifier = new TraitModifier(
                name, trait, safeAmount, AuraSkillsModifier.Operation.ADD);
        modifier.setNonPersistent();
        user.addTraitModifier(modifier);
    }

    private double modifierAmount(SkillsUser user, String name,
                                  dev.aurelium.auraskills.api.stat.Stat expectedStat) {
        StatModifier modifier = user.getStatModifier(name);
        if (modifier == null
                || !modifier.stat().equals(expectedStat)
                || modifier.operation() != AuraSkillsModifier.Operation.ADD) return 0.0;
        return finite(modifier.value());
    }

    private double traitModifierAmount(SkillsUser user, String name,
                                       dev.aurelium.auraskills.api.trait.Trait expectedTrait) {
        TraitModifier modifier = user.getTraitModifier(name);
        if (modifier == null
                || !modifier.trait().equals(expectedTrait)
                || modifier.operation() != AuraSkillsModifier.Operation.ADD) return 0.0;
        return finite(modifier.value());
    }

    private double currentDecorativeGlintStrength(SkillsUser user) {
        StatModifier enchanted = user.getStatModifier(AURA_ENCHANTED_STRENGTH_MODIFIER);
        if (enchanted == null
                || !enchanted.stat().equals(Stats.STRENGTH)
                || enchanted.operation() != AuraSkillsModifier.Operation.ADD
                || enchanted.value() <= EPSILON) return 0.0;
        int abilityLevel = user.getAbilityLevel(Abilities.ENCHANTED_STRENGTH);
        double oneEnchantment = finite(Abilities.ENCHANTED_STRENGTH.getValue(abilityLevel));
        if (oneEnchantment <= EPSILON) oneEnchantment = enchanted.value();
        return Math.min(enchanted.value(), oneEnchantment);
    }

    private double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private SkillsUser loadedUser(Player player) {
        SkillsUser user = api.getUser(player.getUniqueId());
        return user != null && user.isLoaded() ? user : null;
    }

    private void clampMana(SkillsUser user) {
        if (user.getMana() > user.getMaxMana()) user.setMana(user.getMaxMana());
    }

    public record ManaState(boolean available, double current, double maximum) {
        private static ManaState unavailable() {
            return new ManaState(false, 0.0, 0.0);
        }
    }

    public record SuppressedTraits(List<String> modifierNames) {
        private static final SuppressedTraits EMPTY = new SuppressedTraits(List.of());
    }
}
