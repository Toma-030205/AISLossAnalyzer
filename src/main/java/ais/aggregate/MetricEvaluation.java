package ais.aggregate;

import java.util.Objects;
import java.util.Set;

public record MetricEvaluation(
        MetricCounts counts,
        int distinctVesselCount,
        Double lossRatePercent,
        Double freshnessViolationRatePercent,
        Set<InsufficientDataReason> insufficientReasons) {

    public MetricEvaluation {
        Objects.requireNonNull(counts, "counts");
        insufficientReasons = Set.copyOf(insufficientReasons);
    }

    public boolean hasSufficientData() {
        return insufficientReasons.isEmpty();
    }
}
