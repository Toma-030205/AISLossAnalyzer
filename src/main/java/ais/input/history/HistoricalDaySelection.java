package ais.input.history;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record HistoricalDaySelection(
        LocalDate date,
        List<Path> files,
        boolean directSelection) {

    public HistoricalDaySelection {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(files, "files");

        files = files.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted(Comparator.comparing(
                        Path::toString,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (files.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one historical AIS file is required");
        }
        for (Path file : files) {
            if (!HistoricalReaders.isSupported(file)) {
                throw new IllegalArgumentException(
                        "unsupported historical AIS file: " + file);
            }
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException(
                        "historical AIS file does not exist: " + file);
            }
        }
    }
}
