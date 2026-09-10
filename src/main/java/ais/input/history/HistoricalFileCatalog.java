package ais.input.history;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class HistoricalFileCatalog {

    private final Map<LocalDate, List<Path>> filesByDate;
    private final List<Path> unclassifiedFiles;

    private HistoricalFileCatalog(
            Map<LocalDate, List<Path>> filesByDate,
            List<Path> unclassifiedFiles) {
        this.filesByDate = filesByDate;
        this.unclassifiedFiles = List.copyOf(unclassifiedFiles);
    }

    public static HistoricalFileCatalog scan(
            Path dataRoot,
            ZoneId sourceZone) throws IOException {
        Objects.requireNonNull(dataRoot, "dataRoot");
        Objects.requireNonNull(sourceZone, "sourceZone");

        Path root = dataRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("AIS data root is not a directory: " + root);
        }

        HistoricalLineParser parser = new HistoricalLineParser(sourceZone);
        List<Path> candidates;
        try (var paths = Files.walk(root)) {
            candidates = paths
                    .filter(Files::isRegularFile)
                    .filter(HistoricalReaders::isSupported)
                    .sorted(Comparator.comparing(
                            Path::toString,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }

        Map<LocalDate, List<Path>> mutable = new LinkedHashMap<>();
        Map<LocalDate, Map<String, Path>> counterpartByDate =
                new LinkedHashMap<>();
        List<Path> unclassified = new ArrayList<>();

        for (Path file : candidates) {
            LocalDate date;
            try {
                date = firstRecordDate(file, parser, sourceZone);
            } catch (IOException unreadable) {
                unclassified.add(file);
                continue;
            }
            if (date == null) {
                unclassified.add(file);
                continue;
            }
            Map<String, Path> counterparts = counterpartByDate
                    .computeIfAbsent(
                            date,
                            ignored -> new LinkedHashMap<>());
            String logicalKey = logicalFileKey(file);
            Path counterpart = counterparts.putIfAbsent(
                    logicalKey,
                    file);

            boolean sameContent = false;
            if (counterpart != null) {
                try {
                    sameContent = sameUncompressedContent(
                            counterpart, file);
                } catch (IOException unreadable) {
                    unclassified.add(file);
                    continue;
                }
            }
            if (counterpart == null || !sameContent) {
                mutable.computeIfAbsent(date, ignored -> new ArrayList<>())
                        .add(file);
            }
        }

        Map<LocalDate, List<Path>> immutable = new LinkedHashMap<>();
        mutable.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> immutable.put(
                        entry.getKey(),
                        List.copyOf(entry.getValue())));
        return new HistoricalFileCatalog(
                Collections.unmodifiableMap(immutable),
                unclassified);
    }

    public List<LocalDate> availableDates() {
        return List.copyOf(filesByDate.keySet());
    }

    public List<Path> filesFor(LocalDate date) {
        return filesByDate.getOrDefault(date, List.of());
    }

    public List<Path> unclassifiedFiles() {
        return unclassifiedFiles;
    }

    private static boolean sameUncompressedContent(
            Path first,
            Path second) throws IOException {
        return InputFingerprintCalculator.calculateFile(first)
                .key()
                .equals(InputFingerprintCalculator.calculateFile(second)
                        .key());
    }

    private static String logicalFileKey(Path file) {
        String value = file.toAbsolutePath()
                .normalize()
                .toString()
                .toLowerCase(Locale.ROOT);
        return value.endsWith(".gz")
                ? value.substring(0, value.length() - 3)
                : value;
    }

    private static LocalDate firstRecordDate(
            Path file,
            HistoricalLineParser parser,
            ZoneId sourceZone) throws IOException {
        try (BufferedReader reader = HistoricalReaders.open(file)) {
            String line;
            long lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                try {
                    return parser.parse(line, file, lineNumber)
                            .receivedAt()
                            .atZone(sourceZone)
                            .toLocalDate();
                } catch (HistoricalLineException ignored) {
                    // Continue until the first parseable record.
                }
            }
            return null;
        }
    }
}
