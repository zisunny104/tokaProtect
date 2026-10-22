package dev.toka.protect.log;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class LogStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final UUID ALEX = UUID.randomUUID(), SAM = UUID.randomUUID();

    private static LogRecord place(long t, UUID who, String name, int x, int y, int z) {
        return LogRecord.byPlayer(t, who, name, Action.PLACE, "survival", x, y, z, 1, 0);
    }

    @Test public void recordsComeBackNewestFirstWithTheirNames() throws Exception {
        try (var store = new LogStore(temporary.newFolder().toPath().resolve("log.db"))) {
            store.insert(List.of(place(100, ALEX, "Alex", 1, 64, 1), place(200, SAM, "Sam", 1, 64, 1),
                    LogRecord.byCause(300, "tnt", Action.EXPLODE, "survival", 1, 64, 1, 2, 0, null)));
            var rows = store.find(LogStore.Query.at("survival", 1, 64, 1, 0, null, 10, 0));
            assertEquals(3, rows.size());
            assertEquals("tnt", rows.get(0).cause());
            assertNull(rows.get(0).actor());
            assertEquals("Sam", rows.get(1).actorName());
            assertEquals(ALEX, rows.get(2).actor());
        }
    }

    @Test public void aBoxFindsOnlyWhatLiesInsideIt() throws Exception {
        try (var store = new LogStore(temporary.newFolder().toPath().resolve("log.db"))) {
            store.insert(List.of(place(1, ALEX, "Alex", 0, 64, 0), place(2, ALEX, "Alex", 10, 64, 0), place(3, ALEX, "Alex", 100, 64, 0)));
            assertEquals(2, store.find(LogStore.Query.at("survival", 5, 64, 0, 6, null, 10, 0)).size());
            assertTrue(store.find(LogStore.Query.at("nether", 0, 64, 0, 50, null, 10, 0)).isEmpty());
        }
    }

    @Test public void queriesCanFilterByPlayerAndTimeAndPage() throws Exception {
        try (var store = new LogStore(temporary.newFolder().toPath().resolve("log.db"))) {
            for (int i = 0; i < 25; i++) store.insert(List.of(place(1000 + i, i % 2 == 0 ? ALEX : SAM, i % 2 == 0 ? "Alex" : "Sam", i, 64, 0)));
            assertEquals(13, store.find(LogStore.Query.byPlayer(ALEX, null, 100, 0)).size());
            assertEquals(3, store.find(LogStore.Query.byPlayer(ALEX, 1020L, 100, 0)).size());
            assertEquals(10, store.find(LogStore.Query.byPlayer(ALEX, null, 10, 0)).size());
            assertEquals(3, store.find(LogStore.Query.byPlayer(ALEX, null, 10, 10)).size());
        }
    }

    @Test public void purgeRemovesOldRowsThenTheOldestBeyondTheLimit() throws Exception {
        try (var store = new LogStore(temporary.newFolder().toPath().resolve("log.db"))) {
            for (int i = 0; i < 10; i++) store.insert(List.of(place(i * 100L, ALEX, "Alex", i, 64, 0)));
            assertEquals(3, store.purge(300, 1000));
            assertEquals(7, store.count());
            assertEquals(4, store.purge(0, 3));
            assertEquals(3, store.count());
            var rows = store.find(LogStore.Query.byPlayer(ALEX, null, 10, 0));
            assertEquals(900, rows.get(0).time());
            assertEquals(700, rows.get(2).time());
        }
    }

    @Test public void dataAndNamesSurviveReopening() throws Exception {
        var file = temporary.newFolder().toPath().resolve("log.db");
        try (var store = new LogStore(file)) { store.insert(List.of(place(1, ALEX, "Alex", 0, 64, 0))); }
        try (var store = new LogStore(file)) {
            assertEquals(ALEX, store.playerByName("alex").orElseThrow());
            store.insert(List.of(place(2, ALEX, "AlexRenamed", 1, 64, 0)));
            assertEquals(2, store.find(LogStore.Query.byPlayer(ALEX, null, 10, 0)).size());
            assertTrue(store.playerByName("Alex").isEmpty());
            assertEquals(ALEX, store.playerByName("AlexRenamed").orElseThrow());
        }
    }

    @Test public void twoPlayersWithTheSameNameAreNotGuessed() throws Exception {
        try (var store = new LogStore(temporary.newFolder().toPath().resolve("log.db"))) {
            store.insert(List.of(place(1, ALEX, "Same", 0, 64, 0), place(2, SAM, "Same", 1, 64, 0)));
            assertTrue(store.playerByName("same").isEmpty());
        }
    }

    @Test public void statsReportTheSizeAndTheOldestRow() throws Exception {
        try (var store = new LogStore(temporary.newFolder().toPath().resolve("log.db"))) {
            assertNull(store.stats().oldest());
            store.insert(List.of(place(500, ALEX, "Alex", 0, 64, 0), place(900, ALEX, "Alex", 1, 64, 0)));
            var stats = store.stats();
            assertEquals(2, stats.rows());
            assertEquals(500L, (long) stats.oldest());
            assertTrue(stats.fileBytes() > 0);
        }
    }

    @Test(expected = IllegalArgumentException.class) public void aChangeWithNeitherPlayerNorCauseIsRejected() {
        new LogRecord(1, null, null, null, Action.BREAK, "w", 0, 0, 0, 1, 0, 1, 0);
    }
}
