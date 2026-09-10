package ais.analysis;

import ais.domain.PositionReport;
import ais.spatial.ProjectedPoint;

import java.util.List;
import java.util.Objects;

public record AnalyzedInterval(
        PositionReport start,
        PositionReport end,
        double expectedIntervalSeconds,
        double actualSeconds,
        double startDistanceKilometers,
        double endDistanceKilometers,
        long missingCount,
        List<EstimatedPosition> missingPositions,
        FreshnessInterval freshness,
        ProjectedPoint startProjected,
        ProjectedPoint endProjected) {

    public AnalyzedInterval {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(freshness, "freshness");
        Objects.requireNonNull(startProjected, "startProjected");
        Objects.requireNonNull(endProjected, "endProjected");
        missingPositions = List.copyOf(missingPositions);
        if (missingCount < 0 || missingPositions.size() != missingCount) {
            throw new IllegalArgumentException(
                    "missing position count must equal missing count");
        }
    }

}
