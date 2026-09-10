package ais.decode;

import ais.input.InputDiagnosticCode;

import java.util.Objects;

public final class SixBitPayload {

    private static final char[] AIS_TEXT =
            "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_ !\"#$%&'()*+,-./0123456789:;<=>?"
                    .toCharArray();

    private final String payload;
    private final int bitLength;

    public SixBitPayload(String payload, int fillBits)
            throws AisDecodeException {
        this.payload = Objects.requireNonNull(payload, "payload");

        if (payload.isEmpty() || fillBits < 0 || fillBits > 5) {
            throw decodeFailure("invalid payload length or fill bits");
        }
        for (int index = 0; index < payload.length(); index++) {
            decodeArmored(payload.charAt(index));
        }

        bitLength = payload.length() * 6 - fillBits;
        if (bitLength <= 0) {
            throw decodeFailure("payload contains no data bits");
        }
    }

    public int length() {
        return bitLength;
    }

    public int unsigned(int start, int count) throws AisDecodeException {
        if (count > 31) {
            throw decodeFailure(
                    "integer AIS fields must contain at most 31 bits");
        }
        requireRange(start, count);

        int value = 0;
        for (int offset = 0; offset < count; offset++) {
            int bitIndex = start + offset;
            int sixBit = decodeArmored(payload.charAt(bitIndex / 6));
            value = (value << 1)
                    | ((sixBit >> (5 - bitIndex % 6)) & 1);
        }
        return value;
    }

    public int signed(int start, int count) throws AisDecodeException {
        int value = unsigned(start, count);
        int signMask = 1 << (count - 1);
        return (value & signMask) == 0
                ? value
                : value - (1 << count);
    }

    public String text(int start, int bitCount) throws AisDecodeException {
        if (bitCount % 6 != 0) {
            throw decodeFailure("AIS text length must be a multiple of six");
        }
        requireRange(start, bitCount);

        StringBuilder result = new StringBuilder(bitCount / 6);
        for (int offset = 0; offset < bitCount; offset += 6) {
            int value = unsigned(start + offset, 6);
            result.append(AIS_TEXT[value]);
        }
        return cleanText(result.toString());
    }

    private void requireRange(int start, int count)
            throws AisDecodeException {
        if (start < 0
                || count <= 0
                || start > bitLength - count) {
            throw decodeFailure(
                    "invalid AIS bit range: start=" + start
                            + ", count=" + count
                            + ", length=" + bitLength);
        }
    }

    private static int decodeArmored(char character)
            throws AisDecodeException {
        if (character >= '0' && character <= 'W') {
            return character - '0';
        }
        if (character >= '`' && character <= 'w') {
            return character - '0' - 8;
        }
        throw decodeFailure(
                "payload contains an invalid six-bit character");
    }

    private static String cleanText(String value) {
        int end = value.length();
        while (end > 0) {
            char character = value.charAt(end - 1);
            if (character != '@' && character != ' ') {
                break;
            }
            end--;
        }
        String cleaned = value.substring(0, end).replace('@', ' ').trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static AisDecodeException decodeFailure(String message) {
        return new AisDecodeException(
                InputDiagnosticCode.DECODE_FAILED,
                message);
    }
}
