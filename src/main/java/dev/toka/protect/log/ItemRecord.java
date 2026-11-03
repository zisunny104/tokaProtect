package dev.toka.protect.log;

import java.util.Objects;
import java.util.UUID;

/**
 * One slot of a container that changed. {@code before} and {@code after} are the item stacks as saved tags (null for an empty slot).
 * They live in their own database, apart from the block log, because they are large and are kept for a shorter time.
 */
public record ItemRecord(long time, UUID actor, String actorName, String world, int x, int y, int z, int slot, byte[] before, byte[] after) {
    public ItemRecord {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(actorName, "actorName");
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world is required");
    }
}
