package ais.app;

import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;

import java.util.List;
import java.util.Objects;

public record DailyDataQualityResult(
        DailyDataQualityRequest request,
        ReceiverProfile receiverProfile,
        AnalysisProfile analysisProfile,
        List<DailyDataQualityRow> rows) {

    public DailyDataQualityResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receiverProfile, "receiverProfile");
        Objects.requireNonNull(analysisProfile, "analysisProfile");
        rows = List.copyOf(rows);
    }

    public long analyzedDayCount() {
        return rows.stream().filter(DailyDataQualityRow::analyzed).count();
    }

    public long missingDayCount() {
        return rows.size() - analyzedDayCount();
    }

    public long reviewDayCount() {
        return rows.stream()
                .filter(row -> row.state()
                        == DailyDataQualityState.REVIEW_REQUIRED)
                .count();
    }
}
