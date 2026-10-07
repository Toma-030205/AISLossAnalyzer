package ais.storage;

import ais.aggregate.AggregateKey;
import ais.aggregate.AggregateMetric;
import ais.aggregate.AggregationSnapshot;
import ais.aggregate.MetricCounts;
import ais.analysis.AnalysisRunSummary;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisProfileId;
import ais.domain.AnalysisRunId;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.domain.VesselClass;
import ais.domain.VesselMetadata;
import ais.input.history.InputFingerprint;
import ais.spatial.DistanceBand;
import ais.spatial.GridCellId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStorageTest {

    private static final Instant START =
            Instant.parse("2025-06-01T00:00:00Z");
    private static final Instant BUCKET =
            Instant.parse("2025-06-01T00:05:00Z");
    private static final InputFingerprint FINGERPRINT =
            new InputFingerprint("ab".repeat(32), 12_345, 1);
    private static final InputFingerprint CHANGED_FINGERPRINT =
            new InputFingerprint("cd".repeat(32), 23_456, 2);

    @TempDir
    Path temporaryDirectory;

    private SqliteDatabase database;
    private ReceiverProfile receiver;
    private AnalysisProfile profile;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabase(
                temporaryDirectory.resolve("nested/analysis.db"));
        SchemaMigrator migrator = new SchemaMigrator(database);
        migrator.migrate();
        receiver = receiver("receiver-1", LocalDate.of(2020, 1, 1), null);
        profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
    }

    @Test
    void migrationIsIdempotentAndEnablesForeignKeys() throws Exception {
        SchemaMigrator migrator = new SchemaMigrator(database);
        migrator.migrate();

        assertEquals(SchemaMigrator.CURRENT_VERSION,
                migrator.currentVersion());
        try (Connection connection = database.open();
                Statement statement = connection.createStatement();
                ResultSet foreignKeys = statement.executeQuery(
                        "PRAGMA foreign_keys")) {
            assertTrue(foreignKeys.next());
            assertEquals(1, foreignKeys.getInt(1));
        }
    }

    @Test
    void versionFourAndFiveMigrateFromVersionThree()
            throws Exception {
        try (Connection connection = database.open();
                Statement statement = connection.createStatement()) {
            dropVersionFiveTables(statement);
            statement.execute("DROP TABLE communication_model_parameter");
            statement.execute("DROP TABLE communication_model_source_run");
            statement.execute(
                    "DROP TABLE communication_model_excluded_date");
            statement.execute("DROP TABLE communication_model");
            statement.executeUpdate(
                    "DELETE FROM schema_version WHERE version >= 4");
        }
        SchemaMigrator migrator = new SchemaMigrator(database);
        assertEquals(3, migrator.currentVersion());

        migrator.migrate();

        assertEquals(SchemaMigrator.CURRENT_VERSION,
                migrator.currentVersion());
        try (Connection connection = database.open();
                Statement statement = connection.createStatement();
                ResultSet tables = statement.executeQuery("""
                        SELECT COUNT(*)
                          FROM sqlite_master
                         WHERE type = 'table'
                           AND name IN (
                               'communication_model',
                               'communication_model_excluded_date',
                               'communication_model_source_run',
                               'communication_model_parameter')
                        """)) {
            assertTrue(tables.next());
            assertEquals(4, tables.getInt(1));
        }
    }

    @Test
    void versionTwoBackfillsHourlyDistancePresence() throws Exception {
        AnalysisRun run = run(AnalysisRunId.create());
        new AnalysisResultStore(database).replaceCompletedRun(
                run, summary(run, snapshot(4, 2)), List.of(), List.of());
        try (Connection connection = database.open();
                Statement statement = connection.createStatement()) {
            dropVersionFiveTables(statement);
            statement.execute("DROP TABLE communication_model_parameter");
            statement.execute("DROP TABLE communication_model_source_run");
            statement.execute(
                    "DROP TABLE communication_model_excluded_date");
            statement.execute("DROP TABLE communication_model");
            statement.execute("DROP TABLE distance_vessel_presence_hour");
            statement.execute("DROP TABLE distance_vessel_metric_day");
            statement.execute(
                    "ALTER TABLE analysis_run DROP COLUMN vessel_metric_ready");
            statement.executeUpdate(
                    "DELETE FROM schema_version WHERE version >= 2");
        }

        SchemaMigrator migrator = new SchemaMigrator(database);
        migrator.migrate();

        try (Connection connection = database.open();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT observed_date, hour_of_day,
                               distance_band_index, vessel_class, mmsi
                          FROM distance_vessel_presence_hour
                        """)) {
            assertTrue(rows.next());
            assertEquals("2025-06-01", rows.getString("observed_date"));
            assertEquals(9, rows.getInt("hour_of_day"));
            assertEquals(6, rows.getInt("distance_band_index"));
            assertEquals("CLASS_B", rows.getString("vessel_class"));
            assertEquals(431000003, rows.getInt("mmsi"));
            assertFalse(rows.next());
        }
    }

    private static void dropVersionFiveTables(Statement statement)
            throws Exception {
        statement.execute("DROP TABLE simulation_validation_summary");
        statement.execute("DROP TABLE simulation_validation_cell");
        statement.execute("DROP TABLE simulation_distance_metric");
        statement.execute("DROP TABLE simulation_run");
        statement.execute("DROP TABLE simulation_experiment_source_run");
        statement.execute(
                "DROP TABLE simulation_experiment_excluded_date");
        statement.execute("DROP TABLE simulation_experiment");
    }

    @Test
    void transactionRollsBackAllWritesOnFailure() throws Exception {
        try (Connection connection = database.open();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE rollback_probe (value TEXT)");
        }
        TransactionRunner transactions = new TransactionRunner(database);

        assertThrows(StorageException.class, () -> transactions.execute(
                connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate(
                                "INSERT INTO rollback_probe VALUES ('x')");
                    }
                    throw new IllegalStateException("simulated failure");
                }));

        try (Connection connection = database.open();
                Statement statement = connection.createStatement();
                ResultSet count = statement.executeQuery(
                        "SELECT COUNT(*) FROM rollback_probe")) {
            assertTrue(count.next());
            assertEquals(0, count.getInt(1));
        }
    }

    @Test
    void profilesRoundTripAndReceiverPeriodsCannotOverlap() {
        JdbcReceiverProfileRepository receivers =
                new JdbcReceiverProfileRepository(database);
        JdbcAnalysisProfileRepository profiles =
                new JdbcAnalysisProfileRepository(database);

        assertEquals(receiver, receivers.findById(receiver.id()).orElseThrow());
        assertEquals(receiver,
                receivers.findEffectiveOn(LocalDate.of(2025, 6, 1))
                        .orElseThrow());
        assertEquals(profile, profiles.findById(profile.id()).orElseThrow());
        assertThrows(StorageException.class, () -> receivers.save(
                receiver("receiver-2", LocalDate.of(2024, 1, 1), null)));
    }

    @Test
    void completedResultRoundTripsAndReplacementIsAtomic() {
        AnalysisResultStore resultStore = new AnalysisResultStore(database);
        JdbcAnalysisRunRepository runs =
                new JdbcAnalysisRunRepository(database);
        JdbcAggregateRepository aggregates =
                new JdbcAggregateRepository(database);
        JdbcDiagnosticRepository diagnostics =
                new JdbcDiagnosticRepository(database);

        AnalysisRun first = run(AnalysisRunId.create());
        AnalysisRunSummary firstSummary = summary(first, snapshot(2, 1));
        DiagnosticSummary checksum = new DiagnosticSummary(
                "CHECKSUM_INVALID", 4, START, START.plusSeconds(20));
        AnalysisExclusionPeriod overflow = new AnalysisExclusionPeriod(
                START.plusSeconds(30), START.plusSeconds(40),
                "QUEUE_OVERFLOW", 8);
        resultStore.replaceCompletedRun(first, firstSummary,
                List.of(checksum), List.of(overflow));

        assertEquals(firstSummary.aggregation(),
                aggregates.query(AggregateQuery.all(first.id())));
        assertEquals(List.of(checksum),
                diagnostics.findSummaries(first.id()));
        assertEquals(List.of(overflow),
                diagnostics.findExclusionPeriods(first.id()));
        assertTrue(runs.findById(first.id()).orElseThrow().active());

        AnalysisRun second = run(AnalysisRunId.create());
        resultStore.replaceCompletedRun(second,
                summary(second, snapshot(7, 3)), List.of(), List.of());

        StoredAnalysisRun old = runs.findById(first.id()).orElseThrow();
        assertEquals(AnalysisRunStatus.SUPERSEDED, old.status());
        assertFalse(old.active());
        assertEquals(second.id(), runs.findEquivalent(
                        FINGERPRINT, receiver.id(), profile.id())
                .orElseThrow().run().id());

        AnalysisRun failed = run(AnalysisRunId.create());
        DiagnosticSummary duplicate = new DiagnosticSummary(
                "DUPLICATE", 1, null, null);
        assertThrows(StorageException.class,
                () -> resultStore.replaceCompletedRun(
                        failed, summary(failed, snapshot(99, 99)),
                        List.of(duplicate, duplicate), List.of()));

        assertTrue(runs.findById(failed.id()).isEmpty());
        assertTrue(runs.findById(second.id()).orElseThrow().active());
        assertEquals(second.id(), runs.findEquivalent(
                        FINGERPRINT, receiver.id(), profile.id())
                .orElseThrow().run().id());
    }

    @Test
    void changedHistoricalInputSupersedesSameDayAndProfiles() {
        AnalysisResultStore resultStore = new AnalysisResultStore(database);
        JdbcAnalysisRunRepository runs =
                new JdbcAnalysisRunRepository(database);
        AnalysisRun original = run(AnalysisRunId.create());
        resultStore.replaceCompletedRun(original,
                summary(original, snapshot(4, 1)), List.of(), List.of());

        AnalysisRun revised = new AnalysisRun(
                AnalysisRunId.create(), SourceMode.HISTORICAL,
                original.targetDate(), "2025-06-01-revised.log",
                CHANGED_FINGERPRINT, receiver.id(), profile.id(), START);
        resultStore.replaceCompletedRun(revised,
                summary(revised, snapshot(8, 2)), List.of(), List.of());

        StoredAnalysisRun old = runs.findById(original.id()).orElseThrow();
        StoredAnalysisRun current = runs.findById(revised.id()).orElseThrow();
        assertEquals(AnalysisRunStatus.SUPERSEDED, old.status());
        assertFalse(old.active());
        assertEquals(AnalysisRunStatus.COMPLETE, current.status());
        assertTrue(current.active());
        assertTrue(runs.findEquivalent(original.targetDate(), FINGERPRINT,
                receiver.id(), profile.id()).isEmpty());
        assertEquals(revised.id(), runs.findEquivalent(
                revised.targetDate(), CHANGED_FINGERPRINT, receiver.id(),
                profile.id()).orElseThrow().run().id());
        assertEquals(List.of(revised), runs.findCompleted(
                        null, null, receiver.id(), profile.id()).stream()
                .map(StoredAnalysisRun::run)
                .toList());
    }

    @Test
    void aggregateQueryCanRestrictTimeAndVesselClass() {
        AnalysisRun run = run(AnalysisRunId.create());
        AggregationSnapshot snapshot = snapshot(4, 2);
        new AnalysisResultStore(database).replaceCompletedRun(
                run, summary(run, snapshot), List.of(), List.of());

        AggregateQuery query = new AggregateQuery(
                run.id(), BUCKET, BUCKET.plusSeconds(300),
                Set.of(VesselClass.CLASS_A));
        AggregationSnapshot selected =
                new JdbcAggregateRepository(database).query(query);

        assertEquals(snapshot.gridMetrics(), selected.gridMetrics());
        assertTrue(selected.distanceMetrics().isEmpty());
        assertEquals(0, selected.outsideDistanceRangeCount());
    }

    @Test
    void differentAnalysisProfilesRemainSeparateActiveResults() {
        AnalysisResultStore resultStore = new AnalysisResultStore(database);
        JdbcAnalysisRunRepository runs =
                new JdbcAnalysisRunRepository(database);
        AnalysisRun original = run(AnalysisRunId.create());
        resultStore.replaceCompletedRun(original,
                summary(original, snapshot(4, 1)), List.of(), List.of());

        AnalysisProfile alternate = new AnalysisProfile(
                new AnalysisProfileId("phase-1-alternate"),
                profile.rulesVersion(), 5.0,
                profile.gridSizeMeters(), profile.gridOriginEasting(),
                profile.gridOriginNorthing(),
                profile.distanceBinKilometers(),
                profile.maximumDistanceKilometers(),
                profile.minimumExpectedCount(),
                profile.minimumDistinctVessels(),
                profile.trackGapThreshold(),
                profile.maximumDistanceJumpKilometers(),
                profile.classBCsHighSpeedIntervalSeconds());
        new JdbcAnalysisProfileRepository(database).save(alternate);
        AnalysisRun alternateRun = new AnalysisRun(
                AnalysisRunId.create(), SourceMode.HISTORICAL,
                original.targetDate(), original.inputName(),
                CHANGED_FINGERPRINT,
                receiver.id(), alternate.id(), START);
        AnalysisRunSummary alternateSummary = new AnalysisRunSummary(
                new AnalysisContext(receiver, alternate,
                        SourceMode.HISTORICAL, alternateRun.id(), START),
                START.plusSeconds(600), 10, 3, 2, Map.of(),
                snapshot(5, 2));
        resultStore.replaceCompletedRun(alternateRun, alternateSummary,
                List.of(), List.of());

        assertTrue(runs.findById(original.id()).orElseThrow().active());
        assertTrue(runs.findById(alternateRun.id()).orElseThrow().active());
        assertEquals(original.id(), runs.findEquivalent(
                original.targetDate(), FINGERPRINT, receiver.id(),
                profile.id()).orElseThrow().run().id());
        assertEquals(alternateRun.id(), runs.findEquivalent(
                alternateRun.targetDate(), CHANGED_FINGERPRINT,
                receiver.id(), alternate.id()).orElseThrow().run().id());
    }

    @Test
    void vesselMetadataStoresOnlyMeaningfulChanges() {
        JdbcVesselMetadataRepository metadata =
                new JdbcVesselMetadataRepository(database);
        VesselMetadata original = metadata(START, "OSAKA MARU");
        VesselMetadata sameLater = metadata(
                START.plusSeconds(60), "OSAKA MARU");
        VesselMetadata renamed = metadata(
                START.plusSeconds(120), "KOBE MARU");

        assertTrue(metadata.saveIfChanged(original, 5));
        assertFalse(metadata.saveIfChanged(sameLater, 24));
        assertTrue(metadata.saveIfChanged(renamed, 24));
        assertEquals("OSAKA MARU", metadata.findEffectiveAt(
                original.mmsi(), START.plusSeconds(90))
                .orElseThrow().vesselName());
        assertEquals("KOBE MARU", metadata.findLatest(original.mmsi())
                .orElseThrow().vesselName());
    }

    @Test
    void writeQueueSerializesAcceptedTasks() {
        List<Integer> order = new ArrayList<>();
        try (AggregateWriteQueue queue = new AggregateWriteQueue()) {
            var first = queue.submit(() -> {
                order.add(1);
                return 1;
            });
            var second = queue.submit(() -> {
                order.add(2);
                return 2;
            });
            assertEquals(1, first.join());
            assertEquals(2, second.join());
        }
        assertEquals(List.of(1, 2), order);
    }

    private AnalysisRun run(AnalysisRunId runId) {
        return new AnalysisRun(
                runId,
                SourceMode.HISTORICAL,
                LocalDate.of(2025, 6, 1),
                "2025-06-01.log",
                FINGERPRINT,
                receiver.id(),
                profile.id(),
                START);
    }

    private AnalysisRunSummary summary(AnalysisRun run,
                                       AggregationSnapshot snapshot) {
        AnalysisContext context = new AnalysisContext(
                receiver, profile, run.sourceMode(), run.id(),
                run.startedAt());
        return new AnalysisRunSummary(
                context, START.plusSeconds(600), 10, 3, 2,
                Map.of(), snapshot);
    }

    private static AggregationSnapshot snapshot(long observed,
                                                long missing) {
        AggregateKey<GridCellId> gridKey = new AggregateKey<>(
                BUCKET, new GridCellId(53, 251, 3821),
                VesselClass.CLASS_A);
        AggregateKey<DistanceBand> distanceKey = new AggregateKey<>(
                BUCKET.plusSeconds(300), new DistanceBand(6, 30, 35),
                VesselClass.CLASS_B);
        return new AggregationSnapshot(
                Map.of(gridKey, new AggregateMetric(
                        new MetricCounts(observed, missing, 100, 25),
                        Set.of(431000001, 431000002))),
                Map.of(distanceKey, new AggregateMetric(
                        new MetricCounts(observed + 1, missing, 80, 10),
                        Set.of(431000003))),
                5);
    }

    private static ReceiverProfile receiver(String id, LocalDate validFrom,
                                             LocalDate validTo) {
        return new ReceiverProfile(
                new ReceiverProfileId(id), "Kobe receiver",
                new GeoPosition(34.68, 135.19), 30.0,
                "antenna", "receiver", validFrom, validTo, "test");
    }

    private static VesselMetadata metadata(Instant at, String name) {
        return new VesselMetadata(
                431000001, VesselClass.CLASS_A, 9876543,
                "JTEST", name, 70, "OSAKA", 120, at);
    }
}
