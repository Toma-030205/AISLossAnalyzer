package ais.simulation.calibration;

import ais.domain.VesselClass;
import ais.spatial.DistanceBand;

import java.util.Objects;

public record CommunicationParameter(
        DistanceBand distanceBand,
        VesselClass vesselClass,
        long observedCount,
        long missingCount,
        int distinctVesselCount,
        int observedDayCount,
        Double rawLossRate,
        Double rawReceptionRate,
        Double jeffreysReceptionProbability,
        ConfidenceInterval confidenceInterval,
        ParameterApplicability applicability,
        Double appliedReceptionProbability,
        Integer lowerSourceBandIndex,
        Integer upperSourceBandIndex) {

    public CommunicationParameter {
        Objects.requireNonNull(distanceBand, "distanceBand");
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(applicability, "applicability");
        if (observedCount < 0 || missingCount < 0
                || distinctVesselCount < 0 || observedDayCount < 0) {
            throw new IllegalArgumentException(
                    "communication parameter counts must not be negative");
        }
        requireProbability(rawLossRate, "rawLossRate");
        requireProbability(rawReceptionRate, "rawReceptionRate");
        requireProbability(jeffreysReceptionProbability,
                "jeffreysReceptionProbability");
        requireProbability(appliedReceptionProbability,
                "appliedReceptionProbability");
        if (applicability == ParameterApplicability.OUT_OF_MODEL
                && appliedReceptionProbability != null) {
            throw new IllegalArgumentException(
                    "OUT_OF_MODEL must not have an applied probability");
        }
        if (applicability != ParameterApplicability.OUT_OF_MODEL
                && appliedReceptionProbability == null) {
            throw new IllegalArgumentException(
                    "applicable parameters require a probability");
        }
        if (applicability == ParameterApplicability.INTERPOLATED
                && (lowerSourceBandIndex == null
                || upperSourceBandIndex == null)) {
            throw new IllegalArgumentException(
                    "interpolated parameters require source bands");
        }
    }

    public long expectedCount() {
        return Math.addExact(observedCount, missingCount);
    }

    public CommunicationParameterKey key() {
        return new CommunicationParameterKey(
                distanceBand.index(), vesselClass);
    }

    private static void requireProbability(Double value, String name) {
        if (value != null && (!Double.isFinite(value)
                || value < 0.0 || value > 1.0)) {
            throw new IllegalArgumentException(
                    name + " must be between zero and one");
        }
    }
}
