package ais.simulation.validation;

import ais.aggregate.MetricCounts;
import ais.domain.VesselClass;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ValidationComparator {

    public Comparison compare(
            ObservedValidationDataset observed,
            List<ValidationRunResult> runs) {
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(runs, "runs");
        Map<ValidationCellKey, List<MetricCounts>> cm = collect(
                runs, ValidationModelVariant.CM_E1);
        Map<ValidationCellKey, List<MetricCounts>> baseline = collect(
                runs, ValidationModelVariant.CLASS_ONLY_BASELINE);

        List<ValidationCellResult> cells = observed.cells().values().stream()
                .sorted((left, right) -> left.key().compareTo(right.key()))
                .map(cell -> compareCell(
                        cell,
                        cm.getOrDefault(cell.key(), List.of()),
                        baseline.getOrDefault(cell.key(), List.of())))
                .toList();
        EnumMap<ValidationMetric, ValidationMetricSummary> summaries =
                new EnumMap<>(ValidationMetric.class);
        for (ValidationMetric metric : ValidationMetric.values()) {
            summaries.put(metric, summarize(metric, cells));
        }
        return new Comparison(cells, summaries);
    }

    private static Map<ValidationCellKey, List<MetricCounts>> collect(
            List<ValidationRunResult> runs,
            ValidationModelVariant variant) {
        Map<ValidationCellKey, List<MetricCounts>> result = new HashMap<>();
        runs.stream().filter(run -> run.variant() == variant)
                .sorted(java.util.Comparator.comparingInt(
                        ValidationRunResult::iteration))
                .forEach(run -> run.metrics().forEach((key, counts) ->
                        result.computeIfAbsent(key,
                                ignored -> new ArrayList<>()).add(counts)));
        return result;
    }

    private static ValidationCellResult compareCell(
            ObservedValidationCell observed,
            List<MetricCounts> cmCounts,
            List<MetricCounts> baselineCounts) {
        EnumMap<ValidationMetric, ValidationMetricComparison> comparisons =
                new EnumMap<>(ValidationMetric.class);
        for (ValidationMetric metric : ValidationMetric.values()) {
            Double observedRate = observed.rate(metric);
            StatisticalSummary observedRange = observed.dailyRange(metric);
            StatisticalSummary simulation = statistics(metric, cmCounts);
            StatisticalSummary baseline = statistics(metric, baselineCounts);
            Double difference = difference(simulation, observedRate);
            Double baselineDifference = difference(baseline, observedRate);
            ValidationCellStatus status = status(
                    observedRate, observedRange, simulation);
            comparisons.put(metric, new ValidationMetricComparison(
                    observedRate, observedRange, simulation, baseline,
                    difference, baselineDifference, status));
        }
        return new ValidationCellResult(
                observed.key(), observed.counts(),
                observed.distinctVesselCount(),
                observed.observationDayCount(), comparisons);
    }

    private static StatisticalSummary statistics(
            ValidationMetric metric,
            List<MetricCounts> counts) {
        return StatisticalSummary.of(counts.stream()
                .map(metric::ratePercent)
                .filter(Objects::nonNull)
                .toList());
    }

    private static Double difference(
            StatisticalSummary statistics,
            Double observed) {
        return statistics == null || observed == null ? null
                : statistics.mean() - observed;
    }

    private static ValidationCellStatus status(
            Double observed,
            StatisticalSummary observedRange,
            StatisticalSummary simulation) {
        if (observed == null || simulation == null) {
            return ValidationCellStatus.OUT_OF_MODEL;
        }
        boolean simulationContainsObserved = observed >= simulation.lower95()
                && observed <= simulation.upper95();
        boolean observedContainsSimulation = observedRange != null
                && simulation.mean() >= observedRange.lower95()
                && simulation.mean() <= observedRange.upper95();
        return simulationContainsObserved || observedContainsSimulation
                ? ValidationCellStatus.MATCH
                : ValidationCellStatus.REVIEW;
    }

    private static ValidationMetricSummary summarize(
            ValidationMetric metric,
            List<ValidationCellResult> cells) {
        double modelWeightedError = 0.0;
        double baselineWeightedError = 0.0;
        double modelWeight = 0.0;
        double baselineWeight = 0.0;
        int comparable = 0;
        int matching = 0;
        for (ValidationCellResult cell : cells) {
            ValidationMetricComparison value = cell.comparison(metric);
            double weight = weight(metric, cell.observedCounts());
            if (value.simulationDifferencePoints() != null && weight > 0.0) {
                modelWeightedError += Math.abs(
                        value.simulationDifferencePoints()) * weight;
                modelWeight += weight;
                comparable++;
                if (value.status() == ValidationCellStatus.MATCH) {
                    matching++;
                }
            }
            if (value.baselineDifferencePoints() != null && weight > 0.0) {
                baselineWeightedError += Math.abs(
                        value.baselineDifferencePoints()) * weight;
                baselineWeight += weight;
            }
        }
        Double modelMae = modelWeight == 0.0 ? null
                : modelWeightedError / modelWeight;
        Double baselineMae = baselineWeight == 0.0 ? null
                : baselineWeightedError / baselineWeight;
        Double improvement = modelMae == null || baselineMae == null
                || baselineMae == 0.0 ? null
                : (baselineMae - modelMae) * 100.0 / baselineMae;
        return new ValidationMetricSummary(
                metric, modelMae, baselineMae, improvement,
                comparable, matching,
                transitionTrend(metric, cells),
                classDifferenceTrend(metric, cells));
    }

    private static double weight(
            ValidationMetric metric, MetricCounts counts) {
        return metric == ValidationMetric.ESTIMATED_LOSS
                ? counts.expectedCount() : counts.observedSeconds();
    }

    private static String transitionTrend(
            ValidationMetric metric,
            List<ValidationCellResult> cells) {
        Double observedBefore = bandAverage(metric, cells, 25.0, 30.0, true);
        Double observedAfter = bandAverage(metric, cells, 30.0, 35.0, true);
        Double simulatedBefore = bandAverage(metric, cells, 25.0, 30.0, false);
        Double simulatedAfter = bandAverage(metric, cells, 30.0, 35.0, false);
        return directionLabel(observedBefore, observedAfter,
                simulatedBefore, simulatedAfter);
    }

    private static String classDifferenceTrend(
            ValidationMetric metric,
            List<ValidationCellResult> cells) {
        Double observedA = classAverage(metric, cells, VesselClass.CLASS_A,
                true);
        Double observedB = classAverage(metric, cells, VesselClass.CLASS_B,
                true);
        Double simulatedA = classAverage(metric, cells, VesselClass.CLASS_A,
                false);
        Double simulatedB = classAverage(metric, cells, VesselClass.CLASS_B,
                false);
        return directionLabel(observedA, observedB, simulatedA, simulatedB);
    }

    private static Double bandAverage(
            ValidationMetric metric,
            List<ValidationCellResult> cells,
            double lower,
            double upper,
            boolean observed) {
        return average(metric, cells.stream().filter(cell ->
                Math.abs(cell.key().distanceBand().lowerKilometers() - lower)
                        < 1.0e-9
                && Math.abs(cell.key().distanceBand().upperKilometers() - upper)
                        < 1.0e-9).toList(), observed);
    }

    private static Double classAverage(
            ValidationMetric metric,
            List<ValidationCellResult> cells,
            VesselClass vesselClass,
            boolean observed) {
        return average(metric, cells.stream()
                .filter(cell -> cell.key().vesselClass() == vesselClass)
                .toList(), observed);
    }

    private static Double average(
            ValidationMetric metric,
            List<ValidationCellResult> cells,
            boolean observed) {
        double weighted = 0.0;
        double weights = 0.0;
        for (ValidationCellResult cell : cells) {
            ValidationMetricComparison comparison = cell.comparison(metric);
            Double rate = observed ? comparison.observedPercent()
                    : comparison.simulation() == null ? null
                    : comparison.simulation().mean();
            double weight = weight(metric, cell.observedCounts());
            if (rate != null && weight > 0.0) {
                weighted += rate * weight;
                weights += weight;
            }
        }
        return weights == 0.0 ? null : weighted / weights;
    }

    private static String directionLabel(
            Double observedFirst,
            Double observedSecond,
            Double simulatedFirst,
            Double simulatedSecond) {
        if (observedFirst == null || observedSecond == null
                || simulatedFirst == null || simulatedSecond == null) {
            return "判定不能";
        }
        double observedDifference = observedSecond - observedFirst;
        double simulatedDifference = simulatedSecond - simulatedFirst;
        boolean same = Math.abs(observedDifference) < 1.0e-9
                ? Math.abs(simulatedDifference) < 1.0e-9
                : Math.signum(observedDifference)
                == Math.signum(simulatedDifference);
        return same ? "再現" : "不一致";
    }

    public record Comparison(
            List<ValidationCellResult> cells,
            Map<ValidationMetric, ValidationMetricSummary> summaries) {

        public Comparison {
            cells = List.copyOf(cells);
            summaries = Map.copyOf(summaries);
        }
    }
}
