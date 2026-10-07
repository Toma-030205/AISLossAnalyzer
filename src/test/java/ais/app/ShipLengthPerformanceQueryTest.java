package ais.app;

import ais.aggregate.AggregationSnapshot;
import ais.aggregate.DistanceVesselDayKey;
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
import ais.domain.VesselMetadata;
import ais.export.ExportFileNamer;
import ais.export.ShipLengthPerformanceChartPngExporter;
import ais.export.ShipLengthPerformanceCsvExporter;
import ais.input.history.InputFingerprint;
import ais.spatial.DistanceBand;
import ais.storage.AnalysisResultStore;
import ais.storage.AnalysisRun;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.JdbcVesselMetadataRepository;
import ais.storage.SchemaMigrator;
import ais.storage.SqliteDatabase;
import ais.ui.viewmodel.HeatmapMetric;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipLengthPerformanceQueryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void calculatesLossAndFreshnessByLengthDistanceAndClass()
            throws Exception {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("ship-length-performance.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);

        LocalDate first = LocalDate.of(2026, 9, 10);
        Map<DistanceVesselDayKey, MetricCounts> metrics =
                new LinkedHashMap<>();
        DistanceBand thirtyToThirtyFive =
                new DistanceBand(6, 30.0, 35.0);
        DistanceBand tenToFifteen =
                new DistanceBand(2, 10.0, 15.0);
        put(metrics, first, thirtyToThirtyFive,
                VesselClass.CLASS_A, 431000001,
                new MetricCounts(60, 20, 600.0, 120.0));
        put(metrics, first, thirtyToThirtyFive,
                VesselClass.CLASS_A, 431000002,
                new MetricCounts(30, 10, 300.0, 30.0));
        put(metrics, first, thirtyToThirtyFive,
                VesselClass.CLASS_A, 431000003,
                new MetricCounts(40, 40, 100.0, 25.0));
        put(metrics, first, tenToFifteen,
                VesselClass.CLASS_B, 431000004,
                new MetricCounts(90, 10, 200.0, 100.0));
        AnalysisRun firstRun = saveDay(
                database, receiver, profile, first, "a", metrics);

        JdbcVesselMetadataRepository metadata =
                new JdbcVesselMetadataRepository(database);
        saveLength(metadata, first, 431000001, VesselClass.CLASS_A, 49, 5);
        saveLength(metadata, first, 431000002, VesselClass.CLASS_A, 40, 5);
        saveLength(metadata, first, 431000003, VesselClass.CLASS_A, 100, 5);
        saveLength(metadata, first, 431000004, VesselClass.CLASS_B, 80, 24);

        LocalDate second = first.plusDays(1);
        AnalysisRun legacy = saveDay(
                database, receiver, profile, second, "b", Map.of());
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE analysis_run SET vessel_metric_ready = 0 "
                                + "WHERE id = ?")) {
            statement.setString(1, legacy.id().toString());
            assertEquals(1, statement.executeUpdate());
        }

        ShipLengthPerformanceResult result;
        try (AggregateQueryService service =
                     new AggregateQueryService(database)) {
            result = service.queryShipLengthPerformance(
                    new ShipLengthPerformanceRequest(
                            first, second,
                            Set.of(VesselClass.CLASS_A,
                                    VesselClass.CLASS_B),
                            HeatmapMetric.ESTIMATED_LOSS,
                            receiver.id(), profile.id())).join();
        }

        assertEquals(1, result.readyAnalysisRunCount());
        assertEquals(1, result.reanalysisRequiredRunCount());
        assertEquals(4, result.totalDistinctVesselCount());
        assertEquals(4, result.knownLengthDistinctVesselCount());
        assertEquals(100.0, result.knownLengthCoveragePercent(), 0.0001);

        ShipLengthPerformanceRow underFifty = row(
                result, ShipLengthBand.UNDER_50,
                thirtyToThirtyFive, VesselClass.CLASS_A);
        assertEquals(new MetricCounts(90, 30, 900.0, 150.0),
                underFifty.evaluation().counts());
        assertEquals(25.0,
                underFifty.evaluation().lossRatePercent(), 0.0001);
        assertEquals(100.0 / 6.0,
                underFifty.evaluation().freshnessViolationRatePercent(),
                0.0001);
        assertEquals(2, underFifty.evaluation().distinctVesselCount());
        assertEquals(1, underFifty.observationDayCount());
        assertFalse(underFifty.evaluation().hasSufficientData());

        ShipLengthPerformanceRow hundred = row(
                result, ShipLengthBand.FROM_100_TO_149,
                thirtyToThirtyFive, VesselClass.CLASS_A);
        assertEquals(50.0,
                hundred.evaluation().lossRatePercent(), 0.0001);
        assertEquals(25.0,
                hundred.evaluation().freshnessViolationRatePercent(),
                0.0001);
        ShipLengthPerformanceRow classB = row(
                result, ShipLengthBand.FROM_50_TO_99,
                tenToFifteen, VesselClass.CLASS_B);
        assertEquals(10.0,
                classB.evaluation().lossRatePercent(), 0.0001);
        assertEquals(50.0,
                classB.evaluation().freshnessViolationRatePercent(),
                0.0001);

        ShipLengthPerformanceResult excluded;
        try (AggregateQueryService service =
                     new AggregateQueryService(database)) {
            excluded = service.queryShipLengthPerformance(
                    new ShipLengthPerformanceRequest(
                            first, second,
                            Set.of(VesselClass.CLASS_A,
                                    VesselClass.CLASS_B),
                            HeatmapMetric.ESTIMATED_LOSS,
                            receiver.id(), profile.id(),
                            Set.of(first))).join();
        }
        assertEquals(0, excluded.readyAnalysisRunCount());
        assertEquals(1, excluded.reanalysisRequiredRunCount());
        assertTrue(excluded.rows().isEmpty());
        assertTrue(new ExportFileNamer()
                .shipLengthPerformanceCsv(excluded)
                .contains("_exclude_20260910"));

        assertFalse(new AnalysisResultStore(database)
                .hasCompletedEquivalentRun(
                        second, legacy.inputFingerprint(),
                        receiver.id(), profile.id()));
        assertTrue(new AnalysisResultStore(database)
                .hasCompletedEquivalentRun(
                        first, firstRun.inputFingerprint(),
                        receiver.id(), profile.id()));

        Path csv = temporaryDirectory.resolve("performance.csv");
        Path png = temporaryDirectory.resolve("performance.png");
        new ShipLengthPerformanceCsvExporter().write(csv, result);
        new ShipLengthPerformanceChartPngExporter().write(
                png, result, 1_400, 850);
        String exported = Files.readString(csv);
        assertTrue(exported.contains("推定欠落率_%"));
        assertTrue(exported.contains("情報鮮度違反率_%"));
        assertTrue(exported.contains("\"50m未満\",\"30-35 km\",\"Class A\""));
        assertTrue(Files.size(png) > 1_000);
        assertTrue(new ExportFileNamer()
                .shipLengthPerformanceCsv(result)
                .startsWith("ais_ship_length_performance_"));
        assertEquals(2, ((CombinedDomainXYPlot)
                new ais.export.ShipLengthPerformanceHeatmapRenderer()
                        .create(result).getPlot()).getSubplots().size());
    }

    private static ShipLengthPerformanceRow row(
            ShipLengthPerformanceResult result,
            ShipLengthBand lengthBand,
            DistanceBand distanceBand,
            VesselClass vesselClass) {
        return result.rows().stream()
                .filter(row -> row.shipLengthBand() == lengthBand)
                .filter(row -> row.distanceBand().index()
                        == distanceBand.index())
                .filter(row -> row.vesselClass() == vesselClass)
                .findFirst().orElseThrow();
    }

    private static void put(
            Map<DistanceVesselDayKey, MetricCounts> metrics,
            LocalDate date,
            DistanceBand band,
            VesselClass vesselClass,
            int mmsi,
            MetricCounts counts) {
        metrics.put(new DistanceVesselDayKey(
                date, band, vesselClass, mmsi), counts);
    }

    private static AnalysisRun saveDay(
            SqliteDatabase database,
            ReceiverProfile receiver,
            AnalysisProfile profile,
            LocalDate date,
            String fingerprintSeed,
            Map<DistanceVesselDayKey, MetricCounts> metrics) {
        Instant start = date.atStartOfDay(ApplicationContext.JAPAN)
                .toInstant();
        AnalysisRunId runId = AnalysisRunId.create();
        AnalysisRun run = new AnalysisRun(
                runId, SourceMode.HISTORICAL, date, date + ".ais",
                new InputFingerprint(fingerprintSeed.repeat(64), 100, 1),
                receiver.id(), profile.id(), start);
        AnalysisRunSummary summary = new AnalysisRunSummary(
                new AnalysisContext(receiver, profile,
                        SourceMode.HISTORICAL, runId, start),
                start.plusSeconds(86_400), metrics.size(), 0, 0,
                Map.of(), new AggregationSnapshot(
                        Map.of(), Map.of(), 0, metrics));
        new AnalysisResultStore(database).replaceCompletedRun(
                run, summary, List.of(), List.of());
        return run;
    }

    private static void saveLength(
            JdbcVesselMetadataRepository repository,
            LocalDate date,
            int mmsi,
            VesselClass vesselClass,
            int length,
            int messageType) {
        Instant at = date.atTime(12, 0)
                .atZone(ApplicationContext.JAPAN).toInstant();
        repository.saveIfChanged(new VesselMetadata(
                mmsi, vesselClass, null, null, null,
                null, null, length, at), messageType);
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("length-performance-receiver"),
                "研究室受信局", new GeoPosition(34.68, 135.19),
                30.0, "antenna", "receiver",
                LocalDate.of(2020, 1, 1), null, "test");
    }
}
