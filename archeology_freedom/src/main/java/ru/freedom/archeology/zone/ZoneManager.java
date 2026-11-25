package ru.freedom.archeology.zone;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/** Хранит зоны, быстрый индекс «мир+чанк → зоны» и сохраняет всё в zones.yml. */
public final class ZoneManager {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    /** world -> chunkKey -> зоны, пересекающие этот чанк */
    private final Map<String, Map<Long, List<Zone>>> index = new HashMap<>();
    private boolean dirty;
    private final Object writeLock = new Object();

    public ZoneManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "zones.yml");
    }

    // ------------------------------------------------------------------ доступ

    public Collection<Zone> all() {
        return Collections.unmodifiableCollection(zones.values());
    }

    public Zone get(String name) {
        return zones.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean isRegistered(Zone zone) {
        return zones.get(zone.name().toLowerCase(Locale.ROOT)) == zone;
    }

    /** Зоны, пересекающие чанк (пустой список, если нет). O(1). */
    public List<Zone> inChunk(String world, int cx, int cz) {
        Map<Long, List<Zone>> w = index.get(world);
        if (w == null) return List.of();
        List<Zone> list = w.get(Keys.chunk(cx, cz));
        return list == null ? List.of() : list;
    }

    /** Первая зона, содержащая блок, или null. */
    public Zone at(Block block) {
        return at(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public Zone at(String world, int x, int y, int z) {
        for (Zone zone : inChunk(world, x >> 4, z >> 4)) {
            if (zone.contains(world, x, y, z)) return zone;
        }
        return null;
    }

    /** Зона, которая отслеживает этот блок, или null. */
    public Zone trackingZone(Block block) {
        int x = block.getX(), y = block.getY(), z = block.getZ();
        for (Zone zone : inChunk(block.getWorld().getName(), x >> 4, z >> 4)) {
            if (zone.isTracked(x, y, z)) return zone;
        }
        return null;
    }

    public void add(Zone zone) {
        zones.put(zone.name().toLowerCase(Locale.ROOT), zone);
        Map<Long, List<Zone>> w = index.computeIfAbsent(zone.world(), k -> new HashMap<>());
        for (int cx = zone.minChunkX(); cx <= zone.maxChunkX(); cx++) {
            for (int cz = zone.minChunkZ(); cz <= zone.maxChunkZ(); cz++) {
                w.computeIfAbsent(Keys.chunk(cx, cz), k -> new ArrayList<>(1)).add(zone);
            }
        }
        markDirty();
    }

    public Zone remove(String name) {
        Zone zone = zones.remove(name.toLowerCase(Locale.ROOT));
        if (zone == null) return null;
        Map<Long, List<Zone>> w = index.get(zone.world());
        if (w != null) {
            w.values().removeIf(list -> {
                list.remove(zone);
                return list.isEmpty();
            });
            if (w.isEmpty()) index.remove(zone.world());
        }
        markDirty();
        return zone;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    // ------------------------------------------------------------------ загрузка / сохранение

    public void load() {
        zones.clear();
        index.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("zones");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(name);
            if (s == null) continue;
            try {
                List<Integer> min = s.getIntegerList("min");
                List<Integer> max = s.getIntegerList("max");
                Zone zone = new Zone(name, s.getString("world", "world"),
                        min.get(0), min.get(1), min.get(2), max.get(0), max.get(1), max.get(2));
                for (String line : s.getStringList("blocks")) {
                    String[] p = line.split(",");
                    if (p.length < 4) continue;
                    zone.track(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            p[3].equalsIgnoreCase("G"));
                }
                for (long key : s.getLongList("pending-scan")) zone.pendingScan().add(key);
                for (long key : s.getLongList("pending-refresh")) zone.pendingRefresh().add(key);
                add(zone);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Не удалось загрузить зону " + name, e);
            }
        }
        dirty = false;
    }

    /** Собирает YAML в главном потоке (быстро) и пишет файл асинхронно или синхронно. */
    public void save(boolean async) {
        String data = serialize();
        dirty = false;
        if (async) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data));
        } else {
            write(data);
        }
    }

    private String serialize() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Zone zone : zones.values()) {
            String p = "zones." + zone.name() + ".";
            yml.set(p + "world", zone.world());
            yml.set(p + "min", List.of(zone.minX(), zone.minY(), zone.minZ()));
            yml.set(p + "max", List.of(zone.maxX(), zone.maxY(), zone.maxZ()));
            List<String> list = new ArrayList<>(zone.blockCount());
            StringBuilder sb = new StringBuilder(24);
            for (Map<Long, Boolean> chunk : zone.blocks().values()) {
                for (Map.Entry<Long, Boolean> e : chunk.entrySet()) {
                    long k = e.getKey();
                    sb.setLength(0);
                    sb.append(Keys.blockX(k)).append(',').append(Keys.blockY(k)).append(',')
                            .append(Keys.blockZ(k)).append(',').append(e.getValue() ? 'G' : 'S');
                    list.add(sb.toString());
                }
            }
            yml.set(p + "blocks", list);
            yml.set(p + "pending-scan", new ArrayList<>(zone.pendingScan()));
            yml.set(p + "pending-refresh", new ArrayList<>(zone.pendingRefresh()));
        }
        return yml.saveToString();
    }

    private void write(String data) {
        synchronized (writeLock) {
            try {
                Path target = file.toPath();
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling("zones.yml.tmp");
                Files.writeString(tmp, data, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить zones.yml", e);
            }
        }
    }
}
