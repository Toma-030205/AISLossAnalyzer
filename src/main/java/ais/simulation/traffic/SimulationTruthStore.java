package ais.simulation.traffic;

import ais.analysis.AnalysisFilter;
import ais.analysis.VesselStateStore;
import ais.domain.TrailPoint;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SimulationTruthStore {

    private static final Duration TRAIL_RETENTION = Duration.ofMinutes(60);

    private final Map<Integer, MutableTruthState> states = new LinkedHashMap<>();

    public void accept(IdealTransmission transmission) {
        Objects.requireNonNull(transmission, "transmission");
        states.computeIfAbsent(
                        transmission.mmsi(), ignored -> new MutableTruthState())
                .accept(transmission);
    }

    public Map<Integer, SimulationTruthState> snapshot(
            Instant displayTime,
            AnalysisFilter filter) {
        Objects.requireNonNull(displayTime, "displayTime");
        Objects.requireNonNull(filter, "filter");
        Map<Integer, SimulationTruthState> result = new LinkedHashMap<>();
        states.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    MutableTruthState state = entry.getValue();
                    if (state.latest == null
                            || !filter.vesselClasses().contains(
                                    state.latest.vesselClass())) {
                        return;
                    }
                    Duration age = Duration.between(
                            state.latest.plannedAt(), displayTime);
                    if (age.isNegative()
                            || age.compareTo(VesselStateStore.DISPLAY_TIMEOUT)
                            >= 0) {
                        return;
                    }
                    Instant earliest = displayTime.minus(
                            filter.trailDuration());
                    var trail = state.trail.stream()
                            .filter(point -> !point.receivedAt()
                                    .isBefore(earliest))
                            .filter(point -> !point.receivedAt()
                                    .isAfter(displayTime))
                            .toList();
                    result.put(entry.getKey(), new SimulationTruthState(
                            state.latest.mmsi(),
                            state.latest.vesselClass(),
                            state.latest.position(),
                            direction(state.latest),
                            state.latest.plannedAt(),
                            trail));
                });
        return Map.copyOf(result);
    }

    private static Double direction(IdealTransmission transmission) {
        return transmission.trueHeadingDegrees() != null
                ? transmission.trueHeadingDegrees()
                : transmission.cogDegrees();
    }

    private static final class MutableTruthState {
        private final Deque<TrailPoint> trail = new ArrayDeque<>();
        private IdealTransmission latest;

        private void accept(IdealTransmission transmission) {
            if (latest != null
                    && transmission.plannedAt().isBefore(latest.plannedAt())) {
                trail.clear();
            }
            latest = transmission;
            trail.addLast(new TrailPoint(
                    transmission.plannedAt(), transmission.position()));
            Instant earliest = transmission.plannedAt()
                    .minus(TRAIL_RETENTION);
            while (!trail.isEmpty()
                    && trail.peekFirst().receivedAt().isBefore(earliest)) {
                trail.removeFirst();
            }
        }
    }
}
