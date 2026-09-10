package ais.decode;

import ais.domain.VesselClass;
import ais.domain.VesselMetadataUpdate;
import ais.input.InputDiagnosticCode;
import ais.nmea.CompletedAisPayload;

public final class Type24Decoder implements MessageTypeDecoder {

    @Override
    public VesselMetadataUpdate decode(
            CompletedAisPayload completed,
            SixBitPayload bits,
            int messageType,
            int mmsi) throws AisDecodeException {
        DecoderSupport.requireLength(bits, 40, messageType);
        int partNumber = bits.unsigned(38, 2);

        if (partNumber == 0) {
            DecoderSupport.requireLength(bits, 160, messageType);
            return metadata(
                    completed,
                    messageType,
                    mmsi,
                    null,
                    null,
                    bits.text(40, 120),
                    null);
        }
        if (partNumber == 1) {
            DecoderSupport.requireLength(bits, 162, messageType);
            int shipType = bits.unsigned(40, 8);
            String callSign = bits.text(90, 42);
            Integer shipLength = null;

            if (!isAuxiliaryCraft(mmsi)) {
                int length = bits.unsigned(132, 9)
                        + bits.unsigned(141, 9);
                shipLength = DecoderSupport.positiveOrNull(length);
            }

            return metadata(
                    completed,
                    messageType,
                    mmsi,
                    shipType,
                    callSign,
                    null,
                    shipLength);
        }

        throw new AisDecodeException(
                InputDiagnosticCode.DECODE_FAILED,
                "Type 24 part number must be 0 or 1");
    }

    private static VesselMetadataUpdate metadata(
            CompletedAisPayload completed,
            int messageType,
            int mmsi,
            Integer shipType,
            String callSign,
            String vesselName,
            Integer shipLength) {
        return new VesselMetadataUpdate(
                completed.receivedAt(),
                completed.sequence(),
                messageType,
                mmsi,
                VesselClass.CLASS_B,
                null,
                callSign,
                vesselName,
                shipType,
                null,
                shipLength);
    }

    private static boolean isAuxiliaryCraft(int mmsi) {
        return mmsi / 10_000_000 == 98;
    }
}
