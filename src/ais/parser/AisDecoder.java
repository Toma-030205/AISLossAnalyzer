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

            String bits = decodePayload(payload);

            if (fill > 0 && fill < bits.length()) {

                bits = bits.substring(
                        0,
                        bits.length() - fill
                );
            }

            return build(bits);
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

            String bits = decodePayload(sb.toString());

            if (fe.lastFillBits > 0
                    && fe.lastFillBits < bits.length()) {

                bits = bits.substring(
                        0,
                        bits.length() - fe.lastFillBits
                );
            }

            return build(bits);
        }
    }

    private static int tryParse(String s, int def) {

        try {

            return Integer.parseInt(s);

        } catch (Exception e) {

            return def;
        }
    }

    private static String decodePayload(String payload) {

        StringBuilder sb =
                new StringBuilder(payload.length() * 6);

        for (char c : payload.toCharArray()) {

            int val = c - 48;

            if (val > 40) {
                val -= 8;
            }

            String bin =
                    Integer.toBinaryString(val & 0x3F);

            String padded =
                    String.format("%6s", bin)
                            .replace(' ', '0');

            sb.append(padded);
        }

        return sb.toString();
    }

    private static AisMessage build(String bits) {

        if (bits == null || bits.length() < 38) {

            return null;
        }

        try {

            int type =
                    Integer.parseInt(bits.substring(0, 6), 2);

            int mmsi =
                    Integer.parseInt(bits.substring(8, 38), 2);

            AisMessage msg = new AisMessage();

            msg.messageType = type;
            msg.mmsi = mmsi;
            msg.bits = bits;

            // Type 1,2,3
            if (type == 1 || type == 2 || type == 3) {

                if (bits.length() >= 137) {

                    msg.navStatus =
                            Integer.parseInt(
                                    bits.substring(38, 42), 2);

                    msg.sog =
                            Integer.parseInt(
                                    bits.substring(50, 60), 2) / 10.0;

                    msg.lon =
                            twosComp(
                                    bits.substring(61, 89))
                                    / 600000.0;

                    msg.lat =
                            twosComp(
                                    bits.substring(89, 116))
                                    / 600000.0;

                    msg.cog =
                            Integer.parseInt(
                                    bits.substring(116, 128), 2)
                                    / 10.0;

                    msg.trueHeading =
                            (double) Integer.parseInt(
                                    bits.substring(128, 137), 2);
                }
            }

            // Type 5
            if (type == 5) {

                parseType5(msg, bits);
            }

            return msg;

        } catch (Exception e) {

            return null;
        }
    }

    private static void parseType5(
            AisMessage msg,
            String bits) {

        int len = bits.length();

        if (len >= 70) {

            msg.imo =
                    Integer.parseInt(
                            bits.substring(40, 70), 2);
        }

        if (len >= 112) {

            msg.callSign =
                    decode6bit(bits, 70, 42).trim();
        }

        if (len >= 232) {

            msg.vesselName =
                    decode6bit(bits, 112, 120).trim();
        }

        if (len >= 240) {

            msg.shipType =
                    Integer.parseInt(
                            bits.substring(232, 240), 2);
        }

        if (len >= 422) {

            msg.destination =
                    decode6bit(bits, 302, 120).trim();
        }
    }

    private static String decode6bit(
            String bits,
            int start,
            int bitLen) {

        StringBuilder sb = new StringBuilder();

        int end =
                Math.min(bits.length(), start + bitLen);

        for (int i = start; i + 6 <= end; i += 6) {

            String sub = bits.substring(i, i + 6);

            int v = Integer.parseInt(sub, 2);

            if (v < 0 || v >= SIXBIT_TABLE.length) {

                sb.append(' ');

            } else {

                char ch = SIXBIT_TABLE[v];

                if (ch == '@') {
                    ch = ' ';
                }

                sb.append(ch);
            }
        }

        return sb.toString();
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