package ais.logic;

import ais.model.AisMessage;
import ais.parser.AisDecoder;

import java.time.LocalDateTime;

public class AisCoreCalculationTest {

    private static final LocalDateTime BASE_TIME =
            LocalDateTime.of(2026, 1, 1, 0, 0);

    public static void main(String[] args) {

        testLossEstimationUsesRawTime();
        testClassAIntervals();
        testClassBIntervalsAndBoundaries();
        testCourseChangeTracking();
        testUnavailableValuesAndClassBFlags();

        System.out.println("All AIS core calculation tests passed.");
    }

    private static void testLossEstimationUsesRawTime() {

        assertEquals(
                0,
                LossEstimator.estimateMissingMessages(361.0, 360.0),
                "361 seconds at a 360-second interval");

        assertEquals(
                0,
                LossEstimator.estimateMissingMessages(539.999, 360.0),
                "just below 1.5 Type 5 intervals");

        assertEquals(
                1,
                LossEstimator.estimateMissingMessages(540.0, 360.0),
                "1.5 Type 5 intervals");

        assertEquals(
                0,
                LossEstimator.estimateMissingMessages(2.51, 2.0),
                "raw sub-second time at a two-second interval");

        assertEquals(
                1,
                LossEstimator.estimateMissingMessages(3.0, 2.0),
                "1.5 two-second intervals");
    }

    private static void testClassAIntervals() {

        AisMessage underway = position(1, 0, 5.0, 0.0);

        assertDoubleEquals(
                10.0,
                ReportRateTable.getExpectedInterval(underway, false),
                "Class A underway interval");

        assertDoubleEquals(
                10.0 / 3.0,
                ReportRateTable.getExpectedInterval(underway, true),
                "Class A changing-course interval");

        AisMessage noSpeed = position(1, 0, null, 0.0);

        assertDoubleEquals(
                10.0,
                ReportRateTable.getExpectedInterval(noSpeed, false),
                "Class A no-speed default while underway");

        noSpeed.navStatus = 1;

        assertDoubleEquals(
                180.0,
                ReportRateTable.getExpectedInterval(noSpeed, false),
                "Class A no-speed default at anchor");
    }

    private static void testClassBIntervalsAndBoundaries() {

        assertClassBInterval(2.0, true, 180.0);
        assertClassBInterval(2.1, true, 30.0);
        assertClassBInterval(14.0, true, 30.0);
        assertClassBInterval(14.1, true, 30.0);
        assertClassBInterval(23.0, true, 30.0);
        assertClassBInterval(23.1, true, 30.0);

        assertClassBInterval(2.0, false, 180.0);
        assertClassBInterval(14.0, false, 30.0);
        assertClassBInterval(23.0, false, 15.0);
        assertClassBInterval(23.1, false, 5.0);
    }

    private static void testCourseChangeTracking() {

        ReportRateTracker classATracker =
                new ReportRateTracker();

        assertDoubleEquals(
                10.0,
                classATracker.accept(position(1, 0, 5.0, 0.0)),
                "first Class A report");

        assertDoubleEquals(
                10.0 / 3.0,
                classATracker.accept(position(1, 10, 5.0, 6.0)),
                "Class A starts changing course");

        assertDoubleEquals(
                10.0 / 3.0,
                classATracker.accept(position(1, 20, 5.0, 6.0)),
                "Class A keeps the faster rate during release delay");

        assertDoubleEquals(
                10.0,
                classATracker.accept(position(1, 41, 5.0, 6.0)),
                "Class A leaves changing-course state after 20 seconds");

        ReportRateTracker slowCogTracker =
                new ReportRateTracker();

        AisMessage slowFirst = position(1, 0, 1.0, null);
        slowFirst.cog = 0.0;
        AisMessage slowSecond = position(1, 10, 1.0, null);
        slowSecond.cog = 100.0;

        slowCogTracker.accept(slowFirst);

        assertDoubleEquals(
                10.0,
                slowCogTracker.accept(slowSecond),
                "COG must not trigger course change at two knots or less");

        ReportRateTracker classBSoTracker =
                new ReportRateTracker();

        AisMessage soFirst = position(18, 0, 20.0, 0.0);
        soFirst.classBCsUnit = false;
        AisMessage soSecond = position(18, 10, 20.0, 6.0);
        soSecond.classBCsUnit = false;

        classBSoTracker.accept(soFirst);

        assertDoubleEquals(
                5.0,
                classBSoTracker.accept(soSecond),
                "Class B SO changing-course interval");

        ReportRateTracker classBCsTracker =
                new ReportRateTracker();

        AisMessage csFirst = position(18, 0, 20.0, 0.0);
        csFirst.classBCsUnit = true;
        AisMessage csSecond = position(18, 10, 20.0, 90.0);
        csSecond.classBCsUnit = true;

        classBCsTracker.accept(csFirst);

        assertDoubleEquals(
                30.0,
                classBCsTracker.accept(csSecond),
                "Class B CS must ignore changing-course rate");
    }

    private static void testUnavailableValuesAndClassBFlags() {

        char[] bits = new char[168];
        java.util.Arrays.fill(bits, '0');

        setUnsigned(bits, 0, 6, 18);
        setUnsigned(bits, 8, 30, 123456789);
        setUnsigned(bits, 46, 10, 1023);
        setUnsigned(bits, 112, 12, 3600);
        setUnsigned(bits, 124, 9, 511);
        bits[141] = '1';
        bits[146] = '1';

        AisMessage decoded =
                AisDecoder.decode(
                        "!AIVDM,1,1,,A,"
                                + encodePayload(bits)
                                + ",0*00");

        assertNotNull(decoded, "decoded Message 18");
        assertNull(decoded.sog, "unavailable SOG");
        assertNull(decoded.cog, "unavailable COG");
        assertNull(decoded.trueHeading, "unavailable heading");
        assertTrue(decoded.classBCsUnit, "Class B CS flag");
        assertTrue(decoded.assignedMode, "assigned mode flag");
    }

    private static void assertClassBInterval(
            double sog,
            boolean csUnit,
            double expected) {

        AisMessage message = position(18, 0, sog, 0.0);
        message.classBCsUnit = csUnit;

        assertDoubleEquals(
                expected,
                ReportRateTable.getExpectedInterval(message, false),
                "Class B interval at " + sog + " knots, CS=" + csUnit);
    }

    private static AisMessage position(
            int type,
            long seconds,
            Double sog,
            Double heading) {

        AisMessage message = new AisMessage();
        message.messageType = type;
        message.mmsi = 123456789;
        message.timestamp = BASE_TIME.plusSeconds(seconds);
        message.lat = 34.7;
        message.lon = 135.3;
        message.sog = sog;
        message.cog = heading;
        message.trueHeading = heading;
        return message;
    }

    private static void setUnsigned(
            char[] bits,
            int start,
            int length,
            int value) {

        for (int index = length - 1; index >= 0; index--) {
            bits[start + index] =
                    (value & 1) == 1 ? '1' : '0';
            value >>>= 1;
        }
    }

    private static String encodePayload(char[] bits) {

        StringBuilder payload = new StringBuilder();

        for (int offset = 0; offset < bits.length; offset += 6) {

            int value = 0;

            for (int bit = 0; bit < 6; bit++) {
                value = (value << 1) | (bits[offset + bit] - '0');
            }

            payload.append(
                    (char) (value < 40 ? value + 48 : value + 56));
        }

        return payload.toString();
    }

    private static void assertEquals(
            long expected,
            long actual,
            String label) {

        if (expected != actual) {
            throw new AssertionError(
                    label + ": expected " + expected
                            + " but was " + actual);
        }
    }

    private static void assertDoubleEquals(
            double expected,
            double actual,
            String label) {

        if (Math.abs(expected - actual) > 0.000001) {
            throw new AssertionError(
                    label + ": expected " + expected
                            + " but was " + actual);
        }
    }

    private static void assertNull(Object actual, String label) {

        if (actual != null) {
            throw new AssertionError(
                    label + ": expected null but was " + actual);
        }
    }

    private static void assertNotNull(Object actual, String label) {

        if (actual == null) {
            throw new AssertionError(label + ": expected a value");
        }
    }

    private static void assertTrue(Boolean actual, String label) {

        if (!Boolean.TRUE.equals(actual)) {
            throw new AssertionError(
                    label + ": expected true but was " + actual);
        }
    }
}
