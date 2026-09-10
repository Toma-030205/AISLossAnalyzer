package ais.decode;

import ais.domain.NormalizedAisEvent;
import ais.input.InputDiagnosticCode;
import ais.nmea.CompletedAisPayload;

import java.util.Map;
import java.util.Objects;

public final class AisPayloadDecoder {

    private final Map<Integer, MessageTypeDecoder> decoders;

    public AisPayloadDecoder() {
        MessageTypeDecoder type123 = new Type123Decoder();
        decoders = Map.of(
                1, type123,
                2, type123,
                3, type123,
                5, new Type5Decoder(),
                18, new Type18Decoder(),
                24, new Type24Decoder());
    }

    public DecodedAisEnvelope decode(CompletedAisPayload completed)
            throws AisDecodeException {
        Objects.requireNonNull(completed, "completed");
        SixBitPayload bits = new SixBitPayload(
                completed.payload(),
                completed.fillBits());
        if (bits.length() < 38) {
            throw new AisDecodeException(
                    InputDiagnosticCode.DECODE_FAILED,
                    "AIS payload is too short to contain its header");
        }

        int messageType = bits.unsigned(0, 6);
        int mmsi = bits.unsigned(8, 30);

        if (mmsi <= 0 || mmsi > 999_999_999) {
            throw new AisDecodeException(
                    InputDiagnosticCode.INVALID_MMSI,
                    "MMSI is outside the usable range");
        }

        MessageTypeDecoder decoder = decoders.get(messageType);
        if (decoder == null) {
            throw new AisDecodeException(
                    InputDiagnosticCode.UNSUPPORTED_MESSAGE_TYPE,
                    "AIS message type is outside the phase-one target set");
        }

        NormalizedAisEvent event = decoder.decode(
                completed,
                bits,
                messageType,
                mmsi);
        return new DecodedAisEnvelope(
                event,
                completed.payload(),
                completed.fillBits());
    }
}
