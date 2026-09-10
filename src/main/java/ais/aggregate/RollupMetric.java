package ais.aggregate;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

public record RollupMetric(
        AggregateMetric metric,
        Set<LocalDate> observationDates) {

    public RollupMetric {
        Objects.requireNonNull(metric, "metric");
        observationDates = Set.copyOf(observationDates);
    }

    public int observationDayCount() {
        return observationDates.size();
    }
}
