package ais.ui.viewmodel;

import ais.aggregate.AggregateKey;
import ais.aggregate.AggregateMetric;
import ais.aggregate.MetricCalculator;
import ais.aggregate.MetricCounts;
import ais.aggregate.MetricEvaluation;
import ais.analysis.AnalysisSnapshot;
import ais.domain.AnalysisProfile;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.VesselDisplayState;
import ais.spatial.GridCellId;
import ais.spatial.GridDefinition;
import ais.spatial.HaversineDistanceCalculator;
import ais.spatial.ProjectedPoint;
import ais.spatial.Utm53NProjector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MapViewModelFactory {

    private final Utm53NProjector projector = new Utm53NProjector();
    private final HaversineDistanceCalculator distances =
            new HaversineDistanceCalculator();

    public MapViewModel create(
            AnalysisSnapshot snapshot,
            HeatmapMetric metric,
            Integer selectedMmsi,
            GridCellId selectedCell,
            long diagnosticCount) {
        AnalysisProfile profile = snapshot.context().analysisProfile();
        ReceiverProfile receiver = snapshot.context().receiverProfile();
        MetricCalculator calculator = new MetricCalculator(profile);
        Map<GridCellId, MutableMetric> cumulative = new HashMap<>();
        snapshot.aggregation().gridMetrics().forEach((key, value) ->
                cumulative.computeIfAbsent(key.spatialKey(),
                                ignored -> new MutableMetric())
                        .add(value));

        List<GridMapItem> cells = cumulative.entrySet().stream()
                .map(entry -> {
                    MetricEvaluation evaluation = calculator.evaluate(
                            entry.getValue().toMetric());
                    Double rate = metric == HeatmapMetric.ESTIMATED_LOSS
                            ? evaluation.lossRatePercent()
                            : evaluation.freshnessViolationRatePercent();
                    return new GridMapItem(
                            entry.getKey(), evaluation, rate,
                            entry.getKey().equals(selectedCell));
                })
                .sorted(Comparator.comparing(item -> item.cell().toString()))
                .toList();

        List<VesselMapItem> vessels = snapshot.vessels().values().stream()
                .filter(VesselDisplayState::visible)
                .map(vessel -> new VesselMapItem(
                        vessel.mmsi(), vessel.vesselClass(),
                        vessel.position(), vessel.directionDegrees(),
                        vessel.freshness(),
                        vessel.mmsi() == (selectedMmsi == null
                                ? -1 : selectedMmsi)
                                ? vessel.trail() : List.of(),
                        vessel.mmsi() == (selectedMmsi == null
                                ? -1 : selectedMmsi)))
                .sorted(Comparator.comparingInt(VesselMapItem::mmsi))
                .toList();

        VesselDisplayState selectedVesselState = selectedMmsi == null
                ? null : snapshot.vessels().get(selectedMmsi);
        if (selectedVesselState != null && !selectedVesselState.visible()) {
            selectedVesselState = null;
        }
        VesselDetailViewModel vesselDetail = selectedVesselState == null
                ? null : vesselDetail(snapshot, receiver,
                        selectedVesselState);
        GridMapItem selectedGridItem = selectedCell == null
                ? null : cells.stream()
                .filter(item -> item.cell().equals(selectedCell))
                .findFirst().orElse(null);
        GridDetailViewModel gridDetail = selectedGridItem == null
                ? null : gridDetail(receiver, profile, selectedGridItem);

        return new MapViewModel(
                snapshot.displayTime(), receiver, vessels, cells,
                vesselDetail, gridDetail,
                vesselDetail == null ? null : selectedMmsi,
                gridDetail == null ? null : selectedCell,
                metric, profile.freshnessMultiplier(),
                profile.rulesVersion(),
                classLabel(snapshot.filter().vesselClasses()),
                profile.gridSizeMeters(), profile.gridOriginEasting(),
                profile.gridOriginNorthing(),
                snapshot.acceptedIntervalCount(), diagnosticCount);
    }

    private static String classLabel(
            Set<ais.domain.VesselClass> vesselClasses) {
        if (vesselClasses.size() == 2) {
            return "全船舶";
        }
        return vesselClasses.contains(ais.domain.VesselClass.CLASS_A)
                ? "Class A" : "Class B";
    }

    private VesselDetailViewModel vesselDetail(
            AnalysisSnapshot snapshot,
            ReceiverProfile receiver,
            VesselDisplayState vessel) {
        String name = vessel.metadata() == null
                || vessel.metadata().vesselName() == null
                ? null : vessel.metadata().vesselName();
        Integer shipLength = vessel.metadata() == null
                ? null : vessel.metadata().shipLengthMeters();
        long age = Math.max(0, Duration.between(
                vessel.receivedAt(), snapshot.displayTime()).toSeconds());
        return new VesselDetailViewModel(
                vessel.mmsi(), name, shipLength, vessel.vesselClass(),
                vessel.position().latitude(),
                vessel.position().longitude(),
                vessel.sogKnots(), vessel.cogDegrees(),
                vessel.trueHeadingDegrees(), vessel.navigationStatus(),
                vessel.messageType(), vessel.freshness(), age,
                distances.distanceKilometers(
                        receiver.position(), vessel.position()));
    }

    private GridDetailViewModel gridDetail(
            ReceiverProfile receiver,
            AnalysisProfile profile,
            GridMapItem item) {
        GridDefinition grid = new GridDefinition(
                GridCellId.UTM_ZONE_53_NORTH,
                profile.gridOriginEasting(), profile.gridOriginNorthing(),
                profile.gridSizeMeters());
        double west = grid.westernBoundary(item.cell());
        double south = grid.southernBoundary(item.cell());
        GeoPosition center = projector.unproject(new ProjectedPoint(
                west + profile.gridSizeMeters() / 2.0,
                south + profile.gridSizeMeters() / 2.0));
        return new GridDetailViewModel(
                item.cell(), item.evaluation(),
                item.displayedRatePercent(),
                distances.distanceKilometers(receiver.position(), center));
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
