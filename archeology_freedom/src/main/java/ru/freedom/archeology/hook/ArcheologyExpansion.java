package ru.freedom.archeology.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import ru.freedom.archeology.ArcheologyPlugin;

/**
 * %archeology_timer%         — «4 минуты 59 секунд»
 * %archeology_timer_seconds% — «299»
 * %archeology_timer_short%   — «04:59» (или «1:04:59»)
 * %archeology_zones%         — количество зон
 */
public final class ArcheologyExpansion extends PlaceholderExpansion {

    private final ArcheologyPlugin plugin;

    public ArcheologyExpansion(ArcheologyPlugin plugin) {
        this.plugin = plugin;
    }

    public static Object hook(ArcheologyPlugin plugin) {
        ArcheologyExpansion e = new ArcheologyExpansion(plugin);
        return e.register() ? e : null;
    }

    public static void unhook(Object expansion) {
        if (expansion instanceof ArcheologyExpansion e) e.unregister();
    }

    @Override
    public String getIdentifier() {
        return "archeology";
    }

    @Override
    public String getAuthor() {
        return "Freedom";
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        long left = plugin.regen().secondsLeft();
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "timer" -> plugin.settings().formatDuration(left);
            case "timer_seconds" -> String.valueOf(left);
            case "timer_short" -> left >= 3600
                    ? String.format("%d:%02d:%02d", left / 3600, (left % 3600) / 60, left % 60)
                    : String.format("%02d:%02d", left / 60, left % 60);
            case "zones" -> String.valueOf(plugin.zones().all().size());
            default -> null;
        };
    }
}
