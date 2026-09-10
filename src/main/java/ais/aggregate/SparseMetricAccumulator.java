package ais.aggregate;

import ais.domain.VesselClass;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

final class SparseMetricAccumulator<S> {

    private final Map<AggregateKey<S>, MetricCounts> counts =
            new HashMap<>();
    private final VesselPresenceAccumulator<S> presence =
            new VesselPresenceAccumulator<>();

    void addObserved(
            Instant bucketStart,
            S spatialKey,
            VesselClass vesselClass,
            int mmsi) {
        add(bucketStart, spatialKey, vesselClass, mmsi,
                new MetricCounts(1, 0, 0.0, 0.0));
    }

    void addMissing(
            Instant bucketStart,
            S spatialKey,
            VesselClass vesselClass,
            int mmsi) {
        add(bucketStart, spatialKey, vesselClass, mmsi,
                new MetricCounts(0, 1, 0.0, 0.0));
    }

    void addDuration(
            Instant bucketStart,
            S spatialKey,
            VesselClass vesselClass,
            int mmsi,
            double observedSeconds,
            double staleSeconds) {
        add(bucketStart, spatialKey, vesselClass, mmsi,
                new MetricCounts(
                        0,
                        0,
                        observedSeconds,
                        staleSeconds));
    }

    Map<AggregateKey<S>, AggregateMetric> snapshot() {
        Map<AggregateKey<S>, AggregateMetric> result =
                new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted((first, second) -> first.getKey()
                        .bucketStart()
                        .compareTo(second.getKey().bucketStart()))
                .forEach(entry -> result.put(
                        entry.getKey(),
                        new AggregateMetric(
                                entry.getValue(),
                                presence.vesselsFor(entry.getKey()))));
        return Map.copyOf(result);
    }

    void clear() {
        counts.clear();
        presence.clear();
    }

    private void add(
            Instant bucketStart,
            S spatialKey,
            VesselClass vesselClass,
            int mmsi,
            MetricCounts addition) {
        AggregateKey<S> key = new AggregateKey<>(
                bucketStart,
                spatialKey,
                vesselClass);
        counts.merge(key, addition, MetricCounts::plus);
        presence.add(key, mmsi);
    }
}
