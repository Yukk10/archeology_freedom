package ru.freedom.archeology.regen;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrushableBlock;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import ru.freedom.archeology.ArcheologyPlugin;
import ru.freedom.archeology.Settings;
import ru.freedom.archeology.zone.Keys;
import ru.freedom.archeology.zone.Zone;
import ru.freedom.archeology.zone.ZoneManager;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Таймер обновления, очередь восстановления блоков и сканирование чанков.
 *
 * Оптимизация:
 *  - блоки восстанавливаются порциями (performance.blocks-per-tick), без лагов на больших зонах;
 *  - выгруженные чанки НИКОГДА не загружаются: они помечаются и обновляются, когда сами загрузятся;
 *  - поиск подозрительных блоков идёт по ChunkSnapshot в асинхронном потоке.
 */
public final class RegenService {

    private record RefreshJob(Zone zone, long chunkKey, long[] positions, int[] cursor, boolean wholeChunk) {}
    private record ScanJob(Zone zone, long chunkKey) {}
    private record ChunkRef(Zone zone, long chunkKey) {}

    /** Прогресс первичного сканирования новой зоны — чтобы сообщить админу результат. */
    private static final class ScanProgress {
        final UUID playerId; // null = консоль
        final WeakReference<CommandSender> console;
        int remaining;
        int found;

        ScanProgress(CommandSender sender, int remaining) {
            this.playerId = sender instanceof Player p ? p.getUniqueId() : null;
            this.console = new WeakReference<>(sender instanceof Player ? null : sender);
            this.remaining = remaining;
        }

        CommandSender sender() {
            return playerId != null ? Bukkit.getPlayer(playerId) : console.get();
        }
    }

    private final ArcheologyPlugin plugin;
    private final ZoneManager zones;

    private final ArrayDeque<RefreshJob> refreshQueue = new ArrayDeque<>();
    private final ArrayDeque<ScanJob> scanQueue = new ArrayDeque<>();
    private final Set<ScanJob> scanQueued = new HashSet<>();
    /** Чанки, уже стоящие в очереди целиком — чтобы не дублировать работу. */
    private final Set<ChunkRef> chunksQueued = new HashSet<>();
    private final Map<Zone, ScanProgress> progress = new HashMap<>();

    private BukkitTask tickTask;
    private BukkitTask timerTask;
    private volatile long nextRefreshAt;
    private int autosaveCounter;

    public RegenService(ArcheologyPlugin plugin, ZoneManager zones) {
        this.plugin = plugin;
        this.zones = zones;
    }

    private Settings settings() {
        return plugin.settings();
    }

    // ------------------------------------------------------------------ жизненный цикл

    public void start() {
        resetTimer();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::secondTick, 20L, 20L);
        resumePending();
    }

    public void stop() {
        if (tickTask != null) tickTask.cancel();
        if (timerTask != null) timerTask.cancel();
        // Недоделанные чанки помечаем, чтобы восстановить их после рестарта.
        for (RefreshJob job : refreshQueue) {
            if (zones.isRegistered(job.zone())) job.zone().pendingRefresh().add(job.chunkKey());
        }
        refreshQueue.clear();
        chunksQueued.clear();
        scanQueue.clear();
        scanQueued.clear();
        progress.clear();
        zones.markDirty();
    }

    public void resetTimer() {
        nextRefreshAt = System.currentTimeMillis() + settings().reloadTime * 1000L;
    }

    /** Секунд до следующего обновления (потокобезопасно — читается из PlaceholderAPI). */
    public long secondsLeft() {
        long ms = nextRefreshAt - System.currentTimeMillis();
        return ms <= 0 ? 0 : (ms + 999) / 1000;
    }

    private void secondTick() {
        if (System.currentTimeMillis() >= nextRefreshAt) {
            resetTimer();
            refreshAll();
        }
        if (++autosaveCounter >= settings().autosaveInterval) {
            autosaveCounter = 0;
            if (zones.isDirty()) zones.save(true);
        }
    }

    private void tick() {
        if (!scanQueue.isEmpty()) processScans();
        if (!refreshQueue.isEmpty()) processRefresh();
    }

    // ------------------------------------------------------------------ обновление

    /** Ставит в очередь все зоны. Выгруженные чанки откладываются до загрузки. */
    public void refreshAll() {
        for (Zone zone : zones.all()) {
            World world = Bukkit.getWorld(zone.world());
            for (long ck : zone.blocks().keySet()) {
                if (world != null && world.isChunkLoaded(Keys.chunkX(ck), Keys.chunkZ(ck))) {
                    enqueueChunk(zone, ck);
                } else if (zone.pendingRefresh().add(ck)) {
                    zones.markDirty();
                }
            }
        }
    }

    public void enqueueChunk(Zone zone, long chunkKey) {
        Map<Long, Boolean> m = zone.blocks().get(chunkKey);
        if (m == null || m.isEmpty()) return;
        if (!chunksQueued.add(new ChunkRef(zone, chunkKey))) return;
        long[] arr = new long[m.size()];
        int i = 0;
        for (long k : m.keySet()) arr[i++] = k;
        refreshQueue.add(new RefreshJob(zone, chunkKey, arr, new int[1], true));
    }

    public void enqueueBlock(Zone zone, int x, int y, int z) {
        refreshQueue.add(new RefreshJob(zone, Keys.chunk(x >> 4, z >> 4),
                new long[]{Keys.block(x, y, z)}, new int[1], false));
    }

    private void processRefresh() {
        int budget = settings().blocksPerTick;
        while (budget > 0 && !refreshQueue.isEmpty()) {
            RefreshJob job = refreshQueue.peek();
            Zone zone = job.zone();
            if (!zones.isRegistered(zone)) {
                pollRefresh();
                continue;
            }
            int cx = Keys.chunkX(job.chunkKey()), cz = Keys.chunkZ(job.chunkKey());
            World world = Bukkit.getWorld(zone.world());
            if (world == null || !world.isChunkLoaded(cx, cz)) {
                // Чанк выгрузился, пока ждали очередь — не грузим его, обновим при загрузке.
                zone.pendingRefresh().add(job.chunkKey());
                zones.markDirty();
                pollRefresh();
                continue;
            }
            Map<Long, Boolean> tracked = zone.blocks().get(job.chunkKey());
            long[] pos = job.positions();
            int[] cursor = job.cursor();
            while (cursor[0] < pos.length && budget > 0) {
                long key = pos[cursor[0]++];
                budget--;
                Boolean gravel = tracked == null ? null : tracked.get(key);
                if (gravel == null) continue; // блок убрали из зоны
                regenerate(world.getBlockAt(Keys.blockX(key), Keys.blockY(key), Keys.blockZ(key)), gravel);
            }
            if (cursor[0] >= pos.length) pollRefresh();
        }
    }

    private void pollRefresh() {
        RefreshJob job = refreshQueue.poll();
        if (job != null && job.wholeChunk()) chunksQueued.remove(new ChunkRef(job.zone(), job.chunkKey()));
    }

    private void regenerate(Block block, boolean gravel) {
        Material target = gravel ? Material.SUSPICIOUS_GRAVEL : Material.SUSPICIOUS_SAND;
        Material plain = gravel ? Material.GRAVEL : Material.SAND;
        Material type = block.getType();

        boolean wasSuspicious = type == target;
        if (!wasSuspicious) {
            if (type != plain && !type.isAir()) return; // там что-то построили — не трогаем
            block.setType(target, false);
        }

        BlockState state = block.getState();
        if (!(state instanceof BrushableBlock brushable)) return;
        if (wasSuspicious && !settings().rerollUntouched) {
            ItemStack current = brushable.getItem();
            if (current != null && !current.getType().isAir()) return; // лут ещё не выкопали
        }
        if (brushable.getLootTable() != null) brushable.setLootTable(null);
        brushable.setItem(plugin.loot().roll());
        state.update(true, false);
    }

    // ------------------------------------------------------------------ сканирование

    /** Первичное сканирование новой зоны: загруженные чанки — сейчас, остальные — при загрузке. */
    public void scanNewZone(Zone zone, CommandSender requester) {
        World world = Bukkit.getWorld(zone.world());
        int loaded = 0;
        for (int cx = zone.minChunkX(); cx <= zone.maxChunkX(); cx++) {
            for (int cz = zone.minChunkZ(); cz <= zone.maxChunkZ(); cz++) {
                long ck = Keys.chunk(cx, cz);
                zone.pendingScan().add(ck);
                if (world != null && world.isChunkLoaded(cx, cz)) {
                    if (requestScan(zone, ck)) loaded++;
                }
            }
        }
        zones.markDirty();
        if (loaded > 0) {
            progress.put(zone, new ScanProgress(requester, loaded));
        } else {
            settings().send(requester, "zone-scanned",
                    Placeholder.unparsed("name", zone.name()), Placeholder.unparsed("count", "0"));
        }
    }

    public boolean requestScan(Zone zone, long chunkKey) {
        ScanJob job = new ScanJob(zone, chunkKey);
        if (!scanQueued.add(job)) return false;
        scanQueue.add(job);
        return true;
    }

    private void processScans() {
        int budget = settings().scanChunksPerTick;
        while (budget > 0 && !scanQueue.isEmpty()) {
            ScanJob job = scanQueue.poll();
            Zone zone = job.zone();
            long ck = job.chunkKey();
            if (!zones.isRegistered(zone)) {
                scanQueued.remove(job);
                continue;
            }
            int cx = Keys.chunkX(ck), cz = Keys.chunkZ(ck);
            World world = Bukkit.getWorld(zone.world());
            if (world == null || !world.isChunkLoaded(cx, cz)) {
                scanQueued.remove(job); // остаётся в pendingScan — просканируем при загрузке
                finishProgress(zone, 0);
                continue;
            }
            budget--;
            ChunkSnapshot snapshot = world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
            int baseX = cx << 4, baseZ = cz << 4;
            int x0 = Math.max(zone.minX(), baseX), x1 = Math.min(zone.maxX(), baseX + 15);
            int z0 = Math.max(zone.minZ(), baseZ), z1 = Math.min(zone.maxZ(), baseZ + 15);
            int y0 = Math.max(zone.minY(), world.getMinHeight());
            int y1 = Math.min(zone.maxY(), world.getMaxHeight() - 1);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<long[]> found = new ArrayList<>();
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int y = y0; y <= y1; y++) {
                            Material m = snapshot.getBlockType(x & 15, y, z & 15);
                            if (m == Material.SUSPICIOUS_SAND) found.add(new long[]{Keys.block(x, y, z), 0});
                            else if (m == Material.SUSPICIOUS_GRAVEL) found.add(new long[]{Keys.block(x, y, z), 1});
                        }
                    }
                }
                if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> applyScan(job, found));
            });
        }
    }

    private void applyScan(ScanJob job, List<long[]> found) {
        scanQueued.remove(job);
        Zone zone = job.zone();
        if (!zones.isRegistered(zone)) return;
        for (long[] f : found) {
            zone.track(Keys.blockX(f[0]), Keys.blockY(f[0]), Keys.blockZ(f[0]), f[1] == 1);
        }
        zone.pendingScan().remove(job.chunkKey());
        zones.markDirty();
        if (!found.isEmpty()) enqueueChunk(zone, job.chunkKey()); // сразу заполнить лутом
        finishProgress(zone, found.size());
    }

    private void finishProgress(Zone zone, int found) {
        ScanProgress p = progress.get(zone);
        if (p == null) return;
        p.found += found;
        if (--p.remaining > 0) return;
        progress.remove(zone);
        CommandSender sender = p.sender();
        if (sender != null) {
            settings().send(sender, "zone-scanned",
                    Placeholder.unparsed("name", zone.name()),
                    Placeholder.unparsed("count", String.valueOf(p.found)));
        }
    }

    // ------------------------------------------------------------------ загрузка чанков

    /** Вызывается из ChunkLoadEvent — догоняем пропущенные обновления и сканирования. */
    public void onChunkLoad(String world, int cx, int cz) {
        List<Zone> list = zones.inChunk(world, cx, cz);
        if (list.isEmpty()) return;
        long ck = Keys.chunk(cx, cz);
        for (Zone zone : list) {
            if (zone.pendingScan().contains(ck)) requestScan(zone, ck);
            if (zone.pendingRefresh().remove(ck)) {
                zones.markDirty();
                enqueueChunk(zone, ck);
            }
        }
    }

    /** После старта: чанки, которые уже загружены (спавн), догоняем сразу. */
    private void resumePending() {
        for (Zone zone : zones.all()) {
            World world = Bukkit.getWorld(zone.world());
            if (world == null) continue;
            for (long ck : new ArrayList<>(zone.pendingScan())) {
                if (world.isChunkLoaded(Keys.chunkX(ck), Keys.chunkZ(ck))) requestScan(zone, ck);
            }
            for (long ck : new ArrayList<>(zone.pendingRefresh())) {
                if (world.isChunkLoaded(Keys.chunkX(ck), Keys.chunkZ(ck))) {
                    zone.pendingRefresh().remove(ck);
                    enqueueChunk(zone, ck);
                }
            }
        }
    }

    public void forget(Zone zone) {
        progress.remove(zone);
    }
}
