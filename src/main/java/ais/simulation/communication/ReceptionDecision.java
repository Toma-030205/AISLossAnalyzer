package ais.simulation.communication;

import ais.simulation.calibration.CommunicationParameterKey;
import ais.simulation.calibration.ParameterApplicability;

import java.util.Objects;

public record ReceptionDecision(
        ReceptionOutcome outcome,
        Double appliedProbability,
        CommunicationParameterKey parameterKey,
        ParameterApplicability applicability,
        double uniformValue,
        double distanceKilometers) {

    public ReceptionDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(applicability, "applicability");
        if (!Double.isFinite(uniformValue)
                || uniformValue < 0.0 || uniformValue >= 1.0) {
            throw new IllegalArgumentException(
                    "uniformValue must be within [0, 1)");
        }
        if (!Double.isFinite(distanceKilometers)
                || distanceKilometers < 0.0) {
            throw new IllegalArgumentException(
                    "distanceKilometers must be finite and non-negative");
        }
        if (outcome == ReceptionOutcome.OUT_OF_MODEL) {
            if (applicability != ParameterApplicability.OUT_OF_MODEL
                    || appliedProbability != null) {
                throw new IllegalArgumentException(
                        "OUT_OF_MODEL must not have a probability");
            }
        } else {
            if (applicability == ParameterApplicability.OUT_OF_MODEL
                    || appliedProbability == null
                    || !Double.isFinite(appliedProbability)
                    || appliedProbability < 0.0
                    || appliedProbability > 1.0) {
                throw new IllegalArgumentException(
                        "evaluated decisions require a probability");
            }
        }
    }

    public static ReceptionDecision outOfModel(
            CommunicationParameterKey key,
            double uniformValue,
            double distanceKilometers) {
        return new ReceptionDecision(
                ReceptionOutcome.OUT_OF_MODEL,
                null,
                key,
                ParameterApplicability.OUT_OF_MODEL,
                uniformValue,
                distanceKilometers);
    }
}
