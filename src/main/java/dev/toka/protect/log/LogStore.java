package dev.toka.protect.log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * The block log in one SQLite file. Worlds, players and causes are stored once and rows refer to them by number, so a row
 * is small and a million of them stay cheap. Writes come in batches from one thread; queries run on the same connection
 * one at a time. Old rows leave by age and by count, oldest first, in small chunks so a clean-up never holds the file long.
 */
public final class LogStore implements AutoCloseable {
    /** What to look for. Null fields are not filtered. The box is inclusive. */
    public record Query(String world, Integer minX, Integer maxX, Integer minY, Integer maxY, Integer minZ, Integer maxZ,
                        UUID actor, Long since, Long until, Action action, int limit, int offset) {
        public static Query at(String world, int x, int y, int z, int radius, Long since, int limit, int offset) {
            return new Query(world, x - radius, x + radius, y - radius, y + radius, z - radius, z + radius, null, since, null, null, limit, offset);
        }
        public static Query byPlayer(UUID actor, Long since, int limit, int offset) {
            return new Query(null, null, null, null, null, null, null, actor, since, null, null, limit, offset);
        }
    }

    public record Row(long id, long time, UUID actor, String actorName, String cause, Action action, String world, int x, int y, int z, int block, int meta, int prevBlock, int prevMeta) {}

    public record Stats(long rows, Long oldest, long fileBytes) {}

    private static final int CHUNK = 20_000;
    private final Path file;
    private final Connection db;
    private final Map<String, Integer> worlds = new HashMap<>();
    private final Map<String, Integer> causes = new HashMap<>();
    private final Map<UUID, Integer> actorIds = new HashMap<>();
    private final Map<UUID, String> actorNames = new HashMap<>();

    public LogStore(Path file) throws SQLException, IOException {
        this.file = file;
        Files.createDirectories(file.toAbsolutePath().getParent());
        db = Sqlite.open(file);
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("CREATE TABLE IF NOT EXISTS worlds(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            s.execute("CREATE TABLE IF NOT EXISTS actors(id INTEGER PRIMARY KEY, uuid TEXT NOT NULL UNIQUE, name TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS causes(id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)");
            s.execute("CREATE TABLE IF NOT EXISTS log(id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER NOT NULL, actor INTEGER, cause INTEGER,"
                    + " action INTEGER NOT NULL, world INTEGER NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,"
                    + " block INTEGER NOT NULL, meta INTEGER NOT NULL, pblock INTEGER NOT NULL DEFAULT 0, pmeta INTEGER NOT NULL DEFAULT 0)");
            addMissingColumns(s);
            s.execute("CREATE INDEX IF NOT EXISTS log_pos ON log(world, x, z, y, t)");
            s.execute("CREATE INDEX IF NOT EXISTS log_actor ON log(actor, t)");
            s.execute("CREATE INDEX IF NOT EXISTS log_time ON log(t)");
            try (ResultSet r = s.executeQuery("SELECT id, name FROM worlds")) { while (r.next()) worlds.put(r.getString(2), r.getInt(1)); }
            try (ResultSet r = s.executeQuery("SELECT id, name FROM causes")) { while (r.next()) causes.put(r.getString(2), r.getInt(1)); }
            try (ResultSet r = s.executeQuery("SELECT id, uuid, name FROM actors")) {
                while (r.next()) { UUID id = UUID.fromString(r.getString(2)); actorIds.put(id, r.getInt(1)); actorNames.put(id, r.getString(3)); }
            }
        }
    }

    /** Databases made before rollback existed lack the previous-block columns; add them, treating old rows as "unknown = same". */
    private void addMissingColumns(Statement s) throws SQLException {
        boolean has = false;
        try (ResultSet r = s.executeQuery("PRAGMA table_info(log)")) { while (r.next()) if (r.getString(2).equals("pblock")) has = true; }
        if (has) return;
        s.execute("ALTER TABLE log ADD COLUMN pblock INTEGER NOT NULL DEFAULT 0");
        s.execute("ALTER TABLE log ADD COLUMN pmeta INTEGER NOT NULL DEFAULT 0");
        s.execute("UPDATE log SET pblock = block, pmeta = meta");
    }

    private int intern(String table, Map<String, Integer> cache, String name) throws SQLException {
        Integer known = cache.get(name);
        if (known != null) return known;
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO " + table + "(name) VALUES(?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, name);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) { keys.next(); cache.put(name, keys.getInt(1)); return keys.getInt(1); }
        }
    }

    private int actor(UUID id, String name) throws SQLException {
        Integer known = actorIds.get(id);
        if (known != null) {
            // Keep the latest name; most batches change nothing.
            if (!name.equals(actorNames.get(id))) {
                try (PreparedStatement update = db.prepareStatement("UPDATE actors SET name=? WHERE id=?")) {
                    update.setString(1, name);
                    update.setInt(2, known);
                    update.executeUpdate();
                }
                actorNames.put(id, name);
            }
            return known;
        }
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO actors(uuid, name) VALUES(?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, id.toString());
            insert.setString(2, name);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) { keys.next(); actorIds.put(id, keys.getInt(1)); actorNames.put(id, name); return keys.getInt(1); }
        }
    }

    /** Writes the records in one transaction; either all are stored or none. */
    public synchronized void insert(List<LogRecord> records) throws SQLException {
        if (records.isEmpty()) return;
        boolean auto = db.getAutoCommit();
        db.setAutoCommit(false);
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO log(t, actor, cause, action, world, x, y, z, block, meta, pblock, pmeta) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (LogRecord r : records) {
                insert.setLong(1, r.time());
                if (r.actor() != null) { insert.setInt(2, actor(r.actor(), r.actorName())); insert.setNull(3, Types.INTEGER); }
                else { insert.setNull(2, Types.INTEGER); insert.setInt(3, intern("causes", causes, r.cause())); }
                insert.setInt(4, r.action().code());
                insert.setInt(5, intern("worlds", worlds, r.world()));
                insert.setInt(6, r.x());
                insert.setInt(7, r.y());
                insert.setInt(8, r.z());
                insert.setInt(9, r.block());
                insert.setInt(10, r.meta());
                insert.setInt(11, r.prevBlock());
                insert.setInt(12, r.prevMeta());
                insert.addBatch();
            }
            insert.executeBatch();
            db.commit();
        } catch (SQLException | RuntimeException failure) {
            db.rollback();
            // Names interned in this batch were rolled back too; forget them so they are written again.
            worlds.clear(); causes.clear(); actorIds.clear(); actorNames.clear();
            reloadNames();
            throw failure;
        } finally {
            db.setAutoCommit(auto);
        }
    }

    private void reloadNames() throws SQLException {
        try (Statement s = db.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT id, name FROM worlds")) { while (r.next()) worlds.put(r.getString(2), r.getInt(1)); }
            try (ResultSet r = s.executeQuery("SELECT id, name FROM causes")) { while (r.next()) causes.put(r.getString(2), r.getInt(1)); }
            try (ResultSet r = s.executeQuery("SELECT id, uuid, name FROM actors")) {
                while (r.next()) { UUID id = UUID.fromString(r.getString(2)); actorIds.put(id, r.getInt(1)); actorNames.put(id, r.getString(3)); }
            }
        }
    }

    /** Newest first. */
    public synchronized List<Row> find(Query q) throws SQLException { return find(q, false); }

    /** Oldest first when {@code ascending}, which is the order a rollback replays. */
    public synchronized List<Row> find(Query q, boolean ascending) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT l.id, l.t, a.uuid, a.name, c.name, l.action, w.name, l.x, l.y, l.z, l.block, l.meta, l.pblock, l.pmeta FROM log l "
                + "JOIN worlds w ON w.id = l.world LEFT JOIN actors a ON a.id = l.actor LEFT JOIN causes c ON c.id = l.cause WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q.world() != null) {
            Integer world = worlds.get(q.world());
            if (world == null) return List.of();
            sql.append(" AND l.world = ?");
            args.add(world);
        }
        range(sql, args, "l.x", q.minX(), q.maxX());
        range(sql, args, "l.y", q.minY(), q.maxY());
        range(sql, args, "l.z", q.minZ(), q.maxZ());
        if (q.actor() != null) {
            Integer actor = actorIds.get(q.actor());
            if (actor == null) return List.of();
            sql.append(" AND l.actor = ?");
            args.add(actor);
        }
        if (q.since() != null) { sql.append(" AND l.t >= ?"); args.add(q.since()); }
        if (q.until() != null) { sql.append(" AND l.t <= ?"); args.add(q.until()); }
        if (q.action() != null) { sql.append(" AND l.action = ?"); args.add(q.action().code()); }
        sql.append(ascending ? " ORDER BY l.t ASC, l.id ASC LIMIT ? OFFSET ?" : " ORDER BY l.t DESC, l.id DESC LIMIT ? OFFSET ?");
        args.add(Math.max(1, q.limit()));
        args.add(Math.max(0, q.offset()));
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement s = db.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) s.setObject(i + 1, args.get(i));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String uuid = r.getString(3);
                    rows.add(new Row(r.getLong(1), r.getLong(2), uuid == null ? null : UUID.fromString(uuid), r.getString(4), r.getString(5),
                            Action.byCode(r.getInt(6)), r.getString(7), r.getInt(8), r.getInt(9), r.getInt(10), r.getInt(11), r.getInt(12), r.getInt(13), r.getInt(14)));
                }
            }
        }
        return rows;
    }

    private static void range(StringBuilder sql, List<Object> args, String column, Integer min, Integer max) {
        if (min != null) { sql.append(" AND ").append(column).append(" >= ?"); args.add(min); }
        if (max != null) { sql.append(" AND ").append(column).append(" <= ?"); args.add(max); }
    }

    /** The player with this last known name (any case), if exactly one. */
    public synchronized Optional<UUID> playerByName(String name) {
        UUID found = null;
        for (var entry : actorNames.entrySet()) {
            if (!entry.getValue().equalsIgnoreCase(name)) continue;
            if (found != null) return Optional.empty();
            found = entry.getKey();
        }
        return Optional.ofNullable(found);
    }

    /**
     * Removes rows older than the cutoff, then the oldest rows beyond the limit. Works in chunks; returns how many went.
     * The file does not shrink, but the space is reused, so it stays near the size of the limit.
     */
    public synchronized long purge(long cutoffMillis, long maxRows) throws SQLException {
        long removed = 0;
        int changed;
        do {
            try (PreparedStatement s = db.prepareStatement("DELETE FROM log WHERE id IN (SELECT id FROM log WHERE t < ? ORDER BY id LIMIT " + CHUNK + ")")) {
                s.setLong(1, cutoffMillis);
                changed = s.executeUpdate();
            }
            removed += changed;
        } while (changed == CHUNK);
        long over = count() - maxRows;
        while (over > 0) {
            try (PreparedStatement s = db.prepareStatement("DELETE FROM log WHERE id IN (SELECT id FROM log ORDER BY id LIMIT ?)")) {
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
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM log")) { r.next(); return r.getLong(1); }
    }

    public synchronized Stats stats() throws SQLException {
        Long oldest;
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT MIN(t) FROM log")) { r.next(); long t = r.getLong(1); oldest = r.wasNull() ? null : t; }
        long size = 0;
        try { size = Files.size(file); } catch (IOException ignored) { /* size is informational */ }
        return new Stats(count(), oldest, size);
    }

    @Override public synchronized void close() throws SQLException { db.close(); }
}
