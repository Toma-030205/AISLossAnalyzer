package ais.decode;

import ais.domain.ClassBReportingMode;
import ais.domain.PositionReport;
import ais.nmea.CompletedAisPayload;

public final class Type123Decoder implements MessageTypeDecoder {

    @Override
    public PositionReport decode(
            CompletedAisPayload completed,
            SixBitPayload bits,
            int messageType,
            int mmsi) throws AisDecodeException {
        DecoderSupport.requireLength(bits, 137, messageType);

        int navigationStatus = bits.unsigned(38, 4);
        Double sog = DecoderSupport.sog(bits.unsigned(50, 10));
        int longitudeRaw = bits.signed(61, 28);
        int latitudeRaw = bits.signed(89, 27);
        Double cog = DecoderSupport.cog(bits.unsigned(116, 12));
        Double heading = DecoderSupport.heading(bits.unsigned(128, 9));

        return new PositionReport(
                completed.receivedAt(),
                completed.sequence(),
                messageType,
                mmsi,
                DecoderSupport.position(latitudeRaw, longitudeRaw),
                sog,
                cog,
                heading,
                navigationStatus,
                ClassBReportingMode.UNKNOWN,
                false);
    }
}
