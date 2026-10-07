package ais.storage;

import ais.aggregate.RollupDimension;
import ais.domain.AnalysisProfileId;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record AggregateRollupQuery(
        Instant fromInclusive,
        Instant toExclusive,
        RollupDimension dimension,
        Set<VesselClass> vesselClasses,
        ReceiverProfileId receiverProfileId,
        AnalysisProfileId analysisProfileId,
        Set<LocalDate> excludedDates) {

    public AggregateRollupQuery(
            Instant fromInclusive,
            Instant toExclusive,
            RollupDimension dimension,
            Set<VesselClass> vesselClasses,
            ReceiverProfileId receiverProfileId,
            AnalysisProfileId analysisProfileId) {
        this(fromInclusive, toExclusive, dimension, vesselClasses,
                receiverProfileId, analysisProfileId, Set.of());
    }

    public AggregateRollupQuery {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(dimension, "dimension");
        vesselClasses = Set.copyOf(vesselClasses);
        Objects.requireNonNull(receiverProfileId, "receiverProfileId");
        Objects.requireNonNull(analysisProfileId, "analysisProfileId");
        excludedDates = Set.copyOf(excludedDates);
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException(
                    "fromInclusive must be before toExclusive");
        }
        if (vesselClasses.isEmpty()
                || vesselClasses.contains(VesselClass.UNKNOWN)) {
            throw new IllegalArgumentException(
                    "query requires Class A and/or Class B");
        }
    }
}
