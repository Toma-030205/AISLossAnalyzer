package ais.analysis;

import ais.domain.PositionReport;
import ais.domain.VesselClass;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

public final class IntervalCursorStore {

    private final Map<CursorKey, IntervalCursor> cursors =
            new HashMap<>();
    private final Map<CursorKey, IntervalExclusionReason> nextReasons =
            new HashMap<>();

    public Optional<IntervalCursor> previous(PositionReport report) {
        return Optional.ofNullable(cursors.get(CursorKey.from(report)));
    }

    public void put(PositionReport report, double expectedIntervalSeconds) {
        CursorKey key = CursorKey.from(report);
        cursors.put(key, new IntervalCursor(
                report,
                expectedIntervalSeconds));
        nextReasons.remove(key);
    }

    public IntervalExclusionReason firstReason(PositionReport report) {
        CursorKey key = CursorKey.from(report);
        return nextReasons.getOrDefault(
                key,
                IntervalExclusionReason.FIRST_REPORT);
    }

    public void resetForPause() {
        cursors.keySet().forEach(key -> nextReasons.put(
                key,
                IntervalExclusionReason.LIVE_PAUSE_BOUNDARY));
        cursors.clear();
    }

    public void resetVessel(
            int mmsi,
            VesselClass vesselClass,
            IntervalExclusionReason nextReason) {
        if (mmsi <= 0 || mmsi > 999_999_999) {
            throw new IllegalArgumentException("invalid MMSI: " + mmsi);
        }
        Objects.requireNonNull(vesselClass, "vesselClass");
        Objects.requireNonNull(nextReason, "nextReason");
        CursorKey key = new CursorKey(mmsi, vesselClass);
        cursors.remove(key);
        nextReasons.put(key, nextReason);
    }

    public void clear() {
        cursors.clear();
        nextReasons.clear();
    }

    public record IntervalCursor(
            PositionReport report,
            double expectedIntervalSeconds) {
    }

    private record CursorKey(int mmsi, VesselClass vesselClass) {

        private static CursorKey from(PositionReport report) {
            return new CursorKey(report.mmsi(), report.vesselClass());
        }
    }
}
