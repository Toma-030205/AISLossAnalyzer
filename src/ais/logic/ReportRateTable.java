package ais.logic;

import ais.model.AisMessage;

public class ReportRateTable {

    public static long getExpectedInterval(
            AisMessage msg,
            AisMessage prev) {

        // =========================
        // Type5
        // =========================

        if (msg.messageType == 5) {
            return 360;
        }

        // =========================
        // Type1のみ厳密解析
        // =========================

        if (msg.messageType != 1) {
            return -1;
        }

        if (msg.sog == null) {
            return 180;
        }

        double sog = msg.sog;

        // =========================
        // 進路変更判定
        // =========================

        boolean turning = false;

        if (prev != null
                && prev.cog != null
                && msg.cog != null) {

            double diff =
                    Math.abs(msg.cog - prev.cog);

            if (diff > 180) {
                diff = 360 - diff;
            }

            // 閾値は後で調整可能
            turning = diff > 5.0;
        }

        // =========================
        // 錨泊
        // =========================

        if (sog < 0.5) {
            return 180;
        }

        // =========================
        // 14kt未満
        // =========================

        if (sog < 14.0) {

            if (turning) {
                return 4;
            }

            return 12;
        }

        // =========================
        // 14-23kt
        // =========================

        if (sog < 23.0) {

            if (turning) {
                return 2;
            }

            return 6;
        }

        // =========================
        // 23kt以上
        // =========================

        if (turning) {
            return 2;
        }

        return 3;
    }
}