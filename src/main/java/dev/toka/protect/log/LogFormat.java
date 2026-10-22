package dev.toka.protect.log;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Makes a result list short enough to read: a run of the same person doing the same thing to the same block becomes one line. */
public final class LogFormat {
    private LogFormat() {}

    /** A line of output: the newest row of a run, how many rows it stands for, and the time of the oldest. */
    public record Line(LogStore.Row newest, int count, long oldestTime) {}

    /**
     * Rows arrive newest first. Neighbouring rows with the same person (or cause), action and block merge, as long as they
     * are within {@code gapMillis} of each other, so a long wall of the same block collapses but unrelated edits do not.
     */
    public static List<Line> collapse(List<LogStore.Row> rows, long gapMillis) {
        List<Line> lines = new ArrayList<>();
        Line current = null;
        for (LogStore.Row row : rows) {
            if (current != null && same(current.newest(), row) && current.oldestTime() - row.time() <= gapMillis) {
                current = new Line(current.newest(), current.count() + 1, row.time());
            } else {
                if (current != null) lines.add(current);
                current = new Line(row, 1, row.time());
            }
        }
        if (current != null) lines.add(current);
        return lines;
    }

    private static boolean same(LogStore.Row a, LogStore.Row b) {
        return Objects.equals(a.actor(), b.actor()) && Objects.equals(a.cause(), b.cause())
                && a.action() == b.action() && a.block() == b.block() && a.meta() == b.meta() && a.world().equals(b.world());
    }
}
