package ais.app;

import ais.aggregate.AggregateKey;
import ais.aggregate.AggregateMetric;
import ais.aggregate.AggregationSnapshot;
import ais.aggregate.MetricCounts;
import ais.analysis.AnalysisRunSummary;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.domain.VesselClass;
import ais.export.DailyDataQualityCsvExporter;
import ais.input.history.InputFingerprint;
import ais.spatial.DistanceBand;
import ais.storage.AnalysisResultStore;
import ais.storage.AnalysisRun;
import ais.storage.DiagnosticSummary;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.SchemaMigrator;
import ais.storage.SqliteDatabase;
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

class DailyDataQualityQueryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void listsEveryDateAndAppliesBucketQualityBoundaries() throws Exception {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("quality.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);

        LocalDate first = LocalDate.of(2026, 9, 1);
        saveDay(database, receiver, profile, first, 288, "a",
                List.of(
                        diagnostic("DUPLICATE", 7),
                        diagnostic("CHECKSUM_INVALID", 3),
                        diagnostic("INVALID_POSITION", 4),
                        diagnostic("INTERVAL_FIRST_REPORT", 5),
                        diagnostic("INTERVAL_GAP_30_MINUTES_OR_MORE", 2),
                        diagnostic("INTERVAL_DISTANCE_JUMP_OVER_30_KM", 1)));
        saveDay(database, receiver, profile, first.plusDays(1), 276, "b",
                List.of());
        // first.plusDays(2) is intentionally left without an analysis run.
        saveDay(database, receiver, profile, first.plusDays(3), 275, "c",
                List.of());
        saveDay(database, receiver, profile, first.plusDays(4), 288, "d",
                List.of(diagnostic("SOURCE_READ_FAILED", 1)));

        DailyDataQualityResult result;
        try (AggregateQueryService service =
                     new AggregateQueryService(database)) {
            result = service.queryQuality(new DailyDataQualityRequest(
                    first, first.plusDays(4), receiver.id(), profile.id()))
                    .join();
        }

        assertEquals(5, result.rows().size());
        assertEquals(List.of(
                        DailyDataQualityState.ALL_BUCKETS_PRESENT,
                        DailyDataQualityState.PARTIAL,
                        DailyDataQualityState.NOT_ANALYZED,
                        DailyDataQualityState.REVIEW_REQUIRED,
                        DailyDataQualityState.REVIEW_REQUIRED),
                result.rows().stream()
                        .map(DailyDataQualityRow::state).toList());
        assertEquals(4, result.analyzedDayCount());
        assertEquals(1, result.missingDayCount());
        assertEquals(2, result.reviewDayCount());

        DailyDataQualityRow complete = result.rows().getFirst();
        assertEquals(1, complete.distinctVesselCount());
        assertEquals(110, complete.decodedAisEventCount());
        assertEquals(7, complete.duplicateCount());
        assertEquals(3, complete.inputAnomalyCount());
        assertEquals(4, complete.invalidPositionCount());
        assertEquals(2, complete.thirtyMinuteGapCount());
        assertEquals(1, complete.distanceJumpCount());
        assertEquals(4, complete.outsideDistanceRangeCount());
        assertTrue(complete.note().contains("入力形式・復号診断 3件"));
        DailyDataQualityRow sourceFailure = result.rows().get(4);
        assertEquals(0, sourceFailure.inputAnomalyCount());
        assertTrue(sourceFailure.note().contains("入力読込/処理遅延診断 1件"));

        Path csv = temporaryDirectory.resolve("quality.csv");
        new DailyDataQualityCsvExporter().write(csv, result);
        String exported = Files.readString(csv);
        assertTrue(exported.contains("解析5分枠数,期待5分枠数"));
        assertTrue(exported.contains("\"未解析\""));
        assertTrue(exported.contains("2026-09-01 00:00"));
        assertTrue(exported.contains("2026-09-01 23:55"));
    }

    private static void saveDay(
            SqliteDatabase database,
            ReceiverProfile receiver,
            AnalysisProfile profile,
            LocalDate date,
            int bucketCount,
            String fingerprintCharacter,
            List<DiagnosticSummary> diagnostics) {
        Instant start = date.atStartOfDay(ApplicationContext.JAPAN)
                .toInstant();
        AnalysisRunId runId = AnalysisRunId.create();
        AnalysisRun run = new AnalysisRun(
                runId, SourceMode.HISTORICAL, date, date + ".ais.gz",
                new InputFingerprint(
                        fingerprintCharacter.repeat(64), 1_000, 1),
                receiver.id(), profile.id(), start);
        Map<AggregateKey<DistanceBand>, AggregateMetric> metrics =
                new HashMap<>();
        for (int index = 0; index < bucketCount; index++) {
            Instant bucket = start.plusSeconds(index * 300L);
            metrics.put(new AggregateKey<>(
                            bucket, new DistanceBand(0, 0, 5),
                            VesselClass.CLASS_A),
                    new AggregateMetric(
                            new MetricCounts(1, 0, 10, 0),
                            Set.of(431000001)));
        }
        AggregationSnapshot aggregation = new AggregationSnapshot(
                Map.of(), metrics, 4);
        AnalysisRunSummary summary = new AnalysisRunSummary(
                new AnalysisContext(receiver, profile,
                        SourceMode.HISTORICAL, runId, start),
                start.plusSeconds(86_400),
                100, 9, 2, Map.of(), aggregation);
        new AnalysisResultStore(database).replaceCompletedRun(
                run, summary, diagnostics, List.of());
    }

    private static DiagnosticSummary diagnostic(String code, long count) {
        return new DiagnosticSummary(code, count, null, null);
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("quality-receiver"),
                "研究室受信局", new GeoPosition(34.68, 135.19),
                30.0, "antenna", "receiver",
                LocalDate.of(2020, 1, 1), null, "test");
    }
}
