package ais.decode;

import ais.domain.NormalizedAisEvent;
import ais.nmea.CompletedAisPayload;

interface MessageTypeDecoder {

    NormalizedAisEvent decode(
            CompletedAisPayload completed,
            SixBitPayload bits,
            int messageType,
            int mmsi) throws AisDecodeException;
}
