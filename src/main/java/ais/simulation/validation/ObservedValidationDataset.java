package ais.simulation.validation;

import ais.domain.AnalysisRunId;

import java.util.List;
import java.util.Map;

public record ObservedValidationDataset(
        List<AnalysisRunId> sourceRunIds,
        Map<ValidationCellKey, ObservedValidationCell> cells) {

    public ObservedValidationDataset {
        sourceRunIds = List.copyOf(sourceRunIds);
        cells = Map.copyOf(cells);
    }
}
