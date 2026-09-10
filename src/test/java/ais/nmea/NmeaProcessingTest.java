package ais.nmea;

import ais.input.InputDiagnosticCode;
import ais.input.ReceivedNmea;
import ais.input.SourceReference;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NmeaProcessingTest {

    private static final Instant START =
            Instant.parse("2026-09-04T00:00:00Z");

    @Test
    void validatesAndParsesAivdmChecksum() throws Exception {
        String sentence = AisTestData.sentence("13aG?P0000", 0);
        NmeaChecksumValidator validator = new NmeaChecksumValidator();

        assertEquals(ChecksumStatus.VALID,
                validator.validate(sentence).status());
        assertEquals(ChecksumStatus.INVALID,
                validator.validate(sentence.substring(0,
                        sentence.length() - 2) + "00").status());
        assertEquals(ChecksumStatus.MISSING,
                validator.validate("!AIVDM,1,1,,A,13aG?P0000,0").status());

        NmeaFragment fragment = new NmeaEnvelopeParser().parse(
                record(sentence, 0, START));
        assertEquals("13aG?P0000", fragment.payload());
        assertEquals("A", fragment.radioChannel());
    }

    @Test
    void assemblesFragmentsAndIgnoresIdenticalRepeat() {
        AisFragmentAssembler assembler = new AisFragmentAssembler();
        NmeaFragment first = fragment(2, 1, "7", "ABC", 0, START);
        NmeaFragment second = fragment(
                2,
                2,
                "7",
                "DEF",
                2,
                START.plusMillis(100));

        assertTrue(assembler.accept(first).completedPayloads().isEmpty());
        FragmentAssemblyResult repeated = assembler.accept(first);
        assertTrue(repeated.completedPayloads().isEmpty());
        assertTrue(repeated.diagnostics().isEmpty());

        FragmentAssemblyResult completed = assembler.accept(second);
        assertEquals(1, completed.completedPayloads().size());
        assertEquals("ABCDEF",
                completed.completedPayloads().getFirst().payload());
        assertEquals(2,
                completed.completedPayloads().getFirst().fillBits());
    }

    @Test
    void assemblesFragmentsThatArriveOutOfOrder() {
        AisFragmentAssembler assembler = new AisFragmentAssembler();
        NmeaFragment first = fragment(
                2,
                1,
                "8",
                "ABC",
                0,
                START.plusMillis(100));
        NmeaFragment second = fragment(
                2,
                2,
                "8",
                "DEF",
                1,
                START);

        assertTrue(assembler.accept(second).completedPayloads().isEmpty());
        FragmentAssemblyResult completed = assembler.accept(first);

        assertTrue(completed.diagnostics().isEmpty());
        assertEquals("ABCDEF",
                completed.completedPayloads().getFirst().payload());
        assertEquals(1,
                completed.completedPayloads().getFirst().fillBits());
    }

    @Test
    void expiresIncompleteFragments() {
        AisFragmentAssembler assembler = new AisFragmentAssembler(
                Duration.ofSeconds(2));
        assembler.accept(fragment(2, 1, "9", "ABC", 0, START));

        FragmentAssemblyResult result = assembler.accept(fragment(
                1,
                1,
                "",
                "XYZ",
                0,
                START.plusSeconds(3)));

        assertEquals(1, result.completedPayloads().size());
        assertEquals(InputDiagnosticCode.INCOMPLETE_FRAGMENT,
                result.diagnostics().getFirst().code());
    }

    private static ReceivedNmea record(
            String sentence,
            long sequence,
            Instant receivedAt) {
        return new ReceivedNmea(
                receivedAt,
                sequence,
                sentence,
                SourceReference.live("test", sequence));
    }

    private static NmeaFragment fragment(
            int total,
            int number,
            String sequentialId,
            String payload,
            int fillBits,
            Instant receivedAt) {
        return new NmeaFragment(
                receivedAt,
                number,
                SourceReference.live("test", number),
                "!AIVDM",
                total,
                number,
                sequentialId,
                "A",
                payload,
                fillBits);
    }
}
