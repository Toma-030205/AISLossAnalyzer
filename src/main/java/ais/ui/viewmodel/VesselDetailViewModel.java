package ais.ui.viewmodel;

import ais.domain.FreshnessState;
import ais.domain.VesselClass;

public record VesselDetailViewModel(
        int mmsi,
        String vesselName,
        Integer shipLengthMeters,
        VesselClass vesselClass,
        double latitude,
        double longitude,
        Double sogKnots,
        Double cogDegrees,
        Double trueHeadingDegrees,
        Integer navigationStatus,
        int messageType,
        FreshnessState freshness,
        long ageSeconds,
        double receiverDistanceKilometers) {
}
