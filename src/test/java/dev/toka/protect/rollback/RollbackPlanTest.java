package dev.toka.protect.rollback;

import dev.toka.protect.log.Action;
import dev.toka.protect.log.LogStore;
import org.junit.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class RollbackPlanTest {
    private static final UUID ALEX = UUID.randomUUID();
    private static final int AIR = 0, STONE = 1, GRASS = 2, GLASS = 20;

    private static LogStore.Row row(long t, Action action, int x, int block, int prev) {
        return new LogStore.Row(t, t, ALEX, "Alex", null, action, "w", x, 64, 0, block, 0, prev, 0, null);
    }

    @Test public void aBrokenBlockIsPutBack() {
        var plan = RollbackPlan.build(List.of(row(1, Action.BREAK, 0, STONE, STONE)));
        assertEquals(1, plan.size());
        assertEquals(STONE, plan.get(0).restoreId());
        assertEquals(AIR, plan.get(0).expectedId());
    }

    @Test public void aPlacedBlockGoesAndWhatItReplacedComesBack() {
        var plan = RollbackPlan.build(List.of(row(1, Action.PLACE, 0, STONE, GRASS)));
        assertEquals(GRASS, plan.get(0).restoreId());
        assertEquals(STONE, plan.get(0).expectedId());
    }

    @Test public void severalChangesToOneSpotRestoreTheStateBeforeTheFirst() {
        var plan = RollbackPlan.build(List.of(row(1, Action.PLACE, 0, STONE, GRASS), row(2, Action.BREAK, 0, STONE, STONE), row(3, Action.PLACE, 0, GLASS, AIR)));
        assertEquals(1, plan.size());
        assertEquals(GRASS, plan.get(0).restoreId());
        assertEquals(GLASS, plan.get(0).expectedId());
    }

    @Test public void aSpotThatEndsAsItStartedNeedsNothing() {
        var plan = RollbackPlan.build(List.of(row(1, Action.PLACE, 0, STONE, AIR), row(2, Action.BREAK, 0, STONE, STONE)));
        assertTrue(plan.isEmpty());
    }

    @Test public void differentSpotsAreSeparateEntries() {
        var plan = RollbackPlan.build(List.of(row(1, Action.BREAK, 0, STONE, STONE), row(2, Action.BREAK, 1, GRASS, GRASS), row(3, Action.EXPLODE, 2, GLASS, GLASS)));
        assertEquals(3, plan.size());
    }

    @Test public void scoopedLiquidIsRestoredAndPouredLiquidIsRemoved() {
        var plan = RollbackPlan.build(List.of(row(1, Action.FILL, 0, 8, 8), row(2, Action.EMPTY, 1, 8, AIR)));
        assertEquals(8, plan.get(0).restoreId());
        assertEquals(AIR, plan.get(1).restoreId());
        assertEquals(8, plan.get(1).expectedId());
    }

    @Test public void aBrokenSignComesBackWithItsText() {
        byte[] text = {1, 2, 3};
        var plan = RollbackPlan.build(List.of(new LogStore.Row(1, 1, ALEX, "Alex", null, Action.BREAK, "w", 0, 64, 0, 63, 0, 63, 0, text)));
        assertArrayEquals(text, plan.get(0).restoreNbt());
    }
}
