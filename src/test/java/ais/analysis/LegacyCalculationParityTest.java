package ais.analysis;

import ais.decode.LegacyMessageAdapter;
import ais.domain.AnalysisProfile;
import ais.domain.PositionReport;
import ais.logic.ReportRateTable;
import ais.logic.ReportRateTracker;
import ais.model.AisMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyCalculationParityTest {

    private static final double EPSILON = 0.000_001;
    private static final LocalDateTime BASE_TIME =
            LocalDateTime.of(2026, 1, 1, 0, 0);

    private final LegacyMessageAdapter adapter =
            new LegacyMessageAdapter();
    private final ReportRatePolicy newPolicy =
            new SpecificationReportRatePolicy(
                    AnalysisProfile.phaseOneDefaults());

    @Test
    void staticRatePolicyMatchesLegacyBoundaries() {
        List<AisMessage> cases = List.of(
                message(1, 0, null, null, 0, null),
                message(1, 1, null, null, 1, null),
                message(1, 2, 3.0, 90.0, 5, null),
                message(1, 3, 3.1, 90.0, 5, null),
                message(1, 4, 14.0, 90.0, 0, null),
                message(1, 5, 14.1, 90.0, 0, null),
                message(1, 6, 23.0, 90.0, 0, null),
                message(1, 7, 23.1, 90.0, 0, null),
                message(18, 8, 2.0, 90.0, null, true),
                message(18, 9, 2.1, 90.0, null, true),
                message(18, 10, 14.1, 90.0, null, true),
                message(18, 11, 14.1, 90.0, null, false),
                message(18, 12, 23.1, 90.0, null, false));

        long sequence = 0;
        for (AisMessage legacy : cases) {
            PositionReport report = adapt(legacy, sequence++);

            for (boolean changingCourse : List.of(false, true)) {
                assertEquals(
                        ReportRateTable.getExpectedInterval(
                                legacy,
                                changingCourse),
                        newPolicy.expectedIntervalSeconds(
                                report,
                                changingCourse),
                        EPSILON,
                        "type=" + legacy.messageType
                                + ", sog=" + legacy.sog
                                + ", changing=" + changingCourse);
            }
        }
    }

    @Test
    void statefulCourseTrackingMatchesLegacy() {
        List<AisMessage> sequence = List.of(
                message(1, 0, 5.0, 0.0, 0, null),
                message(1, 10, 5.0, 6.0, 0, null),
                message(1, 20, 5.0, 6.0, 0, null),
                message(1, 41, 5.0, 6.0, 0, null));

        ReportRateTracker legacyTracker = new ReportRateTracker();
        CourseChangeTracker newTracker = new CourseChangeTracker();

        long inputSequence = 0;
        for (AisMessage legacy : sequence) {
            PositionReport report = adapt(legacy, inputSequence++);
            boolean changingCourse = newTracker.accept(report);

            assertEquals(
                    legacyTracker.accept(legacy),
                    newPolicy.expectedIntervalSeconds(
                            report,
                            changingCourse),
                    EPSILON);
        }
    }

    @Test
    void lossEstimationMatchesLegacy() {
        double[][] cases = {
                {0.0, 10.0},
                {14.999, 10.0},
                {15.0, 10.0},
                {1_799.0, 180.0}
        };

        for (double[] interval : cases) {
            assertEquals(
                    ais.logic.LossEstimator.estimateMissingMessages(
                            interval[0],
                            interval[1]),
                    LossEstimator.estimateMissingMessages(
                            interval[0],
                            interval[1]));
        }
    }

    private PositionReport adapt(AisMessage message, long sequence) {
        return (PositionReport) adapter.adapt(message, sequence).orElseThrow();
    }

    private static AisMessage message(
            int type,
            long seconds,
            Double sog,
            Double direction,
            Integer navStatus,
            Boolean classBCsUnit) {
        AisMessage message = new AisMessage();
        message.messageType = type;
        message.mmsi = 123_456_789;
        message.timestamp = BASE_TIME.plusSeconds(seconds);
        message.lat = 34.7;
        message.lon = 135.3;
        message.sog = sog;
        message.cog = direction;
        message.trueHeading = direction;
        message.navStatus = navStatus;
        message.classBCsUnit = classBCsUnit;
        return message;
    }
}
