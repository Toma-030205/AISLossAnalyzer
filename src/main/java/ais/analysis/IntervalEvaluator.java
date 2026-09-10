package ais.analysis;

import ais.domain.AnalysisProfile;
import ais.domain.GeoPosition;
import ais.domain.PositionReport;
import ais.domain.ReceiverProfile;
import ais.spatial.DistanceCalculator;

import java.time.Duration;
import java.util.Objects;

public final class IntervalEvaluator {

    private final ReceiverProfile receiver;
    private final AnalysisProfile profile;
    private final DistanceCalculator distanceCalculator;

    public IntervalEvaluator(
            ReceiverProfile receiver,
            AnalysisProfile profile,
            DistanceCalculator distanceCalculator) {
        this.receiver = Objects.requireNonNull(receiver, "receiver");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.distanceCalculator = Objects.requireNonNull(
                distanceCalculator,
                "distanceCalculator");
    }

    public IntervalEvaluation evaluate(
            PositionReport start,
            PositionReport end,
            double expectedIntervalSeconds) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");

        if (start.position().isZeroPosition()
                || end.position().isZeroPosition()) {
            return excluded(IntervalExclusionReason.INVALID_POSITION);
        }
        if (!Double.isFinite(expectedIntervalSeconds)
                || expectedIntervalSeconds <= 0.0) {
            return excluded(
                    IntervalExclusionReason.EXPECTED_INTERVAL_UNAVAILABLE);
        }

        Duration duration = Duration.between(
                start.receivedAt(),
                end.receivedAt());
        if (duration.isNegative()) {
            return excluded(IntervalExclusionReason.NEGATIVE_INTERVAL);
        }
        if (duration.compareTo(profile.trackGapThreshold()) >= 0) {
            return excluded(
                    IntervalExclusionReason.GAP_30_MINUTES_OR_MORE);
        }

        GeoPosition receiverPosition = receiver.position();
        double startDistance = distanceCalculator.distanceKilometers(
                receiverPosition,
                start.position());
        double endDistance = distanceCalculator.distanceKilometers(
                receiverPosition,
                end.position());
        if (Math.abs(endDistance - startDistance)
                > profile.maximumDistanceJumpKilometers()) {
            return excluded(
                    IntervalExclusionReason.DISTANCE_JUMP_OVER_30_KM);
        }

        double actualSeconds = duration.toNanos() / 1_000_000_000.0;
        return new IntervalEvaluation.Accepted(new IntervalCandidate(
                start,
                end,
                expectedIntervalSeconds,
                actualSeconds,
                startDistance,
                endDistance));
    }

    private static IntervalEvaluation excluded(
            IntervalExclusionReason reason) {
        return new IntervalEvaluation.Excluded(reason);
    }
}
