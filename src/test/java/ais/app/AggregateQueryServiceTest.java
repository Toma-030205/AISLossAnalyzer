package ais.app;

import ais.aggregate.AggregateKey;
import ais.aggregate.AggregateMetric;
import ais.aggregate.AggregationSnapshot;
import ais.aggregate.MetricCounts;
import ais.aggregate.RollupDimension;
import ais.analysis.AnalysisRunSummary;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.domain.VesselClass;
import ais.input.history.InputFingerprint;
import ais.spatial.DistanceBand;
import ais.storage.AnalysisResultStore;
import ais.storage.AnalysisRun;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.SchemaMigrator;
import ais.storage.SqliteDatabase;
import ais.ui.viewmodel.HeatmapMetric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregateQueryServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void queriesDailyDistanceRowsAndExportsCsvAndPng() throws Exception {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("aggregate.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = new ReceiverProfile(
                new ReceiverProfileId("receiver"), "Kobe receiver",
                new GeoPosition(34.68, 135.19), 30.0,
                "antenna", "receiver", LocalDate.of(2020, 1, 1),
                null, "test");
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        saveDay(database, receiver, profile,
                LocalDate.of(2026, 9, 4), 10, 2, "a");
        saveDay(database, receiver, profile,
                LocalDate.of(2026, 9, 5), 20, 4, "b");

        AggregateRequest request = new AggregateRequest(
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5),
                RollupDimension.DAY, Set.of(VesselClass.CLASS_A),
                HeatmapMetric.ESTIMATED_LOSS, receiver.id(), profile.id(),
                AggregateAxis.DISTANCE_BAND);
        AggregateResult result;
        try (AggregateQueryService service =
                     new AggregateQueryService(database)) {
            result = service.query(request).join();
        }

        assertEquals(2, result.analysisRunCount());
        assertEquals(2, result.rows().size());
        assertEquals(List.of("2026-09-04", "2026-09-05"),
                result.rows().stream().map(AggregateRow::seriesLabel).toList());

        Path csv = temporaryDirectory.resolve("result.csv");
        Path png = temporaryDirectory.resolve("result.png");
        new ais.export.CsvExporter().write(csv, result);
        new ais.export.ChartPngExporter().write(png, result, 800, 500);
        assertTrue(Files.size(csv) > 100);
        assertTrue(Files.readString(csv).contains("observed"));
        assertTrue(Files.size(png) > 1_000);
    }

    @Test
    void usesTwoDigitJapaneseHourLabels() {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("hour.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = new ReceiverProfile(
                new ReceiverProfileId("receiver-hour"), "Kobe receiver",
                new GeoPosition(34.68, 135.19), 30.0,
                "antenna", "receiver", LocalDate.of(2020, 1, 1),
                null, "test");
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        saveAtHour(database, receiver, profile,
                LocalDate.of(2026, 9, 5), 0, "c");

        AggregateRequest request = new AggregateRequest(
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                RollupDimension.DAY, Set.of(VesselClass.CLASS_A),
                HeatmapMetric.ESTIMATED_LOSS, receiver.id(), profile.id(),
                AggregateAxis.HOUR_OF_DAY);
        try (AggregateQueryService service =
                     new AggregateQueryService(database)) {
            AggregateResult result = service.query(request).join();
            assertEquals(List.of("00時"), result.rows().stream()
                    .map(AggregateRow::categoryLabel).toList());
            assertTrue(new ais.export.ExportFileNamer().chartPng(result)
                    .startsWith("ais_hour_"));
        }
    }

    private static void saveDay(
            SqliteDatabase database,
            ReceiverProfile receiver,
            AnalysisProfile profile,
                LocalDate date,
                long observed,
                long missing,
                String fingerprintSeed) {
        saveAtHour(database, receiver, profile, date, 12,
                fingerprintSeed, observed, missing);
    }

    private static void saveAtHour(
            SqliteDatabase database,
            ReceiverProfile receiver,
            AnalysisProfile profile,
            LocalDate date,
            int hour,
            String fingerprintSeed) {
        saveAtHour(database, receiver, profile, date, hour,
                fingerprintSeed, 40, 5);
    }

    private static void saveAtHour(
            SqliteDatabase database,
            ReceiverProfile receiver,
            AnalysisProfile profile,
            LocalDate date,
            int hour,
            String fingerprintSeed,
            long observed,
            long missing) {
        Instant bucket = date.atTime(hour, 0)
                .atZone(ApplicationContext.JAPAN).toInstant();
        AnalysisRunId runId = AnalysisRunId.create();
        InputFingerprint fingerprint = new InputFingerprint(
                fingerprintSeed.repeat(64), 100, 1);
        AnalysisRun run = new AnalysisRun(
                runId, SourceMode.HISTORICAL, date,
                date + ".ais", fingerprint, receiver.id(), profile.id(),
                bucket);
        AggregationSnapshot aggregation = new AggregationSnapshot(
                Map.of(),
                Map.of(new AggregateKey<>(bucket,
                                new DistanceBand(6, 30, 35),
                                VesselClass.CLASS_A),
                        new AggregateMetric(new MetricCounts(
                                observed, missing, 300, 60),
                                Set.of(431000001))),
                0);
        AnalysisRunSummary summary = new AnalysisRunSummary(
                new AnalysisContext(receiver, profile,
                        SourceMode.HISTORICAL, runId, bucket),
                bucket.plusSeconds(300), 1, missing, 0,
                Map.of(), aggregation);
        new AnalysisResultStore(database).replaceCompletedRun(
                run, summary, List.of(), List.of());
    }
}
