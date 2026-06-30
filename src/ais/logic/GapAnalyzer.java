package ais.logic;

import ais.model.AisMessage;
import ais.model.Vessel;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class GapAnalyzer {

    public void analyze(Vessel vessel) {

        Map<Integer, AisMessage> currentByType =
                new HashMap<>();

        Map<Integer, Double> expectedByType =
                new HashMap<>();

        Map<Integer, ReportRateTracker> trackers =
                new HashMap<>();

        trackers.put(1, new ReportRateTracker());
        trackers.put(5, new ReportRateTracker());
        trackers.put(18, new ReportRateTracker());

        for (AisMessage message : vessel.getMessages()) {

            int analysisType =
                    AisAnalysisRules.getAnalysisType(
                            message.messageType);

            if (analysisType < 0) {
                continue;
            }

            double nextExpected =
                    trackers.get(analysisType).accept(message);

            AisMessage current =
                    currentByType.get(analysisType);

            if (current != null) {

                double actualSeconds =
                        Duration.between(
                                current.timestamp,
                                message.timestamp)
                                .toMillis() / 1000.0;

                double expectedSeconds =
                        expectedByType.get(analysisType);

                if (actualSeconds >= 0
                        && actualSeconds
                        < AisAnalysisRules
                                .getTrackGapThresholdSeconds(
                                        analysisType)) {

                    LossEstimator.estimateMissingMessages(
                            actualSeconds,
                            expectedSeconds);
                }
            }

            currentByType.put(analysisType, message);
            expectedByType.put(analysisType, nextExpected);
        }
    }
}
