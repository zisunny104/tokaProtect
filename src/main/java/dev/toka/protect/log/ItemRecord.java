package dev.toka.protect.log;

import java.util.Objects;
import java.util.UUID;

/**
 * One slot of a container that changed. {@code before} and {@code after} are the item stacks as saved tags (null for an empty slot).
 * They live in their own database, apart from the block log, because they are large and are kept for a shorter time.
 */
public record ItemRecord(long time, UUID actor, String actorName, String world, int x, int y, int z, int slot, byte[] before, byte[] after) {
    /** Not a real slot: the container here was broken and its stacks fell to the ground. */
    public static final int SLOT_SPILL = -2;
    /** Not a real slot: this player picked up a stack (in {@code before}) that fell from the container here. */
    public static final int SLOT_PICKUP = -1;
    /** The actor of changes without a player, such as an explosion. */
    public static final UUID NOBODY = new UUID(0L, 0L);
    public ItemRecord {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(actorName, "actorName");
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world is required");
    }
}
