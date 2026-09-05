package cn.yknrpg.integration;

import cn.yknrpg.YknRpgPlugin;
import cn.yknrpg.rpg.RpgEngine;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class ProtocolLibInputBridge {
    private final YknRpgPlugin plugin;
    private final RpgEngine engine;
    private final ConcurrentHashMap<UUID, Boolean> interceptVanillaUse = new ConcurrentHashMap<>();
    private final LongAdder mainHandPackets = new LongAdder();
    private final LongAdder interceptedPackets = new LongAdder();
    private PacketAdapter listener;
    private BukkitTask refreshTask;
    private boolean warnedUnreadableHand;

    public ProtocolLibInputBridge(YknRpgPlugin plugin, RpgEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    public void start() {
        if (listener != null) return;
        try {
            refreshInterceptionCache();
            listener = new PacketAdapter(plugin, ListenerPriority.HIGHEST,
                    PacketType.Play.Client.USE_ITEM,
                    PacketType.Play.Client.USE_ITEM_ON) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    forward(event);
                }
            };
            ProtocolLibrary.getProtocolManager().addPacketListener(listener);
            refreshTask = Bukkit.getScheduler().runTaskTimer(plugin,
                    this::refreshInterceptionCache, 1L, 1L);
        } catch (RuntimeException | LinkageError exception) {
            if (refreshTask != null) {
                refreshTask.cancel();
                refreshTask = null;
            }
            if (listener != null) {
                try {
                    ProtocolLibrary.getProtocolManager().removePacketListener(listener);
                } catch (RuntimeException | LinkageError ignored) {
                    // The bridge is already falling back to Paper events.
                }
                listener = null;
            }
            interceptVanillaUse.clear();
            throw exception;
        }
    }

    public void stop() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        if (listener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(listener);
            listener = null;
            plugin.getLogger().info("ProtocolLib 右键数据包统计：主手=" + mainHandPackets.sum()
                    + "，拦截原版使用=" + interceptedPackets.sum() + "。");
        }
        interceptVanillaUse.clear();
    }

    private void forward(PacketEvent event) {
        EnumWrappers.Hand hand;
        try {
            hand = event.getPacket().getHands().readSafely(0);
        } catch (RuntimeException exception) {
            warnUnreadableHand(exception);
            return;
        }
        if (hand == null) {
            warnUnreadableHand(new IllegalStateException("手部字段不存在"));
            return;
        }
        if (hand != EnumWrappers.Hand.MAIN_HAND) return;

        Player player = event.getPlayer();
        if (player == null || !plugin.isEnabled()) return;
        mainHandPackets.increment();
        if (!interceptVanillaUse.getOrDefault(player.getUniqueId(), false)) return;
        event.setCancelled(true);
        interceptedPackets.increment();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled() && player.isOnline()) {
                    engine.onRawRightClick(player, EquipmentSlot.HAND);
                }
            });
        } catch (IllegalStateException exception) {
            if (plugin.isEnabled()) throw exception;
        }
    }

    private void refreshInterceptionCache() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            online.add(playerId);
            interceptVanillaUse.put(playerId, engine.shouldInterceptVanillaRightClick(player));
        }
        interceptVanillaUse.keySet().retainAll(online);
    }

    private void warnUnreadableHand(RuntimeException exception) {
        if (warnedUnreadableHand) return;
        warnedUnreadableHand = true;
        plugin.getLogger().warning("ProtocolLib 无法读取右键数据包的手部字段：" + exception.getMessage());
    }
}
