package ais.input.history;

import ais.input.InputDiagnostic;
import ais.input.ReceivedNmea;
import ais.input.SourceListener;
import ais.input.SourceStatus;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalLogSourceTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 4);

    @TempDir
    Path tempDirectory;

    @Test
    void catalogsAndMergesPlainAndGzipLogsByReceptionTime()
            throws Exception {
        String first = sentence(431_000_001, 34.5);
        String second = sentence(431_000_002, 34.6);
        String third = sentence(431_000_003, 34.7);
        Path plain = tempDirectory.resolve("b.ais");
        Path gzip = tempDirectory.resolve("a.ais.gz");
        Path duplicateGzip = tempDirectory.resolve("b.ais.gz");

        String plainContents = "20260904000001000 " + second
                + System.lineSeparator();
        Files.writeString(plain, plainContents, StandardCharsets.UTF_8);
        writeGzip(
                gzip,
                "invalid line" + System.lineSeparator()
                        + "20260904000001000 " + first
                        + System.lineSeparator()
                        + "20260904000003000 " + third
                        + System.lineSeparator());
        writeGzip(duplicateGzip, plainContents);

        HistoricalFileCatalog catalog = HistoricalFileCatalog.scan(
                tempDirectory,
                JST);
        assertEquals(List.of(DATE), catalog.availableDates());
        assertEquals(2, catalog.filesFor(DATE).size());

        HistoricalLogSource source = new HistoricalLogSource(
                new HistoricalDaySelection(
                        DATE,
                        catalog.filesFor(DATE),
                        false),
                JST);
        RecordingListener listener = new RecordingListener();
        source.start(listener);

        assertTrue(listener.completed.await(5, TimeUnit.SECONDS));
        assertEquals(SourceStatus.COMPLETED, source.status());
        assertEquals(List.of(first, second, third),
                listener.records.stream()
                        .map(ReceivedNmea::sentence)
                        .toList());
        assertEquals(List.of(0L, 1L, 2L),
                listener.records.stream()
                        .map(ReceivedNmea::sequence)
                        .toList());
        assertEquals(1, listener.diagnostics.size());
        assertTrue(listener.failures.isEmpty());

        InputFingerprint plainFingerprint =
                new InputFingerprintCalculator().calculate(List.of(plain));
        InputFingerprint compressedFingerprint =
                new InputFingerprintCalculator().calculate(
                        List.of(duplicateGzip));
        assertEquals(plainFingerprint, compressedFingerprint);
    }

    @Test
    void unreadableGzipDoesNotHideOtherUsableDays() throws Exception {
        Path usable = tempDirectory.resolve("usable.ais");
        Files.writeString(usable,
                "20260904000001000 "
                        + sentence(431_000_001, 34.5)
                        + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Path corrupt = tempDirectory.resolve("broken.ais.gz");
        Files.write(corrupt, new byte[]{0x1f, (byte) 0x8b, 0x08, 0x00});

        HistoricalFileCatalog catalog = HistoricalFileCatalog.scan(
                tempDirectory, JST);

        assertEquals(List.of(DATE), catalog.availableDates());
        assertEquals(List.of(usable.toAbsolutePath()),
                catalog.filesFor(DATE));
        assertEquals(List.of(corrupt.toAbsolutePath()),
                catalog.unclassifiedFiles());
    }

    private static String sentence(int mmsi, double latitude) {
        return AisTestData.sentence(
                AisTestData.type1(1, mmsi, latitude, 135.2),
                0);
    }

    private static void writeGzip(Path file, String contents)
            throws IOException {
        try (GZIPOutputStream compressed = new GZIPOutputStream(
                Files.newOutputStream(file));
             OutputStreamWriter writer = new OutputStreamWriter(
                     compressed,
                     StandardCharsets.UTF_8)) {
            writer.write(contents);
        }
    }

    private static final class RecordingListener implements SourceListener {

        private final List<ReceivedNmea> records =
                Collections.synchronizedList(new ArrayList<>());
        private final List<InputDiagnostic> diagnostics =
                Collections.synchronizedList(new ArrayList<>());
        private final List<Throwable> failures =
                Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void onRecord(ReceivedNmea record) {
            records.add(record);
        }

        @Override
        public void onDiagnostic(InputDiagnostic diagnostic) {
            diagnostics.add(diagnostic);
        }

        @Override
        public void onCompleted() {
            completed.countDown();
        }

        @Override
        public void onFailure(Throwable error) {
            failures.add(error);
            completed.countDown();
        }
    }
}
