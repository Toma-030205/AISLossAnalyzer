package ais.decode;

import ais.domain.ClassBReportingMode;
import ais.domain.PositionReport;
import ais.nmea.CompletedAisPayload;

public final class Type18Decoder implements MessageTypeDecoder {

    @Override
    public PositionReport decode(
            CompletedAisPayload completed,
            SixBitPayload bits,
            int messageType,
            int mmsi) throws AisDecodeException {
        DecoderSupport.requireLength(bits, 147, messageType);

        Double sog = DecoderSupport.sog(bits.unsigned(46, 10));
        int longitudeRaw = bits.signed(57, 28);
        int latitudeRaw = bits.signed(85, 27);
        Double cog = DecoderSupport.cog(bits.unsigned(112, 12));
        Double heading = DecoderSupport.heading(bits.unsigned(124, 9));
        boolean carrierSense = bits.unsigned(141, 1) == 1;
        boolean assignedMode = bits.unsigned(146, 1) == 1;

        return new PositionReport(
                completed.receivedAt(),
                completed.sequence(),
                messageType,
                mmsi,
                DecoderSupport.position(latitudeRaw, longitudeRaw),
                sog,
                cog,
                heading,
                null,
                carrierSense
                        ? ClassBReportingMode.CARRIER_SENSE
                        : ClassBReportingMode.SELF_ORGANIZING,
                assignedMode);
    }
}
