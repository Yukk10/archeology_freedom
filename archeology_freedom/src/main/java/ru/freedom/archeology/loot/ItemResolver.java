package ru.freedom.archeology.loot;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Превращает id из конфига в ItemStack. ItemsAdder и MythicMobs подключаются
 * через рефлексию, поэтому плагин работает и без них, и с любыми их версиями.
 * Успешно найденные предметы кэшируются (дальше только clone()).
 */
public final class ItemResolver {

    public enum Source { VANILLA, ITEMSADDER, MYTHICMOBS }

    private final Logger log;
    private final Map<String, ItemStack> cache = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    public ItemResolver(Logger log) {
        this.log = log;
    }

    public void clear() {
        cache.clear();
        warned.clear();
    }

    /** Возвращает копию предмета или null, если найти не удалось. */
    public ItemStack resolve(Source source, String id) {
        String cacheKey = source + ":" + id;
        ItemStack proto = cache.get(cacheKey);
        if (proto == null) {
            try {
                proto = switch (source) {
                    case VANILLA -> vanilla(id);
                    case ITEMSADDER -> itemsAdder(id);
                    case MYTHICMOBS -> mythicMobs(id);
                };
            } catch (Throwable t) {
                proto = null;
                warnOnce(cacheKey, "Ошибка при получении предмета " + cacheKey + ": " + t);
            }
            if (proto == null || proto.getType().isAir()) {
                // Не кэшируем неудачу: ItemsAdder грузит предметы позже старта сервера.
                warnOnce(cacheKey, "Предмет не найден: " + cacheKey
                        + (source == Source.VANILLA ? "" : " (плагин установлен и предмет существует?)"));
                return null;
            }
            cache.put(cacheKey, proto.clone());
        }
        return proto.clone();
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) log.warning(message);
    }

    private static ItemStack vanilla(String id) {
        Material m = Material.matchMaterial(id);
        return (m == null || !m.isItem()) ? null : new ItemStack(m);
    }

    // ---------------------------------------------------------------- ItemsAdder

    private static ItemStack itemsAdder(String id) throws Exception {
        Plugin ia = Bukkit.getPluginManager().getPlugin("ItemsAdder");
        if (ia == null || !ia.isEnabled()) return null;
        ClassLoader cl = ia.getClass().getClassLoader();
        Class<?> customStack = Class.forName("dev.lone.itemsadder.api.CustomStack", true, cl);
        Method getInstance = customStack.getMethod("getInstance", String.class);
        Method getItemStack = customStack.getMethod("getItemStack");

        Object stack = getInstance.invoke(null, id);
        if (stack == null && id.indexOf(':') < 0) {
            // id без namespace — ищем среди всех предметов ItemsAdder
            try {
                Class<?> api = Class.forName("dev.lone.itemsadder.api.ItemsAdder", true, cl);
                Object all = api.getMethod("getAllItems").invoke(null);
                if (all instanceof List<?> list) {
                    Method getId = customStack.getMethod("getId");
                    for (Object o : list) {
                        if (o != null && id.equalsIgnoreCase(String.valueOf(getId.invoke(o)))) {
                            stack = o;
                            break;
                        }
                    }
                }
            } catch (NoSuchMethodException | ClassNotFoundException ignored) {
                // старая/новая версия API без getAllItems
            }
        }
        return stack == null ? null : (ItemStack) getItemStack.invoke(stack);
    }

    // ---------------------------------------------------------------- MythicMobs

    private static ItemStack mythicMobs(String id) throws Exception {
        Plugin mm = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mm == null || !mm.isEnabled()) return null;
        ClassLoader cl = mm.getClass().getClassLoader();
        Class<?> mythic = Class.forName("io.lumine.mythic.bukkit.MythicBukkit", true, cl);
        Object inst = mythic.getMethod("inst").invoke(null);
        Object itemManager = mythic.getMethod("getItemManager").invoke(inst);
        Method getItemStack = itemManager.getClass().getMethod("getItemStack", String.class);
        Object result = getItemStack.invoke(itemManager, id);
        return result instanceof ItemStack is ? is : null;
    }
}
