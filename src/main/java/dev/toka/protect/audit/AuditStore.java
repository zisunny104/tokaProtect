package dev.toka.protect.audit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The command audit in its own SQLite file, so its size and retention are independent of the block log. */
public final class AuditStore implements AutoCloseable {
    public record Row(long id, long time, UUID actor, String name, AuditRecord.Kind kind, String text, String world, Integer x, Integer y, Integer z) {}

    private static final int CHUNK = 20_000;
    private final Connection db;

    public AuditStore(Path file) throws SQLException, IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        db = dev.toka.protect.log.SqliteAccess.open(file);
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, t INTEGER NOT NULL, actor TEXT, name TEXT NOT NULL,"
                    + " kind INTEGER NOT NULL, text TEXT NOT NULL, world TEXT, x INTEGER, y INTEGER, z INTEGER)");
            s.execute("CREATE INDEX IF NOT EXISTS audit_time ON audit(t)");
            s.execute("CREATE INDEX IF NOT EXISTS audit_actor ON audit(actor, t)");
        }
    }

    public synchronized void insert(List<AuditRecord> records) throws SQLException {
        if (records.isEmpty()) return;
        boolean auto = db.getAutoCommit();
        db.setAutoCommit(false);
        try (PreparedStatement s = db.prepareStatement("INSERT INTO audit(t, actor, name, kind, text, world, x, y, z) VALUES(?,?,?,?,?,?,?,?,?)")) {
            for (AuditRecord r : records) {
                s.setLong(1, r.time());
                s.setString(2, r.actor() == null ? null : r.actor().toString());
                s.setString(3, r.name());
                s.setInt(4, r.kind().code());
                s.setString(5, r.text());
                s.setString(6, r.world());
                if (r.world() == null) { s.setNull(7, Types.INTEGER); s.setNull(8, Types.INTEGER); s.setNull(9, Types.INTEGER); }
                else { s.setInt(7, r.x()); s.setInt(8, r.y()); s.setInt(9, r.z()); }
                s.addBatch();
            }
            s.executeBatch();
            db.commit();
        } catch (SQLException | RuntimeException failure) {
            db.rollback();
            throw failure;
        } finally {
            db.setAutoCommit(auto);
        }
    }

    /** Newest first. A null actor means everyone. */
    public synchronized List<Row> find(UUID actor, Long since, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT id, t, actor, name, kind, text, world, x, y, z FROM audit WHERE 1=1");
        if (actor != null) sql.append(" AND actor = ?");
        if (since != null) sql.append(" AND t >= ?");
        sql.append(" ORDER BY t DESC, id DESC LIMIT ?");
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement s = db.prepareStatement(sql.toString())) {
            int at = 1;
            if (actor != null) s.setString(at++, actor.toString());
            if (since != null) s.setLong(at++, since);
            s.setInt(at, Math.max(1, limit));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String id = r.getString(3);
                    String world = r.getString(7);
                    rows.add(new Row(r.getLong(1), r.getLong(2), id == null ? null : UUID.fromString(id), r.getString(4), AuditRecord.Kind.byCode(r.getInt(5)),
                            r.getString(6), world, world == null ? null : r.getInt(8), world == null ? null : r.getInt(9), world == null ? null : r.getInt(10)));
                }
            }
        }
        return rows;
    }

    /** Removes rows older than the cutoff, then the oldest beyond the limit, in chunks. */
    public synchronized long purge(long cutoffMillis, long maxRows) throws SQLException {
        long removed = 0;
        int changed;
        do {
            try (PreparedStatement s = db.prepareStatement("DELETE FROM audit WHERE id IN (SELECT id FROM audit WHERE t < ? ORDER BY id LIMIT " + CHUNK + ")")) {
                s.setLong(1, cutoffMillis);
                changed = s.executeUpdate();
            }
            removed += changed;
        } while (changed == CHUNK);
        long over = count() - maxRows;
        while (over > 0) {
            try (PreparedStatement s = db.prepareStatement("DELETE FROM audit WHERE id IN (SELECT id FROM audit ORDER BY id LIMIT ?)")) {
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
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM audit")) { r.next(); return r.getLong(1); }
    }

    @Override public synchronized void close() throws SQLException { db.close(); }
}
