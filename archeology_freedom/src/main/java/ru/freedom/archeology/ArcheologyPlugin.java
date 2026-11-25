package ru.freedom.archeology;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import ru.freedom.archeology.command.ArcheologyCommand;
import ru.freedom.archeology.hook.ArcheologyExpansion;
import ru.freedom.archeology.listener.WandListener;
import ru.freedom.archeology.listener.ZoneListener;
import ru.freedom.archeology.loot.ItemResolver;
import ru.freedom.archeology.loot.LootTable;
import ru.freedom.archeology.loot.MobLoot;
import ru.freedom.archeology.listener.MobLootListener;
import ru.freedom.archeology.regen.RegenService;
import ru.freedom.archeology.zone.ZoneManager;

import java.util.List;

public final class ArcheologyPlugin extends JavaPlugin {

    private Settings settings;
    private ZoneManager zones;
    private LootTable loot;
    private RegenService regen;
    private WandListener wand;
    /** Хранится как Object: класс PlaceholderAPI загружается, только если PAPI установлен. */
    private Object expansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = new Settings(getConfig());
        ItemResolver resolver = new ItemResolver(getLogger());
        loot = new LootTable(resolver, new MobLoot(this, resolver));
        loadLoot();

        zones = new ZoneManager(this);
        zones.load();

        regen = new RegenService(this, zones);
        wand = new WandListener(this);

        Bukkit.getPluginManager().registerEvents(wand, this);
        Bukkit.getPluginManager().registerEvents(new ZoneListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MobLootListener(loot.mobs()), this);

        ArcheologyCommand command = new ArcheologyCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command.build(), "Зоны археологии", List.of("arch")));

        regen.start();

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = ArcheologyExpansion.hook(this);
            getLogger().info("PlaceholderAPI: %archeology_timer% зарегистрирован.");
        }
        getLogger().info("Загружено зон: " + zones.all().size() + ", лута: " + loot.size());
    }

    @Override
    public void onDisable() {
        if (expansion != null) {
            try {
                ArcheologyExpansion.unhook(expansion);
            } catch (Throwable ignored) {
            }
        }
        if (regen != null) regen.stop();
        if (zones != null) zones.save(false); // синхронно — сервер выключается
    }

    /** /archeology reload */
    public void reloadAll() {
        int oldReload = settings.reloadTime;
        reloadConfig();
        settings = new Settings(getConfig());
        loadLoot();
        if (settings.reloadTime != oldReload) regen.resetTimer();
    }

    private void loadLoot() {
        loot.mobs().configure(getConfig().getString("mob-loot.display-item", "auto"),
                getConfig().getString("mob-loot.fallback-item", "BONE"));
        loot.load(getConfig().getList("loots"), getLogger());
    }

    public Settings settings() { return settings; }
    public ZoneManager zones() { return zones; }
    public LootTable loot() { return loot; }
    public RegenService regen() { return regen; }
    public WandListener wand() { return wand; }
}
