package dev.toka.protect.log;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** The one way into SQLite for every part of tokaProtect, so the driver is loaded once. */
public final class SqliteAccess {
    private SqliteAccess() {}

    public static Connection open(Path file) throws SQLException { return Sqlite.open(file); }
}
