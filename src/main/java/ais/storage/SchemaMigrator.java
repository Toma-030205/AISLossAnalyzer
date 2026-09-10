package ais.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class SchemaMigrator {

    public static final int CURRENT_VERSION = 1;

    private static final List<String> VERSION_ONE_STATEMENTS = List.of(
            """
            CREATE TABLE receiver_profile (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                antenna_height_meters REAL,
                antenna_model TEXT,
                receiver_model TEXT,
                valid_from TEXT NOT NULL,
                valid_to TEXT,
                notes TEXT
            )
            """,
            """
            CREATE TABLE analysis_profile (
                id TEXT PRIMARY KEY,
                rules_version TEXT NOT NULL,
                freshness_multiplier REAL NOT NULL,
                grid_size_meters INTEGER NOT NULL,
                grid_origin_easting REAL NOT NULL,
                grid_origin_northing REAL NOT NULL,
                distance_bin_kilometers INTEGER NOT NULL,
                maximum_distance_kilometers INTEGER NOT NULL,
                minimum_expected_count INTEGER NOT NULL,
                minimum_distinct_vessels INTEGER NOT NULL,
                track_gap_duration TEXT NOT NULL,
                maximum_distance_jump_kilometers REAL NOT NULL,
                class_b_cs_high_speed_interval_seconds REAL NOT NULL
            )
            """,
            """
            CREATE TABLE analysis_run (
                id TEXT PRIMARY KEY,
                source_mode TEXT NOT NULL CHECK (
                    source_mode IN ('HISTORICAL', 'LIVE')),
                target_date TEXT,
                input_name TEXT NOT NULL,
                input_sha256 TEXT,
                input_uncompressed_bytes INTEGER,
                input_file_count INTEGER,
                receiver_profile_id TEXT NOT NULL REFERENCES receiver_profile(id),
                analysis_profile_id TEXT NOT NULL REFERENCES analysis_profile(id),
                started_at TEXT NOT NULL,
                ended_at TEXT,
                status TEXT NOT NULL CHECK (
                    status IN ('PENDING', 'COMPLETE', 'SUPERSEDED')),
                active INTEGER NOT NULL DEFAULT 0 CHECK (active IN (0, 1)),
                accepted_interval_count INTEGER NOT NULL DEFAULT 0,
                estimated_missing_count INTEGER NOT NULL DEFAULT 0,
                metadata_update_count INTEGER NOT NULL DEFAULT 0,
                outside_distance_range_count INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                CHECK (
                    (source_mode = 'HISTORICAL'
                        AND target_date IS NOT NULL
                        AND input_sha256 IS NOT NULL
                        AND input_uncompressed_bytes IS NOT NULL
                        AND input_file_count IS NOT NULL)
                    OR
                    (source_mode = 'LIVE' AND target_date IS NULL)
                )
            )
            """,
            """
            CREATE UNIQUE INDEX active_historical_input
            ON analysis_run (
                target_date, input_sha256, input_uncompressed_bytes,
                input_file_count, receiver_profile_id, analysis_profile_id)
            WHERE source_mode = 'HISTORICAL'
                AND active = 1 AND status = 'COMPLETE'
            """,
            """
            CREATE TABLE cell_metric_5m (
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id)
                    ON DELETE CASCADE,
                bucket_start TEXT NOT NULL,
                utm_zone INTEGER NOT NULL,
                grid_column INTEGER NOT NULL,
                grid_row INTEGER NOT NULL,
                vessel_class TEXT NOT NULL CHECK (
                    vessel_class IN ('CLASS_A', 'CLASS_B')),
                observed_count INTEGER NOT NULL,
                missing_count INTEGER NOT NULL,
                observed_seconds REAL NOT NULL,
                stale_seconds REAL NOT NULL,
                PRIMARY KEY (
                    analysis_run_id, bucket_start, utm_zone,
                    grid_column, grid_row, vessel_class)
            )
            """,
            """
            CREATE TABLE distance_metric_5m (
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id)
                    ON DELETE CASCADE,
                bucket_start TEXT NOT NULL,
                distance_band_index INTEGER NOT NULL,
                lower_kilometers REAL NOT NULL,
                upper_kilometers REAL NOT NULL,
                vessel_class TEXT NOT NULL CHECK (
                    vessel_class IN ('CLASS_A', 'CLASS_B')),
                observed_count INTEGER NOT NULL,
                missing_count INTEGER NOT NULL,
                observed_seconds REAL NOT NULL,
                stale_seconds REAL NOT NULL,
                PRIMARY KEY (
                    analysis_run_id, bucket_start,
                    distance_band_index, vessel_class)
            )
            """,
            """
            CREATE TABLE cell_vessel_presence_5m (
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id)
                    ON DELETE CASCADE,
                bucket_start TEXT NOT NULL,
                utm_zone INTEGER NOT NULL,
                grid_column INTEGER NOT NULL,
                grid_row INTEGER NOT NULL,
                vessel_class TEXT NOT NULL,
                mmsi INTEGER NOT NULL,
                PRIMARY KEY (
                    analysis_run_id, bucket_start, utm_zone,
                    grid_column, grid_row, vessel_class, mmsi)
            )
            """,
            """
            CREATE TABLE distance_vessel_presence_5m (
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id)
                    ON DELETE CASCADE,
                bucket_start TEXT NOT NULL,
                distance_band_index INTEGER NOT NULL,
                vessel_class TEXT NOT NULL,
                mmsi INTEGER NOT NULL,
                PRIMARY KEY (
                    analysis_run_id, bucket_start,
                    distance_band_index, vessel_class, mmsi)
            )
            """,
            """
            CREATE TABLE cell_vessel_presence_day (
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id)
                    ON DELETE CASCADE,
                observed_date TEXT NOT NULL,
                utm_zone INTEGER NOT NULL,
                grid_column INTEGER NOT NULL,
                grid_row INTEGER NOT NULL,
                vessel_class TEXT NOT NULL,
                mmsi INTEGER NOT NULL,
                PRIMARY KEY (
                    analysis_run_id, observed_date, utm_zone,
                    grid_column, grid_row, vessel_class, mmsi)
            )
            """,
            """
            CREATE TABLE distance_vessel_presence_day (
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id)
                    ON DELETE CASCADE,
                observed_date TEXT NOT NULL,
                distance_band_index INTEGER NOT NULL,
                vessel_class TEXT NOT NULL,
                mmsi INTEGER NOT NULL,
                PRIMARY KEY (
                    analysis_run_id, observed_date,
                    distance_band_index, vessel_class, mmsi)
            )
            """,
            """
            CREATE TABLE vessel_metadata_history (
                mmsi INTEGER NOT NULL,
                valid_from TEXT NOT NULL,
                valid_to TEXT,
                source_message_type INTEGER NOT NULL CHECK (
                    source_message_type IN (5, 24)),
                vessel_class TEXT NOT NULL,
                imo INTEGER,
                call_sign TEXT,
                vessel_name TEXT,
                ship_type INTEGER,
                destination TEXT,
                ship_length_meters INTEGER,
                PRIMARY KEY (mmsi, valid_from)
            )
            """,
            """
            CREATE INDEX vessel_metadata_effective_time
            ON vessel_metadata_history (mmsi, valid_from, valid_to)
            """,
            """
            CREATE TABLE analysis_diagnostic_summary (
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id)
                    ON DELETE CASCADE,
                diagnostic_code TEXT NOT NULL,
                occurrence_count INTEGER NOT NULL,
                first_occurred_at TEXT,
                last_occurred_at TEXT,
                PRIMARY KEY (analysis_run_id, diagnostic_code)
            )
            """,
            """
            CREATE TABLE analysis_exclusion_period (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id)
                    ON DELETE CASCADE,
                started_at TEXT NOT NULL,
                ended_at TEXT NOT NULL,
                reason TEXT NOT NULL,
                affected_count INTEGER NOT NULL
            )
            """,
            """
            CREATE INDEX cell_metric_time
            ON cell_metric_5m (analysis_run_id, bucket_start)
            """,
            """
            CREATE INDEX distance_metric_time
            ON distance_metric_5m (analysis_run_id, bucket_start)
            """);

    private final SqliteDatabase database;
    private final TransactionRunner transactions;

    public SchemaMigrator(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transactions = new TransactionRunner(database);
    }

    public void migrate() {
        enableWriteAheadLog();
        transactions.execute(connection -> {
            createVersionTable(connection);
            int version = currentVersion(connection);
            if (version > CURRENT_VERSION) {
                throw new SQLException(
                        "database schema is newer than this application: "
                                + version);
            }
            if (version < 1) {
                applyVersionOne(connection);
            }
            return null;
        });
    }

    public int currentVersion() {
        try (Connection connection = database.open()) {
            createVersionTable(connection);
            return currentVersion(connection);
        } catch (SQLException failure) {
            throw new StorageException("could not read schema version",
                    failure);
        }
    }

    private void enableWriteAheadLog() {
        try (Connection connection = database.open();
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA synchronous = NORMAL");
        } catch (SQLException failure) {
            throw new StorageException("could not configure SQLite database",
                    failure);
        }
    }

    private static void createVersionTable(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_version (
                        version INTEGER PRIMARY KEY,
                        applied_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private static int currentVersion(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet results = statement.executeQuery(
                        "SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return results.next() ? results.getInt(1) : 0;
        }
    }

    private static void applyVersionOne(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : VERSION_ONE_STATEMENTS) {
                statement.execute(sql);
            }
            statement.executeUpdate("INSERT INTO schema_version "
                    + "(version, applied_at) VALUES (1, '"
                    + Instant.now() + "')");
        }
    }
}
