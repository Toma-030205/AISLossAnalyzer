package ais.parser;

import ais.model.AisMessage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class AisDecoder {

    private static final ConcurrentMap<String, FragmentEntry>
            fragmentBuffer = new ConcurrentHashMap<>();

    private static final char[] SIXBIT_TABLE = {
            '@','A','B','C','D','E','F','G','H','I','J','K','L','M','N','O',
            'P','Q','R','S','T','U','V','W','X','Y','Z',' ','0','1','2','3',
            '4','5','6','7','8','9',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',
            ' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',' ',
    };

    private static class FragmentEntry {

        final int total;
        final String[] parts;

        int received = 0;
        int lastFillBits = 0;

        FragmentEntry(int total) {

            this.total = total;
            this.parts = new String[total];
        }
    }

    public static AisMessage decode(String nmea) {

        if (nmea == null) {
            return null;
        }

        nmea = nmea.trim();

        if (!(nmea.startsWith("!AIVDM")
                || nmea.startsWith("!AIVDO"))) {

            return null;
        }

        String[] parts = nmea.split(",", -1);

        if (parts.length < 7) {
            return null;
        }

        int total = tryParse(parts[1], 1);
        int number = tryParse(parts[2], 1);

        String seq = parts[3];
        String channel = parts[4];
        String payload = parts[5];

        int fill = 0;

        try {

            fill = Integer.parseInt(
                    parts[6].split("\\*")[0]);

        } catch (Exception e) {

            fill = 0;
        }

        // 単一メッセージ
        if (total <= 1) {
            return build(payload, fill);
        }

        // マルチフラグメント
        String key = seq + "|" + channel;

        fragmentBuffer.putIfAbsent(
                key,
                new FragmentEntry(total)
        );

        FragmentEntry fe = fragmentBuffer.get(key);

        synchronized (fe) {

            if (number >= 1
                    && number <= fe.parts.length) {

                if (fe.parts[number - 1] == null) {

                    fe.parts[number - 1] = payload;
                    fe.received++;
                }

                fe.lastFillBits = fill;

            } else {

                fragmentBuffer.remove(key);
                return null;
            }

            if (fe.received < fe.total) {

                return null;
            }

            StringBuilder sb = new StringBuilder();

            for (String p : fe.parts) {

                if (p == null) {

                    fragmentBuffer.remove(key);
                    return null;
                }

                sb.append(p);
            }

            fragmentBuffer.remove(key);

            return build(sb.toString(), fe.lastFillBits);
        }
    }

    private static int tryParse(String s, int def) {

        try {

            return Integer.parseInt(s);

        } catch (Exception e) {

            return def;
        }
    }

    private static AisMessage build(String payload, int fillBits) {

        BitPayload bits = new BitPayload(payload, fillBits);

        if (bits.length() < 38) {
            return null;
        }

        try {
            int type = bits.unsigned(0, 6);
            AisMessage msg = new AisMessage();
            msg.messageType = type;
            msg.mmsi = bits.unsigned(8, 30);
            msg.bits = null;

            if ((type == 1 || type == 2 || type == 3)
                    && bits.length() >= 137) {
                msg.navStatus = bits.unsigned(38, 4);
                int sogRaw = bits.unsigned(50, 10);
                msg.sog = sogRaw == 1023 ? null : sogRaw / 10.0;
                msg.lon = bits.signed(61, 28) / 600000.0;
                msg.lat = bits.signed(89, 27) / 600000.0;
                int cogRaw = bits.unsigned(116, 12);
                msg.cog = cogRaw >= 3600 ? null : cogRaw / 10.0;
                int headingRaw = bits.unsigned(128, 9);
                msg.trueHeading = headingRaw >= 360 ? null : (double) headingRaw;
            }

            if (type == 18 && bits.length() >= 133) {
                int sogRaw = bits.unsigned(46, 10);
                msg.sog = sogRaw == 1023 ? null : sogRaw / 10.0;
                msg.lon = bits.signed(57, 28) / 600000.0;
                msg.lat = bits.signed(85, 27) / 600000.0;
                int cogRaw = bits.unsigned(112, 12);
                msg.cog = cogRaw >= 3600 ? null : cogRaw / 10.0;
                int headingRaw = bits.unsigned(124, 9);
                msg.trueHeading = headingRaw >= 360 ? null : (double) headingRaw;
                if (bits.length() >= 147) {
                    msg.classBCsUnit = bits.unsigned(141, 1) == 1;
                    msg.assignedMode = bits.unsigned(146, 1) == 1;
                }
            }

            if (type == 5) {
                parseType5(msg, bits);
            }

            return msg;

        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void parseType5(AisMessage msg, BitPayload bits) {

        if (bits.length() >= 70) {
            msg.imo = bits.unsigned(40, 30);
        }
        if (bits.length() >= 112) {
            msg.callSign = bits.text(70, 42).trim();
        }
        if (bits.length() >= 232) {
            msg.vesselName = bits.text(112, 120).trim();
        }
        if (bits.length() >= 240) {
            msg.shipType = bits.unsigned(232, 8);
        }
        if (bits.length() >= 270) {
            msg.shipLength = bits.unsigned(240, 9) + bits.unsigned(249, 9);
        }
        if (bits.length() >= 422) {
            msg.destination = bits.text(302, 120).trim();
        }
    }

    private static final class BitPayload {

        private final String payload;
        private final int bitLength;

        BitPayload(String payload, int fillBits) {
            this.payload = payload == null ? "" : payload;
            this.bitLength = Math.max(0, this.payload.length() * 6 - Math.max(0, fillBits));
        }

        int length() {
            return bitLength;
        }

        int unsigned(int start, int count) {
            if (start < 0 || count < 0 || start + count > bitLength || count > 31) {
                throw new IllegalArgumentException("Invalid AIS bit range");
            }
            int value = 0;
            for (int i = 0; i < count; i++) {
                int bitIndex = start + i;
                int sixBit = payload.charAt(bitIndex / 6) - 48;
                if (sixBit > 40) {
                    sixBit -= 8;
                }
                value = (value << 1) | ((sixBit >> (5 - bitIndex % 6)) & 1);
            }
            return value;
        }

        int signed(int start, int count) {
            int value = unsigned(start, count);
            int signMask = 1 << (count - 1);
            return (value & signMask) == 0 ? value : value - (1 << count);
        }

        String text(int start, int bitCount) {
            StringBuilder result = new StringBuilder(bitCount / 6);
            int end = Math.min(bitLength, start + bitCount);
            for (int i = start; i + 6 <= end; i += 6) {
                int value = unsigned(i, 6);
                char character = value < SIXBIT_TABLE.length ? SIXBIT_TABLE[value] : ' ';
                result.append(character == '@' ? ' ' : character);
            }
            return result.toString();
        }
    }

    public static int twosComp(String bits) {

        if (bits == null || bits.isEmpty()) {

            return 0;
        }

        if (bits.charAt(0) == '0') {

            return Integer.parseInt(bits, 2);

        } else {

            int val = Integer.parseInt(bits, 2);

            return val - (1 << bits.length());
        }
    }
}
