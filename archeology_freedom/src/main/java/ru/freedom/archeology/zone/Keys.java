package ru.freedom.archeology.zone;

/** Упаковка координат в long — без лишних объектов в памяти. */
public final class Keys {
    private Keys() {}

    public static long chunk(int cx, int cz) {
        return (cx & 0xFFFFFFFFL) | ((cz & 0xFFFFFFFFL) << 32);
    }

    public static int chunkX(long key) {
        return (int) key;
    }

    public static int chunkZ(long key) {
        return (int) (key >>> 32);
    }

    /** x и z: 26 бит, y: 12 бит (от -2048 до 2047). */
    public static long block(int x, int y, int z) {
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }

    public static int blockX(long key) {
        return (int) (key >> 38);
    }

    public static int blockY(long key) {
        return (int) (key << 52 >> 52);
    }

    public static int blockZ(long key) {
        return (int) (key << 26 >> 38);
    }
}
