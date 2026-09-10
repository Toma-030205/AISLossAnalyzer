package ais.analysis;

import ais.domain.PositionReport;
import ais.domain.VesselClass;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class IntervalCursorStore {

    private final Map<CursorKey, IntervalCursor> cursors =
            new HashMap<>();
    private final Set<CursorKey> pauseBoundaryKeys = new HashSet<>();

    public Optional<IntervalCursor> previous(PositionReport report) {
        return Optional.ofNullable(cursors.get(CursorKey.from(report)));
    }

    public void put(PositionReport report, double expectedIntervalSeconds) {
        CursorKey key = CursorKey.from(report);
        cursors.put(key, new IntervalCursor(
                report,
                expectedIntervalSeconds));
        pauseBoundaryKeys.remove(key);
    }

    public IntervalExclusionReason firstReason(PositionReport report) {
        CursorKey key = CursorKey.from(report);
        return pauseBoundaryKeys.contains(key)
                ? IntervalExclusionReason.LIVE_PAUSE_BOUNDARY
                : IntervalExclusionReason.FIRST_REPORT;
    }

    public void resetForPause() {
        pauseBoundaryKeys.addAll(cursors.keySet());
        cursors.clear();
    }

    public void clear() {
        cursors.clear();
        pauseBoundaryKeys.clear();
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
