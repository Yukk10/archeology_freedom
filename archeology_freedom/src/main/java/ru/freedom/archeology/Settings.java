package ru.freedom.archeology;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Все значения config.yml, прочитанные один раз (кэш — не читаем файл на каждом тике). */
public final class Settings {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    public final int reloadTime;
    public final boolean rerollUntouched;
    public final boolean protection;
    public final boolean preventFalling;
    public final int blocksPerTick;
    public final int scanChunksPerTick;
    public final long maxZoneVolume;
    public final int autosaveInterval;
    /** Регионы WorldGuard (id в нижнем регистре), где кисточкой можно копать в обход привата. */
    public final java.util.Set<String> brushRegions;

    public final Material wandMaterial;
    public final Component wandName;
    public final List<Component> wandLore;

    public final String[] hours, minutes, seconds;
    public final boolean showSeconds;
    public final String timerNow;

    private final String prefix;
    private final Map<String, String> messages = new HashMap<>();

    public Settings(FileConfiguration c) {
        reloadTime = Math.max(5, c.getInt("reload_time", 300));
        rerollUntouched = c.getBoolean("reroll-untouched", false);
        protection = c.getBoolean("protection.enabled", true);
        preventFalling = c.getBoolean("protection.prevent-falling", true);
        blocksPerTick = Math.max(1, c.getInt("performance.blocks-per-tick", 300));
        scanChunksPerTick = Math.max(1, c.getInt("performance.scan-chunks-per-tick", 2));
        maxZoneVolume = Math.max(1, c.getLong("performance.max-zone-volume", 4_000_000L));
        autosaveInterval = Math.max(10, c.getInt("performance.autosave-interval", 60));
        brushRegions = c.getStringList("region-list-brush").stream()
                .map(r -> r.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(r -> !r.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Material m = Material.matchMaterial(c.getString("wand.material", "GOLDEN_SHOVEL"));
        wandMaterial = (m == null || !m.isItem()) ? Material.GOLDEN_SHOVEL : m;
        wandName = noItalic(MM.deserialize(c.getString("wand.name", "<gold>Лопата археолога")));
        wandLore = c.getStringList("wand.lore").stream().map(s -> noItalic(MM.deserialize(s))).toList();

        hours = forms(c, "timer.hours", "час", "часа", "часов");
        minutes = forms(c, "timer.minutes", "минута", "минуты", "минут");
        seconds = forms(c, "timer.seconds", "секунда", "секунды", "секунд");
        showSeconds = c.getBoolean("timer.show-seconds", true);
        timerNow = c.getString("timer.now", "сейчас");

        prefix = c.getString("messages.prefix", "");
        ConfigurationSection s = c.getConfigurationSection("messages");
        if (s != null) {
            for (String key : s.getKeys(false)) messages.put(key, s.getString(key, ""));
        }
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static String[] forms(FileConfiguration c, String path, String a, String b, String d) {
        List<String> l = c.getStringList(path);
        if (l.size() >= 3) return new String[]{l.get(0), l.get(1), l.get(2)};
        return new String[]{a, b, d};
    }

    /** Сообщение без префикса (для строк списка). */
    public String raw(String key) {
        return messages.getOrDefault(key, "<red>Нет сообщения: " + key);
    }

    public Component msg(String key, TagResolver... resolvers) {
        String raw = messages.getOrDefault(key, "<red>Нет сообщения: " + key);
        return MM.deserialize(prefix + raw, resolvers);
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        String raw = messages.get(key);
        if (raw != null && raw.isEmpty()) return; // пустое сообщение = отключено
        to.sendMessage(msg(key, resolvers));
    }

    public void actionBar(Player to, String key, TagResolver... resolvers) {
        String raw = messages.get(key);
        if (raw != null && raw.isEmpty()) return;
        to.sendActionBar(MM.deserialize(raw == null ? key : raw, resolvers));
    }

    // ------------------------------------------------------------------ таймер

    /** «4 минуты 59 секунд», «1 час 5 минут», «сейчас». */
    public String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) return timerNow;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) append(sb, h, hours);
        if (m > 0) append(sb, m, minutes);
        if (s > 0 && (showSeconds || (h == 0 && m == 0))) append(sb, s, seconds);
        if (sb.isEmpty()) append(sb, m > 0 ? m : 1, minutes);
        return sb.toString();
    }

    private static void append(StringBuilder sb, long n, String[] f) {
        if (!sb.isEmpty()) sb.append(' ');
        sb.append(n).append(' ').append(plural(n, f));
    }

    static String plural(long n, String[] f) {
        long mod10 = n % 10, mod100 = n % 100;
        if (mod10 == 1 && mod100 != 11) return f[0];
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return f[1];
        return f[2];
    }
}
