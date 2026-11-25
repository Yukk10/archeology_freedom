package ru.freedom.archeology.loot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Мобы в луте: "mob:HUSK:80", "mob:mythicmobs-SkeletonKing:10".
 * В подозрительный блок кладётся предмет-метка (с PDC), а когда кисточка его выбивает —
 * предмет удаляется и на его месте спавнится моб.
 */
public final class MobLoot {

    public enum Source { VANILLA, MYTHICMOBS }

    private final NamespacedKey mobKey;
    private final NamespacedKey countKey;
    private final Logger log;
    private final Set<String> warned = new HashSet<>();
    private final ItemResolver resolver;
    private static final ItemResolver.Ref BONE = new ItemResolver.Ref(ItemResolver.Source.VANILLA, "BONE");
    /** null — режим auto (яйцо призыва). */
    private ItemResolver.Ref display;
    private ItemResolver.Ref fallback = BONE;

    public MobLoot(Plugin plugin, ItemResolver resolver) {
        this.resolver = resolver;
        this.mobKey = new NamespacedKey(plugin, "loot_mob");
        this.countKey = new NamespacedKey(plugin, "loot_mob_count");
        this.log = plugin.getLogger();
    }

    /**
     * display: "auto" (яйцо призыва для ванильных мобов, иначе fallback) или предмет
     * в формате лута — diamond, itemsadder-ns:item, mythicmobs-item.
     */
    public void configure(String displaySpec, String fallbackSpec) {
        warned.clear();
        fallback = checked(fallbackSpec, "mob-loot.fallback-item");
        if (fallback == null) fallback = BONE;
        if (displaySpec == null || displaySpec.isBlank() || displaySpec.trim().equalsIgnoreCase("auto")) {
            display = null;
        } else {
            display = checked(displaySpec, "mob-loot.display-item");
            if (display == null) display = fallback;
        }
    }

    /** Ванильные предметы проверяем сразу; ItemsAdder/MythicMobs — при первом использовании (грузятся позже). */
    private ItemResolver.Ref checked(String spec, String path) {
        ItemResolver.Ref ref = ItemResolver.parseRef(spec);
        if (ref == null) return null;
        if (ref.source() == ItemResolver.Source.VANILLA) {
            Material m = Material.matchMaterial(ref.id());
            if (m == null || !m.isItem() || m.isAir()) {
                log.warning(path + ": неизвестный предмет " + spec + ", используется BONE");
                return null;
            }
        }
        return ref;
    }

    /** Разбор id после "mob:". Возвращает null и пишет предупреждение, если моб неверный. */
    public Parsed parse(String id, String line) {
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("mythicmobs-")) return mythic(id.substring("mythicmobs-".length()), line);
        if (lower.startsWith("mm-")) return mythic(id.substring(3), line);
        if (lower.startsWith("mythic-")) return mythic(id.substring("mythic-".length()), line);

        EntityType type = vanillaType(id);
        if (type == null || !type.isSpawnable() || !type.isAlive()) {
            log.warning("Неизвестный (или неспавнящийся) моб в строке лута: " + line);
            return null;
        }
        return new Parsed(Source.VANILLA, type.name());
    }

    private Parsed mythic(String name, String line) {
        if (name.isEmpty()) {
            log.warning("Пустое имя моба MythicMobs в строке лута: " + line);
            return null;
        }
        return new Parsed(Source.MYTHICMOBS, name);
    }

    public record Parsed(Source source, String id) {}

    private static EntityType vanillaType(String id) {
        String s = id.trim();
        try {
            return EntityType.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }
        NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.ENTITY_TYPE.get(key);
    }

    // ------------------------------------------------------------------ предмет-метка

    public ItemStack marker(Source source, String id, int count) {
        ItemStack item = null;
        if (display == null && source == Source.VANILLA) {
            Material egg = Material.matchMaterial(id + "_SPAWN_EGG");
            if (egg != null && egg.isItem()) item = new ItemStack(egg);
        }
        if (item == null) item = resolver.resolve(display != null ? display : fallback);
        if (item == null) item = new ItemStack(Material.BONE); // ItemsAdder ещё не загрузился / предмета нет
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(mobKey, PersistentDataType.STRING, source.name() + ":" + id);
        pdc.set(countKey, PersistentDataType.INTEGER, Math.max(1, count));
        item.setItemMeta(meta);
        return item;
    }

    public boolean isMarker(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(mobKey, PersistentDataType.STRING);
    }

    /** Спавнит мобов по предмету-метке. Возвращает true, если это была метка (даже если спавн не удался). */
    public boolean spawnFrom(ItemStack item, Location where) {
        if (!isMarker(item) || where.getWorld() == null) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String data = pdc.get(mobKey, PersistentDataType.STRING);
        Integer count = pdc.get(countKey, PersistentDataType.INTEGER);
        int colon = data.indexOf(':');
        if (colon <= 0) return true;
        Source source;
        try {
            source = Source.valueOf(data.substring(0, colon));
        } catch (IllegalArgumentException e) {
            return true;
        }
        String id = data.substring(colon + 1);
        Location loc = safeSpot(where);
        int n = count == null ? 1 : Math.min(Math.max(1, count), 64);
        for (int i = 0; i < n; i++) {
            try {
                if (source == Source.VANILLA) {
                    EntityType type = vanillaType(id);
                    if (type == null) {
                        warnOnce(data, "Моб не найден: " + id);
                        return true;
                    }
                    loc.getWorld().spawnEntity(loc, type);
                } else if (!spawnMythic(id, loc)) {
                    warnOnce(data, "Моб MythicMobs не найден: " + id + " (плагин установлен и моб существует?)");
                    return true;
                }
            } catch (Throwable t) {
                warnOnce(data, "Ошибка при спавне моба " + id + ": " + t);
                return true;
            }
        }
        return true;
    }

    /** Ищет место, где моб высотой 2 блока не задохнётся: точка выпадения, иначе выше. */
    private static Location safeSpot(Location where) {
        Block base = where.getBlock();
        for (int dy = 0; dy <= 3; dy++) {
            Block b = base.getRelative(0, dy, 0);
            if (b.isPassable() && b.getRelative(0, 1, 0).isPassable()) {
                return b.getLocation().add(0.5, 0, 0.5);
            }
        }
        return base.getLocation().add(0.5, 0, 0.5);
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) log.warning(message);
    }

    // ------------------------------------------------------------------ MythicMobs (рефлексия)

    private static boolean spawnMythic(String name, Location loc) throws Exception {
        Plugin mm = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mm == null || !mm.isEnabled()) return false;
        ClassLoader cl = mm.getClass().getClassLoader();
        Class<?> mythic = Class.forName("io.lumine.mythic.bukkit.MythicBukkit", true, cl);
        Object inst = mythic.getMethod("inst").invoke(null);

        // MythicMobs 5: MobManager#spawnMob(String, Location) -> ActiveMob (null, если моба нет)
        try {
            Object mobManager = mythic.getMethod("getMobManager").invoke(inst);
            Method spawn = mobManager.getClass().getMethod("spawnMob", String.class, Location.class);
            return spawn.invoke(mobManager, name, loc) != null;
        } catch (NoSuchMethodException ignored) {
        }
        // Запасной вариант: BukkitAPIHelper#spawnMythicMob(String, Location)
        Object api = mythic.getMethod("getAPIHelper").invoke(inst);
        Method spawn = api.getClass().getMethod("spawnMythicMob", String.class, Location.class);
        try {
            return spawn.invoke(api, name, loc) != null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            return false; // InvalidMobTypeException
        }
    }
}
