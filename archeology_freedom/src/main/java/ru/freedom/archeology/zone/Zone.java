package ru.freedom.archeology.zone;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Зона археологии. Хранит отслеживаемые позиции подозрительных блоков,
 * сгруппированные по чанкам. Меняется только из главного потока.
 */
public final class Zone {

    private final String name;
    private final String world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;

    /** chunkKey -> (blockKey -> true = гравий, false = песок) */
    private final Map<Long, Map<Long, Boolean>> blocks = new HashMap<>();
    /** Чанки, которые ещё не просканированы (не были загружены при создании). */
    private final Set<Long> pendingScan = new HashSet<>();
    /** Чанки, пропустившие обновление, пока были выгружены. */
    private final Set<Long> pendingRefresh = new HashSet<>();

    public Zone(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.name = name;
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ
                && world.equals(worldName);
    }

    public boolean containsXZ(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public void track(int x, int y, int z, boolean gravel) {
        blocks.computeIfAbsent(Keys.chunk(x >> 4, z >> 4), k -> new HashMap<>())
                .put(Keys.block(x, y, z), gravel);
    }

    public boolean untrack(int x, int y, int z) {
        long ck = Keys.chunk(x >> 4, z >> 4);
        Map<Long, Boolean> m = blocks.get(ck);
        if (m == null) return false;
        boolean removed = m.remove(Keys.block(x, y, z)) != null;
        if (m.isEmpty()) blocks.remove(ck);
        return removed;
    }

    public boolean isTracked(int x, int y, int z) {
        Map<Long, Boolean> m = blocks.get(Keys.chunk(x >> 4, z >> 4));
        return m != null && m.containsKey(Keys.block(x, y, z));
    }

    public int blockCount() {
        int n = 0;
        for (Map<Long, Boolean> m : blocks.values()) n += m.size();
        return n;
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public int minChunkX() { return minX >> 4; }
    public int maxChunkX() { return maxX >> 4; }
    public int minChunkZ() { return minZ >> 4; }
    public int maxChunkZ() { return maxZ >> 4; }

    public Map<Long, Map<Long, Boolean>> blocks() { return blocks; }
    public Set<Long> pendingScan() { return pendingScan; }
    public Set<Long> pendingRefresh() { return pendingRefresh; }

    public String name() { return name; }
    public String world() { return world; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }
}
