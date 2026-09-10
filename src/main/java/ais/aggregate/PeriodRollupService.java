package ais.aggregate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PeriodRollupService {

    private final ZoneId zone;

    public PeriodRollupService(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public <S> Map<RollupKey<S>, RollupMetric> rollup(
            Map<AggregateKey<S>, AggregateMetric> metrics,
            RollupDimension dimension) {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(dimension, "dimension");
        Map<RollupKey<S>, MutableRollup> combined = new HashMap<>();

        for (Map.Entry<AggregateKey<S>, AggregateMetric> entry
                : metrics.entrySet()) {
            AggregateKey<S> sourceKey = entry.getKey();
            ZonedDateTime local = sourceKey.bucketStart().atZone(zone);
            RollupKey<S> targetKey = new RollupKey<>(
                    dimension,
                    periodValue(local, dimension),
                    sourceKey.spatialKey(),
                    sourceKey.vesselClass());
            MutableRollup target = combined.computeIfAbsent(
                    targetKey,
                    ignored -> new MutableRollup());
            target.counts = target.counts.plus(entry.getValue().counts());
            target.vessels.addAll(entry.getValue().vesselMmsis());
            target.dates.add(local.toLocalDate());
        }

        Map<RollupKey<S>, RollupMetric> result = new LinkedHashMap<>();
        combined.forEach((key, value) -> result.put(
                key,
                new RollupMetric(
                        new AggregateMetric(value.counts, value.vessels),
                        value.dates)));
        return Map.copyOf(result);
    }

    private static String periodValue(
            ZonedDateTime local,
            RollupDimension dimension) {
        return switch (dimension) {
            case DAY -> local.toLocalDate().toString();
            case DAY_OF_WEEK -> local.getDayOfWeek().name();
            case MONTH -> YearMonth.from(local).toString();
            case YEAR -> Integer.toString(local.getYear());
            case HOUR_OF_DAY -> String.format("%02d", local.getHour());
        };
    }

    private static final class MutableRollup {

        private MetricCounts counts = MetricCounts.ZERO;
        private final Set<Integer> vessels = new HashSet<>();
        private final Set<LocalDate> dates = new HashSet<>();
    }
}
