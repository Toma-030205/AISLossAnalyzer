package ais.decode;

import ais.domain.GeoPosition;
import ais.input.InputDiagnosticCode;

final class DecoderSupport {

    private static final int LONGITUDE_UNAVAILABLE = 181 * 600_000;
    private static final int LATITUDE_UNAVAILABLE = 91 * 600_000;

    private DecoderSupport() {
    }

    static GeoPosition position(int latitudeRaw, int longitudeRaw)
            throws AisDecodeException {
        if (latitudeRaw == LATITUDE_UNAVAILABLE
                || longitudeRaw == LONGITUDE_UNAVAILABLE) {
            throw invalidPosition("AIS position is unavailable");
        }

        double latitude = latitudeRaw / 600_000.0;
        double longitude = longitudeRaw / 600_000.0;

        if (latitude < -90.0
                || latitude > 90.0
                || longitude < -180.0
                || longitude > 180.0
                || (latitude == 0.0 && longitude == 0.0)) {
            throw invalidPosition("AIS position is outside the usable range");
        }
        return new GeoPosition(latitude, longitude);
    }

    static Double sog(int raw) {
        return raw == 1023 ? null : raw / 10.0;
    }

    static Double cog(int raw) {
        return raw >= 3600 ? null : raw / 10.0;
    }

    static Double heading(int raw) {
        return raw >= 360 ? null : (double) raw;
    }

    static Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
    }

    static void requireLength(
            SixBitPayload bits,
            int minimumBits,
            int messageType) throws AisDecodeException {
        if (bits.length() < minimumBits) {
            throw new AisDecodeException(
                    InputDiagnosticCode.DECODE_FAILED,
                    "Type " + messageType + " payload is too short");
        }
    }

    private static AisDecodeException invalidPosition(String message) {
        return new AisDecodeException(
                InputDiagnosticCode.INVALID_POSITION,
                message);
    }
}
