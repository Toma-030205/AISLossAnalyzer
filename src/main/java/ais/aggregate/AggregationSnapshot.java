package ais.aggregate;

import ais.domain.VesselClass;
import ais.spatial.DistanceBand;
import ais.spatial.GridCellId;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record AggregationSnapshot(
        Map<AggregateKey<GridCellId>, AggregateMetric> gridMetrics,
        Map<AggregateKey<DistanceBand>, AggregateMetric> distanceMetrics,
        long outsideDistanceRangeCount) {

    public AggregationSnapshot {
        gridMetrics = Map.copyOf(gridMetrics);
        distanceMetrics = Map.copyOf(distanceMetrics);
        if (outsideDistanceRangeCount < 0) {
            throw new IllegalArgumentException(
                    "outside range count must not be negative");
        }
    }

    public static AggregationSnapshot empty() {
        return new AggregationSnapshot(Map.of(), Map.of(), 0);
    }

    public AggregationSnapshot filterByVesselClasses(
            Set<VesselClass> vesselClasses) {
        Objects.requireNonNull(vesselClasses, "vesselClasses");
        if (vesselClasses.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one vessel class is required");
        }
        return new AggregationSnapshot(
                filter(gridMetrics, vesselClasses),
                filter(distanceMetrics, vesselClasses),
                outsideDistanceRangeCount);
    }

    private static <S> Map<AggregateKey<S>, AggregateMetric> filter(
            Map<AggregateKey<S>, AggregateMetric> source,
            Set<VesselClass> vesselClasses) {
        return source.entrySet().stream()
                .filter(entry -> vesselClasses.contains(
                        entry.getKey().vesselClass()))
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue));
    }
}
