package ais.aggregate;

import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Accumulates compact per-vessel distance metrics at daily resolution. */
final class DistanceVesselMetricAccumulator {

    private final ZoneId aggregationZone;
    private final Map<DistanceVesselDayKey, MetricCounts> counts =
            new HashMap<>();

    DistanceVesselMetricAccumulator(ZoneId aggregationZone) {
        this.aggregationZone = Objects.requireNonNull(
                aggregationZone,
                "aggregationZone");
    }

    void addObserved(
            Instant at,
            DistanceBand band,
            VesselClass vesselClass,
            int mmsi) {
        add(at, band, vesselClass, mmsi,
                new MetricCounts(1, 0, 0.0, 0.0));
    }

    void addMissing(
            Instant at,
            DistanceBand band,
            VesselClass vesselClass,
            int mmsi) {
        add(at, band, vesselClass, mmsi,
                new MetricCounts(0, 1, 0.0, 0.0));
    }

    void addDuration(
            Instant at,
            DistanceBand band,
            VesselClass vesselClass,
            int mmsi,
            double observedSeconds,
            double staleSeconds) {
        add(at, band, vesselClass, mmsi,
                new MetricCounts(0, 0, observedSeconds, staleSeconds));
    }

    Map<DistanceVesselDayKey, MetricCounts> snapshot() {
        return Map.copyOf(counts);
    }

    void clear() {
        counts.clear();
    }

    private void add(
            Instant at,
            DistanceBand band,
            VesselClass vesselClass,
            int mmsi,
            MetricCounts addition) {
        DistanceVesselDayKey key = new DistanceVesselDayKey(
                at.atZone(aggregationZone).toLocalDate(),
                band,
                vesselClass,
                mmsi);
        counts.merge(key, addition, MetricCounts::plus);
    }
}
