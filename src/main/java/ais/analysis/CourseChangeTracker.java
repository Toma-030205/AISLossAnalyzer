package ais.analysis;

import ais.domain.PositionReport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public final class CourseChangeTracker {

    static final Duration COURSE_AVERAGE_WINDOW =
            Duration.ofSeconds(30);
    static final Duration COURSE_RELEASE_DELAY =
            Duration.ofSeconds(20);
    static final double COURSE_CHANGE_THRESHOLD_DEGREES = 5.0;

    private final Deque<PositionReport> history = new ArrayDeque<>();

    private boolean changingCourse;
    private Instant belowThresholdSince;

    public boolean accept(PositionReport report) {
        Objects.requireNonNull(report, "report");

        boolean currentChangingCourse = updateState(report);
        history.addLast(report);
        return currentChangingCourse;
    }

    public boolean isChangingCourse() {
        return changingCourse;
    }

    public void reset() {
        history.clear();
        resetCourseChange();
    }

    private boolean updateState(PositionReport report) {
        pruneHistory(report.receivedAt());

        if (!isCourseChangeApplicable(report)) {
            resetCourseChange();
            return false;
        }

        boolean useHeading = report.trueHeadingDegrees() != null;
        Double currentDirection = directionFor(report, useHeading);

        if (currentDirection == null) {
            resetCourseChange();
            return false;
        }

        Double meanDirection = circularMean(useHeading);

        if (meanDirection == null) {
            resetCourseChange();
            return false;
        }

        double difference = angularDifference(
                currentDirection,
                meanDirection);

        if (difference > COURSE_CHANGE_THRESHOLD_DEGREES) {
            changingCourse = true;
            belowThresholdSince = null;
            return true;
        }

        if (!changingCourse) {
            return false;
        }

        if (belowThresholdSince == null) {
            belowThresholdSince = report.receivedAt();
            return true;
        }

        Duration belowThresholdDuration = Duration.between(
                belowThresholdSince,
                report.receivedAt());

        if (belowThresholdDuration.compareTo(COURSE_RELEASE_DELAY) > 0) {
            resetCourseChange();
        }

        return changingCourse;
    }

    private void pruneHistory(Instant currentTime) {
        while (!history.isEmpty()) {
            Duration age = Duration.between(
                    history.peekFirst().receivedAt(),
                    currentTime);

            if (age.isNegative()) {
                reset();
                return;
            }
            if (age.compareTo(COURSE_AVERAGE_WINDOW) <= 0) {
                return;
            }
            history.removeFirst();
        }
    }

    private Double circularMean(boolean useHeading) {
        double sineSum = 0.0;
        double cosineSum = 0.0;
        int count = 0;

        for (PositionReport sample : history) {
            Double direction = directionFor(sample, useHeading);

            if (direction == null) {
                continue;
            }

            double radians = Math.toRadians(direction);
            sineSum += Math.sin(radians);
            cosineSum += Math.cos(radians);
            count++;
        }

        if (count == 0) {
            return null;
        }

        double mean = Math.toDegrees(Math.atan2(sineSum, cosineSum));
        return mean < 0.0 ? mean + 360.0 : mean;
    }

    private static boolean isCourseChangeApplicable(
            PositionReport report) {
        return !report.isClassBCarrierSense();
    }

    private static Double directionFor(
            PositionReport report,
            boolean useHeading) {
        if (useHeading) {
            return report.trueHeadingDegrees();
        }

        if (report.sogKnots() == null
                || report.sogKnots() <= 2.0) {
            return null;
        }
        return report.cogDegrees();
    }

    static double angularDifference(double first, double second) {
        double difference = Math.abs(first - second) % 360.0;
        return difference > 180.0
                ? 360.0 - difference
                : difference;
    }

    private void resetCourseChange() {
        changingCourse = false;
        belowThresholdSince = null;
    }
}
