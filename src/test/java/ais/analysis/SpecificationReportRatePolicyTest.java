package ais.analysis;

import ais.domain.AnalysisProfile;
import ais.domain.ClassBReportingMode;
import ais.domain.GeoPosition;
import ais.domain.PositionReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpecificationReportRatePolicyTest {

    private static final double EPSILON = 0.000_001;

    private final ReportRatePolicy policy =
            new SpecificationReportRatePolicy(
                    AnalysisProfile.phaseOneDefaults());

    @ParameterizedTest
    @CsvSource({
            "14.0, false, 10.0",
            "14.0, true, 3.3333333333333335",
            "14.1, false, 6.0",
            "14.1, true, 2.0",
            "23.0, false, 6.0",
            "23.0, true, 2.0",
            "23.1, false, 2.0",
            "23.1, true, 2.0"
    })
    void calculatesClassABoundaries(
            double sog,
            boolean changingCourse,
            double expectedSeconds) {
        PositionReport report = report(
                1,
                0,
                sog,
                90.0,
                0,
                ClassBReportingMode.UNKNOWN);

        assertEquals(
                expectedSeconds,
                policy.expectedIntervalSeconds(report, changingCourse),
                EPSILON);
    }

    @Test
    void appliesAnchoredAndMooredRules() {
        assertEquals(180.0, intervalForClassA(1, null), EPSILON);
        assertEquals(180.0, intervalForClassA(5, 3.0), EPSILON);
        assertEquals(10.0, intervalForClassA(1, 3.1), EPSILON);
        assertEquals(10.0, intervalForClassA(0, null), EPSILON);
    }

    @ParameterizedTest
    @CsvSource({
            "2.0, CARRIER_SENSE, false, 180.0",
            "2.1, CARRIER_SENSE, false, 30.0",
            "14.0, CARRIER_SENSE, true, 30.0",
            "14.1, CARRIER_SENSE, true, 30.0",
            "23.1, CARRIER_SENSE, true, 30.0",
            "2.0, SELF_ORGANIZING, false, 180.0",
            "14.0, SELF_ORGANIZING, true, 30.0",
            "14.1, SELF_ORGANIZING, false, 15.0",
            "14.1, SELF_ORGANIZING, true, 5.0",
            "23.0, SELF_ORGANIZING, false, 15.0",
            "23.1, SELF_ORGANIZING, false, 5.0",
            "20.0, UNKNOWN, true, 5.0"
    })
    void calculatesClassBBoundaries(
            double sog,
            ClassBReportingMode reportingMode,
            boolean changingCourse,
            double expectedSeconds) {
        PositionReport report = report(
                18,
                0,
                sog,
                90.0,
                null,
                reportingMode);

        assertEquals(
                expectedSeconds,
                policy.expectedIntervalSeconds(report, changingCourse),
                EPSILON);
    }

    private double intervalForClassA(Integer navStatus, Double sog) {
        return policy.expectedIntervalSeconds(
                report(
                        1,
                        0,
                        sog,
                        90.0,
                        navStatus,
                        ClassBReportingMode.UNKNOWN),
                false);
    }

    private static PositionReport report(
            int type,
            long seconds,
            Double sog,
            Double heading,
            Integer navStatus,
            ClassBReportingMode mode) {
        return new PositionReport(
                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seconds),
                seconds,
                type,
                123_456_789,
                new GeoPosition(34.7, 135.3),
                sog,
                heading,
                heading,
                navStatus,
                mode,
                false);
    }
}
