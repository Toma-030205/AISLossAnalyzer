package ais.analysis;

import ais.domain.AnalysisContext;
import ais.domain.NormalizedAisEvent;
import ais.domain.VesselClass;

import java.time.Instant;
import java.util.List;

public interface AnalysisEngine {

    void begin(AnalysisContext context);

    List<AnalysisEvent> accept(NormalizedAisEvent event);

    void resetIntervalCursors(Instant resumedAt);

    void resetVesselContinuity(
            int mmsi,
            VesselClass vesselClass,
            IntervalExclusionReason nextReason);

    AnalysisSnapshot snapshot(
            Instant displayTime,
            AnalysisFilter filter);

    AnalysisRunSummary checkpoint(Instant at);

    AnalysisRunSummary complete(Instant endedAt);
}
