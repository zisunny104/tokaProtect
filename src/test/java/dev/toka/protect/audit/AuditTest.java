package dev.toka.protect.audit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

public class AuditTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Set<String> HIDDEN = Set.of("login", "register", "dc");
    private static final UUID ALEX = UUID.randomUUID(), SAM = UUID.randomUUID();

    @Test public void argumentsOfLoginStyleCommandsAreHidden() {
        assertEquals("/login ****", AuditMask.mask("/login hunter2", HIDDEN, 100));
        assertEquals("/LOGIN ****", AuditMask.mask("/LOGIN hunter2", HIDDEN, 100));
        assertEquals("/plugin:login ****", AuditMask.mask("/plugin:login hunter2", HIDDEN, 100));
        assertEquals("/dc ****", AuditMask.mask("/dc bind 123456", HIDDEN, 100));
    }

    @Test public void otherCommandsAreKeptAsTyped() {
        assertEquals("/home set base", AuditMask.mask("/home set base", HIDDEN, 100));
        assertEquals("/login", AuditMask.mask("/login", HIDDEN, 100));
    }

    @Test public void hiddenCharactersAreRemovedAndLongTextIsCut() {
        assertEquals("/say a b", AuditMask.mask("/say a\nb", HIDDEN, 100));
        assertEquals("/say aaaa…", AuditMask.mask("/say aaaaaaaa", HIDDEN, 9));
        assertEquals("", AuditMask.mask(null, HIDDEN, 10));
    }

    private static AuditRecord command(long t, UUID who, String name, String text) {
        return new AuditRecord(t, who, name, AuditRecord.Kind.COMMAND, text, "w", 1, 2, 3);
    }

    @Test public void recordsComeBackNewestFirstAndCanBeFiltered() throws Exception {
        try (var store = new AuditStore(temporary.newFolder().toPath().resolve("a.db"))) {
            store.insert(List.of(command(100, ALEX, "Alex", "/a"), command(200, SAM, "Sam", "/b"),
                    new AuditRecord(300, null, "Server", AuditRecord.Kind.CONSOLE, "stop", null, 0, 0, 0)));
            var all = store.find(null, null, 10);
            assertEquals(3, all.size());
            assertEquals("stop", all.get(0).text());
            assertNull(all.get(0).world());
            assertEquals(1, store.find(ALEX, null, 10).size());
            assertEquals(2, store.find(null, 150L, 10).size());
            assertEquals(1, store.find(null, null, 1).size());
        }
    }

    @Test public void purgeRemovesOldRowsThenTheOldestBeyondTheLimit() throws Exception {
        try (var store = new AuditStore(temporary.newFolder().toPath().resolve("a.db"))) {
            for (int i = 0; i < 10; i++) store.insert(List.of(command(i * 100L, ALEX, "Alex", "/c" + i)));
            assertEquals(3, store.purge(300, 1000));
            assertEquals(4, store.purge(0, 3));
            assertEquals(3, store.count());
            assertEquals("/c9", store.find(null, null, 1).get(0).text());
        }
    }

    @Test(expected = IllegalArgumentException.class) public void aRecordNeedsAName() {
        new AuditRecord(1, null, " ", AuditRecord.Kind.CONSOLE, "x", null, 0, 0, 0);
    }
}
