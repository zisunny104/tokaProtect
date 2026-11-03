package dev.toka.protect.rollback;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.toka.protect.log.ItemRecord;
import dev.toka.protect.log.ItemStore;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class ItemPlanTest {
    private static final byte[] DIAMOND = {1}, STONE = {2};

    private static ItemStore.Row row(long id, int slot, byte[] before, byte[] after) {
        return new ItemStore.Row(id, id, UUID.randomUUID(), "thief", "world", 1, 2, 3, slot, before, after);
    }

    @Test public void aStolenStackIsPutBackAndExpectedToStayEmpty() {
        var plan = ItemPlan.build(List.of(row(1, 0, DIAMOND, null)));
        assertEquals(1, plan.size());
        assertArrayEquals(DIAMOND, plan.get(0).restore());
        assertNull(plan.get(0).expected());
    }

    @Test public void severalChangesToOneSlotKeepTheFirstBeforeAndTheLastAfter() {
        var plan = ItemPlan.build(List.of(row(1, 0, DIAMOND, null), row(2, 0, null, STONE)));
        assertEquals(1, plan.size());
        assertArrayEquals(DIAMOND, plan.get(0).restore());
        assertArrayEquals(STONE, plan.get(0).expected());
    }

    @Test public void aSlotThatEndsAsItStartedNeedsNothing() {
        assertTrue(ItemPlan.build(List.of(row(1, 0, DIAMOND, null), row(2, 0, null, DIAMOND))).isEmpty());
    }

    @Test public void differentSlotsAreSeparateEntries() {
        assertEquals(2, ItemPlan.build(List.of(row(1, 0, DIAMOND, null), row(2, 1, STONE, null))).size());
    }

    @Test public void storeKeepsRowsAndFindsThemByPlayerAndBox() throws Exception {
        var file = Files.createTempDirectory("items").resolve("items.db");
        try (var store = new ItemStore(file)) {
            UUID who = UUID.randomUUID();
            long now = System.currentTimeMillis();
            store.insert(List.of(new ItemRecord(now, who, "Alex", "world", 5, 64, 5, 3, DIAMOND, null),
                    new ItemRecord(now, UUID.randomUUID(), "Sam", "world", 100, 64, 100, 0, null, STONE)));
            assertEquals(1, store.find(ItemStore.Query.byPlayer(who, now - 1000, 0)).size());
            assertEquals(1, store.find(ItemStore.Query.at("world", 5, 64, 5, 2, now - 1000, 0)).size());
            assertEquals(who, store.playerByName("alex").orElseThrow());
            assertEquals(0, store.purge(now - 1, 10));
            assertEquals(2, store.purge(now + 1, 10));
            assertEquals(0, store.count());
        }
    }
}
