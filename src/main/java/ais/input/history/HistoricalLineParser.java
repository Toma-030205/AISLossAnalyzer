package ais.input.history;

import ais.input.SourceReference;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Objects;

public final class HistoricalLineParser {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmssSSS")
                    .withResolverStyle(ResolverStyle.STRICT);

    private final ZoneId sourceZone;

    public HistoricalLineParser(ZoneId sourceZone) {
        this.sourceZone = Objects.requireNonNull(sourceZone, "sourceZone");
    }

    public HistoricalRecord parse(
            String line,
            Path file,
            long lineNumber) throws HistoricalLineException {
        Objects.requireNonNull(file, "file");
        SourceReference source =
                SourceReference.historical(file, lineNumber);

        if (line == null || line.isBlank()) {
            throw new HistoricalLineException("line is empty");
        }

        int start = line.charAt(0) == '\uFEFF' ? 1 : 0;
        int separator = firstWhitespace(line, start);

        if (separator < 0) {
            throw new HistoricalLineException(
                    "timestamp and NMEA sentence are not separated");
        }

        String timestampText = line.substring(start, separator);
        String sentence = line.substring(separator).trim();

        if (timestampText.length() != 17 || sentence.isEmpty()) {
            throw new HistoricalLineException(
                    "expected a 17-digit timestamp followed by NMEA");
        }

        try {
            LocalDateTime localTime = LocalDateTime.parse(
                    timestampText,
                    TIMESTAMP_FORMATTER);
            Instant receivedAt = localTime
                    .atZone(sourceZone)
                    .toInstant();
            return new HistoricalRecord(receivedAt, sentence, source);
        } catch (DateTimeException exception) {
            throw new HistoricalLineException(
                    "timestamp is invalid",
                    exception);
        }
    }

    private static int firstWhitespace(String value, int start) {
        for (int index = start; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }
}
