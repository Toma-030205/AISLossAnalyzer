package ais.parser;

import ais.model.AisMessage;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

public class FileLoader {

    public List<AisMessage> loadFile(String filePath) {

        List<AisMessage> messages =
                new ArrayList<>();

        loadFile(
                filePath,
                messages::add);

        return messages;
    }

    public void loadFile(
            String filePath,
            Consumer<AisMessage> consumer) {

        loadFileWithStatistics(filePath, consumer);
    }

    public FileLoadStatistics loadFileWithStatistics(
            String filePath,
            Consumer<AisMessage> consumer) {

        String lowerName =
                filePath.toLowerCase();

        boolean decodedCsv =
                lowerName.endsWith(".csv")
                        || lowerName.endsWith(".csv.gz");

        /* Exact capture duplicates are removed within each input file. */
        Set<String> rowsSeen =
                new HashSet<>();

        long totalRows = 0;
        long exactDuplicateRows = 0;
        long targetMessages = 0;
        long invalidOrNonTargetRows = 0;

        try (BufferedReader reader =
                     openReader(filePath)) {

            String line;

            while ((line = reader.readLine()) != null) {

                totalRows++;

                if (!rowsSeen.add(line)) {
                    exactDuplicateRows++;
                    continue;
                }

                if (decodedCsv) {

                    AisMessage message =
                            DecodedCsvParser.decode(line);

                    if (isValidTargetMessage(message)) {
                        consumer.accept(message);
                        targetMessages++;
                    } else {
                        invalidOrNonTargetRows++;
                    }

                    continue;
                }

                int start =
                        !line.isEmpty() && line.charAt(0) == '\uFEFF'
                                ? 1
                                : 0;

                int separator =
                        line.indexOf(' ', start);

                if (separator < 0) {
                    invalidOrNonTargetRows++;
                    continue;
                }

                String timestamp =
                        line.substring(start, separator);

                String nmea =
                        line.substring(separator + 1).trim();

                AisMessage message =
                        AisDecoder.decode(nmea);

                if (!isValidTargetMessage(message)) {
                    invalidOrNonTargetRows++;
                    continue;
                }

                message.timestamp =
                        parseFixedTimestamp(timestamp);

                consumer.accept(message);
                targetMessages++;
            }

        } catch (Exception e) {

            throw new IllegalArgumentException(
                    "Could not load AIS file: "
                    + filePath,
                    e);
        }

        return new FileLoadStatistics(
                totalRows,
                exactDuplicateRows,
                targetMessages,
                invalidOrNonTargetRows);
    }

    private static LocalDateTime parseFixedTimestamp(
            String value) {

        if (value.length() != 17) {
            throw new IllegalArgumentException(
                    "Unexpected AIS timestamp: " + value);
        }

        return LocalDateTime.of(
                digits(value, 0, 4),
                digits(value, 4, 2),
                digits(value, 6, 2),
                digits(value, 8, 2),
                digits(value, 10, 2),
                digits(value, 12, 2),
                digits(value, 14, 3) * 1_000_000);
    }

    private static int digits(
            String value,
            int start,
            int length) {

        int result = 0;
        for (int i = start; i < start + length; i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(
                        "Unexpected AIS timestamp: " + value);
            }
            result = result * 10 + character - '0';
        }
        return result;
    }

    private static BufferedReader openReader(
            String filePath) throws Exception {

        InputStream input =
                new FileInputStream(
                        Path.of(filePath).toFile());

        if (filePath
                .toLowerCase()
                .endsWith(".gz")) {

            input =
                    new GZIPInputStream(input);
        }

        return new BufferedReader(
                new InputStreamReader(
                        input,
                        StandardCharsets.UTF_8));
    }

    private static boolean isValidTargetMessage(
            AisMessage message) {

        if (message == null) {
            return false;
        }

        if (message.messageType != 1
                && message.messageType != 2
                && message.messageType != 3
                && message.messageType != 5
                && message.messageType != 18) {

            return false;
        }

        if (message.messageType == 5) {
            return true;
        }

        if (message.lat == null
                || message.lon == null) {

            return false;
        }

        if (!Double.isFinite(message.lat)
                || message.lat > 90
                || message.lat < -90) {

            return false;
        }

        if (!Double.isFinite(message.lon)
                || message.lon > 180
                || message.lon < -180) {

            return false;
        }

        return !(message.lat == 0.0
                && message.lon == 0.0);
    }
}
