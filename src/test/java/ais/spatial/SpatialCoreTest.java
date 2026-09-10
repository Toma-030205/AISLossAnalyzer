package ais.spatial;

import ais.domain.GeoPosition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialCoreTest {

    @Test
    void projectsWgs84CentralMeridianToUtm53NAndBack() {
        Utm53NProjector projector = new Utm53NProjector();

        ProjectedPoint equator = projector.project(
                new GeoPosition(0.0, 135.0));
        assertEquals(500_000.0, equator.easting(), 0.001);
        assertEquals(0.0, equator.northing(), 0.001);

        GeoPosition osaka = new GeoPosition(34.68, 135.20);
        GeoPosition roundTrip = projector.unproject(projector.project(osaka));
        assertEquals(osaka.latitude(), roundTrip.latitude(), 1.0e-8);
        assertEquals(osaka.longitude(), roundTrip.longitude(), 1.0e-8);
    }

    @Test
    void calculatesHaversineDistance() {
        HaversineDistanceCalculator calculator =
                new HaversineDistanceCalculator();

        assertEquals(111.195,
                calculator.distanceKilometers(
                        new GeoPosition(0.0, 0.0),
                        new GeoPosition(0.0, 1.0)),
                0.001);
    }

    @Test
    void mapsProjectedCoordinatesToStableTwoKilometerCells() {
        GridDefinition grid = GridDefinition.utm53N(2_000);

        assertEquals(new GridCellId(53, 250, 1_900),
                grid.cellFor(new ProjectedPoint(500_000, 3_800_000)));
        assertEquals(new GridCellId(53, 249, 1_899),
                grid.cellFor(new ProjectedPoint(499_999.9, 3_799_999.9)));
    }

    @Test
    void allocatesSegmentDurationAcrossEveryCrossedGridCell() {
        GridDefinition grid = GridDefinition.utm53N(2_000);
        GridSegmentAllocator allocator = new GridSegmentAllocator(grid);
        Instant start = Instant.parse("2026-09-04T00:00:00Z");

        List<GridTimeAllocation> allocations = allocator.allocate(
                new ProjectedPoint(500, 500),
                new ProjectedPoint(4_500, 500),
                start,
                start.plusSeconds(100));

        assertEquals(List.of(
                        new GridCellId(53, 0, 0),
                        new GridCellId(53, 1, 0),
                        new GridCellId(53, 2, 0)),
                allocations.stream()
                        .map(GridTimeAllocation::cell)
                        .toList());
        assertEquals(37.5, allocations.get(0).seconds(), 1.0e-6);
        assertEquals(50.0, allocations.get(1).seconds(), 1.0e-6);
        assertEquals(12.5, allocations.get(2).seconds(), 1.0e-6);
        assertEquals(100.0,
                allocations.stream()
                        .mapToDouble(GridTimeAllocation::seconds)
                        .sum(),
                1.0e-6);
    }

    @Test
    void interpolatesProjectedPositionByReceptionTime() {
        Instant start = Instant.parse("2026-09-04T00:00:00Z");
        Instant end = start.plusSeconds(20);
        double ratio = TrackInterpolator.ratio(
                start,
                end,
                start.plusSeconds(5));

        assertEquals(0.25, ratio, 1.0e-12);
        assertEquals(new ProjectedPoint(1_500, 2_500),
                TrackInterpolator.interpolate(
                        new ProjectedPoint(1_000, 2_000),
                        new ProjectedPoint(3_000, 4_000),
                        ratio));
    }

    @Test
    void classifiesZeroThroughSeventyKilometersInFiveKilometerBands() {
        DistanceBandDefinition definition =
                new DistanceBandDefinition(5.0, 70.0);

        assertEquals(0, definition.bandFor(0.0).orElseThrow().index());
        assertEquals(1, definition.bandFor(5.0).orElseThrow().index());
        assertEquals(13, definition.bandFor(70.0).orElseThrow().index());
        assertFalse(definition.bandFor(70.0001).isPresent());
        assertEquals(14, definition.bands().size());
        assertTrue(definition.bands().get(5).label().startsWith("25-30"));
    }

    @Test
    void allocatesTimeWhenTrackCrossesDistanceBands() {
        CoordinateProjector scaledProjector = new CoordinateProjector() {
            @Override
            public ProjectedPoint project(GeoPosition position) {
                return new ProjectedPoint(
                        position.longitude() * 1_000.0,
                        position.latitude() * 1_000.0);
            }

            @Override
            public GeoPosition unproject(ProjectedPoint point) {
                return new GeoPosition(
                        point.northing() / 1_000.0,
                        point.easting() / 1_000.0);
            }
        };
        DistanceCalculator scaledDistance = (first, second) ->
                Math.hypot(
                        second.longitude() - first.longitude(),
                        second.latitude() - first.latitude());
        DistanceSegmentAllocator allocator = new DistanceSegmentAllocator(
                new DistanceBandDefinition(5.0, 70.0),
                scaledProjector,
                scaledDistance,
                new GeoPosition(0.0, 0.0));
        Instant startAt = Instant.parse("2026-09-04T00:00:00Z");

        List<DistanceTimeAllocation> allocations = allocator.allocate(
                new ProjectedPoint(1_000, 0),
                new ProjectedPoint(11_000, 0),
                startAt,
                startAt.plusSeconds(100));

        assertEquals(List.of(0, 1, 2),
                allocations.stream()
                        .map(allocation -> allocation.band().index())
                        .toList());
        assertEquals(40.0, allocations.get(0).seconds(), 1.0e-6);
        assertEquals(50.0, allocations.get(1).seconds(), 1.0e-6);
        assertEquals(10.0, allocations.get(2).seconds(), 1.0e-6);
    }
}
