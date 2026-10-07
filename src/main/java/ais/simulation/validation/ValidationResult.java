package ais.simulation.validation;

import ais.domain.AnalysisRunId;
import ais.simulation.calibration.CommunicationModelDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ValidationResult(
        SimulationExperimentId experimentId,
        ValidationRequest request,
        CommunicationModelDefinition model,
        Instant createdAt,
        Instant completedAt,
        List<Long> seeds,
        List<AnalysisRunId> observedSourceRunIds,
        List<ValidationCellResult> cells,
        Map<ValidationMetric, ValidationMetricSummary> summaries,
        List<String> warnings,
        String inputFingerprint) {

    public ValidationResult {
        seeds = List.copyOf(seeds);
        observedSourceRunIds = List.copyOf(observedSourceRunIds);
        cells = List.copyOf(cells);
        summaries = Map.copyOf(summaries);
        warnings = List.copyOf(warnings);
        if (experimentId == null || request == null || model == null
                || createdAt == null || completedAt == null
                || inputFingerprint == null || inputFingerprint.isBlank()) {
            throw new IllegalArgumentException("invalid validation result");
        }
    }

    public ValidationMetricSummary summary(ValidationMetric metric) {
        return summaries.get(metric);
    }
}
