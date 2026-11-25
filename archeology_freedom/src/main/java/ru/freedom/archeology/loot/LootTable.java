package ru.freedom.archeology.loot;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Список лута из config.yml. В подозрительном блоке лежит один предмет:
 * если сумма шансов <= 100 — каждый выпадает со своим шансом, остальное — пустой блок;
 * если > 100 — шансы работают как веса.
 */
public final class LootTable {

    public record Entry(ItemResolver.Source source, String id, double chance, int minAmount, int maxAmount) {}

    private final List<Entry> entries = new ArrayList<>();
    private final ItemResolver resolver;
    private double total;

    public LootTable(ItemResolver resolver) {
        this.resolver = resolver;
    }

    public void load(List<?> raw, Logger log) {
        entries.clear();
        total = 0;
        resolver.clear();
        if (raw == null) return;
        for (Object o : raw) {
            if (o instanceof Map<?, ?> map) { // если написали "- diamond: 50"
                for (Map.Entry<?, ?> e : map.entrySet()) parse(e.getKey() + ":" + e.getValue(), log);
            } else if (o != null) {
                parse(o.toString(), log);
            }
        }
    }

    private void parse(String line, Logger log) {
        String s = line.trim();
        int colon = s.lastIndexOf(':');
        if (colon <= 0 || colon == s.length() - 1) {
            log.warning("Неверная строка лута (нужно предмет:шанс): " + line);
            return;
        }
        double chance;
        try {
            chance = Double.parseDouble(s.substring(colon + 1).trim().replace(',', '.').replace("%", ""));
        } catch (NumberFormatException e) {
            log.warning("Неверный шанс в строке лута: " + line);
            return;
        }
        if (chance <= 0) return;

        String item = s.substring(0, colon).trim();
        int min = 1, max = 1;
        int star = item.lastIndexOf('*');
        if (star > 0) {
            String amount = item.substring(star + 1);
            item = item.substring(0, star);
            try {
                int dash = amount.indexOf('-');
                if (dash > 0) {
                    min = Integer.parseInt(amount.substring(0, dash).trim());
                    max = Integer.parseInt(amount.substring(dash + 1).trim());
                } else {
                    min = max = Integer.parseInt(amount.trim());
                }
            } catch (NumberFormatException e) {
                log.warning("Неверное количество в строке лута: " + line);
                return;
            }
            min = Math.max(1, min);
            max = Math.max(min, max);
        }

        String lower = item.toLowerCase(Locale.ROOT);
        ItemResolver.Source source = ItemResolver.Source.VANILLA;
        String id = item;
        if (lower.startsWith("itemsadder-")) {
            source = ItemResolver.Source.ITEMSADDER;
            id = item.substring("itemsadder-".length());
        } else if (lower.startsWith("ia-")) {
            source = ItemResolver.Source.ITEMSADDER;
            id = item.substring(3);
        } else if (lower.startsWith("mythicmobs-")) {
            source = ItemResolver.Source.MYTHICMOBS;
            id = item.substring("mythicmobs-".length());
        } else if (lower.startsWith("mm-")) {
            source = ItemResolver.Source.MYTHICMOBS;
            id = item.substring(3);
        }
        if (id.isEmpty()) {
            log.warning("Пустой id предмета в строке лута: " + line);
            return;
        }
        entries.add(new Entry(source, id, chance, min, max));
        total += chance;
    }

    public int size() {
        return entries.size();
    }

    /** Случайный предмет или null (пустой блок). */
    public ItemStack roll() {
        if (entries.isEmpty()) return null;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double r = rnd.nextDouble() * Math.max(100.0, total);
        for (Entry e : entries) {
            r -= e.chance();
            if (r < 0) {
                ItemStack item = resolver.resolve(e.source(), e.id());
                if (item == null) return null;
                if (e.maxAmount() > 1) { // количество указано через *N — иначе оставляем как у предмета
                    int amount = e.minAmount() == e.maxAmount()
                            ? e.minAmount()
                            : rnd.nextInt(e.minAmount(), e.maxAmount() + 1);
                    item.setAmount(Math.min(amount, item.getMaxStackSize()));
                }
                return item;
            }
        }
        return null;
    }
}
