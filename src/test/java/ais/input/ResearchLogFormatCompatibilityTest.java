package ais.input;

import ais.domain.PositionReport;
import ais.domain.VesselMetadataUpdate;
import ais.input.history.HistoricalLineParser;
import ais.input.history.HistoricalRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchLogFormatCompatibilityTest {

    @Test
    void acceptsObservedType5AndClassifiesUnavailableAivdoPosition()
            throws Exception {
        HistoricalLineParser lineParser = new HistoricalLineParser(
                ZoneId.of("Asia/Tokyo"));
        AisInputPipeline pipeline = new AisInputPipeline();
        Path sample = Path.of("sample.ais.gz");

        HistoricalRecord type5First = lineParser.parse(
                "20260101000000292 !AIVDM,2,1,4,A,"
                        + "56K2TaD2EiDL`C?W3V0lTd4LF222222222222216:0?"
                        + "497?8N;ORT85B,0*3E",
                sample,
                1);
        HistoricalRecord type5Second = lineParser.parse(
                "20260101000000292 !AIVDM,2,2,4,A,"
                        + "h`4<`3iQ`888880,2*13",
                sample,
                2);
        HistoricalRecord ownVessel = lineParser.parse(
                "20260101000000360 !AIVDO,1,1,,,"
                        + "1>qc66?P?w<tSF0l4Q@>4?wp0P00,0*29",
                sample,
                3);
        HistoricalRecord positionReport = lineParser.parse(
                "20260101000000292 !AIVDM,1,1,,A,"
                        + "16K2oDh0009d6DdCm21VPi3n20S4,0*1A",
                sample,
                4);

        PipelineResult first = pipeline.accept(received(type5First, 0));
        PipelineResult second = pipeline.accept(received(type5Second, 1));
        PipelineResult third = pipeline.accept(received(positionReport, 2));
        PipelineResult fourth = pipeline.accept(received(ownVessel, 3));

        assertTrue(first.events().isEmpty());
        assertTrue(first.diagnostics().isEmpty());
        VesselMetadataUpdate metadata =
                (VesselMetadataUpdate) second.events().getFirst();
        assertEquals(5, metadata.messageType());
        assertTrue(metadata.mmsi() > 0);
        assertTrue(second.diagnostics().isEmpty());
        PositionReport position =
                (PositionReport) third.events().getFirst();
        assertEquals(1, position.messageType());
        assertTrue(third.diagnostics().isEmpty());
        assertTrue(fourth.events().isEmpty());
        assertEquals(InputDiagnosticCode.INVALID_POSITION,
                fourth.diagnostics().getFirst().code());
    }

    private static ReceivedNmea received(
            HistoricalRecord record,
            long sequence) {
        return new ReceivedNmea(
                record.receivedAt(),
                sequence,
                record.sentence(),
                record.source());
    }
}
