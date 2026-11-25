package ru.freedom.archeology.listener;

import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import ru.freedom.archeology.loot.MobLoot;

import java.util.Iterator;

/** Кисточка выбила предмет-метку моба — вместо предмета появляется моб. */
public final class MobLootListener implements Listener {

    private final MobLoot mobs;

    public MobLootListener(MobLoot mobs) {
        this.mobs = mobs;
    }

    /** Paper вызывает это событие, когда кисточка докапывает подозрительный блок. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrushDrop(BlockDropItemEvent event) {
        Iterator<Item> it = event.getItems().iterator();
        while (it.hasNext()) {
            Item item = it.next();
            if (mobs.spawnFrom(item.getItemStack(), item.getLocation())) it.remove();
        }
    }

    /** Запасной путь: если метка всё же появилась в мире как предмет — заменяем её мобом. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        if (mobs.spawnFrom(item.getItemStack(), item.getLocation())) event.setCancelled(true);
    }
}
