package dev.toka.protect.log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Container slot changes in their own SQLite file. Same habits as the block log: batches from one thread, old rows leave by age
 * and by count in small chunks. Names are kept as text because these rows live for days, not months.
 */
public final class ItemStore implements AutoCloseable {
    /** @param box inclusive bounds; null world means every world, null actor every player */
    public record Query(String world, Integer minX, Integer maxX, Integer minY, Integer maxY, Integer minZ, Integer maxZ, UUID actor, Long since, int limit) {
        public static Query at(String world, int x, int y, int z, int radius, long since, int limit) {
            return new Query(world, x - radius, x + radius, y - radius, y + radius, z - radius, z + radius, null, since, limit);
        }
        public static Query byPlayer(UUID actor, long since, int limit) {
            return new Query(null, null, null, null, null, null, null, actor, since, limit);
        }
    }

    public record Row(long id, long time, UUID actor, String actorName, String world, int x, int y, int z, int slot, byte[] before, byte[] after) {}

    private static final int CHUNK = 20_000;
    private final Connection db;

    public ItemStore(Path file) throws SQLException, IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        db = Sqlite.open(file);
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("CREATE TABLE IF NOT EXISTS slots(id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER NOT NULL, actor TEXT NOT NULL, name TEXT NOT NULL,"
                    + " world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, slot INTEGER NOT NULL, before BLOB, after BLOB)");
            s.execute("CREATE INDEX IF NOT EXISTS slots_pos ON slots(world, x, z, y, t)");
            s.execute("CREATE INDEX IF NOT EXISTS slots_actor ON slots(actor, t)");
            s.execute("CREATE INDEX IF NOT EXISTS slots_time ON slots(t)");
        }
    }

    public synchronized void insert(List<ItemRecord> records) throws SQLException {
        if (records.isEmpty()) return;
        db.setAutoCommit(false);
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO slots(t, actor, name, world, x, y, z, slot, before, after) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            for (ItemRecord r : records) {
                insert.setLong(1, r.time());
                insert.setString(2, r.actor().toString());
                insert.setString(3, r.actorName());
                insert.setString(4, r.world());
                insert.setInt(5, r.x());
                insert.setInt(6, r.y());
                insert.setInt(7, r.z());
                insert.setInt(8, r.slot());
                if (r.before() != null) insert.setBytes(9, r.before()); else insert.setNull(9, Types.BLOB);
                if (r.after() != null) insert.setBytes(10, r.after()); else insert.setNull(10, Types.BLOB);
                insert.addBatch();
            }
            insert.executeBatch();
            db.commit();
        } catch (SQLException | RuntimeException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(true);
        }
    }

    /** Rows matching the query, oldest first. */
    public synchronized List<Row> find(Query q) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT id, t, actor, name, world, x, y, z, slot, before, after FROM slots WHERE t >= ?");
        List<Object> args = new ArrayList<>();
        args.add(q.since() == null ? 0L : q.since());
        if (q.world() != null) { sql.append(" AND world = ?"); args.add(q.world()); }
        if (q.minX() != null) { sql.append(" AND x BETWEEN ? AND ?"); args.add(q.minX()); args.add(q.maxX()); }
        if (q.minY() != null) { sql.append(" AND y BETWEEN ? AND ?"); args.add(q.minY()); args.add(q.maxY()); }
        if (q.minZ() != null) { sql.append(" AND z BETWEEN ? AND ?"); args.add(q.minZ()); args.add(q.maxZ()); }
        if (q.actor() != null) { sql.append(" AND actor = ?"); args.add(q.actor().toString()); }
        sql.append(" ORDER BY id");
        if (q.limit() > 0) sql.append(" LIMIT ").append(q.limit());
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement s = db.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) s.setObject(i + 1, args.get(i));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) rows.add(new Row(r.getLong(1), r.getLong(2), UUID.fromString(r.getString(3)), r.getString(4), r.getString(5),
                        r.getInt(6), r.getInt(7), r.getInt(8), r.getInt(9), r.getBytes(10), r.getBytes(11)));
            }
        }
        return rows;
    }

    /** The player whose logged name matches exactly one account, for typed names. */
    public synchronized Optional<UUID> playerByName(String name) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT DISTINCT actor FROM slots WHERE name = ? COLLATE NOCASE LIMIT 2")) {
            s.setString(1, name);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                UUID found = UUID.fromString(r.getString(1));
                return r.next() ? Optional.empty() : Optional.of(found);
            }
        }
    }

    public synchronized long purge(long cutoffMillis, long maxRows) throws SQLException {
        long removed = 0;
        int changed;
        do {
            try (PreparedStatement s = db.prepareStatement("DELETE FROM slots WHERE id IN (SELECT id FROM slots WHERE t < ? ORDER BY id LIMIT " + CHUNK + ")")) {
                s.setLong(1, cutoffMillis);
                changed = s.executeUpdate();
            }
            removed += changed;
        } while (changed == CHUNK);
        long over = count() - maxRows;
        while (over > 0) {
            try (PreparedStatement s = db.prepareStatement("DELETE FROM slots WHERE id IN (SELECT id FROM slots ORDER BY id LIMIT ?)")) {
                s.setLong(1, Math.min(over, CHUNK));
                changed = s.executeUpdate();
            }
            if (changed == 0) break;
            removed += changed;
            over -= changed;
        }
        return removed;
    }

    public synchronized long count() throws SQLException {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM slots")) { r.next(); return r.getLong(1); }
    }

    @Override public synchronized void close() throws SQLException { db.close(); }
}
