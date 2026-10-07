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
import ais.domain.VesselMetadata;
import ais.export.ExportFileNamer;
import ais.export.ShipLengthChartPngExporter;
import ais.export.ShipLengthCsvExporter;
import ais.input.history.InputFingerprint;
import ais.spatial.DistanceBand;
import ais.storage.AnalysisResultStore;
import ais.storage.AnalysisRun;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.JdbcVesselMetadataRepository;
import ais.storage.SchemaMigrator;
import ais.storage.SqliteDatabase;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CombinedRangeXYPlot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYBlockRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipLengthAnalysisQueryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void summarizesDailyFarthestTrackBandByLengthAndClass()
            throws Exception {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("ship-length.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);

        LocalDate first = LocalDate.of(2026, 9, 1);
        Map<AggregateKey<DistanceBand>, AggregateMetric> dayOne =
                new HashMap<>();
        add(dayOne, first, 2, VesselClass.CLASS_A, 431000001);
        add(dayOne, first, 8, VesselClass.CLASS_A, 431000001);
        add(dayOne, first, 6, VesselClass.CLASS_A, 431000002);
        add(dayOne, first, 1, VesselClass.CLASS_A, 431000004);
        add(dayOne, first, 7, VesselClass.CLASS_A, 431000005);
        saveDay(database, receiver, profile, first, "a", dayOne);

        LocalDate second = first.plusDays(1);
        Map<AggregateKey<DistanceBand>, AggregateMetric> dayTwo =
                new HashMap<>();
        add(dayTwo, second, 10, VesselClass.CLASS_A, 431000001);
        add(dayTwo, second, 4, VesselClass.CLASS_A, 431000002);
        add(dayTwo, second, 12, VesselClass.CLASS_B, 431000003);
        saveDay(database, receiver, profile, second, "b", dayTwo);

        JdbcVesselMetadataRepository metadata =
                new JdbcVesselMetadataRepository(database);
        metadata.saveIfChanged(metadata(431000001, VesselClass.CLASS_A,
                49, first.atTime(12, 0)
                        .atZone(ApplicationContext.JAPAN).toInstant()), 5);
        // A later NULL report must not erase the last known length.
        metadata.saveIfChanged(metadata(431000001, VesselClass.CLASS_A,
                null, second.atTime(12, 0)
                        .atZone(ApplicationContext.JAPAN).toInstant()), 5);
        metadata.saveIfChanged(metadata(431000002, VesselClass.CLASS_A,
                100, first.atTime(13, 0)
                        .atZone(ApplicationContext.JAPAN).toInstant()), 5);
        metadata.saveIfChanged(metadata(431000003, VesselClass.CLASS_B,
                80, second.atTime(13, 0)
                        .atZone(ApplicationContext.JAPAN).toInstant()), 24);
        // This value is not yet known during the requested period.
        metadata.saveIfChanged(metadata(431000004, VesselClass.CLASS_A,
                70, second.plusDays(1).atStartOfDay(
                        ApplicationContext.JAPAN).toInstant()), 5);
        // Values above the explicit 500 m plausibility boundary are unknown.
        metadata.saveIfChanged(metadata(431000005, VesselClass.CLASS_A,
                501, first.atTime(14, 0)
                        .atZone(ApplicationContext.JAPAN).toInstant()), 5);

        ShipLengthAnalysisRequest request = new ShipLengthAnalysisRequest(
                first, second,
                Set.of(VesselClass.CLASS_A, VesselClass.CLASS_B),
                receiver.id(), profile.id());
        ShipLengthAnalysisResult result;
        try (AggregateQueryService service =
                     new AggregateQueryService(database)) {
            result = service.queryShipLength(request).join();
        }

        assertEquals(2, result.analysisRunCount());
        assertEquals(5, result.totalDistinctVesselCount());
        assertEquals(3, result.knownLengthDistinctVesselCount());
        assertEquals(7, result.totalVesselDayCount());
        assertEquals(5, result.knownLengthVesselDayCount());
        assertEquals(60.0, result.knownLengthCoveragePercent(), 0.0001);
        assertEquals(5 * 100.0 / 7,
                result.knownLengthVesselDayCoveragePercent(), 0.0001);

        ShipLengthAnalysisRow underFifty = row(result,
                ShipLengthBand.UNDER_50, VesselClass.CLASS_A);
        assertEquals(1, underFifty.distinctVesselCount());
        assertEquals(2, underFifty.vesselDayCount());
        assertEquals(45.0,
                underFifty.averageDailyMaximumLowerKilometers(), 0.0001);
        assertEquals("40-45 km",
                underFifty.medianDailyMaximumDistanceBand());
        assertEquals(2, underFifty.atLeastThirtyKilometerVesselDays());
        assertEquals(100.0,
                underFifty.atLeastThirtyKilometerRatePercent(), 0.0001);
        assertEquals(1, underFifty.atLeastFiftyKilometerVesselDays());
        assertEquals(50.0,
                underFifty.atLeastFiftyKilometerRatePercent(), 0.0001);
        assertFalse(underFifty.sufficientData());

        ShipLengthAnalysisRow hundred = row(result,
                ShipLengthBand.FROM_100_TO_149, VesselClass.CLASS_A);
        assertEquals(25.0,
                hundred.averageDailyMaximumLowerKilometers(), 0.0001);
        assertEquals(1, hundred.atLeastThirtyKilometerVesselDays());
        ShipLengthAnalysisRow classB = row(result,
                ShipLengthBand.FROM_50_TO_99, VesselClass.CLASS_B);
        assertEquals("60-65 km",
                classB.medianDailyMaximumDistanceBand());
        ShipLengthAnalysisRow unknown = row(result,
                ShipLengthBand.UNKNOWN_OR_IMPLAUSIBLE,
                VesselClass.CLASS_A);
        assertEquals(2, unknown.distinctVesselCount());
        assertEquals(2, unknown.vesselDayCount());

        assertEquals(1, result.cells().stream()
                .filter(cell -> cell.shipLengthBand()
                        == ShipLengthBand.UNDER_50)
                .filter(cell -> cell.dailyMaximumDistanceBand().index() == 8)
                .findFirst().orElseThrow().vesselDayCount());

        ShipLengthAnalysisResult excluded;
        try (AggregateQueryService service =
                     new AggregateQueryService(database)) {
            excluded = service.queryShipLength(
                    new ShipLengthAnalysisRequest(
                            first, second,
                            Set.of(VesselClass.CLASS_A,
                                    VesselClass.CLASS_B),
                            receiver.id(), profile.id(),
                            Set.of(second))).join();
        }
        assertEquals(1, excluded.analysisRunCount());
        assertEquals(4, excluded.totalVesselDayCount());
        assertEquals(2, excluded.knownLengthDistinctVesselCount());
        assertTrue(new ExportFileNamer().shipLengthCsv(excluded)
                .contains("_exclude_20260902"));

        Path csv = temporaryDirectory.resolve("ship-length.csv");
        Path png = temporaryDirectory.resolve("ship-length.png");
        new ShipLengthCsvExporter().write(csv, result);
        new ShipLengthChartPngExporter().write(png, result, 1_400, 850);
        String exported = Files.readString(csv);
        assertTrue(exported.contains("日別最遠距離帯"));
        assertTrue(exported.contains("欠落率・情報鮮度違反率ではない"));
        assertTrue(exported.contains("\"50m未満\",\"Class A\""));
        assertTrue(Files.size(png) > 1_000);
        assertTrue(new ExportFileNamer().shipLengthCsv(result)
                .startsWith("ais_ship_length_reach_"));

        JFreeChart chart = new ais.export.ShipLengthHeatmapRenderer()
                .create(result);
        CombinedRangeXYPlot plot = (CombinedRangeXYPlot) chart.getPlot();
        assertEquals(2, plot.getSubplots().size());
        XYBlockRenderer renderer = (XYBlockRenderer)
                ((XYPlot) plot.getSubplots().getFirst()).getRenderer();
        assertEquals(new Color(150, 155, 160),
                renderer.getPaintScale().getPaint(Double.NaN));
    }

    @Test
    void classifiesLengthBoundariesAndHandlesAnEmptyPeriod() {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("ship-length-boundaries.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        LocalDate date = LocalDate.of(2026, 9, 3);

        int[] lengths = {1, 49, 50, 99, 100, 149, 150,
                199, 200, 249, 250, 500, 501};
        Set<Integer> vessels = new java.util.LinkedHashSet<>();
        JdbcVesselMetadataRepository metadata =
                new JdbcVesselMetadataRepository(database);
        for (int index = 0; index < lengths.length; index++) {
            int mmsi = 432000000 + index;
            vessels.add(mmsi);
            metadata.saveIfChanged(metadata(
                    mmsi, VesselClass.CLASS_A, lengths[index],
                    date.atTime(1, index)
                            .atZone(ApplicationContext.JAPAN).toInstant()),
                    5);
        }
        int noMetadataMmsi = 432000099;
        vessels.add(noMetadataMmsi);
        Instant bucket = date.atTime(12, 0)
                .atZone(ApplicationContext.JAPAN).toInstant();
        Map<AggregateKey<DistanceBand>, AggregateMetric> metrics = Map.of(
                new AggregateKey<>(bucket,
                        new DistanceBand(0, 0, 5),
                        VesselClass.CLASS_A),
                new AggregateMetric(
                        new MetricCounts(vessels.size(), 0, 1.0, 0.0),
                        vessels));
        saveDay(database, receiver, profile, date, "c", metrics);

        try (AggregateQueryService service =
                     new AggregateQueryService(database)) {
            ShipLengthAnalysisResult result = service.queryShipLength(
                    new ShipLengthAnalysisRequest(
                            date, date, Set.of(VesselClass.CLASS_A),
                            receiver.id(), profile.id())).join();
            assertEquals(2, row(result, ShipLengthBand.UNDER_50,
                    VesselClass.CLASS_A).distinctVesselCount());
            assertEquals(2, row(result, ShipLengthBand.FROM_50_TO_99,
                    VesselClass.CLASS_A).distinctVesselCount());
            assertEquals(2, row(result, ShipLengthBand.FROM_100_TO_149,
                    VesselClass.CLASS_A).distinctVesselCount());
            assertEquals(2, row(result, ShipLengthBand.FROM_150_TO_199,
                    VesselClass.CLASS_A).distinctVesselCount());
            assertEquals(2, row(result, ShipLengthBand.FROM_200_TO_249,
                    VesselClass.CLASS_A).distinctVesselCount());
            assertEquals(2, row(result, ShipLengthBand.AT_LEAST_250,
                    VesselClass.CLASS_A).distinctVesselCount());
            assertEquals(2, row(result,
                    ShipLengthBand.UNKNOWN_OR_IMPLAUSIBLE,
                    VesselClass.CLASS_A).distinctVesselCount());

            ShipLengthAnalysisResult empty = service.queryShipLength(
                    new ShipLengthAnalysisRequest(
                            date.plusDays(1), date.plusDays(1),
                            Set.of(VesselClass.CLASS_A),
                            receiver.id(), profile.id())).join();
            assertEquals(0, empty.analysisRunCount());
            assertEquals(0, empty.totalVesselDayCount());
            assertTrue(empty.rows().isEmpty());
            assertTrue(empty.cells().isEmpty());
        }
    }

    private static ShipLengthAnalysisRow row(
            ShipLengthAnalysisResult result,
            ShipLengthBand band,
            VesselClass vesselClass) {
        return result.rows().stream()
                .filter(row -> row.shipLengthBand() == band)
                .filter(row -> row.vesselClass() == vesselClass)
                .findFirst().orElseThrow();
    }

    private static void add(
            Map<AggregateKey<DistanceBand>, AggregateMetric> metrics,
            LocalDate date,
            int bandIndex,
            VesselClass vesselClass,
            int mmsi) {
        Instant bucket = date.atTime(12, bandIndex)
                .atZone(ApplicationContext.JAPAN).toInstant();
        metrics.put(new AggregateKey<>(bucket,
                        new DistanceBand(
                                bandIndex, bandIndex * 5,
                                bandIndex * 5 + 5),
                        vesselClass),
                new AggregateMetric(
                        new MetricCounts(1, 0, 1.0, 0.0),
                        Set.of(mmsi)));
    }

    private static void saveDay(
            SqliteDatabase database,
            ReceiverProfile receiver,
            AnalysisProfile profile,
            LocalDate date,
            String fingerprintSeed,
            Map<AggregateKey<DistanceBand>, AggregateMetric> metrics) {
        Instant start = date.atStartOfDay(ApplicationContext.JAPAN)
                .toInstant();
        AnalysisRunId runId = AnalysisRunId.create();
        AnalysisRun run = new AnalysisRun(
                runId, SourceMode.HISTORICAL, date, date + ".ais",
                new InputFingerprint(
                        fingerprintSeed.repeat(64), 100, 1),
                receiver.id(), profile.id(), start);
        AnalysisRunSummary summary = new AnalysisRunSummary(
                new AnalysisContext(receiver, profile,
                        SourceMode.HISTORICAL, runId, start),
                start.plusSeconds(86_400), metrics.size(), 0, 0,
                Map.of(), new AggregationSnapshot(
                        Map.of(), metrics, 0));
        new AnalysisResultStore(database).replaceCompletedRun(
                run, summary, List.of(), List.of());
    }

    private static VesselMetadata metadata(
            int mmsi,
            VesselClass vesselClass,
            Integer length,
            Instant at) {
        return new VesselMetadata(
                mmsi, vesselClass, null, null, null,
                null, null, length, at);
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("ship-length-receiver"),
                "研究室受信局", new GeoPosition(34.68, 135.19),
                30.0, "antenna", "receiver",
                LocalDate.of(2020, 1, 1), null, "test");
    }
}
