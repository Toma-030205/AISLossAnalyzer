package ais.logic;

import ais.model.AisMessage;

public class ReportRateTable {

    public static double getExpectedInterval(
        AisMessage msg,
        AisMessage prev) {

    // Type5
    if (msg.messageType == 5) {
        return 360;
    }

    // Type18（Class B）
    if (msg.messageType == 18) {

        if (msg.sog == null) {
            return 180;
        }

        if (msg.sog < 2.0) {
            return 180;
        }

        if (msg.sog < 14) {
            return 30;
        }

        if (msg.sog < 23) {
            return 15;
        }

        return 5;
    }

    // Class A
    if (!(msg.messageType == 1
            || msg.messageType == 2
            || msg.messageType == 3)) {

        return -1;
    }

    if (msg.sog == null) {
        return 180;
    }

    double sog = msg.sog;

    boolean turning = false;

    if (prev != null
            && prev.cog != null
            && msg.cog != null) {

        double diff =
                Math.abs(
                        msg.cog
                                - prev.cog);

        if (diff > 180) {
            diff = 360 - diff;
        }

        turning =
                diff > 5.0;
    }

    // 錨泊・停泊
    if (msg.navStatus != null
            && (msg.navStatus == 1
            || msg.navStatus == 5)) {

        if (sog <= 3) {
            return 180;
        }

        return 10;
    }

    // 航行中

    if (sog <= 14) {

        if (turning) {
            return 3.33;
        }

        return 10;
    }

    if (sog <= 23) {

        if (turning) {
            return 2;
        }

        return 6;
    }

    return 2;
}
}