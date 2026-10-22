package dev.toka.protect;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class BreakerTest {
    private final AtomicLong now = new AtomicLong(1000);
    private final Breaker breaker = new Breaker(3, 60_000, now::get);

    @Test public void aFeatureWorksUntilItFailsTooOften() {
        assertTrue(breaker.working(Feature.HAZARDS));
        assertFalse(breaker.failed(Feature.HAZARDS, new IllegalStateException("a")));
        assertFalse(breaker.failed(Feature.HAZARDS, new IllegalStateException("b")));
        assertTrue(breaker.working(Feature.HAZARDS));
        assertTrue(breaker.failed(Feature.HAZARDS, new IllegalStateException("c")));
        assertFalse(breaker.working(Feature.HAZARDS));
    }

    @Test public void oneFeatureTrippingLeavesTheOthersRunning() {
        for (int i = 0; i < 3; i++) breaker.failed(Feature.HAZARDS, new RuntimeException("x"));
        assertFalse(breaker.working(Feature.HAZARDS));
        assertTrue(breaker.working(Feature.EXPLOSIONS));
        assertTrue(breaker.working(Feature.PVP));
    }

    @Test public void failuresOutsideTheWindowDoNotAdd() {
        breaker.failed(Feature.PVP, new RuntimeException("old"));
        breaker.failed(Feature.PVP, new RuntimeException("old"));
        now.addAndGet(61_000);
        assertFalse(breaker.failed(Feature.PVP, new RuntimeException("new")));
        assertTrue(breaker.working(Feature.PVP));
        assertEquals(1, breaker.state(Feature.PVP).recentFailures());
    }

    @Test public void resetBringsATrippedFeatureBack() {
        for (int i = 0; i < 3; i++) breaker.failed(Feature.FARMLAND, new RuntimeException("x"));
        breaker.reset(Feature.FARMLAND);
        assertTrue(breaker.working(Feature.FARMLAND));
        assertEquals(0, breaker.state(Feature.FARMLAND).recentFailures());
    }

    @Test public void theLastErrorIsKeptForTheStatusLine() {
        breaker.failed(Feature.PISTONS, new IllegalStateException("boom"));
        assertEquals("IllegalStateException: boom", breaker.state(Feature.PISTONS).lastError());
    }

    @Test public void modesAllowOnlyWhatTheyPromise() {
        assertFalse(Mode.STANDBY.enforces());
        for (Feature feature : Feature.values()) assertFalse(Mode.STANDBY.allows(feature));
        assertTrue(Mode.MINIMAL.allows(Feature.EXPLOSIONS));
        assertFalse(Mode.MINIMAL.allows(Feature.HAZARDS));
        assertFalse(Mode.MINIMAL.usesRegionsAndClaims());
        for (Feature feature : Feature.values()) assertTrue(Mode.FULL.allows(feature));
        assertTrue(Mode.FULL.usesRegionsAndClaims());
    }

    @Test public void anUnknownModeWordMeansStandby() {
        assertEquals(Mode.STANDBY, Mode.parse("fulll"));
        assertEquals(Mode.STANDBY, Mode.parse(null));
        assertEquals(Mode.FULL, Mode.parse(" FULL "));
        assertEquals(Mode.MINIMAL, Mode.parse("minimal"));
    }
}
