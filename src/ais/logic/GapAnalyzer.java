package ais.logic;

import ais.model.AisMessage;
import ais.model.Vessel;

import java.time.Duration;
import java.util.List;

public class GapAnalyzer {

    public void analyze(Vessel vessel) {

        List<AisMessage> messages =
                vessel.getMessages();

        if (messages.size() < 2) {
            return;
        }

        for (int i = 0;
             i < messages.size() - 1;
             i++) {

            AisMessage current =
                    messages.get(i);

            AisMessage next =
                    messages.get(i + 1);

            double actualDeltaRaw =
                    Duration.between(
                        current.timestamp,
                        next.timestamp
                    ).toMillis() / 1000.0;

            long actualDelta =
                    Math.round(actualDeltaRaw);

            long expectedDelta =
                    ReportRateTable
                            .getExpectedInterval(
                                    current);

            // 欠落推定回数
            long estimatedLoss =
                    Math.round(
                                (double) actualDelta
                                    / expectedDelta
                    ) - 1;

            // debug output
            /*
            System.out.println(
                    "MMSI="
                            + vessel.getMmsi()
                            + " EXPECTED="
                            + expectedDelta
                            + " ACTUAL="
                            + actualDelta
                            + " LOSS_EST="
                            + estimatedLoss
            );
            */
        }
    }
}