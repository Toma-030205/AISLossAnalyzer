package ais.input;

import ais.domain.PositionReport;
import ais.domain.VesselMetadataUpdate;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AisInputPipelineTest {

    private static final Instant START =
            Instant.parse("2026-09-04T00:00:00Z");
    private static final int MMSI = 431_000_001;

    @Test
    void emitsPositionReportFromValidSentence() {
        AisInputPipeline pipeline = new AisInputPipeline();
        String sentence = AisTestData.sentence(
                AisTestData.type1(1, MMSI, 34.5, 135.25),
                0);

        PipelineResult result = pipeline.accept(record(
                sentence,
                4,
                START,
                SourceModeValue.LIVE));

        assertTrue(result.diagnostics().isEmpty());
        PositionReport report = (PositionReport) result.events().getFirst();
        assertEquals(MMSI, report.mmsi());
        assertEquals(4, report.sequence());
        assertEquals(START, report.receivedAt());
    }

    @Test
    void reportsChecksumFailureWithoutDecoding() {
        AisInputPipeline pipeline = new AisInputPipeline();
        String valid = AisTestData.sentence(
                AisTestData.type1(1, MMSI, 34.5, 135.25),
                0);
        char last = valid.charAt(valid.length() - 1);
        String invalid = valid.substring(0, valid.length() - 1)
                + (last == '0' ? '1' : '0');

        PipelineResult result = pipeline.accept(record(
                invalid,
                0,
                START,
                SourceModeValue.LIVE));

        assertTrue(result.events().isEmpty());
        assertEquals(InputDiagnosticCode.CHECKSUM_INVALID,
                result.diagnostics().getFirst().code());
    }

    @Test
    void classifiesStructurallyValidSentenceWithoutChecksum() {
        AisInputPipeline pipeline = new AisInputPipeline();
        String complete = AisTestData.sentence(
                AisTestData.type1(1, MMSI, 34.5, 135.25),
                0);
        String missing = complete.substring(0, complete.indexOf('*'));

        PipelineResult result = pipeline.accept(record(
                missing,
                0,
                START,
                SourceModeValue.LIVE));

        assertTrue(result.events().isEmpty());
        assertEquals(InputDiagnosticCode.CHECKSUM_MISSING,
                result.diagnostics().getFirst().code());
    }

    @Test
    void removesOnlyDuplicatesInsideOneSecondWindow() {
        AisInputPipeline pipeline = new AisInputPipeline();
        String sentence = AisTestData.sentence(
                AisTestData.type1(2, MMSI, 34.5, 135.25),
                0);

        PipelineResult first = pipeline.accept(record(
                sentence,
                0,
                START,
                SourceModeValue.LIVE));
        PipelineResult duplicate = pipeline.accept(record(
                sentence,
                1,
                START.plusMillis(999),
                SourceModeValue.LIVE));
        PipelineResult later = pipeline.accept(record(
                sentence,
                2,
                START.plusSeconds(2),
                SourceModeValue.LIVE));

        assertEquals(1, first.events().size());
        assertTrue(duplicate.events().isEmpty());
        assertEquals(InputDiagnosticCode.DUPLICATE,
                duplicate.diagnostics().getFirst().code());
        assertEquals(1, later.events().size());
    }

    @Test
    void reassemblesMultiFragmentType5BeforeDecoding() {
        AisTestData.PayloadBuilder builder = AisTestData.payload(424)
                .unsigned(0, 6, 5)
                .unsigned(8, 30, MMSI)
                .text(70, 42, "JP0001")
                .text(112, 120, "MERGED VESSEL")
                .unsigned(232, 8, 70);
        String payload = builder.armored();
        int boundary = payload.length() / 2;
        String firstSentence = AisTestData.sentence(
                2, 1, "4", "B", payload.substring(0, boundary), 0);
        String secondSentence = AisTestData.sentence(
                2, 2, "4", "B", payload.substring(boundary),
                builder.fillBits());

        AisInputPipeline pipeline = new AisInputPipeline();
        PipelineResult first = pipeline.accept(record(
                firstSentence,
                0,
                START,
                SourceModeValue.HISTORICAL));
        PipelineResult second = pipeline.accept(record(
                secondSentence,
                1,
                START.plusMillis(100),
                SourceModeValue.HISTORICAL));

        assertTrue(first.events().isEmpty());
        VesselMetadataUpdate metadata =
                (VesselMetadataUpdate) second.events().getFirst();
        assertEquals("MERGED VESSEL", metadata.vesselName());
        assertEquals("JP0001", metadata.callSign());
    }

    @Test
    void classifiesUnsupportedMessageTypeAsDiagnostic() {
        AisTestData.PayloadBuilder builder = AisTestData.payload(168)
                .unsigned(0, 6, 19)
                .unsigned(8, 30, MMSI);
        AisInputPipeline pipeline = new AisInputPipeline();

        PipelineResult result = pipeline.accept(record(
                AisTestData.sentence(builder.armored(), builder.fillBits()),
                0,
                START,
                SourceModeValue.LIVE));

        assertTrue(result.events().isEmpty());
        assertEquals(InputDiagnosticCode.UNSUPPORTED_MESSAGE_TYPE,
                result.diagnostics().getFirst().code());
    }

    private static ReceivedNmea record(
            String sentence,
            long sequence,
            Instant receivedAt,
            SourceModeValue mode) {
        SourceReference source = mode == SourceModeValue.HISTORICAL
                ? new SourceReference(
                        ais.domain.SourceMode.HISTORICAL,
                        "history.ais",
                        "history.ais",
                        sequence)
                : SourceReference.live("udp://test", sequence);
        return new ReceivedNmea(receivedAt, sequence, sentence, source);
    }

    private enum SourceModeValue {
        HISTORICAL,
        LIVE
    }
}
