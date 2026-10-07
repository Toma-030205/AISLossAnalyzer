package ais.simulation.validation;

import java.util.List;

public record ValidationExperiment(
        ValidationResult result,
        List<ValidationRunResult> runs) {

    public ValidationExperiment {
        if (result == null) {
            throw new NullPointerException("result");
        }
        runs = List.copyOf(runs);
    }
}
