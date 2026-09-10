package ais.storage;

import ais.domain.AnalysisRunId;
import ais.domain.VesselClass;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record AggregateQuery(
        AnalysisRunId runId,
        Instant fromInclusive,
        Instant toExclusive,
        Set<VesselClass> vesselClasses) {

    public AggregateQuery {
        Objects.requireNonNull(runId, "runId");
        vesselClasses = Set.copyOf(vesselClasses);
        if (vesselClasses.isEmpty()
                || vesselClasses.contains(VesselClass.UNKNOWN)) {
            throw new IllegalArgumentException(
                    "query requires Class A and/or Class B");
        }
        if (fromInclusive != null && toExclusive != null
                && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException(
                    "fromInclusive must be before toExclusive");
        }
    }

    public static AggregateQuery all(AnalysisRunId runId) {
        return new AggregateQuery(runId, null, null,
                Set.of(VesselClass.CLASS_A, VesselClass.CLASS_B));
    }
}
