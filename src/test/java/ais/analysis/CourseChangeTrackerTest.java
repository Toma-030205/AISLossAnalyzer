package ais.analysis;

import ais.domain.ClassBReportingMode;
import ais.domain.GeoPosition;
import ais.domain.PositionReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CourseChangeTrackerTest {

    @Test
    void detectsChangeAndReleasesAfterMoreThanTwentySeconds() {
        CourseChangeTracker tracker = new CourseChangeTracker();

        assertFalse(tracker.accept(report(1, 0, 5.0, 0.0, 0.0,
                ClassBReportingMode.UNKNOWN)));
        assertTrue(tracker.accept(report(1, 10, 5.0, 6.0, 6.0,
                ClassBReportingMode.UNKNOWN)));
        assertTrue(tracker.accept(report(1, 20, 5.0, 6.0, 6.0,
                ClassBReportingMode.UNKNOWN)));
        assertFalse(tracker.accept(report(1, 41, 5.0, 6.0, 6.0,
                ClassBReportingMode.UNKNOWN)));
    }

    @Test
    void handlesCircularDirectionAroundNorth() {
        CourseChangeTracker tracker = new CourseChangeTracker();

        assertFalse(tracker.accept(report(1, 0, 8.0, 359.0, 359.0,
                ClassBReportingMode.UNKNOWN)));
        assertFalse(tracker.accept(report(1, 10, 8.0, 1.0, 1.0,
                ClassBReportingMode.UNKNOWN)));
    }

    @Test
    void ignoresCogAtTwoKnotsOrLess() {
        CourseChangeTracker tracker = new CourseChangeTracker();

        assertFalse(tracker.accept(report(1, 0, 2.0, 0.0, null,
                ClassBReportingMode.UNKNOWN)));
        assertFalse(tracker.accept(report(1, 10, 2.0, 100.0, null,
                ClassBReportingMode.UNKNOWN)));
    }

    @Test
    void ignoresCourseChangeForClassBCarrierSense() {
        CourseChangeTracker tracker = new CourseChangeTracker();

        assertFalse(tracker.accept(report(18, 0, 20.0, 0.0, 0.0,
                ClassBReportingMode.CARRIER_SENSE)));
        assertFalse(tracker.accept(report(18, 10, 20.0, 90.0, 90.0,
                ClassBReportingMode.CARRIER_SENSE)));
    }

    @Test
    void resetCutsContinuityAtLivePauseBoundary() {
        CourseChangeTracker tracker = new CourseChangeTracker();

        tracker.accept(report(1, 0, 5.0, 0.0, 0.0,
                ClassBReportingMode.UNKNOWN));
        assertTrue(tracker.accept(report(1, 10, 5.0, 10.0, 10.0,
                ClassBReportingMode.UNKNOWN)));

        tracker.reset();

        assertFalse(tracker.accept(report(1, 20, 5.0, 90.0, 90.0,
                ClassBReportingMode.UNKNOWN)));
    }

    private static PositionReport report(
            int type,
            long seconds,
            Double sog,
            Double cog,
            Double heading,
            ClassBReportingMode mode) {
        return new PositionReport(
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seconds),
                seconds,
                type,
                123_456_789,
                new GeoPosition(34.7, 135.3),
                sog,
                cog,
                heading,
                type == 18 ? null : 0,
                mode,
                false);
    }
}
