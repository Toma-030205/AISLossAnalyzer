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
import java.util.HashMap;
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
    void rollsUpMonthlyCountsAndDistinctVesselsInSql() {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("monthly.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = new ReceiverProfile(
                new ReceiverProfileId("receiver-month"), "Kobe receiver",
                new GeoPosition(34.68, 135.19), 30.0,
                "antenna", "receiver", LocalDate.of(2020, 1, 1),
                null, "test");
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        saveDay(database, receiver, profile,
                LocalDate.of(2026, 9, 4), 10, 2, "d");
        saveDay(database, receiver, profile,
                LocalDate.of(2026, 9, 5), 20, 4, "e");

        AggregateRequest request = new AggregateRequest(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                RollupDimension.MONTH, Set.of(VesselClass.CLASS_A),
                HeatmapMetric.ESTIMATED_LOSS, receiver.id(), profile.id(),
                AggregateAxis.DISTANCE_BAND);
        try (AggregateQueryService service =
                     new AggregateQueryService(database)) {
            AggregateResult result = service.query(request).join();
            assertEquals(1, result.rows().size());
            AggregateRow row = result.rows().getFirst();
            assertEquals("2026-09", row.seriesLabel());
            assertEquals(30, row.evaluation().counts().observedCount());
            assertEquals(6, row.evaluation().counts().missingCount());
            assertEquals(1, row.evaluation().distinctVesselCount());
            assertEquals(2, row.observationDayCount());
        }
    }

    @Test
    void excludesSpecifiedDatesFromCountsAndRunCount() {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("excluded-dates.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = new ReceiverProfile(
                new ReceiverProfileId("receiver-excluded"),
                "Kobe receiver", new GeoPosition(34.68, 135.19),
                30.0, "antenna", "receiver",
                LocalDate.of(2020, 1, 1), null, "test");
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        LocalDate first = LocalDate.of(2026, 9, 4);
        LocalDate anomalous = first.plusDays(1);
        saveDay(database, receiver, profile, first, 10, 2, "a");
        saveDay(database, receiver, profile, anomalous, 20, 4, "b");

        AggregateRequest request = new AggregateRequest(
                first, anomalous, RollupDimension.MONTH,
                Set.of(VesselClass.CLASS_A),
                HeatmapMetric.ESTIMATED_LOSS,
                receiver.id(), profile.id(),
                AggregateAxis.DISTANCE_BAND, Set.of(anomalous));
        AggregateResult result;
        try (AggregateQueryService service =
                     new AggregateQueryService(database)) {
            result = service.query(request).join();
        }

        assertEquals(1, result.analysisRunCount());
        assertEquals(1, result.rows().size());
        assertEquals(10, result.rows().getFirst().evaluation()
                .counts().observedCount());
        assertEquals(2, result.rows().getFirst().evaluation()
                .counts().missingCount());
        assertEquals(1, result.rows().getFirst().observationDayCount());
        assertTrue(new ais.export.ExportMetadataFormatter().summary(result)
                .contains("除外日: 2026-09-05"));
        assertTrue(new ais.export.ExportFileNamer().chartPng(result)
                .contains("_exclude_20260905"));
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

    @Test
    void aggregatesDistanceByJapaneseHourAndClassWithDistinctVessels()
            throws Exception {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("distance-hour.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = new ReceiverProfile(
                new ReceiverProfileId("receiver-distance-hour"),
                "Kobe receiver", new GeoPosition(34.68, 135.19), 30.0,
                "antenna", "receiver", LocalDate.of(2020, 1, 1),
                null, "test");
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);

        LocalDate first = LocalDate.of(2026, 9, 5);
        Instant midnight = first.atStartOfDay(ApplicationContext.JAPAN)
                .toInstant();
        Map<AggregateKey<DistanceBand>, AggregateMetric> firstMetrics =
                new HashMap<>();
        firstMetrics.put(distanceKey(midnight, 6, VesselClass.CLASS_A),
                metric(10, 2, Set.of(431000001, 431000002)));
        firstMetrics.put(distanceKey(midnight.plusSeconds(300), 6,
                        VesselClass.CLASS_A),
                metric(20, 3, Set.of(431000002, 431000003)));
        firstMetrics.put(distanceKey(midnight, 2, VesselClass.CLASS_B),
                metric(5, 1, Set.of(431000010)));
        saveSnapshot(database, receiver, profile, first, "e", firstMetrics);

        LocalDate second = first.plusDays(1);
        Instant nextMidnight = second.atStartOfDay(ApplicationContext.JAPAN)
                .toInstant();
        saveSnapshot(database, receiver, profile, second, "f",
                Map.of(distanceKey(nextMidnight, 6, VesselClass.CLASS_A),
                        metric(30, 4,
                                Set.of(431000001, 431000004))));

        AggregateRequest request = new AggregateRequest(
                first, second, RollupDimension.MONTH,
                Set.of(VesselClass.CLASS_A, VesselClass.CLASS_B),
                HeatmapMetric.ESTIMATED_LOSS, receiver.id(), profile.id(),
                AggregateAxis.DISTANCE_BY_HOUR);
        AggregateResult result;
        try (AggregateQueryService service =
                     new AggregateQueryService(database)) {
            result = service.query(request).join();
        }

        AggregateRow classA = result.rows().stream()
                .filter(row -> row.vesselClass() == VesselClass.CLASS_A)
                .filter(row -> row.hourOfDay() == 0)
                .filter(row -> row.categoryOrder() == 6)
                .findFirst().orElseThrow();
        assertEquals("30-35 km", classA.categoryLabel());
        assertEquals(60, classA.evaluation().counts().observedCount());
        assertEquals(9, classA.evaluation().counts().missingCount());
        assertEquals(4, classA.evaluation().distinctVesselCount());
        assertEquals(2, classA.observationDayCount());
        assertTrue(new ais.export.ExportMetadataFormatter().summary(result)
                .contains("集計単位: 選択期間全体"));

        Path csv = temporaryDirectory.resolve("distance-hour.csv");
        Path png = temporaryDirectory.resolve("distance-hour.png");
        new ais.export.CsvExporter().write(csv, result);
        new ais.export.ChartPngExporter().write(png, result, 1_400, 800);
        String exported = Files.readString(csv);
        assertTrue(exported.contains("時間帯,距離帯,Class"));
        assertTrue(exported.contains("\"00時\",\"30-35 km\",\"Class A\""));
        assertTrue(Files.size(png) > 1_000);
        assertTrue(new ais.export.ExportFileNamer().chartPng(result)
                .startsWith("ais_distance_hour_"));
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

    private static AggregateKey<DistanceBand> distanceKey(
            Instant bucket, int bandIndex, VesselClass vesselClass) {
        return new AggregateKey<>(bucket,
                new DistanceBand(bandIndex, bandIndex * 5,
                        bandIndex * 5 + 5), vesselClass);
    }

    private static AggregateMetric metric(
            long observed, long missing, Set<Integer> vessels) {
        return new AggregateMetric(
                new MetricCounts(observed, missing, 100, 20), vessels);
    }

    private static void saveSnapshot(
            SqliteDatabase database,
            ReceiverProfile receiver,
            AnalysisProfile profile,
            LocalDate date,
            String fingerprintCharacter,
            Map<AggregateKey<DistanceBand>, AggregateMetric> metrics) {
        Instant start = date.atStartOfDay(ApplicationContext.JAPAN)
                .toInstant();
        AnalysisRunId runId = AnalysisRunId.create();
        AnalysisRun run = new AnalysisRun(
                runId, SourceMode.HISTORICAL, date, date + ".ais",
                new InputFingerprint(
                        fingerprintCharacter.repeat(64), 100, 1),
                receiver.id(), profile.id(), start);
        AggregationSnapshot aggregation = new AggregationSnapshot(
                Map.of(), metrics, 0);
        AnalysisRunSummary summary = new AnalysisRunSummary(
                new AnalysisContext(receiver, profile,
                        SourceMode.HISTORICAL, runId, start),
                start.plusSeconds(86_400), 10, 0, 0,
                Map.of(), aggregation);
        new AnalysisResultStore(database).replaceCompletedRun(
                run, summary, List.of(), List.of());
    }
}
