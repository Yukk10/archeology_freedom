package ru.freedom.archeology.listener;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import ru.freedom.archeology.ArcheologyPlugin;
import ru.freedom.archeology.zone.Zone;
import ru.freedom.archeology.zone.ZoneManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Защита зон, учёт поставленных/сломанных админом блоков и догрузка чанков. */
public final class ZoneListener implements Listener {

    private static final String BYPASS = "archeology.bypass";

    private final ArcheologyPlugin plugin;
    private final ZoneManager zones;
    private final Map<UUID, Long> messageCooldown = new HashMap<>();

    public ZoneListener(ArcheologyPlugin plugin) {
        this.plugin = plugin;
        this.zones = plugin.zones();
    }

    private boolean protection() {
        return plugin.settings().protection;
    }

    private void deny(Cancellable event, Player player) {
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        Long last = messageCooldown.get(player.getUniqueId());
        if (last == null || now - last > 1500) {
            messageCooldown.put(player.getUniqueId(), now);
            plugin.settings().actionBar(player, "protected");
        }
    }

    private static boolean isSuspicious(Material m) {
        return m == Material.SUSPICIOUS_SAND || m == Material.SUSPICIOUS_GRAVEL;
    }

    // ------------------------------------------------------------------ ломание / установка

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreakProtect(BlockBreakEvent event) {
        if (!protection() || event.getPlayer().hasPermission(BYPASS)) return;
        if (zones.at(event.getBlock()) != null) deny(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakTrack(BlockBreakEvent event) {
        // Только админ убирает блок из зоны; если защита выключена и сломал игрок — блок восстановится.
        if (!event.getPlayer().hasPermission(BYPASS)) return;
        Block b = event.getBlock();
        Zone zone = zones.trackingZone(b);
        if (zone == null) return;
        zone.untrack(b.getX(), b.getY(), b.getZ());
        zones.markDirty();
        plugin.settings().actionBar(event.getPlayer(), "block-untracked", Placeholder.unparsed("name", zone.name()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlaceProtect(BlockPlaceEvent event) {
        if (!protection() || event.getPlayer().hasPermission(BYPASS)) return;
        if (zones.at(event.getBlockPlaced()) != null) deny(event, event.getPlayer());
    }

    /** Админ поставил подозрительный песок/гравий в зоне — блок начинает обновляться. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceTrack(BlockPlaceEvent event) {
        Block b = event.getBlockPlaced();
        Material type = b.getType();
        if (!isSuspicious(type) || !event.getPlayer().hasPermission(BYPASS)) return;
        Zone zone = zones.at(b);
        if (zone == null) return;
        zone.track(b.getX(), b.getY(), b.getZ(), type == Material.SUSPICIOUS_GRAVEL);
        zones.markDirty();
        plugin.regen().enqueueBlock(zone, b.getX(), b.getY(), b.getZ());
        plugin.settings().actionBar(event.getPlayer(), "block-tracked", Placeholder.unparsed("name", zone.name()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!protection() || event.getPlayer().hasPermission(BYPASS)) return;
        if (zones.at(event.getBlock()) != null) deny(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!protection() || event.getPlayer().hasPermission(BYPASS)) return;
        if (zones.at(event.getBlock()) != null) deny(event, event.getPlayer());
    }

    // ------------------------------------------------------------------ взрывы и поршни

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (protection()) stripZoneBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (protection()) stripZoneBlocks(event.blockList());
    }

    private void stripZoneBlocks(List<Block> blocks) {
        if (blocks.isEmpty()) return;
        blocks.removeIf(b -> zones.at(b) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!protection()) return;
        BlockFace dir = event.getDirection();
        if (zones.at(event.getBlock().getRelative(dir)) != null) {
            event.setCancelled(true);
            return;
        }
        for (Block b : event.getBlocks()) {
            if (zones.at(b) != null || zones.at(b.getRelative(dir)) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!protection()) return;
        BlockFace dir = event.getDirection();
        for (Block b : event.getBlocks()) {
            if (zones.at(b) != null || zones.at(b.getRelative(dir)) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Мобы (эндермены, разрушители и т.п.) не трогают зону; песок/гравий в зоне не осыпается.
     * Игроков не трогаем — именно так кисточка превращает подозрительный блок в обычный.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Player) return;
        Block b = event.getBlock();
        if (event.getEntity() instanceof FallingBlock) {
            // начало падения: блок становится воздухом
            if (plugin.settings().preventFalling && event.getTo().isAir() && zones.at(b) != null) {
                event.setCancelled(true);
            }
            return;
        }
        if (protection() && zones.at(b) != null) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ чанки

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.regen().onChunkLoad(event.getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        messageCooldown.remove(event.getPlayer().getUniqueId());
    }
}
