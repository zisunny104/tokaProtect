package dev.toka.protect.rollback;

import cn.nukkit.block.BlockID;
import dev.toka.protect.log.LogStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what a rollback has to put back. Rows come oldest first. For each place the plan keeps what stood there before
 * the first change in the window (that is what to restore) and what the last change left there (that is what must still be
 * there when the rollback runs; if it is not, someone changed the spot later and it is left alone).
 */
public final class RollbackPlan {
    private RollbackPlan() {}

    /** @param expectedId the block id that must be there now for the restore to be safe */
    public record Entry(String world, int x, int y, int z, int restoreId, int restoreMeta, byte[] restoreNbt, int expectedId) {}

    /** What a change leaves behind: placed, poured and lit things stay; broken, scooped and blown-up things leave air. */
    static int[] after(LogStore.Row row) {
        return switch (row.action()) {
            case PLACE, EMPTY, IGNITE -> new int[]{row.block(), row.meta()};
            case BREAK, FILL, EXPLODE -> new int[]{BlockID.AIR, 0};
        };
    }

    public static List<Entry> build(List<LogStore.Row> oldestFirst) {
        record Span(LogStore.Row first, LogStore.Row last) {}
        Map<String, Span> spans = new LinkedHashMap<>();
        for (LogStore.Row row : oldestFirst) {
            String key = row.world() + ':' + row.x() + ':' + row.y() + ':' + row.z();
            Span known = spans.get(key);
            spans.put(key, known == null ? new Span(row, row) : new Span(known.first(), row));
        }
        List<Entry> entries = new ArrayList<>();
        for (Span span : spans.values()) {
            int[] now = after(span.last());
            LogStore.Row first = span.first();
            // Putting back what is already there would change nothing.
            if (first.prevBlock() == now[0] && first.prevMeta() == now[1] && first.prevNbt() == null) continue;
            entries.add(new Entry(first.world(), first.x(), first.y(), first.z(), first.prevBlock(), first.prevMeta(), first.prevNbt(), now[0]));
        }
        return entries;
    }
}
