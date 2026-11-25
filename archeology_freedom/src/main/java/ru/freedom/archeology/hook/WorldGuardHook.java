package ru.freedom.archeology.hook;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Проверка регионов WorldGuard через рефлексию: плагин работает и без WorldGuard,
 * и не зависит от его версии на этапе сборки.
 */
public final class WorldGuardHook {

    private final Logger log;
    private boolean ready;
    private boolean failed;

    private Object regionContainer;
    private Method createQuery;
    private Method getApplicableRegions;
    private Method adapt;
    private Method getId;

    private Class<?> delegateEvent;
    private Method getOriginalEvent;
    private Method setAllowed;

    public WorldGuardHook(Logger log) {
        this.log = log;
    }

    public static boolean present() {
        Plugin wg = Bukkit.getPluginManager().getPlugin("WorldGuard");
        return wg != null && wg.isEnabled();
    }

    private boolean init() {
        if (ready) return true;
        if (failed || !present()) return false;
        try {
            ClassLoader cl = Bukkit.getPluginManager().getPlugin("WorldGuard").getClass().getClassLoader();
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard", true, cl);
            Object wg = wgClass.getMethod("getInstance").invoke(null);
            Method getPlatform = wgClass.getMethod("getPlatform");
            Object platform = getPlatform.invoke(wg);
            Method getContainer = getPlatform.getReturnType().getMethod("getRegionContainer");
            regionContainer = getContainer.invoke(platform);
            createQuery = getContainer.getReturnType().getMethod("createQuery");

            Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, cl);
            adapt = bukkitAdapter.getMethod("adapt", Location.class);
            getApplicableRegions = createQuery.getReturnType().getMethod("getApplicableRegions", adapt.getReturnType());

            Class<?> protectedRegion = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", true, cl);
            getId = protectedRegion.getMethod("getId");

            delegateEvent = Class.forName("com.sk89q.worldguard.bukkit.event.DelegateEvent", true, cl);
            getOriginalEvent = delegateEvent.getMethod("getOriginalEvent");
            setAllowed = delegateEvent.getMethod("setAllowed", boolean.class);
            ready = true;
            return true;
        } catch (Throwable t) {
            failed = true;
            log.warning("Не удалось подключиться к WorldGuard (region-list-brush не будет работать): " + t);
            return false;
        }
    }

    /** Находится ли точка хотя бы в одном из регионов (id в нижнем регистре). */
    public boolean inAnyRegion(Location loc, Set<String> regionIds) {
        if (regionIds.isEmpty() || !init()) return false;
        try {
            Object query = createQuery.invoke(regionContainer);
            Object set = getApplicableRegions.invoke(query, adapt.invoke(null, loc));
            if (!(set instanceof Iterable<?> regions)) return false;
            for (Object region : regions) {
                Object id = getId.invoke(region);
                if (id != null && regionIds.contains(id.toString().toLowerCase(Locale.ROOT))) return true;
            }
        } catch (Throwable t) {
            if (!failed) {
                failed = true;
                log.warning("Ошибка проверки регионов WorldGuard: " + t);
            }
        }
        return false;
    }

    /** Классы внутренних событий WorldGuard, которые можно заранее разрешить. */
    public Class<? extends Event>[] protectionEvents() {
        if (!init()) return emptyEvents();
        String[] names = {
                "com.sk89q.worldguard.bukkit.event.block.UseBlockEvent",
                "com.sk89q.worldguard.bukkit.event.block.BreakBlockEvent",
                "com.sk89q.worldguard.bukkit.event.block.PlaceBlockEvent",
                "com.sk89q.worldguard.bukkit.event.inventory.UseItemEvent",
        };
        ClassLoader cl = delegateEvent.getClassLoader();
        java.util.List<Class<? extends Event>> out = new java.util.ArrayList<>();
        for (String n : names) {
            try {
                Class<?> c = Class.forName(n, true, cl);
                if (Event.class.isAssignableFrom(c)) out.add(c.asSubclass(Event.class));
            } catch (Throwable ignored) {
                // в этой версии WorldGuard такого события нет
            }
        }
        @SuppressWarnings("unchecked")
        Class<? extends Event>[] arr = out.toArray(new Class[0]);
        return arr;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Event>[] emptyEvents() {
        return new Class[0];
    }

    /** Исходное событие Bukkit, из которого WorldGuard сделал своё. */
    public Event originalEvent(Event wgEvent) {
        if (!ready || !delegateEvent.isInstance(wgEvent)) return null;
        try {
            Object o = getOriginalEvent.invoke(wgEvent);
            return o instanceof Event e ? e : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Помечает событие WorldGuard как разрешённое — WorldGuard его не отменит и не напишет «Hey! Sorry». */
    public void allow(Event wgEvent) {
        try {
            setAllowed.invoke(wgEvent, true);
        } catch (Throwable ignored) {
        }
    }
}
