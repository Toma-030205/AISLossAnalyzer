package ais.simulation.calibration;

import ais.domain.AnalysisProfileId;
import ais.domain.ReceiverProfileId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record CommunicationModelDefinition(
        CommunicationModelId id,
        CommunicationModelCode modelCode,
        int revision,
        String name,
        ReceiverProfileId receiverProfileId,
        AnalysisProfileId analysisProfileId,
        LocalDate trainingStartDate,
        LocalDate trainingEndDate,
        String formulaVersion,
        int bootstrapIterations,
        long bootstrapSeed,
        Instant createdAt,
        String notes) {

    public CommunicationModelDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(modelCode, "modelCode");
        Objects.requireNonNull(receiverProfileId, "receiverProfileId");
        Objects.requireNonNull(analysisProfileId, "analysisProfileId");
        Objects.requireNonNull(trainingStartDate, "trainingStartDate");
        Objects.requireNonNull(trainingEndDate, "trainingEndDate");
        Objects.requireNonNull(createdAt, "createdAt");
        if (revision < 1 || bootstrapIterations < 1) {
            throw new IllegalArgumentException(
                    "revision and bootstrapIterations must be positive");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("model name must not be blank");
        }
        name = name.trim();
        if (formulaVersion == null || formulaVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "formulaVersion must not be blank");
        }
        formulaVersion = formulaVersion.trim();
        if (trainingEndDate.isBefore(trainingStartDate)) {
            throw new IllegalArgumentException(
                    "training period is invalid");
        }
        notes = normalize(notes);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
