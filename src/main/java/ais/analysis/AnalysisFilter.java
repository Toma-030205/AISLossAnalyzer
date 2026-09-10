package ais.analysis;

import ais.domain.VesselClass;

import java.time.Duration;
import java.util.Set;

public record AnalysisFilter(
        Set<VesselClass> vesselClasses,
        Duration trailDuration) {

    public AnalysisFilter {
        vesselClasses = Set.copyOf(vesselClasses);
        if (vesselClasses.isEmpty()
                || vesselClasses.contains(VesselClass.UNKNOWN)) {
            throw new IllegalArgumentException(
                    "filter must contain Class A and/or Class B");
        }
        if (trailDuration == null
                || trailDuration.isNegative()
                || trailDuration.compareTo(TrailBuffer.DEFAULT_RETENTION) > 0) {
            throw new IllegalArgumentException(
                    "trail duration must be between zero and 60 minutes");
        }
    }

    public static AnalysisFilter all() {
        return new AnalysisFilter(
                Set.of(VesselClass.CLASS_A, VesselClass.CLASS_B),
                Duration.ofMinutes(60));
    }
}
