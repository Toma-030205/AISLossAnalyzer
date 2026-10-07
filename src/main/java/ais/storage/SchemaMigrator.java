package ais.storage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class SchemaMigrator {

    public static final int CURRENT_VERSION = 5;

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

    private static final List<String> VERSION_TWO_STATEMENTS = List.of(
            """
            CREATE TABLE distance_vessel_presence_hour (
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id)
                    ON DELETE CASCADE,
                observed_date TEXT NOT NULL,
                hour_of_day INTEGER NOT NULL CHECK (
                    hour_of_day BETWEEN 0 AND 23),
                distance_band_index INTEGER NOT NULL,
                vessel_class TEXT NOT NULL CHECK (
                    vessel_class IN ('CLASS_A', 'CLASS_B')),
                mmsi INTEGER NOT NULL,
                PRIMARY KEY (
                    analysis_run_id, observed_date, hour_of_day,
                    distance_band_index, vessel_class, mmsi)
            ) WITHOUT ROWID
            """,
            """
            INSERT OR IGNORE INTO distance_vessel_presence_hour (
                analysis_run_id, observed_date, hour_of_day,
                distance_band_index, vessel_class, mmsi)
            SELECT analysis_run_id,
                   date(bucket_start, '+9 hours'),
                   CAST(strftime('%H', bucket_start, '+9 hours') AS INTEGER),
                   distance_band_index, vessel_class, mmsi
              FROM distance_vessel_presence_5m
            """);

    private static final List<String> VERSION_THREE_STATEMENTS = List.of(
            """
            ALTER TABLE analysis_run
            ADD COLUMN vessel_metric_ready INTEGER NOT NULL DEFAULT 0
                CHECK (vessel_metric_ready IN (0, 1))
            """,
            """
            CREATE TABLE distance_vessel_metric_day (
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id)
                    ON DELETE CASCADE,
                observed_date TEXT NOT NULL,
                distance_band_index INTEGER NOT NULL,
                lower_kilometers REAL NOT NULL,
                upper_kilometers REAL NOT NULL,
                vessel_class TEXT NOT NULL CHECK (
                    vessel_class IN ('CLASS_A', 'CLASS_B')),
                mmsi INTEGER NOT NULL,
                observed_count INTEGER NOT NULL,
                missing_count INTEGER NOT NULL,
                observed_seconds REAL NOT NULL,
                stale_seconds REAL NOT NULL,
                PRIMARY KEY (
                    analysis_run_id, observed_date,
                    distance_band_index, vessel_class, mmsi)
            ) WITHOUT ROWID
            """,
            """
            CREATE INDEX distance_vessel_metric_day_lookup
            ON distance_vessel_metric_day (
                observed_date, distance_band_index, vessel_class, mmsi)
            """);

    private static final List<String> VERSION_FOUR_STATEMENTS = List.of(
            """
            CREATE TABLE communication_model (
                id TEXT PRIMARY KEY,
                model_code TEXT NOT NULL,
                revision INTEGER NOT NULL CHECK (revision >= 1),
                name TEXT NOT NULL,
                receiver_profile_id TEXT NOT NULL
                    REFERENCES receiver_profile(id),
                analysis_profile_id TEXT NOT NULL
                    REFERENCES analysis_profile(id),
                training_start_date TEXT NOT NULL,
                training_end_date TEXT NOT NULL,
                formula_version TEXT NOT NULL,
                bootstrap_iterations INTEGER NOT NULL
                    CHECK (bootstrap_iterations >= 1),
                bootstrap_seed INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                notes TEXT,
                CHECK (training_end_date >= training_start_date),
                UNIQUE (
                    model_code, receiver_profile_id,
                    analysis_profile_id, revision)
            )
            """,
            """
            CREATE TABLE communication_model_excluded_date (
                model_id TEXT NOT NULL REFERENCES communication_model(id)
                    ON DELETE CASCADE,
                excluded_date TEXT NOT NULL,
                reason TEXT NOT NULL,
                PRIMARY KEY (model_id, excluded_date)
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE communication_model_source_run (
                model_id TEXT NOT NULL REFERENCES communication_model(id)
                    ON DELETE CASCADE,
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id),
                PRIMARY KEY (model_id, analysis_run_id)
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE communication_model_parameter (
                model_id TEXT NOT NULL REFERENCES communication_model(id)
                    ON DELETE CASCADE,
                distance_band_index INTEGER NOT NULL
                    CHECK (distance_band_index >= 0),
                lower_kilometers REAL NOT NULL,
                upper_kilometers REAL NOT NULL,
                vessel_class TEXT NOT NULL CHECK (
                    vessel_class IN ('CLASS_A', 'CLASS_B')),
                observed_count INTEGER NOT NULL CHECK (observed_count >= 0),
                missing_count INTEGER NOT NULL CHECK (missing_count >= 0),
                expected_count INTEGER NOT NULL CHECK (
                    expected_count = observed_count + missing_count),
                distinct_vessels INTEGER NOT NULL
                    CHECK (distinct_vessels >= 0),
                observed_days INTEGER NOT NULL CHECK (observed_days >= 0),
                raw_loss_rate REAL,
                raw_reception_rate REAL,
                jeffreys_reception_probability REAL,
                ci_lower REAL,
                ci_upper REAL,
                applicability TEXT NOT NULL CHECK (
                    applicability IN (
                        'DIRECT', 'INTERPOLATED', 'OUT_OF_MODEL')),
                applied_reception_probability REAL,
                lower_source_band_index INTEGER,
                upper_source_band_index INTEGER,
                PRIMARY KEY (
                    model_id, distance_band_index, vessel_class),
                CHECK (upper_kilometers > lower_kilometers),
                CHECK (raw_loss_rate IS NULL OR
                    raw_loss_rate BETWEEN 0.0 AND 1.0),
                CHECK (raw_reception_rate IS NULL OR
                    raw_reception_rate BETWEEN 0.0 AND 1.0),
                CHECK (jeffreys_reception_probability IS NULL OR
                    jeffreys_reception_probability BETWEEN 0.0 AND 1.0),
                CHECK (ci_lower IS NULL OR ci_lower BETWEEN 0.0 AND 1.0),
                CHECK (ci_upper IS NULL OR ci_upper BETWEEN 0.0 AND 1.0),
                CHECK (ci_lower IS NULL OR ci_upper IS NULL OR
                    ci_upper >= ci_lower),
                CHECK (applied_reception_probability IS NULL OR
                    applied_reception_probability BETWEEN 0.0 AND 1.0),
                CHECK ((applicability = 'OUT_OF_MODEL'
                            AND applied_reception_probability IS NULL)
                    OR (applicability <> 'OUT_OF_MODEL'
                            AND applied_reception_probability IS NOT NULL))
            ) WITHOUT ROWID
            """,
            """
            CREATE INDEX communication_model_created
            ON communication_model (created_at, id)
            """);

    private static final List<String> VERSION_FIVE_STATEMENTS = List.of(
            """
            CREATE TABLE simulation_experiment (
                id TEXT PRIMARY KEY,
                model_id TEXT NOT NULL REFERENCES communication_model(id),
                validation_start_date TEXT NOT NULL,
                validation_end_date TEXT NOT NULL,
                iteration_count INTEGER NOT NULL CHECK (iteration_count >= 1),
                seed_base INTEGER NOT NULL,
                status TEXT NOT NULL CHECK (
                    status IN ('RUNNING', 'COMPLETE', 'CANCELLED', 'FAILED')),
                sensitivity INTEGER NOT NULL DEFAULT 0
                    CHECK (sensitivity IN (0, 1)),
                created_at TEXT NOT NULL,
                completed_at TEXT,
                input_fingerprint TEXT NOT NULL,
                warning_text TEXT,
                CHECK (validation_end_date >= validation_start_date)
            )
            """,
            """
            CREATE TABLE simulation_experiment_excluded_date (
                experiment_id TEXT NOT NULL REFERENCES simulation_experiment(id)
                    ON DELETE CASCADE,
                excluded_date TEXT NOT NULL,
                reason TEXT NOT NULL,
                PRIMARY KEY (experiment_id, excluded_date)
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE simulation_experiment_source_run (
                experiment_id TEXT NOT NULL REFERENCES simulation_experiment(id)
                    ON DELETE CASCADE,
                analysis_run_id TEXT NOT NULL REFERENCES analysis_run(id),
                PRIMARY KEY (experiment_id, analysis_run_id)
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE simulation_run (
                id TEXT PRIMARY KEY,
                experiment_id TEXT NOT NULL REFERENCES simulation_experiment(id)
                    ON DELETE CASCADE,
                iteration INTEGER NOT NULL CHECK (iteration >= 0),
                seed INTEGER NOT NULL,
                model_variant TEXT NOT NULL CHECK (
                    model_variant IN ('CM_E1', 'CLASS_ONLY_BASELINE')),
                status TEXT NOT NULL CHECK (
                    status IN ('COMPLETE', 'CANCELLED', 'FAILED')),
                out_of_model_count INTEGER NOT NULL DEFAULT 0
                    CHECK (out_of_model_count >= 0),
                started_at TEXT NOT NULL,
                completed_at TEXT,
                UNIQUE (experiment_id, iteration, model_variant)
            )
            """,
            """
            CREATE TABLE simulation_distance_metric (
                simulation_run_id TEXT NOT NULL REFERENCES simulation_run(id)
                    ON DELETE CASCADE,
                distance_band_index INTEGER NOT NULL,
                lower_kilometers REAL NOT NULL,
                upper_kilometers REAL NOT NULL,
                vessel_class TEXT NOT NULL CHECK (
                    vessel_class IN ('CLASS_A', 'CLASS_B')),
                observed_count INTEGER NOT NULL CHECK (observed_count >= 0),
                missing_count INTEGER NOT NULL CHECK (missing_count >= 0),
                observed_seconds REAL NOT NULL CHECK (observed_seconds >= 0),
                stale_seconds REAL NOT NULL CHECK (
                    stale_seconds >= 0 AND stale_seconds <= observed_seconds),
                PRIMARY KEY (
                    simulation_run_id, distance_band_index, vessel_class),
                CHECK (upper_kilometers > lower_kilometers)
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE simulation_validation_cell (
                experiment_id TEXT NOT NULL REFERENCES simulation_experiment(id)
                    ON DELETE CASCADE,
                metric TEXT NOT NULL CHECK (
                    metric IN ('ESTIMATED_LOSS', 'FRESHNESS_VIOLATION')),
                distance_band_index INTEGER NOT NULL,
                lower_kilometers REAL NOT NULL,
                upper_kilometers REAL NOT NULL,
                vessel_class TEXT NOT NULL CHECK (
                    vessel_class IN ('CLASS_A', 'CLASS_B')),
                observed_percent REAL,
                observed_range_lower REAL,
                observed_range_upper REAL,
                simulation_mean REAL,
                simulation_range_lower REAL,
                simulation_range_upper REAL,
                baseline_mean REAL,
                simulation_difference_points REAL,
                baseline_difference_points REAL,
                expected_count INTEGER NOT NULL,
                distinct_vessels INTEGER NOT NULL,
                observed_days INTEGER NOT NULL,
                comparison_status TEXT NOT NULL CHECK (
                    comparison_status IN ('MATCH', 'REVIEW', 'OUT_OF_MODEL')),
                PRIMARY KEY (
                    experiment_id, metric,
                    distance_band_index, vessel_class)
            ) WITHOUT ROWID
            """,
            """
            CREATE TABLE simulation_validation_summary (
                experiment_id TEXT NOT NULL REFERENCES simulation_experiment(id)
                    ON DELETE CASCADE,
                metric TEXT NOT NULL CHECK (
                    metric IN ('ESTIMATED_LOSS', 'FRESHNESS_VIOLATION')),
                model_weighted_mae_points REAL,
                baseline_weighted_mae_points REAL,
                improvement_percent REAL,
                comparable_cell_count INTEGER NOT NULL,
                matching_cell_count INTEGER NOT NULL,
                transition_trend TEXT NOT NULL,
                class_difference_trend TEXT NOT NULL,
                PRIMARY KEY (experiment_id, metric)
            ) WITHOUT ROWID
            """,
            """
            CREATE INDEX simulation_experiment_lookup
            ON simulation_experiment (
                model_id, validation_start_date, validation_end_date,
                created_at)
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
                version = 1;
            }
            if (version < 2) {
                applyVersionTwo(connection);
                version = 2;
            }
            if (version < 3) {
                applyVersionThree(connection);
                version = 3;
            }
            if (version < 4) {
                applyVersionFour(connection);
                version = 4;
            }
            if (version < 5) {
                applyVersionFive(connection);
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

    private static void applyVersionTwo(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : VERSION_TWO_STATEMENTS) {
                statement.execute(sql);
            }
            statement.executeUpdate("INSERT INTO schema_version "
                    + "(version, applied_at) VALUES (2, '"
                    + Instant.now() + "')");
        }
    }

    private static void applyVersionThree(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : VERSION_THREE_STATEMENTS) {
                statement.execute(sql);
            }
            statement.executeUpdate("INSERT INTO schema_version "
                    + "(version, applied_at) VALUES (3, '"
                    + Instant.now() + "')");
        }
    }

    private static void applyVersionFour(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : VERSION_FOUR_STATEMENTS) {
                statement.execute(sql);
            }
            statement.executeUpdate("INSERT INTO schema_version "
                    + "(version, applied_at) VALUES (4, '"
                    + Instant.now() + "')");
        }
    }

    private static void applyVersionFive(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : VERSION_FIVE_STATEMENTS) {
                statement.execute(sql);
            }
            statement.executeUpdate("INSERT INTO schema_version "
                    + "(version, applied_at) VALUES (5, '"
                    + Instant.now() + "')");
        }
    }
}
