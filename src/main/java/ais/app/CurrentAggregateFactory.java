package ais.app;

import ais.aggregate.AggregateKey;
import ais.aggregate.AggregateMetric;
import ais.aggregate.MetricCalculator;
import ais.aggregate.MetricCounts;
import ais.analysis.AnalysisSnapshot;
import ais.domain.VesselClass;
import ais.spatial.DistanceBand;
import ais.ui.viewmodel.HeatmapMetric;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CurrentAggregateFactory {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    public AggregateResult create(AnalysisSnapshot snapshot,
                                  HeatmapMetric metric) {
        Map<ClassBand, MutableMetric> combined = new HashMap<>();
        snapshot.aggregation().distanceMetrics().forEach((key, value) ->
                combined.computeIfAbsent(new ClassBand(
                                key.vesselClass(), key.spatialKey()),
                                ignored -> new MutableMetric())
                        .add(value));
        MetricCalculator calculator = new MetricCalculator(
                snapshot.context().analysisProfile());
        List<AggregateRow> rows = combined.entrySet().stream()
                .map(entry -> new AggregateRow(
                        classLabel(entry.getKey().vesselClass()),
                        entry.getKey().band().label(),
                        entry.getKey().vesselClass(),
                        calculator.evaluate(entry.getValue().toMetric()),
                        1, entry.getKey().band().index()))
                .sorted(Comparator.comparingInt(AggregateRow::categoryOrder)
                        .thenComparing(row -> row.vesselClass().name()))
                .toList();
        var startDate = snapshot.context().startedAt()
                .atZone(JAPAN).toLocalDate();
        var endDate = snapshot.displayTime().atZone(JAPAN).toLocalDate();
        AggregateRequest request = new AggregateRequest(
                startDate, endDate,
                ais.aggregate.RollupDimension.DAY,
                snapshot.filter().vesselClasses(), metric,
                snapshot.context().receiverProfile().id(),
                snapshot.context().analysisProfile().id(),
                AggregateAxis.DISTANCE_BAND);
        return new AggregateResult(request,
                snapshot.context().receiverProfile(),
                snapshot.context().analysisProfile(), 1, rows);
    }

    private static String classLabel(VesselClass value) {
        return value == VesselClass.CLASS_A ? "Class A" : "Class B";
    }

    private record ClassBand(VesselClass vesselClass, DistanceBand band) {
    }

    private static final class MutableMetric {
        private MetricCounts counts = MetricCounts.ZERO;
        private final Set<Integer> vessels = new HashSet<>();

        private void add(AggregateMetric metric) {
            counts = counts.plus(metric.counts());
            vessels.addAll(metric.vesselMmsis());
        }

        private AggregateMetric toMetric() {
            return new AggregateMetric(counts, vessels);
        }
    }
}
