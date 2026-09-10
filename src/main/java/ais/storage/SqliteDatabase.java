package ais.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class SqliteDatabase {

    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private final Path databasePath;

    public static SqliteDatabase defaultDatabase() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData == null || localAppData.isBlank()
                ? Path.of(System.getProperty("user.home"),
                        "AppData", "Local")
                : Path.of(localAppData);
        return new SqliteDatabase(base.resolve("AISLossAnalyzer")
                .resolve("data").resolve("aisloss.db"));
    }

    public SqliteDatabase(Path databasePath) {
        this.databasePath = Objects.requireNonNull(
                databasePath, "databasePath").toAbsolutePath().normalize();
    }

    public Path databasePath() {
        return databasePath;
    }

    public Connection open() throws SQLException {
        createParentDirectory();
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = "
                    + BUSY_TIMEOUT_MILLIS);
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    private void createParentDirectory() throws SQLException {
        Path parent = databasePath.getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException exception) {
            throw new SQLException(
                    "could not create SQLite data directory: " + parent,
                    exception);
        }
    }
}
