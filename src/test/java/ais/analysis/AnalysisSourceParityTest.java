package ais.analysis;

import ais.aggregate.AggregationSnapshot;
import ais.domain.AnalysisContext;
import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.input.AisInputPipeline;
import ais.input.PipelineResult;
import ais.input.ReceivedNmea;
import ais.input.SourceReference;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisSourceParityTest {

    private static final Instant START =
            Instant.parse("2026-09-04T00:00:00Z");
    private static final int MMSI = 431_100_001;

    @Test
    void historicalAndLiveInputProduceIdenticalAnalysisResults() {
        AnalysisRunSummary historical = analyze(SourceMode.HISTORICAL);
        AnalysisRunSummary live = analyze(SourceMode.LIVE);

        assertEquals(historical.acceptedIntervalCount(),
                live.acceptedIntervalCount());
        assertEquals(historical.estimatedMissingCount(),
                live.estimatedMissingCount());
        assertEquals(historical.excludedIntervalCounts(),
                live.excludedIntervalCounts());
        assertEquals(historical.aggregation(), live.aggregation());
    }

    private static AnalysisRunSummary analyze(SourceMode mode) {
        AisInputPipeline pipeline = new AisInputPipeline();
        DefaultAnalysisEngine engine = new DefaultAnalysisEngine();
        engine.begin(new AnalysisContext(
                receiver(),
                AnalysisProfile.phaseOneDefaults(),
                mode,
                AnalysisRunId.create(),
                START));
        List<String> sentences = List.of(
                sentence(34.68, 135.20),
                sentence(34.681, 135.201),
                sentence(34.682, 135.202));

        for (int index = 0; index < sentences.size(); index++) {
            SourceReference source = new SourceReference(
                    mode,
                    mode.name().toLowerCase(),
                    "source parity",
                    index);
            PipelineResult result = pipeline.accept(new ReceivedNmea(
                    START.plusSeconds(index * 20L),
                    index,
                    sentences.get(index),
                    source));
            assertTrue(result.diagnostics().isEmpty());
            result.events().forEach(engine::accept);
        }
        return engine.complete(START.plusSeconds(40));
    }

    private static String sentence(double latitude, double longitude) {
        return AisTestData.sentence(
                AisTestData.type1(1, MMSI, latitude, longitude),
                0);
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("lab"),
                "Laboratory",
                new GeoPosition(34.718983358515715,
                        135.29057866131427),
                null,
                null,
                null,
                LocalDate.of(2020, 1, 1),
                null,
                null);
    }
}
