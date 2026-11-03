package dev.toka.protect.rollback;

import dev.toka.protect.log.ItemStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a rollback of container contents puts back. Rows come oldest first. For each slot the plan keeps what it held before the
 * first change in the window (to restore) and what the last change left in it (which must still be there when the rollback runs,
 * or someone used the slot later and it is left alone). Items that already left the container are not taken from anyone.
 */
public final class ItemPlan {
    private ItemPlan() {}

    public record Entry(String world, int x, int y, int z, int slot, byte[] restore, byte[] expected) {}

    public static List<Entry> build(List<ItemStore.Row> oldestFirst) {
        record Span(ItemStore.Row first, ItemStore.Row last) {}
        Map<String, Span> spans = new LinkedHashMap<>();
        for (ItemStore.Row row : oldestFirst) {
            String key = row.world() + ':' + row.x() + ':' + row.y() + ':' + row.z() + ':' + row.slot();
            Span known = spans.get(key);
            spans.put(key, known == null ? new Span(row, row) : new Span(known.first(), row));
        }
        List<Entry> entries = new ArrayList<>();
        for (Span span : spans.values()) {
            if (Arrays.equals(span.first().before(), span.last().after())) continue;
            entries.add(new Entry(span.first().world(), span.first().x(), span.first().y(), span.first().z(), span.first().slot(), span.first().before(), span.last().after()));
        }
        return entries;
    }
}
