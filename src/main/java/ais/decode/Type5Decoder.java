package ais.decode;

import ais.domain.VesselClass;
import ais.domain.VesselMetadataUpdate;
import ais.nmea.CompletedAisPayload;

public final class Type5Decoder implements MessageTypeDecoder {

    @Override
    public VesselMetadataUpdate decode(
            CompletedAisPayload completed,
            SixBitPayload bits,
            int messageType,
            int mmsi) throws AisDecodeException {
        DecoderSupport.requireLength(bits, 270, messageType);

        Integer imo = DecoderSupport.positiveOrNull(bits.unsigned(40, 30));
        String callSign = bits.text(70, 42);
        String vesselName = bits.text(112, 120);
        int shipType = bits.unsigned(232, 8);
        int shipLength = bits.unsigned(240, 9)
                + bits.unsigned(249, 9);
        String destination = bits.length() >= 422
                ? bits.text(302, 120)
                : null;

        return new VesselMetadataUpdate(
                completed.receivedAt(),
                completed.sequence(),
                messageType,
                mmsi,
                VesselClass.CLASS_A,
                imo,
                callSign,
                vesselName,
                shipType,
                destination,
                DecoderSupport.positiveOrNull(shipLength));
    }
}
