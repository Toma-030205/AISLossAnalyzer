package ais.parser;

import ais.model.AisMessage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Parses the already-decoded AIS CSV format used by the June 2007 archive.
 *
 * <pre>
 * 0 date, 1 time, 2 message type, 3 repeat, 4 MMSI,
 * 5 navigation status, 7 SOG, 9 longitude, 10 latitude,
 * 11 COG, 12 true heading
 * </pre>
 */
public final class DecodedCsvParser {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern(
                    "yy/MM/dd HH:mm:ss",
                    Locale.ROOT);

    private DecodedCsvParser() {
    }

    public static AisMessage decode(String line) {

        if (line == null || line.isBlank()) {
            return null;
        }

        String[] columns = line.split(",", -1);

        if (columns.length < 5) {
            return null;
        }

        Integer messageType = parseInteger(columns[2]);
        Integer mmsi = parseInteger(columns[4]);

        if (messageType == null
                || mmsi == null
                || mmsi <= 0) {

            return null;
        }

        if (messageType != 1
                && messageType != 2
                && messageType != 3
                && messageType != 5
                && messageType != 18) {

            return null;
        }

        LocalDateTime timestamp;

        try {
            timestamp = LocalDateTime.parse(
                    clean(columns[0])
                            + " "
                            + clean(columns[1]),
                    TIMESTAMP_FORMAT);
        } catch (Exception e) {
            return null;
        }

        AisMessage message = new AisMessage();
        message.messageType = messageType;
        message.mmsi = mmsi;
        message.timestamp = timestamp;

        if (messageType == 1
                || messageType == 2
                || messageType == 3
                || messageType == 18) {

            parsePositionReport(message, columns);
        }

        if (messageType == 5) {
            parseType5(message, columns);
        }

        return message;
    }

    private static void parsePositionReport(
            AisMessage message,
            String[] columns) {

        if (columns.length <= 12) {
            return;
        }

        message.sog = parseDouble(columns[7]);

        if (message.sog != null
                && message.sog >= 102.3) {

            message.sog = null;
        }

        message.lon = parseDouble(columns[9]);
        message.lat = parseDouble(columns[10]);
        message.cog = parseDouble(columns[11]);

        if (message.cog != null
                && message.cog >= 360.0) {

            message.cog = null;
        }

        message.trueHeading =
                parseDouble(columns[12]);

        if (message.trueHeading != null
                && message.trueHeading >= 360.0) {

            message.trueHeading = null;
        }

        if (message.messageType == 1
                || message.messageType == 2
                || message.messageType == 3) {

            message.navStatus =
                    parseInteger(columns[5]);
        }

        /*
         * The decoded 2007 Type 18 rows do not retain the CS/SO flag.
         * Leaving classBCsUnit null applies the existing SO/default rule.
         */
    }

    private static void parseType5(
            AisMessage message,
            String[] columns) {

        if (columns.length > 19) {
            message.imo = parseInteger(columns[19]);
        }

        if (columns.length > 20) {
            message.callSign = clean(columns[20]);
        }

        if (columns.length > 21) {
            message.vesselName = clean(columns[21]);
        }

        if (columns.length > 22) {
            message.shipType = parseInteger(columns[22]);
        }

        if (columns.length > 24) {

            Integer toBow = parseInteger(columns[23]);
            Integer toStern = parseInteger(columns[24]);

            if (toBow != null
                    && toStern != null
                    && toBow + toStern > 0) {

                message.shipLength = toBow + toStern;
            }
        }

        if (columns.length > 31) {

            StringBuilder destination =
                    new StringBuilder(clean(columns[31]));

            for (int i = 32;
                    i < columns.length;
                    i++) {

                destination
                        .append(',')
                        .append(clean(columns[i]));
            }

            message.destination =
                    destination.toString().trim();
        }
    }

    private static Integer parseInteger(String value) {

        try {
            return Integer.parseInt(clean(value));
        } catch (Exception e) {
            return null;
        }
    }

    private static Double parseDouble(String value) {

        try {
            return Double.parseDouble(clean(value));
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace("\uFEFF", "")
                .trim();
    }
}
