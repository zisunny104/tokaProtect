package dev.toka.protect.log;

import java.util.Objects;
import java.util.UUID;

/**
 * One block change. The person is a player's UUID with the name they had then, or, for things like explosions with no
 * known player, a short cause such as "tnt". Names and worlds are kept as text here and interned when stored.
 * The block is the one involved (placed, broken, poured...); the previous block is what stood there before, which is what a
 * rollback puts back. For a break, scoop or explosion the two are the same. {@code prevNbt} is the tile data (sign text and
 * the like) the previous block carried, or null.
 */
public record LogRecord(long time, UUID actor, String actorName, String cause, Action action, String world, int x, int y, int z,
                        int block, int meta, int prevBlock, int prevMeta, byte[] prevNbt) {
    public LogRecord {
        Objects.requireNonNull(action, "action");
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world is required");
        if (actor != null && (actorName == null || actorName.isBlank())) throw new IllegalArgumentException("a player needs a name");
        if (actor == null && (cause == null || cause.isBlank())) throw new IllegalArgumentException("a change without a player needs a cause");
    }

    public LogRecord(long time, UUID actor, String actorName, String cause, Action action, String world, int x, int y, int z, int block, int meta, int prevBlock, int prevMeta) {
        this(time, actor, actorName, cause, action, world, x, y, z, block, meta, prevBlock, prevMeta, null);
    }

    public static LogRecord byPlayer(long time, UUID actor, String name, Action action, String world, int x, int y, int z, int block, int meta) {
        return new LogRecord(time, actor, name, null, action, world, x, y, z, block, meta, block, meta);
    }

    public static LogRecord byPlayer(long time, UUID actor, String name, Action action, String world, int x, int y, int z, int block, int meta, int prevBlock, int prevMeta, byte[] prevNbt) {
        return new LogRecord(time, actor, name, null, action, world, x, y, z, block, meta, prevBlock, prevMeta, prevNbt);
    }

    public static LogRecord byCause(long time, String cause, Action action, String world, int x, int y, int z, int block, int meta, byte[] prevNbt) {
        return new LogRecord(time, null, null, cause, action, world, x, y, z, block, meta, block, meta, prevNbt);
    }
}
