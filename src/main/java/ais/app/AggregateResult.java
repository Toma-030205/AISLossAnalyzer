package ais.app;

import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;

import java.util.List;
import java.util.Objects;

public record AggregateResult(
        AggregateRequest request,
        ReceiverProfile receiverProfile,
        AnalysisProfile analysisProfile,
        int analysisRunCount,
        List<AggregateRow> rows) {

    public AggregateResult {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receiverProfile, "receiverProfile");
        Objects.requireNonNull(analysisProfile, "analysisProfile");
        rows = List.copyOf(rows);
        if (analysisRunCount < 0) {
            throw new IllegalArgumentException(
                    "analysisRunCount must not be negative");
        }
    }
}
