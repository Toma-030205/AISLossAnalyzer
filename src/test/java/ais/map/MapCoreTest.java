package ais.map;

import ais.domain.GeoPosition;
import ais.spatial.GridCellId;
import ais.spatial.GridDefinition;
import ais.spatial.Utm53NProjector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapCoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void catalogAndReaderKeepOnlyRequiredGeography() throws Exception {
        Path file = temporaryDirectory.resolve("osaka.senc");
        Files.writeString(file, """
                LNDARE_a
                34.0 135.0
                34.1 135.0
                34.1 135.1
                COALNE
                34.0 135.0
                34.1 135.1
                RIVERS_l
                34.05 135.01
                34.06 135.02
                SOUNDG
                34.07 135.03
                34.08 135.04
                """, StandardCharsets.UTF_8);

        List<Path> files = new SencCatalog().scan(temporaryDirectory);
        MapDataset dataset = new SencReader().read(files);

        assertEquals(List.of(file.toAbsolutePath()), files);
        assertEquals(3, dataset.features().size());
        assertEquals(Set.of(MapFeatureType.LAND,
                        MapFeatureType.COASTLINE, MapFeatureType.RIVER),
                dataset.features().stream()
                        .map(MapFeature::type).collect(
                                java.util.stream.Collectors.toSet()));
    }

    @Test
    void projectionAndViewportRoundTripAtZoomAndPan() {
        MapDataset dataset = dataset();
        MercatorMapProjection projection = new MercatorMapProjection();
        GeoPosition position = new GeoPosition(34.68, 135.22);
        GeoPosition projectedRoundTrip = projection.unproject(
                projection.project(position));
        assertEquals(position.latitude(),
                projectedRoundTrip.latitude(), 1.0e-9);
        assertEquals(position.longitude(),
                projectedRoundTrip.longitude(), 1.0e-9);

        MapViewport viewport = new MapViewport(dataset, projection);
        ScreenPoint before = viewport.toScreen(position, 800, 600, 24);
        viewport.zoomAt(2.0, before.x(), before.y(), 800, 600);
        ScreenPoint after = viewport.toScreen(position, 800, 600, 24);
        assertEquals(before.x(), after.x(), 1.0e-8);
        assertEquals(before.y(), after.y(), 1.0e-8);
        viewport.panBy(17, -9);
        ScreenPoint moved = viewport.toScreen(position, 800, 600, 24);
        GeoPosition inverse = viewport.toGeo(
                moved.x(), moved.y(), 800, 600, 24);
        assertEquals(position.latitude(), inverse.latitude(), 1.0e-8);
        assertEquals(position.longitude(), inverse.longitude(), 1.0e-8);
    }

    @Test
    void hitTestingPrioritizesVisibleVesselAndFindsAvailableGrid() {
        MapDataset dataset = dataset();
        MapViewport viewport = new MapViewport(
                dataset, new MercatorMapProjection());
        MapHitTester tester = new MapHitTester();
        GeoPosition vessel = new GeoPosition(34.68, 135.22);
        ScreenPoint vesselPoint = viewport.toScreen(
                vessel, 800, 600, 24);

        assertEquals(431000001, tester.vesselAt(
                vesselPoint.x() + 3, vesselPoint.y(),
                Map.of(431000001, vessel), viewport,
                800, 600, 24, 15).orElseThrow());

        Utm53NProjector utm = new Utm53NProjector();
        GridDefinition grid = GridDefinition.utm53N(2_000);
        GridCellId expected = grid.cellFor(utm.project(vessel));
        assertEquals(expected, tester.gridCellAt(
                vesselPoint.x(), vesselPoint.y(), Set.of(expected),
                viewport, 800, 600, 24, utm, grid).orElseThrow());
        assertTrue(tester.gridCellAt(
                vesselPoint.x(), vesselPoint.y(), Set.of(),
                viewport, 800, 600, 24, utm, grid).isEmpty());
    }

    private static MapDataset dataset() {
        Path source = Path.of("map.senc").toAbsolutePath();
        return MapDataset.from(List.of(new MapFeature(
                MapFeatureType.LAND, "LNDARE_a", List.of(
                new GeoPosition(34.4, 134.9),
                new GeoPosition(34.9, 134.9),
                new GeoPosition(34.9, 135.5),
                new GeoPosition(34.4, 135.5)), source)));
    }
}
