package ais.simulation.calibration;

import ais.domain.AnalysisProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ParameterEligibilityPolicy {

    public static final int MINIMUM_OBSERVED_DAYS = 5;
    public static final double MAXIMUM_CI_WIDTH = 0.20;

    public List<String> rejectionReasons(
            PooledEstimate estimate,
            ConfidenceInterval interval,
            AnalysisProfile profile) {
        Objects.requireNonNull(estimate, "estimate");
        Objects.requireNonNull(profile, "profile");
        List<String> reasons = new ArrayList<>();
        if (estimate.expectedCount() < profile.minimumExpectedCount()) {
            reasons.add("期待送信数<" + profile.minimumExpectedCount());
        }
        if (estimate.distinctVesselCount()
                < profile.minimumDistinctVessels()) {
            reasons.add("MMSI数<" + profile.minimumDistinctVessels());
        }
        if (estimate.observedDayCount() < MINIMUM_OBSERVED_DAYS) {
            reasons.add("観測日数<" + MINIMUM_OBSERVED_DAYS);
        }
        if (interval == null) {
            reasons.add("信頼区間なし");
        } else if (interval.width() > MAXIMUM_CI_WIDTH) {
            reasons.add("95%信頼区間幅>20ポイント");
        }
        return List.copyOf(reasons);
    }
}
