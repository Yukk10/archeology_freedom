package ru.freedom.archeology.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.inventory.ItemStack;
import ru.freedom.archeology.ArcheologyPlugin;
import ru.freedom.archeology.hook.WorldGuardHook;

/**
 * region-list-brush: в перечисленных регионах WorldGuard кисточкой можно раскапывать
 * подозрительный песок/гравий, даже если регион приватный. Ломать/ставить блоки — нельзя.
 *
 * Два слоя: (1) заранее разрешаем внутренние события WorldGuard, чтобы он ничего не отменял
 * и не писал игроку «Hey! Sorry...»; (2) на всякий случай снимаем отмену с событий Bukkit
 * на приоритете HIGHEST (если кисточку заблокировал другой плагин).
 */
public final class BrushRegionListener implements Listener {

    private final ArcheologyPlugin plugin;
    private final WorldGuardHook wg;
    private boolean wgEventsHooked;

    public BrushRegionListener(ArcheologyPlugin plugin) {
        this.plugin = plugin;
        this.wg = new WorldGuardHook(plugin.getLogger());
        hookWorldGuardEvents();
    }

    private static boolean isSuspicious(Material m) {
        return m == Material.SUSPICIOUS_SAND || m == Material.SUSPICIOUS_GRAVEL;
    }

    private static boolean holdsBrush(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.BRUSH
                || p.getInventory().getItemInOffHand().getType() == Material.BRUSH;
    }

    private boolean inBrushRegion(Block block) {
        var regions = plugin.settings().brushRegions;
        return !regions.isEmpty() && wg.inAnyRegion(block.getLocation(), regions);
    }

    // ------------------------------------------------------------------ что считается «копать кисточкой»

    private boolean isBrushStart(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return false;
        ItemStack item = e.getItem();
        Block b = e.getClickedBlock();
        return item != null && item.getType() == Material.BRUSH
                && b != null && isSuspicious(b.getType())
                && inBrushRegion(b);
    }

    /** Кисточка меняет стадию «запылённости» или превращает блок в обычный песок/гравий. */
    private boolean isBrushProgress(EntityChangeBlockEvent e) {
        if (!(e.getEntity() instanceof Player p) || !holdsBrush(p)) return false;
        Block b = e.getBlock();
        Material from = b.getType();
        Material to = e.getTo();
        if (!isSuspicious(from)) return false;
        boolean ok = to == from
                || (from == Material.SUSPICIOUS_SAND && to == Material.SAND)
                || (from == Material.SUSPICIOUS_GRAVEL && to == Material.GRAVEL);
        return ok && inBrushRegion(b);
    }

    private boolean isBrushDrop(BlockDropItemEvent e) {
        return isSuspicious(e.getBlockState().getType()) && holdsBrush(e.getPlayer()) && inBrushRegion(e.getBlock());
    }

    private boolean isBrushAction(Event original) {
        if (original instanceof PlayerInteractEvent e) return isBrushStart(e);
        if (original instanceof EntityChangeBlockEvent e) return isBrushProgress(e);
        if (original instanceof BlockDropItemEvent e) return isBrushDrop(e);
        return false;
    }

    // ------------------------------------------------------------------ слой 1: события WorldGuard

    private void hookWorldGuardEvents() {
        if (wgEventsHooked || !WorldGuardHook.present()) return;
        for (Class<? extends Event> type : wg.protectionEvents()) {
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.LOWEST, (listener, event) -> {
                if (!type.isInstance(event) || plugin.settings().brushRegions.isEmpty()) return;
                Event original = wg.originalEvent(event);
                if (original != null && isBrushAction(original)) wg.allow(event);
            }, plugin, false);
            wgEventsHooked = true;
        }
    }

    /** WorldGuard загрузился позже нас (на всякий случай). */
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("WorldGuard")) hookWorldGuardEvents();
    }

    // ------------------------------------------------------------------ слой 2: события Bukkit

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() == null || e.useItemInHand() != Event.Result.DENY) return;
        if (!isBrushStart(e)) return;
        e.setUseItemInHand(Event.Result.ALLOW);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        if (e.isCancelled() && isBrushProgress(e)) e.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(BlockDropItemEvent e) {
        if (e.isCancelled() && isBrushDrop(e)) e.setCancelled(false);
    }
}
