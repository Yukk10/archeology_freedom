package ru.freedom.archeology.listener;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.freedom.archeology.ArcheologyPlugin;
import ru.freedom.archeology.Settings;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Лопата выделения: ЛКМ — точка 1, ПКМ — точка 2. */
public final class WandListener implements Listener {

    public record Selection(Location pos1, Location pos2) {
        public boolean complete() {
            return pos1 != null && pos2 != null;
        }
    }

    private final ArcheologyPlugin plugin;
    private final NamespacedKey wandKey;
    private final Map<UUID, Selection> selections = new HashMap<>();

    public WandListener(ArcheologyPlugin plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "wand");
    }

    public ItemStack createWand() {
        Settings s = plugin.settings();
        ItemStack item = new ItemStack(s.wandMaterial);
        item.editMeta(meta -> {
            meta.displayName(s.wandName);
            meta.lore(s.wandLore);
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    public boolean isWand(ItemStack item) {
        return item != null && !item.getType().isAir() && item.hasItemMeta()
                && item.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    public Selection selection(Player player) {
        return selections.get(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!isWand(event.getItem()) || !player.hasPermission("archeology.wand")) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        event.setCancelled(true); // не делать тропинку / не ломать блок

        Location loc = block.getLocation();
        Selection old = selections.get(player.getUniqueId());
        Location p1 = old == null ? null : old.pos1();
        Location p2 = old == null ? null : old.pos2();
        boolean first = action == Action.LEFT_CLICK_BLOCK;
        if (first) p1 = loc; else p2 = loc;
        Selection sel = new Selection(p1, p2);
        selections.put(player.getUniqueId(), sel);

        Settings s = plugin.settings();
        s.send(player, first ? "pos1" : "pos2",
                Placeholder.unparsed("x", String.valueOf(loc.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(loc.getBlockY())),
                Placeholder.unparsed("z", String.valueOf(loc.getBlockZ())));
        if (sel.complete() && sel.pos1().getWorld() == sel.pos2().getWorld()) {
            long volume = (long) (Math.abs(p1.getBlockX() - p2.getBlockX()) + 1)
                    * (Math.abs(p1.getBlockY() - p2.getBlockY()) + 1)
                    * (Math.abs(p1.getBlockZ() - p2.getBlockZ()) + 1);
            s.send(player, "selection-size", Placeholder.unparsed("volume", String.valueOf(volume)));
        }
    }

    /** В креативе ЛКМ лопатой ломает блок — не даём. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (isWand(player.getInventory().getItemInMainHand()) && player.hasPermission("archeology.wand")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
    }
}
