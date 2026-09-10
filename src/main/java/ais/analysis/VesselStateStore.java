package ais.analysis;

import ais.domain.FreshnessState;
import ais.domain.PositionReport;
import ais.domain.VesselDisplayState;
import ais.domain.VesselMetadata;
import ais.domain.VesselMetadataUpdate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class VesselStateStore {

    public static final Duration DISPLAY_TIMEOUT = Duration.ofMinutes(10);

    private final Map<Integer, VesselAnalysisState> states =
            new HashMap<>();

    VesselAnalysisState stateFor(int mmsi) {
        return states.computeIfAbsent(
                mmsi,
                ignored -> new VesselAnalysisState());
    }

    public VesselMetadata accept(VesselMetadataUpdate update) {
        Objects.requireNonNull(update, "update");
        return stateFor(update.mmsi()).mergeMetadata(update);
    }

    public Map<Integer, VesselDisplayState> snapshot(
            Instant displayTime,
            AnalysisFilter filter,
            FreshnessEvaluator freshnessEvaluator) {
        Objects.requireNonNull(displayTime, "displayTime");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(freshnessEvaluator, "freshnessEvaluator");
        Map<Integer, VesselDisplayState> result = new LinkedHashMap<>();

        states.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    VesselAnalysisState state = entry.getValue();
                    PositionReport report = state.latestPosition();
                    if (report == null
                            || !filter.vesselClasses().contains(
                                    report.vesselClass())) {
                        return;
                    }
                    Duration age = Duration.between(
                            report.receivedAt(),
                            displayTime);
                    boolean visible = !age.isNegative()
                            && age.compareTo(DISPLAY_TIMEOUT) < 0;
                    FreshnessState freshness = freshnessEvaluator.state(
                            report.receivedAt(),
                            state.latestExpectedIntervalSeconds(),
                            displayTime);
                    Double direction = report.trueHeadingDegrees() != null
                            ? report.trueHeadingDegrees()
                            : report.cogDegrees();
                    result.put(entry.getKey(), new VesselDisplayState(
                            report.mmsi(),
                            report.vesselClass(),
                            report.position(),
                            direction,
                            report.sogKnots(),
                            report.cogDegrees(),
                            report.trueHeadingDegrees(),
                            report.navigationStatus(),
                            report.messageType(),
                            freshness,
                            report.receivedAt(),
                            state.metadata(),
                            state.trail().pointsSince(
                                    displayTime,
                                    filter.trailDuration()),
                            visible));
                });
        return Map.copyOf(result);
    }

    public void resetContinuity() {
        states.values().forEach(VesselAnalysisState::resetContinuity);
    }

    public void clear() {
        states.clear();
    }
}
