package dev.toka.protect;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Keeps one broken feature from hurting the server. A feature that fails {@code limit} times within {@code windowMillis}
 * is switched off until someone resets it, so a bug in a hot event handler cannot flood the log or slow every tick.
 * The other features keep running.
 */
public final class Breaker {
    /** What is known about one feature. */
    public record State(boolean tripped, int recentFailures, String lastError) {}

    private final int limit;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<Feature, int[]> counts = new EnumMap<>(Feature.class);
    private final Map<Feature, long[]> windowStart = new EnumMap<>(Feature.class);
    private final Map<Feature, String> lastError = new EnumMap<>(Feature.class);
    private final Map<Feature, Boolean> tripped = new EnumMap<>(Feature.class);

    public Breaker(int limit, long windowMillis, LongSupplier clock) {
        if (limit < 1 || windowMillis < 1) throw new IllegalArgumentException("limit and window must be positive");
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    public synchronized boolean working(Feature feature) { return !tripped.getOrDefault(feature, false); }

    /** Records a failure; returns true when this failure switched the feature off. */
    public synchronized boolean failed(Feature feature, Throwable error) {
        long now = clock.getAsLong();
        long[] start = windowStart.computeIfAbsent(feature, f -> new long[]{now});
        int[] count = counts.computeIfAbsent(feature, f -> new int[1]);
        if (now - start[0] > windowMillis) { start[0] = now; count[0] = 0; }
        count[0]++;
        lastError.put(feature, error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage()));
        if (count[0] >= limit && !tripped.getOrDefault(feature, false)) {
            tripped.put(feature, true);
            return true;
        }
        return false;
    }

    public synchronized void reset(Feature feature) {
        tripped.remove(feature);
        counts.remove(feature);
        windowStart.remove(feature);
        lastError.remove(feature);
    }

    public synchronized State state(Feature feature) {
        return new State(tripped.getOrDefault(feature, false), counts.getOrDefault(feature, new int[1])[0], lastError.get(feature));
    }
}
