package ais.input;

import ais.decode.AisDecodeException;
import ais.decode.AisPayloadDecoder;
import ais.decode.DecodedAisEnvelope;
import ais.decode.InputDeduplicator;
import ais.domain.NormalizedAisEvent;
import ais.nmea.AisFragmentAssembler;
import ais.nmea.ChecksumStatus;
import ais.nmea.ChecksumValidation;
import ais.nmea.CompletedAisPayload;
import ais.nmea.FragmentAssemblyResult;
import ais.nmea.NmeaChecksumValidator;
import ais.nmea.NmeaEnvelopeParser;
import ais.nmea.NmeaFragment;
import ais.nmea.NmeaParseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AisInputPipeline {

    private final NmeaChecksumValidator checksumValidator;
    private final NmeaEnvelopeParser envelopeParser;
    private final AisFragmentAssembler fragmentAssembler;
    private final AisPayloadDecoder payloadDecoder;
    private final InputDeduplicator deduplicator;

    public AisInputPipeline() {
        this(
                new NmeaChecksumValidator(),
                new NmeaEnvelopeParser(),
                new AisFragmentAssembler(),
                new AisPayloadDecoder(),
                new InputDeduplicator());
    }

    public AisInputPipeline(
            NmeaChecksumValidator checksumValidator,
            NmeaEnvelopeParser envelopeParser,
            AisFragmentAssembler fragmentAssembler,
            AisPayloadDecoder payloadDecoder,
            InputDeduplicator deduplicator) {
        this.checksumValidator = Objects.requireNonNull(
                checksumValidator,
                "checksumValidator");
        this.envelopeParser = Objects.requireNonNull(
                envelopeParser,
                "envelopeParser");
        this.fragmentAssembler = Objects.requireNonNull(
                fragmentAssembler,
                "fragmentAssembler");
        this.payloadDecoder = Objects.requireNonNull(
                payloadDecoder,
                "payloadDecoder");
        this.deduplicator = Objects.requireNonNull(
                deduplicator,
                "deduplicator");
    }

    public PipelineResult accept(ReceivedNmea record) {
        Objects.requireNonNull(record, "record");

        NmeaFragment fragment;
        try {
            fragment = envelopeParser.parse(record);
        } catch (NmeaParseException exception) {
            return diagnosticResult(
                    record,
                    exception.diagnosticCode(),
                    exception.getMessage());
        }

        ChecksumValidation checksum =
                checksumValidator.validate(record.sentence());
        if (!checksum.isValid()) {
            return diagnosticResult(
                    record,
                    checksumCode(checksum.status()),
                    checksumDetail(checksum));
        }

        FragmentAssemblyResult assembly =
                fragmentAssembler.accept(fragment);
        List<InputDiagnostic> diagnostics =
                new ArrayList<>(assembly.diagnostics());
        List<NormalizedAisEvent> events = new ArrayList<>();

        for (CompletedAisPayload completed :
                assembly.completedPayloads()) {
            try {
                DecodedAisEnvelope decoded =
                        payloadDecoder.decode(completed);

                if (deduplicator.isDuplicate(decoded)) {
                    diagnostics.add(new InputDiagnostic(
                            completed.receivedAt(),
                            InputDiagnosticCode.DUPLICATE,
                            completed.source(),
                            "same MMSI, message type, and completed payload within one second"));
                } else {
                    events.add(decoded.event());
                }
            } catch (AisDecodeException exception) {
                diagnostics.add(new InputDiagnostic(
                        completed.receivedAt(),
                        exception.diagnosticCode(),
                        completed.source(),
                        exception.getMessage()));
            }
        }

        return new PipelineResult(events, diagnostics);
    }

    public PipelineResult finish(Instant occurredAt) {
        return new PipelineResult(
                List.of(),
                fragmentAssembler.flushIncomplete(occurredAt));
    }

    public void reset() {
        fragmentAssembler.clear();
        deduplicator.clear();
    }

    private static PipelineResult diagnosticResult(
            ReceivedNmea record,
            InputDiagnosticCode code,
            String detail) {
        return new PipelineResult(
                List.of(),
                List.of(new InputDiagnostic(
                        record.receivedAt(),
                        code,
                        record.source(),
                        detail)));
    }

    private static InputDiagnosticCode checksumCode(
            ChecksumStatus status) {
        return switch (status) {
            case MISSING -> InputDiagnosticCode.CHECKSUM_MISSING;
            case INVALID -> InputDiagnosticCode.CHECKSUM_INVALID;
            case MALFORMED -> InputDiagnosticCode.INVALID_NMEA_FORMAT;
            case VALID -> throw new IllegalArgumentException(
                    "valid checksum has no diagnostic code");
        };
    }

    private static String checksumDetail(ChecksumValidation validation) {
        if (validation.status() != ChecksumStatus.INVALID) {
            return "NMEA checksum is "
                    + validation.status().name().toLowerCase();
        }
        return "NMEA checksum does not match: expected="
                + String.format("%02X", validation.expected())
                + ", actual="
                + String.format("%02X", validation.actual());
    }
}
