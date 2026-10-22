package dev.toka.protect.log;

import org.junit.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class LogFormatTest {
    private static final UUID ALEX = UUID.randomUUID(), SAM = UUID.randomUUID();

    private static LogStore.Row row(long time, UUID who, Action action, int block) {
        return new LogStore.Row(time, time, who, "n", null, action, "w", 0, 0, 0, block, 0, block, 0, null);
    }

    @Test public void aRunOfTheSameThingBecomesOneLine() {
        var lines = LogFormat.collapse(List.of(row(5000, ALEX, Action.PLACE, 1), row(4000, ALEX, Action.PLACE, 1), row(3000, ALEX, Action.PLACE, 1)), 60_000);
        assertEquals(1, lines.size());
        assertEquals(3, lines.get(0).count());
        assertEquals(5000, lines.get(0).newest().time());
        assertEquals(3000, lines.get(0).oldestTime());
    }

    @Test public void differentPeopleActionsOrBlocksStaySeparate() {
        var lines = LogFormat.collapse(List.of(row(5000, ALEX, Action.PLACE, 1), row(4900, SAM, Action.PLACE, 1),
                row(4800, SAM, Action.BREAK, 1), row(4700, SAM, Action.BREAK, 2)), 60_000);
        assertEquals(4, lines.size());
    }

    @Test public void aLongGapEndsTheRun() {
        var lines = LogFormat.collapse(List.of(row(100_000, ALEX, Action.PLACE, 1), row(10_000, ALEX, Action.PLACE, 1)), 60_000);
        assertEquals(2, lines.size());
    }

    @Test public void timeSpansParseAndRejectNonsense() {
        assertEquals(1_800_000L, TimeSpan.parseMillis("30m"));
        assertEquals(7_200_000L, TimeSpan.parseMillis(" 2H "));
        assertEquals(604_800_000L, TimeSpan.parseMillis("1w"));
        assertThrows(IllegalArgumentException.class, () -> TimeSpan.parseMillis("soon"));
        assertThrows(IllegalArgumentException.class, () -> TimeSpan.parseMillis("-5m"));
    }
}
