package ais.simulation.calibration;

import ais.domain.AnalysisRunId;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CommunicationModelSnapshot(
        CommunicationModelDefinition definition,
        Map<LocalDate, String> excludedDates,
        List<AnalysisRunId> sourceRunIds,
        List<CommunicationParameter> parameters) {

    public CommunicationModelSnapshot {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(excludedDates, "excludedDates");
        Objects.requireNonNull(sourceRunIds, "sourceRunIds");
        Objects.requireNonNull(parameters, "parameters");
        excludedDates = Map.copyOf(excludedDates);
        sourceRunIds = List.copyOf(sourceRunIds);
        parameters = List.copyOf(parameters);
    }
}
