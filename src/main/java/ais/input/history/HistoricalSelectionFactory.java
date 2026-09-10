package ais.input.history;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

public final class HistoricalSelectionFactory {

    public HistoricalDaySelection direct(Path file, ZoneId sourceZone)
            throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(sourceZone, "sourceZone");
        Path normalized = file.toAbsolutePath().normalize();
        HistoricalLineParser parser = new HistoricalLineParser(sourceZone);
        try (BufferedReader reader = HistoricalReaders.open(normalized)) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                try {
                    LocalDate date = parser.parse(
                                    line, normalized, lineNumber)
                            .receivedAt().atZone(sourceZone).toLocalDate();
                    return new HistoricalDaySelection(
                            date, List.of(normalized), true);
                } catch (HistoricalLineException ignored) {
                    // Continue until a timestamped record identifies the day.
                }
            }
        }
        throw new IOException(
                "The selected file contains no timestamped AIS record: "
                        + normalized);
    }
}
