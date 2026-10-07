package ais.storage;

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
import ais.input.history.InputFingerprint;
import ais.simulation.validation.ValidationCellKey;
import ais.spatial.DistanceBand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservedValidationRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsPooledAndDailyObservedMetricsWithExclusions() {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("observed.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = new ReceiverProfile(
                new ReceiverProfileId("receiver"), "Receiver",
                new GeoPosition(34.6, 135.2), null, null, null,
                LocalDate.of(2020, 1, 1), null, null);
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        save(database, receiver, profile, LocalDate.of(2025, 12, 1),
                new MetricCounts(90, 10, 100.0, 10.0));
        save(database, receiver, profile, LocalDate.of(2025, 12, 2),
                new MetricCounts(80, 20, 100.0, 20.0));

        var result = new JdbcObservedValidationRepository(database).load(
                new ObservedValidationQuery(
                        LocalDate.of(2025, 12, 1),
                        LocalDate.of(2025, 12, 2),
                        receiver.id(), profile.id(),
                        Set.of(LocalDate.of(2025, 12, 2))));

        assertEquals(1, result.sourceRunIds().size());
        ValidationCellKey key = new ValidationCellKey(
                new DistanceBand(0, 0.0, 5.0), VesselClass.CLASS_A);
        var cell = result.cells().get(key);
        assertEquals(new MetricCounts(90, 10, 100.0, 10.0),
                cell.counts());
        assertEquals(List.of(10.0), cell.dailyLossRates());
        assertEquals(List.of(10.0), cell.dailyFreshnessRates());
        assertEquals(1, cell.distinctVesselCount());
        assertEquals(1, cell.observationDayCount());
    }

    private static void save(
            SqliteDatabase database,
            ReceiverProfile receiver,
            AnalysisProfile profile,
            LocalDate date,
            MetricCounts counts) {
        ZoneId japan = ZoneId.of("Asia/Tokyo");
        Instant start = date.atStartOfDay(japan).toInstant();
        AnalysisRunId id = AnalysisRunId.create();
        AnalysisRun run = new AnalysisRun(
                id, SourceMode.HISTORICAL, date, date + ".ais",
                new InputFingerprint("cd".repeat(32), 100, 1),
                receiver.id(), profile.id(), start);
        DistanceBand band = new DistanceBand(0, 0.0, 5.0);
        AggregationSnapshot aggregation = new AggregationSnapshot(
                Map.of(),
                Map.of(new AggregateKey<>(
                                start, band, VesselClass.CLASS_A),
                        new AggregateMetric(counts, Set.of(431_000_001))),
                0);
        AnalysisContext context = new AnalysisContext(
                receiver, profile, SourceMode.HISTORICAL, id, start);
        AnalysisRunSummary summary = new AnalysisRunSummary(
                context, start.plusSeconds(300), 1,
                counts.missingCount(), 0, Map.of(), aggregation);
        new AnalysisResultStore(database).replaceCompletedRun(
                run, summary, List.of(), List.of());
    }
}
