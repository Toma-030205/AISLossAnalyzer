package ais.ui;

import ais.analysis.AnalysisFilter;
import ais.analysis.DefaultAnalysisEngine;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.ClassBReportingMode;
import ais.domain.GeoPosition;
import ais.domain.PositionReport;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.domain.VesselClass;
import ais.domain.VesselMetadataUpdate;
import ais.map.MapDataset;
import ais.map.MapFeature;
import ais.map.MapFeatureType;
import ais.ui.viewmodel.HeatmapMetric;
import ais.ui.viewmodel.MapViewModel;
import ais.ui.viewmodel.MapViewModelFactory;
import ais.ui.viewmodel.SimulationOverlayViewModel;
import ais.ui.viewmodel.SimulationTruthMapItem;
import ais.simulation.communication.ReceptionOutcome;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapViewModelTest {

    private static final Instant START =
            Instant.parse("2026-09-04T00:00:00Z");

    @Test
    void createsSelectionDetailsAndCanvasRendersHeadlessly() {
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        DefaultAnalysisEngine engine = new DefaultAnalysisEngine();
        engine.begin(new AnalysisContext(
                receiver, profile, SourceMode.HISTORICAL,
                AnalysisRunId.create(), START));
        engine.accept(new VesselMetadataUpdate(
                START, 0, 5, 431000001, VesselClass.CLASS_A,
                null, null, "TEST VESSEL", null, null, 123));
        engine.accept(report(START, 0, 34.60));
        engine.accept(report(START.plusSeconds(10), 1, 34.61));
        var snapshot = engine.snapshot(
                START.plusSeconds(10), AnalysisFilter.all());
        var cell = snapshot.aggregation().gridMetrics().keySet()
                .iterator().next().spatialKey();

        MapViewModel model = new MapViewModelFactory().create(
                snapshot, HeatmapMetric.FRESHNESS_VIOLATION,
                431000001, cell, 2);

        assertEquals(1, model.vessels().size());
        assertNotNull(model.selectedVessel());
        assertEquals(12.3, model.selectedVessel().sogKnots());
        assertEquals(123, model.selectedVessel().shipLengthMeters());
        assertNotNull(model.selectedGrid());
        assertTrue(model.vessels().getFirst().selected());
        assertEquals(2, model.diagnosticCount());

        MapCanvas canvas = new MapCanvas(mapDataset());
        canvas.setSize(900, 650);
        canvas.setViewModel(model);
        canvas.setSimulationOverlay(new SimulationOverlayViewModel(
                List.of(new SimulationTruthMapItem(
                        431000001, VesselClass.CLASS_A,
                        new GeoPosition(34.615, 135.21),
                        92.0, START.plusSeconds(10), List.of(),
                        new GeoPosition(34.61, 135.20),
                        ReceptionOutcome.LOST, 0.75, true)),
                null, "CM-E1 v1", 42L));
        BufferedImage image = new BufferedImage(
                900, 650, BufferedImage.TYPE_INT_ARGB);
        canvas.paint(image.createGraphics());

        BufferedImage exported = canvas.renderForExport();

        assertNotEquals(0, image.getRGB(450, 325));
        assertNotEquals(image.getRGB(0, 0), image.getRGB(450, 325));
        assertEquals(900, exported.getWidth());
        assertEquals(650, exported.getHeight());
    }

    @Test
    void insufficientDataLegendSwatchUsesTheGridFillAndHatching() {
        BufferedImage image = new BufferedImage(
                24, 18, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            MapCanvas.paintInsufficientData(
                    graphics, new Rectangle(2, 2, 17, 12));
        } finally {
            graphics.dispose();
        }

        long paintedColors = java.util.stream.IntStream.range(0, 24)
                .boxed()
                .flatMap(x -> java.util.stream.IntStream.range(0, 18)
                        .mapToObj(y -> image.getRGB(x, y)))
                .filter(argb -> (argb >>> 24) != 0)
                .distinct()
                .count();
        assertTrue(paintedColors >= 2,
                "swatch must contain both gray fill and diagonal hatching");
    }

    private static PositionReport report(
            Instant at, long sequence, double latitude) {
        return new PositionReport(
                at, sequence, 1, 431000001,
                new GeoPosition(latitude, 135.20),
                12.3, 91.5, 90.0, 0,
                ClassBReportingMode.UNKNOWN, false);
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("lab"), "Laboratory",
                new GeoPosition(34.718983358515715,
                        135.29057866131427),
                null, null, null,
                LocalDate.of(2000, 1, 1), null, null);
    }

    private static MapDataset mapDataset() {
        return MapDataset.from(List.of(new MapFeature(
                MapFeatureType.LAND, "LNDARE_a", List.of(
                new GeoPosition(34.4, 134.9),
                new GeoPosition(34.9, 134.9),
                new GeoPosition(34.9, 135.5),
                new GeoPosition(34.4, 135.5)),
                Path.of("map.senc").toAbsolutePath())));
    }
}
