package ais.input;

import ais.domain.NormalizedAisEvent;
import ais.domain.SourceMode;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommonInputPipelineParityTest {

    @Test
    void historicalAndLiveRecordsProduceIdenticalNormalizedEvents() {
        Instant start = Instant.parse("2026-09-04T02:00:00Z");
        List<String> sentences = List.of(
                AisTestData.sentence(
                        AisTestData.type1(
                                1, 431_000_101, 34.60, 135.20),
                        0),
                AisTestData.sentence(
                        AisTestData.type18(
                                431_000_202, 34.61, 135.21),
                        0));

        List<NormalizedAisEvent> historical = process(
                sentences,
                start,
                SourceMode.HISTORICAL);
        List<NormalizedAisEvent> live = process(
                sentences,
                start,
                SourceMode.LIVE);

        assertEquals(historical, live);
    }

    private static List<NormalizedAisEvent> process(
            List<String> sentences,
            Instant start,
            SourceMode mode) {
        AisInputPipeline pipeline = new AisInputPipeline();
        List<NormalizedAisEvent> events = new ArrayList<>();

        for (int index = 0; index < sentences.size(); index++) {
            SourceReference source = new SourceReference(
                    mode,
                    mode.name().toLowerCase(),
                    "parity test",
                    index);
            PipelineResult result = pipeline.accept(new ReceivedNmea(
                    start.plusSeconds(index),
                    index,
                    sentences.get(index),
                    source));
            events.addAll(result.events());
        }
        return events;
    }
}
