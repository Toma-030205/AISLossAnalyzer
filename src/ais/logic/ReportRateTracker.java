package ais.logic;

import ais.model.AisMessage;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;

public class ReportRateTracker {

    private static final long COURSE_AVERAGE_SECONDS = 30;
    private static final long COURSE_RELEASE_SECONDS = 20;

    private final Deque<AisMessage> history =
            new ArrayDeque<>();

    private boolean changingCourse;
    private LocalDateTime belowThresholdSince;

    public double accept(AisMessage message) {

        boolean currentChangingCourse =
                updateCourseChangeState(message);

        double expectedInterval =
                ReportRateTable.getExpectedInterval(
                        message,
                        currentChangingCourse);

        history.addLast(message);
        return expectedInterval;
    }

    private boolean updateCourseChangeState(AisMessage message) {

        pruneHistory(message.timestamp);

        if (!ReportRateTable.isCourseChangeApplicable(message)) {
            resetCourseChange();
            return false;
        }

        boolean useHeading = message.trueHeading != null;
        Double currentDirection =
                ReportRateTable.getMatchingDirection(
                        message,
                        useHeading);

        if (currentDirection == null) {
            resetCourseChange();
            return false;
        }

        Double meanDirection =
                calculateCircularMean(useHeading);

        if (meanDirection == null) {
            resetCourseChange();
            return false;
        }

        double difference =
                ReportRateTable.angularDifference(
                        currentDirection,
                        meanDirection);

        if (difference > 5.0) {
            changingCourse = true;
            belowThresholdSince = null;
            return true;
        }

        if (!changingCourse) {
            return false;
        }

        if (belowThresholdSince == null) {
            belowThresholdSince = message.timestamp;
            return true;
        }

        long belowThresholdMillis =
                Duration.between(
                        belowThresholdSince,
                        message.timestamp)
                        .toMillis();

        if (belowThresholdMillis
                > COURSE_RELEASE_SECONDS * 1000L) {

            resetCourseChange();
        }

        return changingCourse;
    }

    private void pruneHistory(LocalDateTime currentTime) {

        while (!history.isEmpty()) {

            long ageMillis =
                    Duration.between(
                            history.peekFirst().timestamp,
                            currentTime)
                            .toMillis();

            if (ageMillis < 0) {
                history.clear();
                resetCourseChange();
                return;
            }

            if (ageMillis <= COURSE_AVERAGE_SECONDS * 1000L) {
                return;
            }

            history.removeFirst();
        }
    }

    private Double calculateCircularMean(boolean useHeading) {

        double sineSum = 0.0;
        double cosineSum = 0.0;
        int count = 0;

        for (AisMessage sample : history) {

            Double direction =
                    ReportRateTable.getMatchingDirection(
                            sample,
                            useHeading);

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

        double mean =
                Math.toDegrees(
                        Math.atan2(sineSum, cosineSum));

        return mean < 0 ? mean + 360.0 : mean;
    }

    private void resetCourseChange() {

        changingCourse = false;
        belowThresholdSince = null;
    }
}
