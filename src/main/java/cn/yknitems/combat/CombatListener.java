package cn.yknitems.combat;

import cn.yknitems.YknItemsPlugin;
import cn.yknitems.hook.ProgressionHook;
import cn.yknitems.item.CustomItemFactory;
import cn.yknitems.model.ArmorSetDefinition;
import cn.yknitems.model.ArmorSlot;
import cn.yknitems.model.ItemCategory;
import cn.yknitems.model.ItemDefinition;
import cn.yknitems.model.WeaponType;
import cn.yknitems.registry.ItemRegistry;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CombatListener implements Listener {
    private final YknItemsPlugin plugin;
    private final ItemRegistry registry;
    private final CustomItemFactory factory;
    private final ProgressionHook progression;
    private final NamespacedKey projectileItemKey;
    private final Map<String, Integer> hitCounters = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private boolean applyingSecondaryDamage;

    public CombatListener(YknItemsPlugin plugin, ItemRegistry registry, CustomItemFactory factory,
                          ProgressionHook progression) {
        this.plugin = plugin;
        this.registry = registry;
        this.factory = factory;
        this.progression = progression;
        this.projectileItemKey = new NamespacedKey(plugin, "projectile_item");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemDefinition definition = factory.definition(event.getBow()).orElse(null);
        if (definition == null || definition.weaponType() != WeaponType.BOW) return;
        if (!progression.meetsRequirement(player, definition, true)) {
            event.setCancelled(true);
            return;
        }
        event.getProjectile().getPersistentDataContainer().set(
                projectileItemKey, PersistentDataType.STRING, definition.id());
        if (event.getProjectile() instanceof AbstractArrow arrow) arrow.setDamage(definition.damage());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOutgoingDamage(EntityDamageByEntityEvent event) {
        if (applyingSecondaryDamage) return;
        Attack attack = attack(event.getDamager());
        if (attack == null || attack.definition.category() != ItemCategory.WEAPON) return;
        if (!progression.meetsRequirement(attack.player, attack.definition, true)) {
            event.setCancelled(true);
            return;
        }

        ArmorSetDefinition set = fullSet(attack.player).orElse(null);
        if (set != null && attack.definition.weaponType() != WeaponType.STAFF) {
            event.setDamage(event.getDamage() * set.outgoingDamageMultiplier());
        }

        if (attack.projectile) {
            handleProjectileAbility(event, attack);
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        switch (attack.definition.ability()) {
            case "ember_combo" -> emberCombo(event, attack.player, attack.definition);
            case "cleave" -> cleave(event, attack.player, target, attack.definition);
            case "execute" -> execute(event, target, attack.definition);
            case "backstab" -> backstab(event, attack.player, target, attack.definition);
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIncomingDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ArmorSetDefinition set = fullSet(player).orElse(null);
        if (set != null) event.setDamage(event.getDamage() * set.incomingDamageMultiplier());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemDefinition definition = factory.definition(event.getItem()).orElse(null);
        if (definition == null || definition.category() != ItemCategory.WEAPON) return;
        if (!definition.ability().equals("ground_slam")
                && !definition.ability().equals("thrust")
                && !definition.ability().equals("arcane_bolt")) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!progression.meetsRequirement(player, definition, true)) return;
        ArmorSetDefinition set = fullSet(player).orElse(null);
        double cooldownMultiplier = definition.weaponType() == WeaponType.STAFF && set != null
                ? set.staffCooldownMultiplier() : 1.0;
        double cooldown = definition.abilityArg("cooldown", 1.0) * cooldownMultiplier;
        if (!beginCooldown(player, definition, cooldown)) return;
        if (definition.weaponType() == WeaponType.STAFF
                && !progression.consumeStaffMana(player, definition.abilityArg("mana", 0.0))) {
            clearCooldown(player, definition);
            return;
        }

        switch (definition.ability()) {
            case "ground_slam" -> groundSlam(player, definition, set);
            case "thrust" -> thrust(player, definition, set);
            case "arcane_bolt" -> arcaneBolt(player, definition, set);
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        String prefix = event.getPlayer().getUniqueId() + ":";
        hitCounters.keySet().removeIf(key -> key.startsWith(prefix));
        cooldowns.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void emberCombo(EntityDamageByEntityEvent event, Player player, ItemDefinition definition) {
        int hits = Math.max(1, (int) definition.abilityArg("hits", 4));
        if (increment(player, definition) % hits != 0) return;
        event.setDamage(event.getDamage() + definition.abilityArg("bonus-damage", 10.0));
        double maximum = maximumHealth(player);
        player.setHealth(Math.min(maximum, player.getHealth() + definition.abilityArg("healing", 2.0)));
        player.getWorld().spawnParticle(Particle.FLAME, event.getEntity().getLocation().add(0, 1, 0),
                18, 0.35, 0.45, 0.35, 0.02);
        player.playSound(player.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.7f, 1.35f);
    }

    private void cleave(EntityDamageByEntityEvent event, Player player, LivingEntity primary,
                        ItemDefinition definition) {
        int hits = Math.max(1, (int) definition.abilityArg("hits", 3));
        if (increment(player, definition) % hits != 0) return;
        double damage = event.getDamage() * definition.abilityArg("ratio", 0.4);
        double radius = definition.abilityArg("radius", 3.5);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (LivingEntity target : nearbyEnemies(player, primary.getLocation(), radius, primary)) {
                secondaryDamage(target, damage, player);
            }
            primary.getWorld().spawnParticle(Particle.SWEEP_ATTACK, primary.getLocation().add(0, 1, 0),
                    6, 1.2, 0.35, 1.2, 0.0);
            primary.getWorld().playSound(primary.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.75f);
        });
    }

    private void execute(EntityDamageByEntityEvent event, LivingEntity target, ItemDefinition definition) {
        double maximum = maximumHealth(target);
        if (target.getHealth() / maximum > definition.abilityArg("threshold", 0.25)) return;
        event.setDamage(event.getDamage() * definition.abilityArg("multiplier", 1.45));
        target.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, target.getLocation().add(0, 1, 0),
                22, 0.45, 0.65, 0.45, 0.025);
    }

    private void backstab(EntityDamageByEntityEvent event, Player player, LivingEntity target,
                          ItemDefinition definition) {
        Vector forward = target.getLocation().getDirection().setY(0);
        Vector toAttacker = player.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
        if (forward.lengthSquared() < 0.001 || toAttacker.lengthSquared() < 0.001) return;
        double dot = forward.normalize().dot(toAttacker.normalize());
        double threshold = -Math.cos(Math.toRadians(definition.abilityArg("rear-degrees", 65.0)));
        if (dot > threshold) return;
        event.setDamage(event.getDamage() * definition.abilityArg("multiplier", 1.45));
        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0),
                24, 0.35, 0.55, 0.35, 0.15);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 1.2f);
    }

    private void handleProjectileAbility(EntityDamageByEntityEvent event, Attack attack) {
        if (!(event.getEntity() instanceof LivingEntity primary)) return;
        switch (attack.definition.ability()) {
            case "windshot" -> {
                int ticks = Math.max(1, (int) Math.round(attack.definition.abilityArg("duration", 3.0) * 20.0));
                int amplifier = Math.max(0, (int) attack.definition.abilityArg("amplifier", 0.0));
                attack.player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, amplifier, true, false, true));
            }
            case "chain_lightning" -> chainLightning(event, attack.player, primary, attack.definition);
            case "starfall" -> starfall(event, attack.player, primary, attack.definition);
            default -> {
            }
        }
    }

    private void chainLightning(EntityDamageByEntityEvent event, Player player, LivingEntity primary,
                                ItemDefinition definition) {
        int maximumTargets = Math.max(1, (int) definition.abilityArg("targets", 2));
        double radius = definition.abilityArg("radius", 6.0);
        double damage = event.getDamage() * definition.abilityArg("ratio", 0.45);
        List<LivingEntity> targets = nearbyEnemies(player, primary.getLocation(), radius, primary);
        targets.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(primary.getLocation())));
        for (LivingEntity target : targets.stream().limit(maximumTargets).toList()) {
            secondaryDamage(target, damage, player);
            target.getWorld().strikeLightningEffect(target.getLocation());
        }
    }

    private void starfall(EntityDamageByEntityEvent event, Player player, LivingEntity primary,
                          ItemDefinition definition) {
        if (!beginCooldown(player, definition, definition.abilityArg("cooldown", 3.0))) return;
        double damage = event.getDamage() * definition.abilityArg("ratio", 0.5);
        double radius = definition.abilityArg("radius", 4.0);
        for (LivingEntity target : nearbyEnemies(player, primary.getLocation(), radius, primary)) {
            secondaryDamage(target, damage, player);
        }
        primary.getWorld().spawnParticle(Particle.END_ROD, primary.getLocation().add(0, 1, 0),
                55, radius * 0.6, 1.1, radius * 0.6, 0.08);
        primary.getWorld().playSound(primary.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 0.7f);
    }

    private void groundSlam(Player player, ItemDefinition definition, ArmorSetDefinition set) {
        double damage = definition.abilityArg("damage", 38.0);
        if (set != null) damage *= set.outgoingDamageMultiplier();
        double radius = definition.abilityArg("radius", 3.5);
        for (LivingEntity target : nearbyEnemies(player, player.getLocation(), radius, null)) {
            secondaryDamage(target, damage, player);
            Vector knockback = target.getLocation().toVector().subtract(player.getLocation().toVector());
            if (knockback.lengthSquared() > 0.01) target.setVelocity(knockback.normalize().multiply(0.7).setY(0.35));
        }
        player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation(), 70,
                radius * 0.65, 0.15, radius * 0.65, 0.1,
                player.getLocation().subtract(0, 1, 0).getBlock().getBlockData());
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.1f, 0.65f);
    }

    private void thrust(Player player, ItemDefinition definition, ArmorSetDefinition set) {
        Vector direction = player.getEyeLocation().getDirection().normalize();
        player.setVelocity(player.getVelocity().add(direction.clone()
                .multiply(definition.abilityArg("dash", 0.55)).setY(Math.max(0.08, direction.getY() * 0.3))));
        double range = definition.abilityArg("range", 6.0);
        traceParticles(player.getEyeLocation(), direction, range, Particle.CRIT);
        RayTraceResult trace = player.getWorld().rayTraceEntities(player.getEyeLocation(), direction, range, 0.9,
                entity -> entity instanceof LivingEntity living && canAbilityTarget(player, living));
        if (trace == null || !(trace.getHitEntity() instanceof LivingEntity target)) return;
        double damage = definition.abilityArg("damage", 34.0);
        if (set != null) damage *= set.outgoingDamageMultiplier();
        secondaryDamage(target, damage, player);
        target.getWorld().playSound(target.getLocation(), Sound.ITEM_TRIDENT_HIT, 1.0f, 0.85f);
    }

    private void arcaneBolt(Player player, ItemDefinition definition, ArmorSetDefinition set) {
        Vector direction = player.getEyeLocation().getDirection().normalize();
        double range = definition.abilityArg("range", 18.0);
        traceParticles(player.getEyeLocation(), direction, range, Particle.END_ROD);
        RayTraceResult trace = player.getWorld().rayTrace(
                player.getEyeLocation(), direction, range, FluidCollisionMode.NEVER, true, 0.8,
                entity -> entity instanceof LivingEntity living && canAbilityTarget(player, living));
        if (trace == null || !(trace.getHitEntity() instanceof LivingEntity target)) {
            player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.6f, 1.5f);
            return;
        }
        double damage = definition.abilityArg("damage", 30.0);
        if (set != null) damage *= set.staffDamageMultiplier();
        secondaryDamage(target, damage, player);
        target.getWorld().spawnParticle(Particle.ENCHANT, target.getLocation().add(0, 1, 0),
                35, 0.45, 0.65, 0.45, 0.2);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 0.8f, 1.35f);
    }

    private void traceParticles(Location start, Vector direction, double range, Particle particle) {
        for (double distance = 0.5; distance <= range; distance += 0.65) {
            start.getWorld().spawnParticle(particle,
                    start.clone().add(direction.clone().multiply(distance)), 1, 0, 0, 0, 0);
        }
    }

    private Attack attack(Entity damager) {
        if (damager instanceof Player player) {
            ItemDefinition definition = factory.definition(player.getInventory().getItemInMainHand()).orElse(null);
            return definition == null ? null : new Attack(player, definition, false);
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            String itemId = projectile.getPersistentDataContainer().get(projectileItemKey, PersistentDataType.STRING);
            ItemDefinition definition = itemId == null ? null : registry.item(itemId).orElse(null);
            return definition == null ? null : new Attack(player, definition, true);
        }
        return null;
    }

    private Optional<ArmorSetDefinition> fullSet(Player player) {
        ItemStack[] armor = {
                player.getInventory().getHelmet(),
                player.getInventory().getChestplate(),
                player.getInventory().getLeggings(),
                player.getInventory().getBoots()
        };
        String setId = null;
        for (int index = 0; index < armor.length; index++) {
            ItemDefinition definition = factory.definition(armor[index]).orElse(null);
            if (definition == null || definition.category() != ItemCategory.ARMOR
                    || definition.armorSlot() != ArmorSlot.values()[index]) return Optional.empty();
            if (setId == null) setId = definition.armorSet();
            else if (!setId.equals(definition.armorSet())) return Optional.empty();
        }
        ArmorSetDefinition set = registry.armorSet(setId).orElse(null);
        if (set == null || !progression.meetsDefenseRequirement(player, set.requiredLevel())) return Optional.empty();
        return Optional.of(set);
    }

    private List<LivingEntity> nearbyEnemies(Player source, Location center, double radius, LivingEntity excluded) {
        List<LivingEntity> result = new ArrayList<>();
        for (LivingEntity living : center.getWorld().getNearbyLivingEntities(center, radius)) {
            if (living == excluded || !canAbilityTarget(source, living)) continue;
            result.add(living);
        }
        return result;
    }

    private boolean canAbilityTarget(Player source, LivingEntity target) {
        if (!target.isValid() || target.isDead() || target == source || target instanceof ArmorStand) return false;
        if (target instanceof Player) return !plugin.getConfig().getBoolean("combat.prevent-ability-friendly-fire", true);
        if (target instanceof Tameable tameable && tameable.isTamed()) return false;
        return true;
    }

    private void secondaryDamage(LivingEntity target, double damage, Player source) {
        if (damage <= 0.0 || !target.isValid() || target.isDead()) return;
        applyingSecondaryDamage = true;
        try {
            target.damage(damage, source);
        } finally {
            applyingSecondaryDamage = false;
        }
    }

    private int increment(Player player, ItemDefinition definition) {
        String key = key(player, definition);
        int next = hitCounters.getOrDefault(key, 0) + 1;
        hitCounters.put(key, next);
        return next;
    }

    private boolean beginCooldown(Player player, ItemDefinition definition, double seconds) {
        String key = key(player, definition);
        long now = System.currentTimeMillis();
        if (now < cooldowns.getOrDefault(key, 0L)) return false;
        cooldowns.put(key, now + Math.max(1L, Math.round(seconds * 1000.0)));
        player.setCooldown(definition.material(), Math.max(1, (int) Math.round(seconds * 20.0)));
        return true;
    }

    private void clearCooldown(Player player, ItemDefinition definition) {
        cooldowns.remove(key(player, definition));
        player.setCooldown(definition.material(), 0);
    }

    private static String key(Player player, ItemDefinition definition) {
        return player.getUniqueId() + ":" + definition.id();
    }

    private static double maximumHealth(LivingEntity entity) {
        var attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? Math.max(1.0, entity.getHealth()) : Math.max(1.0, attribute.getValue());
    }

    private record Attack(Player player, ItemDefinition definition, boolean projectile) {
    }
}
