package ais.logic;

import ais.model.AisMessage;

public final class ReportRateTable {

    private static final double CLASS_B_CS_HIGH_SPEED_INTERVAL =
            readClassBCsHighSpeedInterval();

    private ReportRateTable() {
    }

    public static double getExpectedInterval(
            AisMessage message,
            AisMessage previous) {

        return getExpectedInterval(
                message,
                isChangingCourse(message, previous));
    }

    public static double getExpectedInterval(
            AisMessage message,
            boolean changingCourse) {

        if (message.messageType == 5) {
            return 360.0;
        }

        if (message.messageType == 18) {
            return getClassBInterval(message, changingCourse);
        }

        if (message.messageType == 1
                || message.messageType == 2
                || message.messageType == 3) {

            return getClassAInterval(message, changingCourse);
        }

        return -1.0;
    }

    private static double getClassAInterval(
            AisMessage message,
            boolean changingCourse) {

        boolean anchoredOrMoored =
                message.navStatus != null
                        && (message.navStatus == 1
                        || message.navStatus == 5);

        if (message.sog == null) {
            return anchoredOrMoored ? 180.0 : 10.0;
        }

        double sog = message.sog;

        if (anchoredOrMoored) {
            return sog <= 3.0 ? 180.0 : 10.0;
        }

        if (sog <= 14.0) {
            return changingCourse ? 10.0 / 3.0 : 10.0;
        }

        if (sog <= 23.0) {
            return changingCourse ? 2.0 : 6.0;
        }

        return 2.0;
    }

    private static double getClassBInterval(
            AisMessage message,
            boolean changingCourse) {

        if (message.sog == null || message.sog <= 2.0) {
            return 180.0;
        }

        double sog = message.sog;

        if (Boolean.TRUE.equals(message.classBCsUnit)) {
            return sog <= 14.0
                    ? 30.0
                    : CLASS_B_CS_HIGH_SPEED_INTERVAL;
        }

        if (sog <= 14.0) {
            return 30.0;
        }

        if (sog <= 23.0) {
            return changingCourse ? 5.0 : 15.0;
        }

        return 5.0;
    }

    private static boolean isChangingCourse(
            AisMessage message,
            AisMessage previous) {

        if (previous == null
                || !isCourseChangeApplicable(message)) {

            return false;
        }

        Double currentDirection =
                getCourseChangeDirection(message);
        Double previousDirection =
                getMatchingDirection(
                        previous,
                        message.trueHeading != null);

        return currentDirection != null
                && previousDirection != null
                && angularDifference(
                        currentDirection,
                        previousDirection) > 5.0;
    }

    static boolean isCourseChangeApplicable(AisMessage message) {

        return message.messageType == 1
                || message.messageType == 2
                || message.messageType == 3
                || (message.messageType == 18
                && !Boolean.TRUE.equals(message.classBCsUnit));
    }

    static Double getCourseChangeDirection(AisMessage message) {

        return getMatchingDirection(
                message,
                message.trueHeading != null);
    }

    static Double getMatchingDirection(
            AisMessage message,
            boolean useHeading) {

        if (useHeading) {
            return message.trueHeading;
        }

        if (message.sog == null
                || message.sog <= 2.0) {

            return null;
        }

        return message.cog;
    }

    static double angularDifference(
            double first,
            double second) {

        double difference =
                Math.abs(first - second) % 360.0;

        return difference > 180.0
                ? 360.0 - difference
                : difference;
    }

    private static double readClassBCsHighSpeedInterval() {

        String configured =
                System.getProperty(
                        "ais.classBCsHighSpeedIntervalSeconds",
                        "30");

        double interval;

        try {
            interval = Double.parseDouble(configured);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "ais.classBCsHighSpeedIntervalSeconds must be a number: "
                            + configured,
                    e);
        }

        if (!Double.isFinite(interval) || interval <= 0) {
            throw new IllegalArgumentException(
                    "ais.classBCsHighSpeedIntervalSeconds must be greater "
                            + "than 0: " + configured);
        }

        return interval;
    }
}
