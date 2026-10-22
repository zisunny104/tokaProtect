package dev.toka.protect.log;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens SQLite connections. The log is far too big for the document store, so it has a real table. The driver is kept
 * in its own class loader (when the jar carries one) so it cannot clash with another plugin's copy.
 */
final class Sqlite {
    private static final Driver DRIVER = load();

    private Sqlite() {}

    private static Driver load() {
        try {
            URL resource = Sqlite.class.getResource("/META-INF/toka/sqlite-jdbc.jar");
            if (resource == null) return (Driver) Class.forName("org.sqlite.JDBC", true, Sqlite.class.getClassLoader()).getDeclaredConstructor().newInstance();
            Path jar = Files.createTempFile("toka-protect-sqlite-", ".jar");
            jar.toFile().deleteOnExit();
            try (InputStream input = resource.openStream()) { Files.copy(input, jar, StandardCopyOption.REPLACE_EXISTING); }
            var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            return (Driver) Class.forName("org.sqlite.JDBC", true, loader).getDeclaredConstructor().newInstance();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    static Connection open(Path file) throws SQLException {
        return DRIVER.connect("jdbc:sqlite:" + file, new Properties());
    }
}
