package ru.freedom.archeology.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.freedom.archeology.ArcheologyPlugin;
import ru.freedom.archeology.Settings;
import ru.freedom.archeology.listener.WandListener;
import ru.freedom.archeology.zone.Zone;

import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * /archeology. Через Brigadier: если у игрока нет прав, сервер даже не отправляет
 * ему эту команду — её нет в подсказках, а при вводе будет «неизвестная команда».
 */
public final class ArcheologyCommand {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");
    private static final String[] PERMS = {
            "archeology.wand", "archeology.claim", "archeology.remove",
            "archeology.reload", "archeology.list", "archeology.refresh"
    };

    private final ArcheologyPlugin plugin;

    public ArcheologyCommand(ArcheologyPlugin plugin) {
        this.plugin = plugin;
    }

    private static Predicate<CommandSourceStack> perm(String permission) {
        return src -> src.getSender().hasPermission(permission);
    }

    private static boolean hasAny(CommandSender sender) {
        if (sender.hasPermission("archeology.admin")) return true;
        for (String p : PERMS) if (sender.hasPermission(p)) return true;
        return false;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        SuggestionProvider<CommandSourceStack> zoneNames = (ctx, builder) -> {
            String input = builder.getRemainingLowerCase();
            for (Zone z : plugin.zones().all()) {
                if (z.name().toLowerCase(Locale.ROOT).startsWith(input)) builder.suggest(z.name());
            }
            return builder.buildFuture();
        };

        return Commands.literal("archeology")
                .requires(src -> hasAny(src.getSender()))
                .executes(this::help)
                .then(Commands.literal("wand")
                        .requires(perm("archeology.wand"))
                        .executes(this::wand))
                .then(Commands.literal("claim")
                        .requires(perm("archeology.claim"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(this::claim)))
                .then(Commands.literal("remove")
                        .requires(perm("archeology.remove"))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(zoneNames)
                                .executes(this::remove)))
                .then(Commands.literal("reload")
                        .requires(perm("archeology.reload"))
                        .executes(this::reload))
                .then(Commands.literal("list")
                        .requires(perm("archeology.list"))
                        .executes(this::list))
                .then(Commands.literal("refresh")
                        .requires(perm("archeology.refresh"))
                        .executes(this::refresh))
                .build();
    }

    private Settings s() {
        return plugin.settings();
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MiniMessage mm = MiniMessage.miniMessage();
        sender.sendMessage(mm.deserialize("<gold><bold>Археология</bold> <gray>— команды:"));
        line(sender, mm, "archeology.wand", "/archeology wand", "выдать лопату выделения");
        line(sender, mm, "archeology.claim", "/archeology claim <название>", "создать зону из выделения");
        line(sender, mm, "archeology.remove", "/archeology remove <название>", "удалить зону");
        line(sender, mm, "archeology.list", "/archeology list", "список зон");
        line(sender, mm, "archeology.refresh", "/archeology refresh", "обновить все зоны сейчас");
        line(sender, mm, "archeology.reload", "/archeology reload", "перезагрузить конфиг");
        return Command.SINGLE_SUCCESS;
    }

    private static void line(CommandSender sender, MiniMessage mm, String perm, String cmd, String desc) {
        if (!sender.hasPermission(perm)) return;
        sender.sendMessage(mm.deserialize("<yellow><cmd></yellow> <dark_gray>— <gray><desc>",
                Placeholder.unparsed("cmd", cmd), Placeholder.unparsed("desc", desc)));
    }

    private int wand(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            s().send(ctx.getSource().getSender(), "player-only");
            return 0;
        }
        player.getInventory().addItem(plugin.wand().createWand())
                .values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        s().send(player, "wand-given");
        return Command.SINGLE_SUCCESS;
    }

    private int claim(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            s().send(ctx.getSource().getSender(), "player-only");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        if (!NAME.matcher(name).matches()) {
            s().send(player, "invalid-name");
            return 0;
        }
        if (plugin.zones().get(name) != null) {
            s().send(player, "zone-exists", Placeholder.unparsed("name", name));
            return 0;
        }
        WandListener.Selection sel = plugin.wand().selection(player);
        if (sel == null || !sel.complete()) {
            s().send(player, "no-selection");
            return 0;
        }
        Location a = sel.pos1(), b = sel.pos2();
        if (a.getWorld() == null || a.getWorld() != b.getWorld()) {
            s().send(player, "different-worlds");
            return 0;
        }
        Zone zone = new Zone(name, a.getWorld().getName(),
                a.getBlockX(), a.getBlockY(), a.getBlockZ(), b.getBlockX(), b.getBlockY(), b.getBlockZ());
        if (zone.volume() > s().maxZoneVolume) {
            s().send(player, "too-big",
                    Placeholder.unparsed("volume", String.valueOf(zone.volume())),
                    Placeholder.unparsed("max", String.valueOf(s().maxZoneVolume)));
            return 0;
        }
        plugin.zones().add(zone);
        s().send(player, "zone-created", Placeholder.unparsed("name", name));
        plugin.regen().scanNewZone(zone, player);
        plugin.zones().save(true);
        return Command.SINGLE_SUCCESS;
    }

    private int remove(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        Zone zone = plugin.zones().remove(name);
        if (zone == null) {
            s().send(sender, "zone-not-found", Placeholder.unparsed("name", name));
            return 0;
        }
        plugin.regen().forget(zone);
        plugin.zones().save(true);
        s().send(sender, "zone-removed", Placeholder.unparsed("name", zone.name()));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        plugin.reloadAll();
        s().send(ctx.getSource().getSender(), "reloaded",
                Placeholder.unparsed("loot", String.valueOf(plugin.loot().size())),
                Placeholder.unparsed("zones", String.valueOf(plugin.zones().all().size())));
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        var all = plugin.zones().all();
        if (all.isEmpty()) {
            s().send(sender, "list-empty");
            return Command.SINGLE_SUCCESS;
        }
        s().send(sender, "list-header", Placeholder.unparsed("count", String.valueOf(all.size())));
        for (Zone z : all) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize(
                    s().raw("list-entry"),
                    Placeholder.unparsed("name", z.name()),
                    Placeholder.unparsed("world", z.world()),
                    Placeholder.unparsed("x1", String.valueOf(z.minX())),
                    Placeholder.unparsed("y1", String.valueOf(z.minY())),
                    Placeholder.unparsed("z1", String.valueOf(z.minZ())),
                    Placeholder.unparsed("x2", String.valueOf(z.maxX())),
                    Placeholder.unparsed("y2", String.valueOf(z.maxY())),
                    Placeholder.unparsed("z2", String.valueOf(z.maxZ())),
                    Placeholder.unparsed("blocks", String.valueOf(z.blockCount()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int refresh(CommandContext<CommandSourceStack> ctx) {
        plugin.regen().resetTimer();
        plugin.regen().refreshAll();
        s().send(ctx.getSource().getSender(), "refreshed");
        return Command.SINGLE_SUCCESS;
    }
}
