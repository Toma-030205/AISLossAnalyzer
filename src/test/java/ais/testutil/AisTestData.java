package ais.testutil;

import java.util.Arrays;

public final class AisTestData {

    private static final String AIS_TEXT =
            "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_ !\"#$%&'()*+,-./0123456789:;<=>?";

    private AisTestData() {
    }

    public static PayloadBuilder payload(int bitLength) {
        return new PayloadBuilder(bitLength);
    }

    public static String sentence(String payload, int fillBits) {
        return sentence(1, 1, "", "A", payload, fillBits);
    }

    public static String sentence(
            int totalFragments,
            int fragmentNumber,
            String sequentialMessageId,
            String channel,
            String payload,
            int fillBits) {
        String body = "AIVDM," + totalFragments
                + "," + fragmentNumber
                + "," + sequentialMessageId
                + "," + channel
                + "," + payload
                + "," + fillBits;
        int checksum = 0;
        for (int index = 0; index < body.length(); index++) {
            checksum ^= body.charAt(index);
        }
        return "!" + body + "*" + String.format("%02X", checksum);
    }

    public static String type1(
            int messageType,
            int mmsi,
            double latitude,
            double longitude) {
        return payload(168)
                .unsigned(0, 6, messageType)
                .unsigned(8, 30, mmsi)
                .unsigned(38, 4, 0)
                .unsigned(50, 10, 123)
                .signed(61, 28, Math.round(longitude * 600_000))
                .signed(89, 27, Math.round(latitude * 600_000))
                .unsigned(116, 12, 905)
                .unsigned(128, 9, 91)
                .armored();
    }

    public static String type18(
            int mmsi,
            double latitude,
            double longitude) {
        return payload(168)
                .unsigned(0, 6, 18)
                .unsigned(8, 30, mmsi)
                .unsigned(46, 10, 87)
                .signed(57, 28, Math.round(longitude * 600_000))
                .signed(85, 27, Math.round(latitude * 600_000))
                .unsigned(112, 12, 1802)
                .unsigned(124, 9, 180)
                .unsigned(141, 1, 1)
                .unsigned(146, 1, 1)
                .armored();
    }

    public static final class PayloadBuilder {

        private final boolean[] bits;

        private PayloadBuilder(int bitLength) {
            if (bitLength <= 0) {
                throw new IllegalArgumentException(
                        "bit length must be greater than zero");
            }
            bits = new boolean[bitLength];
        }

        public PayloadBuilder unsigned(int start, int count, long value) {
            checkRange(start, count);
            if (value < 0 || (count < 63 && value >= (1L << count))) {
                throw new IllegalArgumentException("value does not fit field");
            }
            for (int offset = 0; offset < count; offset++) {
                int shift = count - offset - 1;
                bits[start + offset] = ((value >> shift) & 1L) == 1L;
            }
            return this;
        }

        public PayloadBuilder signed(int start, int count, long value) {
            long minimum = -(1L << (count - 1));
            long maximum = (1L << (count - 1)) - 1;
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException("value does not fit field");
            }
            long encoded = value < 0 ? (1L << count) + value : value;
            return unsigned(start, count, encoded);
        }

        public PayloadBuilder text(int start, int bitCount, String value) {
            if (bitCount % 6 != 0) {
                throw new IllegalArgumentException(
                        "text field must contain complete six-bit groups");
            }
            checkRange(start, bitCount);
            String normalized = value == null
                    ? ""
                    : value.toUpperCase();
            int characterCount = bitCount / 6;
            for (int index = 0; index < characterCount; index++) {
                char character = index < normalized.length()
                        ? normalized.charAt(index)
                        : '@';
                int encoded = AIS_TEXT.indexOf(character);
                if (encoded < 0) {
                    throw new IllegalArgumentException(
                            "text contains an unsupported AIS character");
                }
                unsigned(start + index * 6, 6, encoded);
            }
            return this;
        }

        public String armored() {
            StringBuilder result = new StringBuilder(
                    (bits.length + 5) / 6);
            for (int start = 0; start < bits.length; start += 6) {
                int value = 0;
                for (int offset = 0; offset < 6; offset++) {
                    value <<= 1;
                    if (start + offset < bits.length
                            && bits[start + offset]) {
                        value |= 1;
                    }
                }
                result.append((char) (value < 40
                        ? value + 48
                        : value + 56));
            }
            return result.toString();
        }

        public int fillBits() {
            return (6 - bits.length % 6) % 6;
        }

        private void checkRange(int start, int count) {
            if (start < 0
                    || count <= 0
                    || start > bits.length - count) {
                throw new IllegalArgumentException(
                        "field is outside payload: "
                                + Arrays.toString(new int[]{start, count}));
            }
        }
    }
}
