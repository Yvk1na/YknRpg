package cn.yknrpg.rpg;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.integration.AuraSkillsMana;
import cn.yknrpg.integration.AuraSkillsMana.ManaState;
import cn.yknrpg.integration.AuraSkillsMana.SuppressedTraits;
import cn.yknrpg.rpg.RpgRegistry.Ability;
import cn.yknrpg.rpg.RpgRegistry.AbilitySource;
import cn.yknrpg.rpg.RpgRegistry.Effect;
import cn.yknrpg.rpg.RpgRegistry.Profile;
import cn.yknrpg.rpg.RpgRegistry.Stat;
import cn.yknrpg.rpg.RpgRegistry.Stats;
import cn.yknrpg.rpg.RpgRegistry.Trigger;
import dev.aurelium.auraskills.api.event.damage.DamageEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Trident;
import org.bukkit.entity.ThrowableProjectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RpgEngine implements Listener {
    private static final DecimalFormat NUMBER = new DecimalFormat("0.#");
    private static final double SHIELD_EPSILON = 0.000001;
    private static final double DEFAULT_SHIELD_DURATION_SECONDS = 10.0;

    private final YknRpgPlugin plugin;
    private final RpgRegistry registry;
    private final AuraSkillsMana mana;
    private final NamespacedKey healthKey;
    private final NamespacedKey attackSpeedKey;
    private final NamespacedKey movementSpeedKey;
    private final NamespacedKey skyblockMenuItemKey;
    private final NamespacedKey skyblockMenuReturnKey;
    private final Map<AbilityKey, Long> cooldowns = new HashMap<>();
    private final Map<AbilityKey, ChargeState> charges = new HashMap<>();
    private final Map<UUID, List<TimedBuff>> buffs = new HashMap<>();
    private final Map<UUID, PlayerWeaponShields> weaponShields = new HashMap<>();
    private final Set<UUID> queuedShieldSyncs = new HashSet<>();
    /**
     * 每位玩家最近一次的裝備檔案。檔案只取決於六個裝備槽的物品內容、rpg.yml（重載會經過 stop()）
     * 和鍛造加成提供者，所以物品沒變時直接重用，省掉 tick 與每次攻擊都重新解析物品資料的開銷。
     * 短暫的有效期用來兜住鍛造插件在物品不變的情況下改變結果的情形（例如它自己重載配置）。
     */
    private static final long PROFILE_CACHE_NANOS = 2_000_000_000L;
    private final Map<UUID, CachedProfile> profileCache = new HashMap<>();
    private final Map<UUID, PendingProjectile> pendingProjectiles = new HashMap<>();
    private final Set<UUID> resolvedProjectiles = new HashSet<>();
    private final Map<UUID, ProjectileSnapshot> projectileSnapshots = new HashMap<>();
    private final Map<UUID, RememberedWeapon> recentMainHandWeapons = new HashMap<>();
    private final Map<DamageEvent, UUID> criticalTraitSuppressionEvents =
            new IdentityHashMap<>();
    private final Map<UUID, CriticalTraitSuppression> activeCriticalTraitSuppressions =
            new HashMap<>();
    private final Map<Event, Player> menuDisplayExposures = new IdentityHashMap<>();
    private final Map<InventoryClickEvent, Player> deferredMenuDisplayExposures =
            new IdentityHashMap<>();
    private final Map<UUID, Integer> menuDisplayExposureDepths = new HashMap<>();
    private final Map<String, Long> rightClickRejections = new HashMap<>();
    private BukkitTask tickTask;
    private BukkitTask shieldTickTask;
    private boolean applyingSkillDamage;
    private long paperRightClickInputs;
    private long rawRightClickInputs;
    private long activatedRightClickInputs;

    public RpgEngine(YknRpgPlugin plugin, RpgRegistry registry, AuraSkillsMana mana) {
        this.plugin = plugin;
        this.registry = registry;
        this.mana = mana;
        healthKey = new NamespacedKey(plugin, "rpg_health");
        attackSpeedKey = new NamespacedKey(plugin, "rpg_attack_speed");
        movementSpeedKey = new NamespacedKey(plugin, "rpg_movement_speed");
        skyblockMenuItemKey = NamespacedKey.fromString("skyblock-core:skyblock-menu-item");
        skyblockMenuReturnKey = NamespacedKey.fromString("skyblock-core:skyblock-menu-return");
    }

    public void start() {
        stopTask();
        paperRightClickInputs = 0L;
        rawRightClickInputs = 0L;
        activatedRightClickInputs = 0L;
        rightClickRejections.clear();
        long interval = Math.max(1L, plugin.getConfig().getLong("rpg.tick-interval", 10L));
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, interval);
        shieldTickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickWeaponShields, 1L, 1L);
    }

    public void stop() {
        stopTask();
        if (paperRightClickInputs + rawRightClickInputs > 0L) {
            plugin.getLogger().info("右键技能输入统计：Paper=" + paperRightClickInputs
                    + "，数据包=" + rawRightClickInputs
                    + "，成功施放=" + activatedRightClickInputs + "。");
            if (!rightClickRejections.isEmpty()) {
                plugin.getLogger().info("右键技能拒绝原因：" + rightClickRejections + "。");
            }
        }
        for (CriticalTraitSuppression suppression : activeCriticalTraitSuppressions.values()) {
            mana.clearEquipmentCriticalSuppression(suppression.player(), suppression.modifiers());
        }
        criticalTraitSuppressionEvents.clear();
        activeCriticalTraitSuppressions.clear();
        menuDisplayExposures.clear();
        deferredMenuDisplayExposures.clear();
        menuDisplayExposureDepths.clear();
        for (Player player : Bukkit.getOnlinePlayers()) removeAttributes(player);
        for (UUID entityId : pendingProjectiles.keySet()) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) entity.remove();
        }
        cooldowns.clear();
        charges.clear();
        buffs.clear();
        weaponShields.clear();
        queuedShieldSyncs.clear();
        pendingProjectiles.clear();
        resolvedProjectiles.clear();
        projectileSnapshots.clear();
        recentMainHandWeapons.clear();
        profileCache.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        mana.clearWeaponShieldHealth(event.getPlayer());
        UUID playerId = event.getPlayer().getUniqueId();
        weaponShields.remove(playerId);
        queuedShieldSyncs.remove(playerId);
        rememberMainHandWeapon(event.getPlayer());
        Profile profile = effectiveProfile(event.getPlayer());
        refreshAttributes(event.getPlayer(), profile.stats());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearWeaponShield(event.getPlayer(), true);
        profileCache.remove(event.getPlayer().getUniqueId());
        // Equipment modifiers are non-persistent and AuraSkills unloads them
        // with the user. Removing WISDOM here can race AuraSkills' asynchronous
        // quit save and persist mana after it has been clamped to the unequipped
        // maximum.
        UUID playerId = event.getPlayer().getUniqueId();
        recentMainHandWeapons.remove(playerId);
        CriticalTraitSuppression criticalSuppression =
                activeCriticalTraitSuppressions.remove(playerId);
        if (criticalSuppression != null) {
            mana.clearEquipmentCriticalSuppression(
                    criticalSuppression.player(), criticalSuppression.modifiers());
        }
        criticalTraitSuppressionEvents.entrySet().removeIf(entry ->
                entry.getValue().equals(playerId));
        menuDisplayExposures.entrySet().removeIf(entry ->
                entry.getValue().getUniqueId().equals(playerId));
        deferredMenuDisplayExposures.entrySet().removeIf(entry ->
                entry.getValue().getUniqueId().equals(playerId));
        menuDisplayExposureDepths.remove(playerId);
        buffs.remove(playerId);
        cooldowns.keySet().removeIf(key -> key.playerId().equals(playerId));
        charges.keySet().removeIf(key -> key.playerId().equals(playerId));
        projectileSnapshots.entrySet().removeIf(entry -> entry.getValue().casterId().equals(playerId));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack previous = player.getInventory().getItem(event.getPreviousSlot());
        ItemStack next = player.getInventory().getItem(event.getNewSlot());
        if (registry.isMainHandItem(next)) {
            rememberWeapon(player, event.getNewSlot(), next);
        } else if (isSkyblockMenuItem(next) && registry.isMainHandItem(previous)) {
            rememberWeapon(player, event.getPreviousSlot(), previous);
        } else if (!isSkyblockMenuItem(next)) {
            recentMainHandWeapons.remove(player.getUniqueId());
        }
        scheduleAttributeRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        scheduleAttributeRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryEquipmentChange(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) scheduleAttributeRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryEquipmentDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) scheduleAttributeRefresh(player);
    }

    // Skyblock-Core replaces the held weapon with its hotbar star before it
    // builds the main-menu lore. Keep the cached weapon's Aura stat levels
    // visible for every menu entry path, while trait guards ensure the cached
    // weapon cannot affect gameplay. Health/speed use the displayed Bukkit
    // attributes only during menu construction because that menu reads their
    // effective traits instead of raw stat levels.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMenuInteractDisplayBegin(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) {
            beginMenuDisplayExposure(event, event.getPlayer(), event.getItem());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMenuInteractDisplayComplete(PlayerInteractEvent event) {
        clearMenuDisplayExposure(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMenuEntityInteractDisplayBegin(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.HAND) {
            beginMenuDisplayExposure(event, event.getPlayer(),
                    event.getPlayer().getInventory().getItemInMainHand());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMenuEntityInteractDisplayComplete(PlayerInteractEntityEvent event) {
        clearMenuDisplayExposure(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMenuEntityDamageDisplayBegin(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            beginMenuDisplayExposure(event, player,
                    player.getInventory().getItemInMainHand());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMenuEntityDamageDisplayComplete(EntityDamageByEntityEvent event) {
        clearMenuDisplayExposure(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMenuInventoryDisplayBegin(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean coreInventory = isSkyblockCoreInventory(event);
        boolean bazaarExit = isSkyblockBazaarExitCandidate(event);
        boolean externalReturn = !coreInventory
                && isSkyblockMenuReturnClick(event);
        boolean hotbarMenuClick = isSkyblockMenuItem(event.getCurrentItem())
                && (event.getClick().isLeftClick() || event.getClick().isRightClick());
        if (bazaarExit || externalReturn || hotbarMenuClick) {
            deferredMenuDisplayExposures.put(event, player);
            // Register before Skyblock-Core's LOWEST listener registers its
            // openMain task. Bazaar exits are registered before its NORMAL
            // listener does the same. All run next tick in begin -> render order.
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player pending = deferredMenuDisplayExposures.get(event);
                if (pending == null || !pending.isOnline()) return;
                // A real Bazaar exit closes the old view synchronously. If it
                // is still open, no main menu will be rendered and exposure is
                // unnecessary.
                if (bazaarExit && isOpenSkyblockBazaarInventory(pending)) {
                    deferredMenuDisplayExposures.remove(event);
                    return;
                }
                beginMenuDisplayExposure(event, pending,
                        pending.getInventory().getItemInMainHand());
            });
            return;
        }
        if (coreInventory) {
            beginMenuDisplayExposure(event, player,
                    player.getInventory().getItemInMainHand());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMenuInventoryDisplayComplete(InventoryClickEvent event) {
        if (menuDisplayExposures.containsKey(event)) {
            // Core-owned menu transitions are synchronous, so their item lore
            // is complete before MONITOR and can be restored immediately.
            clearMenuDisplayExposure(event);
        }
        if (!deferredMenuDisplayExposures.containsKey(event)) return;
        // External returns are scheduled by Skyblock-Core. Registered after
        // its task, this restores the real attributes immediately after render
        // in the same scheduler tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            deferredMenuDisplayExposures.remove(event);
            clearMenuDisplayExposure(event);
        });
    }

    // Paper may pre-cancel right clicks whose vanilla result is a no-op (for example,
    // right-clicking air with a sword). Custom item abilities must still see them.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!enabled() || event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        paperRightClickInputs++;
        ActivationResult result = activateRightClick(player, event.getItem(), event.getHand());
        if (result.cancelInteraction()) {
            event.setCancelled(true);
            player.clearActiveItem();
        }
    }

    public void onRawRightClick(Player player, EquipmentSlot hand) {
        if (!enabled() || hand != EquipmentSlot.HAND || !player.isOnline()) return;
        rawRightClickInputs++;
        boolean interceptVanilla = shouldInterceptVanillaRightClick(player);
        ActivationResult result = activateRightClick(player, player.getInventory().getItemInMainHand(), hand);
        if (interceptVanilla || result.cancelInteraction()) player.clearActiveItem();
    }

    public boolean shouldInterceptVanillaRightClick(Player player) {
        if (!enabled() || !player.isOnline()) return false;
        Profile profile = effectiveProfile(player);
        Trigger trigger = resolveRightClickTrigger(profile, player.isSneaking());
        return profile.abilities().stream().anyMatch(source ->
                source.ability().trigger() == trigger && source.ability().cancelInteraction());
    }

    private ActivationResult activateRightClick(Player player, ItemStack item, EquipmentSlot hand) {
        Profile profile = effectiveProfile(player, item, hand);
        Trigger trigger = resolveRightClickTrigger(profile, player.isSneaking());
        boolean matched = profile.abilities().stream()
                .anyMatch(source -> source.ability().trigger() == trigger);
        if (!matched) rightClickRejections.merge("无匹配技能", 1L, Long::sum);
        ActivationResult result = activate(profile, player, trigger, null, true);
        if (result.activated()) activatedRightClickInputs++;
        return result;
    }

    private Trigger resolveRightClickTrigger(Profile profile, boolean sneaking) {
        if (!sneaking) return Trigger.RIGHT_CLICK;
        boolean hasSneakAbility = profile.abilities().stream()
                .anyMatch(source -> source.ability().trigger() == Trigger.SNEAK_RIGHT_CLICK);
        return hasSneakAbility ? Trigger.SNEAK_RIGHT_CLICK : Trigger.RIGHT_CLICK;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!enabled() || !(projectile.getShooter() instanceof Player player)) return;
        Profile profile = projectile instanceof ThrowableProjectile throwable
                ? effectiveProfile(player, throwable.getItem(), EquipmentSlot.HAND)
                : effectiveProfile(player);
        projectileSnapshots.putIfAbsent(projectile.getUniqueId(), new ProjectileSnapshot(
                player.getUniqueId(), profile, System.currentTimeMillis() + 120_000L));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!enabled() || !(event.getEntity() instanceof Player player)
                || !(event.getProjectile() instanceof Projectile projectile)) return;
        Profile firingProfile = effectiveProfile(player, event.getBow(), event.getHand());
        activate(firingProfile, player, Trigger.BOW_SHOT, null, true, projectile);
        Profile projectileProfile = effectiveProfile(player, event.getBow(), event.getHand());
        projectileSnapshots.put(projectile.getUniqueId(), new ProjectileSnapshot(
                player.getUniqueId(), projectileProfile, System.currentTimeMillis() + 120_000L));
    }

    // The positive AuraSkills critical stats are kept active for HUD/menu reads.
    // Hide only their trait contribution while AuraSkills builds this damage
    // event, because YknRpg already resolves equipment critical chance/damage.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onAuraDamageBeforeTraits(DamageEvent event) {
        if (!enabled()) return;
        Player attacker = event.getDamageMeta().getAttackerAsPlayer();
        if (attacker == null) return;
        UUID playerId = attacker.getUniqueId();
        CriticalTraitSuppression suppression = activeCriticalTraitSuppressions.get(playerId);
        if (suppression == null) {
            SuppressedTraits modifiers = mana.suppressEquipmentCriticalTraits(attacker);
            if (modifiers.modifierNames().isEmpty()) return;
            suppression = new CriticalTraitSuppression(attacker, modifiers);
            activeCriticalTraitSuppressions.put(playerId, suppression);
        }
        suppression.incrementDepth();
        criticalTraitSuppressionEvents.put(event, playerId);

        // Defensive cleanup for a third-party listener that aborts dispatch.
        Bukkit.getScheduler().runTask(plugin, () -> clearCriticalTraitSuppression(event));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAuraDamageAfterTraits(DamageEvent event) {
        clearCriticalTraitSuppression(event);
    }

    // Resolve YknRpg's base RPG damage before YknClass applies its HIGH-priority
    // profession multiplier. Running later would overwrite Saber/Archer/Lancer
    // and Assassin bonuses with a fresh absolute damage value.
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!enabled() || applyingSkillDamage) return;
        if (event.getDamager() instanceof Player menuPlayer
                && isSkyblockMenuItem(menuPlayer.getInventory().getItemInMainHand())) return;

        Player attacker = directOrProjectilePlayer(event.getDamager());
        LivingEntity victim = event.getEntity() instanceof LivingEntity living ? living : null;
        boolean engineProjectile = event.getDamager() instanceof Projectile projectile
                && pendingProjectiles.containsKey(projectile.getUniqueId());
        if (engineProjectile) {
            event.setCancelled(true);
            return;
        }
        if (attacker != null && victim != null) {
            ProjectileSnapshot snapshot = event.getDamager() instanceof Projectile projectile
                    ? projectileSnapshots.get(projectile.getUniqueId()) : null;
            Profile profile = snapshot != null && snapshot.casterId().equals(attacker.getUniqueId())
                    ? snapshot.profile() : effectiveProfile(attacker);
            if (!(event.getDamager() instanceof Projectile)
                    || event.getDamager() instanceof AbstractArrow) {
                applyOutgoingDamage(event, victim, profile.stats());
            }
            Trigger trigger = event.getDamager() instanceof Projectile
                    ? Trigger.PROJECTILE_HIT : Trigger.MELEE_HIT;
            activate(profile, attacker, trigger, victim, false);
        }

        if (victim instanceof Player player) {
            Profile profile = effectiveProfile(player);
            if (plugin.getConfig().getBoolean("rpg.combat.defense-enabled", true)) {
                double defense = Math.max(0.0, profile.stats().get(Stat.DEFENSE));
                double cap = Math.max(0.0, plugin.getConfig().getDouble("rpg.combat.defense-cap", 10000.0));
                event.setDamage(event.getDamage() / (1.0 + Math.min(defense, cap) / 100.0));
            }
            LivingEntity source = event.getDamager() instanceof LivingEntity living
                    ? living
                    : event.getDamager() instanceof Projectile projectile
                    && projectile.getShooter() instanceof LivingEntity living ? living : null;
            double finalDamage = Math.max(0.0, event.getFinalDamage());
            UUID playerId = player.getUniqueId();
            double logicalShield = activeWeaponShield(playerId);
            double consumedShield = Math.min(finalDamage, logicalShield);
            double projectedMaximum = maximumHealth(player) - consumedShield;
            PlayerWeaponShields shieldState = weaponShields.get(playerId);
            if (shieldState != null) {
                // The applied max-health modifier can intentionally lag one or
                // more same-tick hits. Derive the post-hit cap from the logical
                // shield instead of that stale modifier.
                projectedMaximum = maximumHealth(player)
                        - mana.weaponShieldHealth(player)
                        + Math.max(0.0, logicalShield - consumedShield);
            }
            double projectedHealthPercent = Math.max(0.0,
                    player.getHealth() - finalDamage)
                    / Math.max(1.0, projectedMaximum) * 100.0;
            activate(profile, player, Trigger.DAMAGED, source, false,
                    null, projectedHealthPercent);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShieldBeforeDamage(EntityDamageEvent event) {
        if (!enabled() || !(event.getEntity() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();
        PlayerWeaponShields state = weaponShields.get(playerId);
        if (state == null) return;

        // Damage is applied only after every listener returns. A nested damage
        // event may therefore start while an outer hit is still queued; lowering
        // MAX_HEALTH here would clamp once and let the outer hit deduct again.
        // Keep all decreases logical until the already-scheduled next-tick sync.
        if (queuedShieldSyncs.contains(playerId)) {
            if (!refreshQueuedWeaponShieldState(
                    player, state, System.currentTimeMillis())) return;
            removeEmptyWeaponShield(playerId, state);
            return;
        }
        refreshWeaponShieldState(player, state, System.currentTimeMillis());
        removeEmptyWeaponShield(playerId, state);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShieldDamage(EntityDamageEvent event) {
        if (!enabled() || !(event.getEntity() instanceof Player player)) return;
        UUID playerId = player.getUniqueId();
        PlayerWeaponShields state = weaponShields.get(playerId);
        double damage = event.getFinalDamage();
        if (state == null || !Double.isFinite(damage) || damage <= 0.0) return;
        double consumed = state.timeline.damage(damage);
        if (consumed <= SHIELD_EPSILON) return;

        // The event's damage is applied after listeners return. Lowering MAX_HEALTH
        // here could clamp health first and make the same hit count twice, so the
        // AuraSkills modifier follows on the next server tick.
        queueWeaponShieldSync(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!enabled()) return;
        if (event.getEntity() instanceof Player player) clearWeaponShield(player, false);
        Player killer = event.getEntity().getKiller();
        if (killer != null) activateTrigger(killer, Trigger.KILL, event.getEntity(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent event) {
        UUID projectileId = event.getEntity().getUniqueId();
        PendingProjectile pending = pendingProjectiles.get(projectileId);
        if (pending == null || !resolvedProjectiles.add(projectileId)) return;
        Player caster = Bukkit.getPlayer(pending.casterId());
        Location hit = event.getEntity().getLocation();
        if (caster != null && event.getHitEntity() instanceof LivingEntity target && canDamage(caster, target)) {
            double amount = scaledDamage(pending.effect(), pending.profile().stats());
            if (amount > 0.0) dealSkillDamage(caster, target, amount);
            activate(pending.profile(), caster, Trigger.PROJECTILE_HIT, target, false);
        }
        spawnNamedParticle(hit, pending.effect().text("hit-particle", "CRIT"),
                (int) pending.effect().number("hit-particle-count", 10), pending.effect());
        Bukkit.getScheduler().runTask(plugin, () -> finishPendingProjectile(projectileId));
    }

    private void tick() {
        if (!enabled()) {
            for (Player player : Bukkit.getOnlinePlayers()) removeAttributes(player);
            return;
        }
        long now = System.currentTimeMillis();
        cleanup(now);
        for (Player player : Bukkit.getOnlinePlayers()) {
            rememberMainHandWeapon(player);
            Profile profile = effectiveProfile(player);
            refreshAttributes(player, profile.stats());
            activate(profile, player, Trigger.TICK, null, false);
        }
    }

    private void cleanup(long now) {
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        buffs.values().forEach(list -> list.removeIf(buff -> buff.expiresAt() <= now));
        buffs.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        Iterator<Map.Entry<UUID, PendingProjectile>> iterator = pendingProjectiles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingProjectile> entry = iterator.next();
            if (entry.getValue().expiresAt() > now) continue;
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity != null) entity.remove();
            resolvedProjectiles.remove(entry.getKey());
            projectileSnapshots.remove(entry.getKey());
            iterator.remove();
        }
        projectileSnapshots.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt() <= now) return true;
            Entity entity = Bukkit.getEntity(entry.getKey());
            return entity == null || !entity.isValid();
        });
    }

    private void finishPendingProjectile(UUID projectileId) {
        pendingProjectiles.remove(projectileId);
        resolvedProjectiles.remove(projectileId);
        projectileSnapshots.remove(projectileId);
        Entity entity = Bukkit.getEntity(projectileId);
        if (entity != null) entity.remove();
    }

    private ActivationResult activateTrigger(Player player, Trigger trigger, LivingEntity target, boolean feedback) {
        return activate(effectiveProfile(player), player, trigger, target, feedback);
    }

    private ActivationResult activate(Profile profile, Player player, Trigger trigger,
                                      LivingEntity initialTarget, boolean feedback) {
        return activate(profile, player, trigger, initialTarget, feedback, null, null);
    }

    private ActivationResult activate(Profile profile, Player player, Trigger trigger,
                                      LivingEntity initialTarget, boolean feedback,
                                      Projectile triggerProjectile) {
        return activate(profile, player, trigger, initialTarget, feedback, triggerProjectile, null);
    }

    private ActivationResult activate(Profile profile, Player player, Trigger trigger,
                                      LivingEntity initialTarget, boolean feedback,
                                      Projectile triggerProjectile, Double healthPercentOverride) {
        boolean activated = false;
        boolean cancel = false;
        for (AbilitySource source : profile.abilities()) {
            Ability ability = source.ability();
            if (ability.trigger() != trigger) continue;
            cancel |= ability.cancelInteraction();
            if (activateAbility(source, player, initialTarget, profile, feedback,
                    triggerProjectile, healthPercentOverride)) {
                activated = true;
            }
        }
        return new ActivationResult(activated, cancel);
    }

    private boolean activateAbility(AbilitySource source, Player player, LivingEntity initialTarget,
                                    Profile profile, boolean feedback, Projectile triggerProjectile,
                                    Double healthPercentOverride) {
        if (cn.yknrpg.item.ForgeSupport.disabled(source.item())) return false;
        Ability ability = source.ability();
        Stats stats = profile.stats();
        if (ability.sneaking() != null && ability.sneaking() != player.isSneaking()) {
            return rejectRightClick(ability, "潜行条件不符");
        }
        // 100% is the configuration default and means that the ability has no
        // health requirement. Attribute plugins can briefly leave current health
        // above a freshly recalculated maximum, so comparing against 100 here
        // would permanently reject otherwise unrestricted abilities.
        if (ability.healthBelowPercent() < 100.0) {
            double healthPercent = healthPercentOverride != null ? healthPercentOverride
                    : player.getHealth() / Math.max(1.0, maximumHealth(player)) * 100.0;
            if (healthPercent > ability.healthBelowPercent()) {
                return rejectRightClick(ability, "生命条件不符");
            }
        }
        if (ThreadLocalRandom.current().nextDouble() > ability.chance()) return rejectRightClick(ability, "概率未触发");

        LivingEntity target = initialTarget;
        if (target == null && (ability.targetRequired() || ability.behindTarget())) {
            target = rayTarget(player, ability.targetRange());
        }
        if (ability.targetRequired() && target == null) {
            if (feedback) sendFeedback(player, "messages.ability-no-target",
                    "ability", ability.name());
            return rejectRightClick(ability, "没有目标");
        }
        if (ability.behindTarget() && (target == null || !isBehind(player, target))) {
            return rejectRightClick(ability, "不在目标背后");
        }

        long now = System.currentTimeMillis();
        AbilityKey key = new AbilityKey(player.getUniqueId(), source.key());
        long end = cooldowns.getOrDefault(key, 0L);
        if (end > now) {
            if (feedback) sendFeedback(player, "messages.ability-cooldown",
                    "ability", ability.name(), "seconds", NUMBER.format((end - now) / 1000.0));
            return rejectRightClick(ability, "冷却中");
        }

        ChargeState charge = null;
        if (ability.maxCharges() > 0) {
            charge = charges.computeIfAbsent(key, ignored ->
                    new ChargeState(ability.maxCharges(), ability.maxCharges(), now));
            refreshCharges(charge, ability, now);
            if (charge.current() <= 0) {
                if (feedback) sendFeedback(player, "messages.ability-no-charges",
                        "ability", ability.name(), "seconds",
                        NUMBER.format(Math.max(0.0, (charge.nextRechargeAt(ability) - now) / 1000.0)));
                return rejectRightClick(ability, "无充能");
            }
        }

        ManaState manaBefore = mana.state(player);
        if (ability.manaCost() > 0.0 && !manaBefore.available()) {
            if (feedback) sendFeedback(player, "messages.ability-mana-unavailable",
                    "ability", ability.name());
            return rejectRightClick(ability, "法力数据未加载");
        }
        if (manaBefore.current() + 0.0001 < ability.manaCost()) {
            if (feedback) sendFeedback(player, "messages.ability-no-mana",
                    "ability", ability.name(), "mana", NUMBER.format(manaBefore.current()),
                    "cost", NUMBER.format(ability.manaCost()));
            return rejectRightClick(ability, "法力不足");
        }
        if (!mana.consume(player, ability.manaCost())) {
            ManaState latest = mana.state(player);
            if (feedback) sendFeedback(player,
                    latest.available() ? "messages.ability-no-mana" : "messages.ability-mana-unavailable",
                    "ability", ability.name(), "mana", NUMBER.format(latest.current()),
                    "cost", NUMBER.format(ability.manaCost()));
            return rejectRightClick(ability, "法力扣除失败");
        }

        SkillContext context = new SkillContext(player, target, stats, profile, source, triggerProjectile);
        boolean applied = false;
        for (Effect effect : ability.effects()) {
            try {
                applied |= executeEffect(effect, context);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("技能效果执行失败（" + source.key() + "/" + effect.type()
                        + "）：" + exception.getMessage());
            }
        }
        if (!applied) {
            mana.refund(player, ability.manaCost());
            return rejectRightClick(ability, "效果均未生效");
        }

        if (charge != null) charge.consume(now);
        if (ability.cooldownSeconds() > 0.0) {
            cooldowns.put(key, now + (long) (ability.cooldownSeconds() * 1000.0));
        }
        if (ability.announce()) {
            ManaState remaining = mana.state(player);
            sendFeedback(player, "messages.ability-used",
                    "ability", ability.name(), "mana", NUMBER.format(remaining.current()),
                    "max_mana", NUMBER.format(remaining.maximum()));
        }
        return true;
    }

    private boolean rejectRightClick(Ability ability, String reason) {
        if (ability.trigger() == Trigger.RIGHT_CLICK || ability.trigger() == Trigger.SNEAK_RIGHT_CLICK) {
            rightClickRejections.merge(reason, 1L, Long::sum);
        }
        return false;
    }

    private void sendFeedback(Player player, String path, Object... replacements) {
        var message = plugin.message(path, replacements);
        player.sendActionBar(message);
        if (plugin.getConfig().getBoolean("rpg.feedback-in-chat", true)) {
            player.sendMessage(message);
        }
    }

    private boolean executeEffect(Effect effect, SkillContext context) {
        return switch (effect.type()) {
            case "DAMAGE" -> damage(effect, context);
            case "HEAL" -> heal(effect, context);
            case "POTION" -> potion(effect, context);
            case "TELEPORT" -> teleport(effect, context.caster());
            case "DASH" -> dash(effect, context.caster());
            case "PROJECTILE" -> projectile(effect, context);
            case "KNOCKBACK" -> knockback(effect, context);
            case "LIGHTNING" -> lightning(effect, context);
            case "PARTICLE" -> particle(effect, context);
            case "SOUND" -> sound(effect, context);
            case "ABSORPTION" -> absorption(effect, context);
            case "CLEANSE" -> cleanse(effect, context);
            case "STAT_BUFF" -> statBuff(effect, context);
            default -> throw new IllegalArgumentException("未知效果类型：" + effect.type());
        };
    }

    private boolean damage(Effect effect, SkillContext context) {
        List<LivingEntity> targets = targets(effect, context, true);
        double amount = scaledDamage(effect, context.stats());
        if (targets.isEmpty() || amount <= 0.0) return false;
        for (LivingEntity target : targets) dealSkillDamage(context.caster(), target, amount);
        return true;
    }

    private boolean heal(Effect effect, SkillContext context) {
        List<LivingEntity> targets = targets(effect, context, false);
        if (targets.isEmpty()) return false;
        double raw = scaledAmount(effect, context.stats());
        double scale = plugin.getConfig().getDouble("rpg.health.value-scale", 0.05);
        for (LivingEntity target : targets) {
            double amount = raw * scale + maximumHealth(target) * effect.number("max-health-percent", 0.0) / 100.0;
            if (target instanceof Player) {
                amount *= 1.0 + Math.max(0.0, context.stats().get(Stat.MENDING)) / 100.0;
            }
            EntityRegainHealthEvent regain = new EntityRegainHealthEvent(
                    target, Math.max(0.0, amount), EntityRegainHealthEvent.RegainReason.CUSTOM);
            Bukkit.getPluginManager().callEvent(regain);
            if (!regain.isCancelled() && regain.getAmount() > 0.0) {
                target.setHealth(Math.min(maximumHealth(target), target.getHealth() + regain.getAmount()));
            }
        }
        return raw > 0.0 || effect.number("max-health-percent", 0.0) > 0.0;
    }

    private boolean potion(Effect effect, SkillContext context) {
        PotionEffectType type = Registry.MOB_EFFECT.get(configuredKey(effect.text("potion", "")));
        if (type == null) throw new IllegalArgumentException("未知药水效果：" + effect.text("potion", ""));
        List<LivingEntity> targets = targets(effect, context, false);
        int duration = Math.max(1, (int) Math.round(effect.number("duration", 5.0) * 20.0));
        int amplifier = Math.max(0, (int) effect.number("amplifier", 0.0));
        for (LivingEntity target : targets) {
            target.addPotionEffect(new PotionEffect(type, duration, amplifier, true,
                    effect.flag("particles", true), effect.flag("icon", true)));
        }
        return !targets.isEmpty();
    }

    private boolean teleport(Effect effect, Player player) {
        double distance = Math.max(0.0, effect.number("distance", 8.0));
        Location origin = player.getLocation();
        Vector direction = player.getEyeLocation().getDirection().normalize();
        Location best = origin.clone();
        for (double step = 0.5; step <= distance; step += 0.5) {
            Location candidate = origin.clone().add(direction.clone().multiply(step));
            if (!candidate.getBlock().isPassable()
                    || !candidate.clone().add(0, 1, 0).getBlock().isPassable()) break;
            best = candidate;
        }
        if (best.distanceSquared(origin) < 0.1) return false;
        best.setYaw(origin.getYaw());
        best.setPitch(origin.getPitch());
        return player.teleport(best);
    }

    private boolean dash(Effect effect, Player player) {
        Vector velocity = player.getEyeLocation().getDirection().normalize().multiply(effect.number("power", 1.2));
        velocity.setY(velocity.getY() + effect.number("vertical", 0.15));
        player.setVelocity(velocity);
        return true;
    }

    private boolean projectile(Effect effect, SkillContext context) {
        Player player = context.caster();
        Projectile projectile = effect.flag("reuse-trigger", false)
                ? context.triggerProjectile() : null;
        if (projectile == null) {
            String type = effect.text("projectile", "SNOWBALL").toUpperCase(Locale.ROOT);
            switch (type) {
                case "ARROW" -> projectile = player.launchProjectile(Arrow.class);
                case "TRIDENT" -> projectile = player.launchProjectile(Trident.class);
                case "SNOWBALL" -> projectile = player.launchProjectile(Snowball.class);
                default -> throw new IllegalArgumentException("未知投射物：" + type);
            }
        }
        if (projectile instanceof AbstractArrow arrow) {
            arrow.setDamage(0.0);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        }
        projectile.setGravity(effect.flag("gravity", true));
        projectile.setVelocity(player.getEyeLocation().getDirection().normalize()
                .multiply(effect.number("speed", 1.8)));
        long expires = System.currentTimeMillis() + (long) (Math.max(1.0,
                effect.number("lifetime", 10.0)) * 1000.0);
        pendingProjectiles.put(projectile.getUniqueId(),
                new PendingProjectile(player.getUniqueId(), effect, context.profile(), expires));
        return true;
    }

    private boolean knockback(Effect effect, SkillContext context) {
        List<LivingEntity> targets = targets(effect, context, true);
        for (LivingEntity target : targets) {
            Vector vector = target.getLocation().toVector().subtract(context.caster().getLocation().toVector());
            if (vector.lengthSquared() < 0.001) vector = context.caster().getLocation().getDirection();
            target.setVelocity(vector.normalize().multiply(effect.number("power", 1.0))
                    .setY(effect.number("vertical", 0.2)));
        }
        return !targets.isEmpty();
    }

    private boolean lightning(Effect effect, SkillContext context) {
        List<LivingEntity> targets = targets(effect, context, true);
        for (LivingEntity target : targets) {
            target.getWorld().strikeLightningEffect(target.getLocation());
            double amount = scaledDamage(effect, context.stats());
            if (amount > 0.0) dealSkillDamage(context.caster(), target, amount);
        }
        return !targets.isEmpty();
    }

    private boolean particle(Effect effect, SkillContext context) {
        List<Location> locations = effectLocations(effect, context);
        for (Location location : locations) {
            spawnNamedParticle(location, effect.text("particle", "END_ROD"),
                    Math.max(1, (int) effect.number("count", 16)), effect);
        }
        return !locations.isEmpty();
    }

    private boolean sound(Effect effect, SkillContext context) {
        Sound sound = configuredSound(effect.text("sound", "ENTITY_EXPERIENCE_ORB_PICKUP"));
        List<Location> locations = effectLocations(effect, context);
        for (Location location : locations) {
            location.getWorld().playSound(location, sound,
                    (float) effect.number("volume", 1.0), (float) effect.number("pitch", 1.0));
        }
        return !locations.isEmpty();
    }

    private boolean absorption(Effect effect, SkillContext context) {
        List<LivingEntity> targets = targets(effect, context, false);
        double amount = scaledAmount(effect, context.stats())
                * plugin.getConfig().getDouble("rpg.health.value-scale", 0.05);
        if (!Double.isFinite(amount) || amount <= SHIELD_EPSILON) {
            throw new IllegalArgumentException("ABSORPTION amount 计算结果必须为有限正数");
        }
        if (targets.isEmpty()) return false;
        double configuredLimit = effect.number("cap", 20.0);
        if (!Double.isFinite(configuredLimit) || configuredLimit <= SHIELD_EPSILON) {
            throw new IllegalArgumentException("ABSORPTION cap 必须为有限正数");
        }
        boolean weaponAbility = context.source().owner().startsWith("item:");
        double durationSeconds = shieldDurationSeconds(effect);
        boolean applied = false;
        for (LivingEntity target : targets) {
            if (weaponAbility && target instanceof Player player) {
                applied |= grantWeaponShield(player, context.source().owner(), amount,
                        configuredLimit, durationSeconds);
            } else {
                addVanillaAbsorption(target, amount, configuredLimit);
                applied = true;
            }
        }
        return applied;
    }

    private void addVanillaAbsorption(LivingEntity target, double amount, double configuredLimit) {
        AttributeInstance maximum = target.getAttribute(Attribute.MAX_ABSORPTION);
        double limit = maximum == null || maximum.getValue() <= 0.0
                ? configuredLimit : Math.min(configuredLimit, maximum.getValue());
        target.setAbsorptionAmount(Math.min(limit, target.getAbsorptionAmount() + amount));
    }

    private boolean grantWeaponShield(Player player, String source, double amount,
                                      double limit, double durationSeconds) {
        double value = Math.min(limit, amount);
        if (value <= SHIELD_EPSILON) return false;
        UUID playerId = player.getUniqueId();
        PlayerWeaponShields state = weaponShields.computeIfAbsent(playerId,
                ignored -> new PlayerWeaponShields());

        long now = System.currentTimeMillis();
        boolean pendingDamage = queuedShieldSyncs.contains(playerId);
        if (pendingDamage) {
            // Pending decreases stay logical until the outermost Bukkit damage
            // dispatch returns; a positive inheritance can still be added safely.
            if (!refreshQueuedWeaponShieldState(player, state, now)) {
                removeEmptyWeaponShield(playerId, state);
                return false;
            }
        } else {
            if (!refreshWeaponShieldState(player, state, now)) {
                removeEmptyWeaponShield(playerId, state);
                return false;
            }
        }

        WeaponShieldTimeline previous = state.timeline.copy();
        double beforeGrant = state.timeline.activeRemaining();
        long expiresAt = shieldExpiration(now, durationSeconds);
        state.timeline.grant(source, value, expiresAt, now);
        if (pendingDamage) {
            if (!applyQueuedNonDamageShieldDelta(
                    player, state, state.timeline.activeRemaining() - beforeGrant)) {
                state.timeline = previous;
                removeEmptyWeaponShield(playerId, state);
                return false;
            }
            return true;
        }
        if (!applyWeaponShieldAmount(player, state, state.timeline.activeRemaining(), true)) {
            state.timeline = previous;
            removeEmptyWeaponShield(playerId, state);
            return false;
        }
        return true;
    }

    private boolean refreshQueuedWeaponShieldState(
            Player player, PlayerWeaponShields state, long now) {
        WeaponShieldTimeline previous = state.timeline.copy();
        double before = state.timeline.activeRemaining();
        state.timeline.refresh(now);
        if (applyQueuedNonDamageShieldDelta(
                player, state, state.timeline.activeRemaining() - before)) return true;
        state.timeline = previous;
        return false;
    }

    private boolean applyQueuedNonDamageShieldDelta(
            Player player, PlayerWeaponShields state, double delta) {
        if (!Double.isFinite(delta) || Math.abs(delta) <= SHIELD_EPSILON) return true;
        if (delta < 0.0) {
            // Unlike damage consumption, expiry/replacement does not deduct
            // Bukkit health by itself. Preserve that provenance until the safe
            // next-tick max-health reconciliation.
            state.deferredNonDamageHealthDelta += delta;
            return true;
        }
        // A positive inheritance/grant is safe to expose immediately: add it
        // on top of the stale applied value without materializing any pending
        // damage decrease.
        return applyWeaponShieldAmount(
                player, state, state.appliedAmount + delta, true);
    }

    private boolean settleQueuedWeaponShield(
            Player player, PlayerWeaponShields state) {
        double healthBefore = player.getHealth();
        if (!applyWeaponShieldAmount(
                player, state, state.timeline.activeRemaining(), false)) return false;
        double healthDelta = state.deferredNonDamageHealthDelta;
        if (Math.abs(healthDelta) > SHIELD_EPSILON && !player.isDead()) {
            double desired = healthBefore + healthDelta;
            player.setHealth(Math.min(maximumHealth(player),
                    Math.max(SHIELD_EPSILON, desired)));
        }
        state.deferredNonDamageHealthDelta = 0.0;
        return true;
    }

    private void queueWeaponShieldSync(UUID playerId) {
        if (!queuedShieldSyncs.add(playerId)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            PlayerWeaponShields state = weaponShields.get(playerId);
            if (player == null || !player.isOnline() || state == null) {
                queuedShieldSyncs.remove(playerId);
                return;
            }
            if (!settleQueuedWeaponShield(player, state)) return;
            queuedShieldSyncs.remove(playerId);
            refreshWeaponShieldState(player, state, System.currentTimeMillis());
            removeEmptyWeaponShield(playerId, state);
        });
    }

    private double shieldDurationSeconds(Effect effect) {
        double defaultDuration = plugin.getConfig().getDouble(
                "rpg.shield.default-duration", DEFAULT_SHIELD_DURATION_SECONDS);
        double duration = effect.number("duration", defaultDuration);
        if (!Double.isFinite(duration) || duration <= 0.0) {
            throw new IllegalArgumentException("ABSORPTION duration 必须为有限正数");
        }
        return duration;
    }

    private long shieldExpiration(long now, double durationSeconds) {
        double durationMillis = durationSeconds * 1000.0;
        if (!Double.isFinite(durationMillis)
                || durationMillis >= (double) (Long.MAX_VALUE - now)) return Long.MAX_VALUE;
        return now + Math.max(50L, Math.round(durationMillis));
    }

    private double activeWeaponShield(UUID playerId) {
        PlayerWeaponShields state = weaponShields.get(playerId);
        return state == null ? 0.0 : state.timeline.activeRemaining();
    }

    private void tickWeaponShields() {
        if (weaponShields.isEmpty()) return;
        if (!enabled()) {
            for (UUID playerId : List.copyOf(weaponShields.keySet())) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) clearWeaponShield(player, true);
                else {
                    weaponShields.remove(playerId);
                    queuedShieldSyncs.remove(playerId);
                }
            }
            return;
        }

        long now = System.currentTimeMillis();
        for (UUID playerId : List.copyOf(weaponShields.keySet())) {
            PlayerWeaponShields state = weaponShields.get(playerId);
            if (state == null) continue;
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                weaponShields.remove(playerId);
                queuedShieldSyncs.remove(playerId);
                continue;
            }
            if (queuedShieldSyncs.contains(playerId)) {
                if (!settleQueuedWeaponShield(player, state)) continue;
                queuedShieldSyncs.remove(playerId);
            }
            refreshWeaponShieldState(player, state, now);
            removeEmptyWeaponShield(playerId, state);
        }
    }

    private boolean refreshWeaponShieldState(Player player, PlayerWeaponShields state, long now) {
        WeaponShieldTimeline previous = state.timeline.copy();
        state.timeline.refresh(now);
        if (applyWeaponShieldAmount(player, state, state.timeline.activeRemaining(), true)) return true;
        state.timeline = previous;
        return false;
    }

    private boolean applyWeaponShieldAmount(Player player, PlayerWeaponShields state,
                                            double target, boolean adjustHealth) {
        target = Math.max(0.0, target);
        double previous = state.appliedAmount;

        double healthBefore = player.getHealth();
        double maximumBefore = maximumHealth(player);
        double actualShieldBefore = mana.weaponShieldHealth(player);
        if (!mana.syncWeaponShieldHealth(player, target)) return false;
        double maximumAfter = maximumHealth(player);
        double expectedChange = target - actualShieldBefore;
        double actualChange = maximumAfter - maximumBefore;
        double tolerance = Math.max(SHIELD_EPSILON, Math.abs(expectedChange) * 0.000001);
        if (Math.abs(actualChange - expectedChange) > tolerance) {
            mana.syncWeaponShieldHealth(player, previous);
            if (!player.isDead()) {
                player.setHealth(Math.min(maximumHealth(player),
                        Math.max(SHIELD_EPSILON, healthBefore)));
            }
            return false;
        }

        state.appliedAmount = target;
        if (adjustHealth && !player.isDead()) {
            // Current health tracks the logical shield, not transient Bukkit
            // amplification caused by a class multiplier changing around it.
            double desiredHealth = healthBefore - previous + target;
            player.setHealth(Math.min(maximumAfter, Math.max(SHIELD_EPSILON, desiredHealth)));
        }
        return true;
    }

    private void syncAppliedWeaponShield(Player player) {
        PlayerWeaponShields state = weaponShields.get(player.getUniqueId());
        mana.syncWeaponShieldHealth(player, state == null ? 0.0 : state.appliedAmount);
    }

    private void removeEmptyWeaponShield(UUID playerId, PlayerWeaponShields state) {
        if (state.timeline.isEmpty()
                && state.appliedAmount <= SHIELD_EPSILON
                && Math.abs(state.deferredNonDamageHealthDelta) <= SHIELD_EPSILON) {
            weaponShields.remove(playerId, state);
        }
    }

    private void clearWeaponShield(Player player, boolean removeRemainingHealth) {
        UUID playerId = player.getUniqueId();
        PlayerWeaponShields state = weaponShields.remove(playerId);
        double remaining = state == null ? 0.0 : state.timeline.activeRemaining();
        double deferred = state == null ? 0.0 : state.deferredNonDamageHealthDelta;
        queuedShieldSyncs.remove(playerId);
        double healthBefore = player.getHealth();
        mana.clearWeaponShieldHealth(player);
        double healthDelta = deferred - remaining;
        if (removeRemainingHealth
                && Math.abs(healthDelta) > SHIELD_EPSILON
                && !player.isDead()) {
            double baseHealth = Math.max(SHIELD_EPSILON, healthBefore + healthDelta);
            player.setHealth(Math.min(maximumHealth(player), baseHealth));
        }
    }

    private boolean cleanse(Effect effect, SkillContext context) {
        List<LivingEntity> targets = targets(effect, context, false);
        Set<String> negative = Set.of("POISON", "WITHER", "SLOWNESS", "WEAKNESS", "BLINDNESS",
                "NAUSEA", "MINING_FATIGUE", "HUNGER", "DARKNESS");
        for (LivingEntity target : targets) {
            for (PotionEffect active : List.copyOf(target.getActivePotionEffects())) {
                String key = active.getType().getKey().getKey().toUpperCase(Locale.ROOT);
                if (effect.flag("all", false) || negative.contains(key)) target.removePotionEffect(active.getType());
            }
        }
        return !targets.isEmpty();
    }

    private boolean statBuff(Effect effect, SkillContext context) {
        Stat stat;
        try {
            stat = Stat.valueOf(effect.text("stat", "").toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知临时属性：" + effect.text("stat", ""));
        }
        if (stat == Stat.VITALITY || stat == Stat.FEROCITY) return false;
        double amount = effect.number("amount", 0.0);
        long expires = System.currentTimeMillis() + (long) (Math.max(0.05,
                effect.number("duration", 5.0)) * 1000.0);
        List<LivingEntity> targets = targets(effect, context, false);
        for (LivingEntity target : targets) {
            if (target instanceof Player player) {
                List<TimedBuff> active = buffs.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>());
                long now = System.currentTimeMillis();
                active.removeIf(buff -> buff.expiresAt() <= now);
                active.add(new TimedBuff(stat, amount, expires));
            }
        }
        return targets.stream().anyMatch(Player.class::isInstance);
    }

    private List<LivingEntity> targets(Effect effect, SkillContext context, boolean damageOnly) {
        String selector = effect.text("target", context.target() == null ? "SELF" : "CURRENT")
                .toUpperCase(Locale.ROOT).replace('-', '_');
        List<LivingEntity> result = new ArrayList<>();
        switch (selector) {
            case "SELF", "CASTER" -> result.add(context.caster());
            case "CURRENT", "TARGET" -> {
                if (context.target() != null) result.add(context.target());
            }
            case "RAY" -> {
                LivingEntity target = rayTarget(context.caster(), effect.number("range", 16.0));
                if (target != null) result.add(target);
            }
            case "NEARBY_ENEMIES" -> {
                double radius = Math.max(0.1, effect.number("radius", 5.0));
                for (Entity entity : context.caster().getWorld().getNearbyEntities(
                        context.caster().getLocation(), radius, radius, radius)) {
                    if (entity instanceof LivingEntity living && canDamage(context.caster(), living)) result.add(living);
                }
            }
            case "NEARBY_ALLIES" -> {
                double radius = Math.max(0.1, effect.number("radius", 5.0));
                result.add(context.caster());
                for (Entity entity : context.caster().getWorld().getNearbyEntities(
                        context.caster().getLocation(), radius, radius, radius)) {
                    if (entity instanceof Player player && player != context.caster()) result.add(player);
                }
            }
            default -> throw new IllegalArgumentException("未知目标选择器：" + selector);
        }
        result.removeIf(target -> target.isDead() || (damageOnly && !canDamage(context.caster(), target)));
        return result;
    }

    private List<Location> effectLocations(Effect effect, SkillContext context) {
        String selector = effect.text("target", context.target() == null ? "SELF" : "CURRENT")
                .toUpperCase(Locale.ROOT).replace('-', '_');
        if (selector.equals("SELF") || selector.equals("CASTER")) {
            return List.of(context.caster().getLocation().add(0, 1, 0));
        }
        return targets(effect, context, false).stream()
                .map(entity -> entity.getLocation().add(0, entity.getHeight() * 0.5, 0)).toList();
    }

    private void applyOutgoingDamage(EntityDamageByEntityEvent event, LivingEntity victim, Stats stats) {
        double weaponDamage = stats.get(Stat.DAMAGE);
        if (weaponDamage <= 0.0) return;
        double amount = (plugin.getConfig().getDouble("rpg.combat.base-melee-damage", 5.0) + weaponDamage)
                * (1.0 + stats.get(Stat.STRENGTH) / 100.0)
                * plugin.getConfig().getDouble("rpg.combat.melee-damage-scale", 0.1);
        if (ThreadLocalRandom.current().nextDouble(100.0) < Math.max(0.0, stats.get(Stat.CRIT_CHANCE))) {
            amount *= 1.0 + Math.max(0.0, stats.get(Stat.CRIT_DAMAGE)) / 100.0;
            victim.getWorld().spawnParticle(Particle.CRIT,
                    victim.getLocation().add(0, victim.getHeight() * 0.5, 0), 8, 0.3, 0.3, 0.3, 0.1);
        }
        event.setDamage(Math.max(0.0, amount));
    }

    private double scaledDamage(Effect effect, Stats stats) {
        double amount = scaledAmount(effect, stats);
        if (effect.flag("ability-damage-scaling", true)) {
            amount *= 1.0 + Math.max(0.0, stats.get(Stat.ABILITY_DAMAGE)) / 100.0;
        }
        return Math.max(0.0, amount * plugin.getConfig().getDouble("rpg.combat.skill-damage-scale", 0.1));
    }

    private double scaledAmount(Effect effect, Stats stats) {
        return effect.number("amount", effect.number("base", effect.number("damage", 0.0)))
                + stats.get(Stat.STRENGTH) * effect.number("strength-scaling", 0.0)
                + stats.get(Stat.WISDOM) * effect.number("wisdom-scaling",
                effect.number("intelligence-scaling", 0.0));
    }

    private void dealSkillDamage(Player caster, LivingEntity target, double amount) {
        if (!canDamage(caster, target)) return;
        applyingSkillDamage = true;
        try {
            target.damage(amount, caster);
        } finally {
            applyingSkillDamage = false;
        }
    }

    private boolean canDamage(Player caster, LivingEntity target) {
        if (target == caster || target instanceof ArmorStand) return false;
        if (target instanceof Player && !plugin.getConfig().getBoolean("rpg.combat.player-skill-damage", false)) return false;
        return !target.isDead();
    }

    private LivingEntity rayTarget(Player player, double range) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        LivingEntity best = null;
        double bestProjection = Double.MAX_VALUE;
        for (Entity entity : player.getWorld().getNearbyEntities(eye, range, range, range)) {
            if (!(entity instanceof LivingEntity living) || !canDamage(player, living)) continue;
            Location middle = living.getLocation().add(0, living.getHeight() * 0.5, 0);
            Vector delta = middle.toVector().subtract(eye.toVector());
            double projection = delta.dot(direction);
            if (projection <= 0.0 || projection > range) continue;
            double perpendicularSquared = delta.lengthSquared() - projection * projection;
            double width = Math.max(0.8, living.getWidth() * 0.75);
            if (perpendicularSquared > width * width || !player.hasLineOfSight(living)) continue;
            if (projection < bestProjection) {
                bestProjection = projection;
                best = living;
            }
        }
        return best;
    }

    private boolean isBehind(Player player, LivingEntity target) {
        Vector toPlayer = player.getLocation().toVector().subtract(target.getLocation().toVector());
        if (toPlayer.lengthSquared() < 0.001) return false;
        Vector facing = target.getLocation().getDirection().setY(0).normalize();
        return facing.dot(toPlayer.setY(0).normalize()) < -0.45;
    }

    private Profile effectiveProfile(Player player) {
        return effectiveProfile(player, cachedBaseProfile(player));
    }

    private Profile cachedBaseProfile(Player player) {
        UUID playerId = player.getUniqueId();
        Object provider = cn.yknrpg.item.ForgeSupport.provider();
        long now = System.nanoTime();
        CachedProfile cached = profileCache.get(playerId);
        if (cached != null && cached.provider() == provider && now - cached.expiresAt() < 0
                && sameEquipment(cached.equipment(), player)) {
            return cached.profile();
        }
        Profile profile = registry.profile(player);
        // registry.profile 可能就地改寫物品 lore，所以快照必須在計算之後取。
        profileCache.put(playerId, new CachedProfile(
                snapshotEquipment(player), provider, now + PROFILE_CACHE_NANOS, profile));
        return profile;
    }

    private record CachedProfile(ItemStack[] equipment, Object provider, long expiresAt, Profile profile) {}

    private static ItemStack[] equipment(Player player) {
        var inventory = player.getInventory();
        return new ItemStack[] {
                inventory.getItemInMainHand(), inventory.getItemInOffHand(), inventory.getHelmet(),
                inventory.getChestplate(), inventory.getLeggings(), inventory.getBoots()};
    }

    private static boolean emptyStack(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }

    private static ItemStack[] snapshotEquipment(Player player) {
        ItemStack[] equipment = equipment(player);
        for (int index = 0; index < equipment.length; index++) {
            equipment[index] = emptyStack(equipment[index]) ? null : equipment[index].clone();
        }
        return equipment;
    }

    private static boolean sameEquipment(ItemStack[] snapshot, Player player) {
        ItemStack[] current = equipment(player);
        for (int index = 0; index < current.length; index++) {
            boolean currentEmpty = emptyStack(current[index]);
            if (currentEmpty != (snapshot[index] == null)) return false;
            if (!currentEmpty && !snapshot[index].equals(current[index])) return false;
        }
        return true;
    }

    private Profile effectiveProfile(Player player, ItemStack eventItem, EquipmentSlot eventHand) {
        return effectiveProfile(player, registry.profile(player, eventItem, eventHand));
    }

    private Profile effectiveProfile(Player player, Profile base) {
        Stats stats = base.stats();
        long now = System.currentTimeMillis();
        List<TimedBuff> active = buffs.get(player.getUniqueId());
        if (active != null) {
            Map<Stat, Double> strongest = new EnumMap<>(Stat.class);
            for (TimedBuff buff : active) {
                if (buff.expiresAt() > now) {
                    strongest.merge(buff.stat(), buff.amount(), Math::max);
                }
            }
            for (Map.Entry<Stat, Double> entry : strongest.entrySet()) {
                stats = stats.plus(entry.getKey(), entry.getValue());
            }
        }
        return new Profile(stats, base.abilities(), base.equippedItemIds());
    }

    private void refreshAttributes(Player player, Stats stats) {
        ItemStack actualMainHand = player.getInventory().getItemInMainHand();
        ItemStack displayedMainHand = displayedMainHand(player, actualMainHand);
        boolean displayOnlyWeapon = displayedMainHand != actualMainHand;
        Stats displayedStats = !displayOnlyWeapon
                ? stats
                : effectiveProfile(player, displayedMainHand, EquipmentSlot.HAND).stats();
        boolean exposeDisplayTraits =
                menuDisplayExposureDepths.containsKey(player.getUniqueId());
        Stats attributeDisplayStats = exposeDisplayTraits ? displayedStats : stats;
        double actualEquipmentHealth = attributeDisplayStats.get(Stat.HEALTH)
                * plugin.getConfig().getDouble("rpg.health.equipment-value-scale", 1.0);
        applyAttribute(player, Attribute.MAX_HEALTH, healthKey,
                mana.rawMaxHealthAddNumber(player, actualEquipmentHealth),
                AttributeModifier.Operation.ADD_NUMBER);
        applyAttribute(player, Attribute.ATTACK_SPEED, attackSpeedKey,
                Math.max(-0.9, stats.get(Stat.ATTACK_SPEED) / 100.0), AttributeModifier.Operation.ADD_SCALAR);
        applyAttribute(player, Attribute.MOVEMENT_SPEED, movementSpeedKey,
                Math.max(-0.9, attributeDisplayStats.get(Stat.SPEED) / 100.0),
                AttributeModifier.Operation.ADD_SCALAR);
        Stats auraDisplayStats = exposeDisplayTraits ? displayedStats : stats;
        ItemStack auraDisplayMainHand = exposeDisplayTraits ? displayedMainHand : actualMainHand;
        double displayOnlyWisdom = exposeDisplayTraits && displayOnlyWeapon
                ? displayedStats.get(Stat.WISDOM) - stats.get(Stat.WISDOM) : 0.0;
        mana.syncEquipmentStats(player, auraDisplayStats,
                hasDecorativeWeaponGlint(auraDisplayMainHand), displayOnlyWisdom);
        syncAppliedWeaponShield(player);
        if (player.getHealth() > maximumHealth(player)) player.setHealth(maximumHealth(player));
    }

    private void applyAttribute(Player player, Attribute attribute, NamespacedKey key,
                                double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;
        AttributeModifier current = instance.getModifier(key);
        if (Math.abs(amount) < 0.000001) {
            if (current != null) instance.removeModifier(current);
            return;
        }
        if (current != null && Math.abs(current.getAmount() - amount) < 0.000001
                && current.getOperation() == operation) return;
        if (current != null) instance.removeModifier(current);
        instance.addTransientModifier(new AttributeModifier(key, amount, operation, EquipmentSlotGroup.ANY));
    }

    private void removeAttributes(Player player) {
        clearWeaponShield(player, true);
        removeAttribute(player, Attribute.MAX_HEALTH, healthKey);
        removeAttribute(player, Attribute.ATTACK_SPEED, attackSpeedKey);
        removeAttribute(player, Attribute.MOVEMENT_SPEED, movementSpeedKey);
        mana.clearEquipmentStats(player);
        if (player.getHealth() > maximumHealth(player)) player.setHealth(maximumHealth(player));
    }

    private void removeAttribute(Player player, Attribute attribute, NamespacedKey key) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;
        AttributeModifier modifier = instance.getModifier(key);
        if (modifier != null) instance.removeModifier(modifier);
    }

    private void clearCriticalTraitSuppression(DamageEvent event) {
        UUID playerId = criticalTraitSuppressionEvents.remove(event);
        if (playerId == null) return;
        CriticalTraitSuppression suppression = activeCriticalTraitSuppressions.get(playerId);
        if (suppression == null) return;
        if (suppression.decrementDepth() > 0) return;
        activeCriticalTraitSuppressions.remove(playerId, suppression);
        mana.clearEquipmentCriticalSuppression(suppression.player(), suppression.modifiers());
    }

    private void beginMenuDisplayExposure(Event event, Player player, ItemStack menuItem) {
        if (!enabled() || !isSkyblockMenuItem(menuItem)
                || resolveRememberedWeapon(player) == null
                || menuDisplayExposures.containsKey(event)) return;
        menuDisplayExposures.put(event, player);
        UUID playerId = player.getUniqueId();
        menuDisplayExposureDepths.merge(playerId, 1, Integer::sum);
        try {
            refreshAttributes(player, effectiveProfile(player).stats());
        } catch (RuntimeException exception) {
            menuDisplayExposures.remove(event);
            decrementMenuDisplayExposure(playerId);
            plugin.getLogger().warning("准备 SkyBlock 菜单装备属性预览失败："
                    + exception.getMessage());
            scheduleAttributeRefresh(player);
        }
    }

    private void clearMenuDisplayExposure(Event event) {
        Player player = menuDisplayExposures.remove(event);
        if (player == null || !player.isOnline()) return;
        if (decrementMenuDisplayExposure(player.getUniqueId()) > 0) return;
        if (!enabled()) {
            removeAttributes(player);
            return;
        }
        refreshAttributes(player, effectiveProfile(player).stats());
    }

    private int decrementMenuDisplayExposure(UUID playerId) {
        Integer current = menuDisplayExposureDepths.get(playerId);
        if (current == null || current <= 1) {
            menuDisplayExposureDepths.remove(playerId);
            return 0;
        }
        int remaining = current - 1;
        menuDisplayExposureDepths.put(playerId, remaining);
        return remaining;
    }

    private void scheduleAttributeRefresh(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (!enabled()) {
                removeAttributes(player);
                return;
            }
            rememberMainHandWeapon(player);
            refreshAttributes(player, effectiveProfile(player).stats());
        });
    }

    private void rememberMainHandWeapon(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (registry.isMainHandItem(mainHand)) {
            rememberWeapon(player, player.getInventory().getHeldItemSlot(), mainHand);
        } else if (isSkyblockMenuItem(mainHand)) {
            resolveRememberedWeapon(player);
        } else {
            recentMainHandWeapons.remove(player.getUniqueId());
        }
    }

    private void rememberWeapon(Player player, int slot, ItemStack weapon) {
        String itemId = registry.mainHandItemId(weapon);
        if (itemId == null) {
            recentMainHandWeapons.remove(player.getUniqueId());
            return;
        }
        recentMainHandWeapons.put(player.getUniqueId(), new RememberedWeapon(slot, itemId));
    }

    private ItemStack displayedMainHand(Player player, ItemStack mainHand) {
        if (!isSkyblockMenuItem(mainHand)) return mainHand;
        ItemStack remembered = resolveRememberedWeapon(player);
        return remembered == null ? mainHand : remembered;
    }

    private ItemStack resolveRememberedWeapon(Player player) {
        RememberedWeapon remembered = recentMainHandWeapons.get(player.getUniqueId());
        if (remembered == null) return null;
        ItemStack current = player.getInventory().getItem(remembered.slot());
        String currentId = registry.mainHandItemId(current);
        if (!remembered.itemId().equals(currentId)) {
            recentMainHandWeapons.remove(player.getUniqueId());
            return null;
        }
        return current;
    }

    private boolean isSkyblockMenuItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(
                skyblockMenuItemKey, PersistentDataType.BYTE);
    }

    private boolean isSkyblockCoreInventory(InventoryClickEvent event) {
        if (event.getInventory().getHolder() != null
                && event.getInventory().getHolder().getClass().getName()
                .startsWith("dev.chengzhi.skyblockcore.")) return true;
        return false;
    }

    private boolean isSkyblockBazaarExitCandidate(InventoryClickEvent event) {
        if (!isSkyblockBazaarInventory(event)
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getInventory().getSize()) return false;
        int slot = event.getRawSlot();
        ItemStack clicked = event.getCurrentItem();
        return (slot == 40 || slot == 47 || slot == 49)
                && clicked != null && clicked.getType() == org.bukkit.Material.BARRIER;
    }

    private boolean isSkyblockBazaarInventory(InventoryClickEvent event) {
        return isSkyblockBazaarHolder(event.getInventory().getHolder());
    }

    private boolean isOpenSkyblockBazaarInventory(Player player) {
        return isSkyblockBazaarHolder(player.getOpenInventory().getTopInventory().getHolder());
    }

    private boolean isSkyblockBazaarHolder(Object holder) {
        return holder != null && holder.getClass().getName().equals(
                "dev.chengzhi.skyblockcore.bazaar.BazaarInventoryHolder");
    }

    private boolean isSkyblockMenuReturnClick(InventoryClickEvent event) {
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getInventory().getSize()) {
            return false;
        }
        ItemStack clicked = event.getCurrentItem();
        return clicked != null && clicked.hasItemMeta()
                && clicked.getItemMeta().getPersistentDataContainer().has(
                skyblockMenuReturnKey, PersistentDataType.BYTE);
    }

    private boolean hasDecorativeWeaponGlint(ItemStack item) {
        if (!registry.isMainHandItem(item)) return false;
        NamespacedKey unbreaking = NamespacedKey.minecraft("unbreaking");
        return item.getEnchantments().keySet().stream()
                .anyMatch(enchantment -> enchantment.getKey().equals(unbreaking));
    }

    private double maximumHealth(LivingEntity entity) {
        AttributeInstance instance = entity.getAttribute(Attribute.MAX_HEALTH);
        return instance == null ? Math.max(1.0, entity.getHealth()) : Math.max(1.0, instance.getValue());
    }

    private void refreshCharges(ChargeState state, Ability ability, long now) {
        if (state.current() >= ability.maxCharges()) {
            state.setLastRecharge(now);
            return;
        }
        long interval = Math.max(50L, (long) (ability.rechargeSeconds() * 1000.0));
        int restored = (int) ((now - state.lastRecharge()) / interval);
        if (restored <= 0) return;
        state.setCurrent(Math.min(ability.maxCharges(), state.current() + restored));
        state.setLastRecharge(state.lastRecharge() + restored * interval);
        if (state.current() >= ability.maxCharges()) state.setLastRecharge(now);
    }

    private void spawnNamedParticle(Location location, String name, int count, Effect effect) {
        Particle particle = Registry.PARTICLE_TYPE.get(configuredKey(name));
        if (particle == null) throw new IllegalArgumentException("未知粒子：" + name);
        double offsetX = effect.number("offset-x", 0.3);
        double offsetY = effect.number("offset-y", 0.3);
        double offsetZ = effect.number("offset-z", 0.3);
        double speed = effect.number("particle-speed", effect.number("speed", 0.05));
        if (particle == Particle.DUST) {
            Color color = Color.fromRGB(clampColor(effect.number("red", 255)),
                    clampColor(effect.number("green", 255)), clampColor(effect.number("blue", 255)));
            location.getWorld().spawnParticle(particle, location, count, offsetX, offsetY, offsetZ,
                    speed, new Particle.DustOptions(color, (float) effect.number("size", 1.0)));
        } else if (particle.getDataType() == Color.class) {
            Color color = Color.fromRGB(clampColor(effect.number("red", 255)),
                    clampColor(effect.number("green", 255)), clampColor(effect.number("blue", 255)));
            location.getWorld().spawnParticle(particle, location, count, offsetX, offsetY, offsetZ,
                    speed, color);
        } else if (particle.getDataType() == Void.class) {
            location.getWorld().spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, speed);
        } else {
            throw new IllegalArgumentException("粒子 " + name + " 需要尚未支持的额外数据类型："
                    + particle.getDataType().getSimpleName());
        }
    }

    private int clampColor(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private NamespacedKey configuredKey(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        NamespacedKey key = normalized.contains(":")
                ? NamespacedKey.fromString(normalized)
                : NamespacedKey.minecraft(normalized);
        if (key == null) throw new IllegalArgumentException("无效注册表键：" + value);
        return key;
    }

    private Sound configuredSound(String value) {
        Sound sound = Registry.SOUNDS.get(configuredKey(value));
        if (sound != null) return sound;
        String legacyPath = value.trim().toLowerCase(Locale.ROOT)
                .replace('_', '.')
                .replace('-', '.');
        sound = Registry.SOUNDS.get(NamespacedKey.minecraft(legacyPath));
        if (sound != null) return sound;
        throw new IllegalArgumentException("未知音效：" + value);
    }

    private Player directOrProjectilePlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("rpg.enabled", true);
    }

    private void stopTask() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (shieldTickTask != null) {
            shieldTickTask.cancel();
            shieldTickTask = null;
        }
    }

    private record SkillContext(Player caster, LivingEntity target, Stats stats,
                                Profile profile, AbilitySource source, Projectile triggerProjectile) {}
    private record AbilityKey(UUID playerId, String abilityKey) {}
    private record ActivationResult(boolean activated, boolean cancelInteraction) {}
    private record TimedBuff(Stat stat, double amount, long expiresAt) {}
    private record PendingProjectile(UUID casterId, Effect effect, Profile profile, long expiresAt) {}
    private record ProjectileSnapshot(UUID casterId, Profile profile, long expiresAt) {}
    private record RememberedWeapon(int slot, String itemId) {}

    private static final class CriticalTraitSuppression {
        private final Player player;
        private final SuppressedTraits modifiers;
        private int depth;

        private CriticalTraitSuppression(Player player, SuppressedTraits modifiers) {
            this.player = player;
            this.modifiers = modifiers;
        }

        Player player() { return player; }
        SuppressedTraits modifiers() { return modifiers; }
        void incrementDepth() { depth++; }
        int decrementDepth() { return --depth; }
    }

    private static final class PlayerWeaponShields {
        private WeaponShieldTimeline timeline = new WeaponShieldTimeline();
        private double appliedAmount;
        private double deferredNonDamageHealthDelta;
    }

    private static final class ChargeState {
        private final int maximum;
        private int current;
        private long lastRecharge;

        private ChargeState(int maximum, int current, long lastRecharge) {
            this.maximum = maximum;
            this.current = current;
            this.lastRecharge = lastRecharge;
        }

        int current() { return current; }
        long lastRecharge() { return lastRecharge; }
        void setCurrent(int current) { this.current = Math.min(maximum, Math.max(0, current)); }
        void setLastRecharge(long value) { lastRecharge = value; }
        void consume(long now) {
            if (current == maximum) lastRecharge = now;
            setCurrent(current - 1);
        }
        long nextRechargeAt(Ability ability) {
            return lastRecharge + (long) (ability.rechargeSeconds() * 1000.0);
        }
    }
}
