package ais.logic;

import ais.model.AisMessage;

public class ReportRateTable {

    public static long getExpectedInterval(
            AisMessage msg) {

        if (msg.sog == null) {
            return 180;
        }

        double sog = msg.sog;

        // 停泊・低速
        if (sog < 0.5) {
            return 180;
        }

        // 通常航行
        if (sog < 14.0) {
            return 10;
        }

        if (msg.messageType == 5) {
            return 360;
        }

        // 高速
        return 6;
    }
}