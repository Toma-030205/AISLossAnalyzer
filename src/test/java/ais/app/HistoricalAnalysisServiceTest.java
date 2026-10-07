package ais.app;

import ais.domain.AnalysisProfile;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.input.history.HistoricalDaySelection;
import ais.storage.AnalysisResultStore;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcAnalysisRunRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.SchemaMigrator;
import ais.storage.SqliteDatabase;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalAnalysisServiceTest {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 4);

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsAdvancesRebuildsBackwardAndSavesFullDay() throws Exception {
        Path log = temporaryDirectory.resolve("day.ais");
        Files.writeString(log,
                line("20260904090000000", 34.6000)
                        + line("20260904090010000", 34.6005)
                        + line("20260904090020000", 34.6010),
                StandardCharsets.UTF_8);
        HistoricalDaySelection selection = new HistoricalDaySelection(
                DAY, List.of(log), true);
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("analysis.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);

        try (HistoricalAnalysisService service =
                     new HistoricalAnalysisService(
                             receiver, profile, JAPAN,
                             new AnalysisResultStore(database))) {
            ReplayFrame loaded = service.load(selection, ignored -> { })
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ReplayState.READY, loaded.state());
            assertEquals(1, loaded.processedEventCount());
            assertEquals(3, loaded.dataset().events().size());

            ReplayFrame end = service.advance(Duration.ofSeconds(20))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ReplayState.END, end.state());
            assertEquals(3, end.processedEventCount());
            assertEquals(2, end.snapshot().acceptedIntervalCount());

            ReplayFrame rewound = service.seek(
                            loaded.displayTime().plusSeconds(10))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(ReplayState.PAUSED, rewound.state());
            assertEquals(2, rewound.processedEventCount());
            assertEquals(1, rewound.snapshot().acceptedIntervalCount());

            FullDayAnalysisResult saved = service.analyzeAndSave()
                    .get(5, TimeUnit.SECONDS);
            assertEquals(2, saved.summary().acceptedIntervalCount());
            var stored = new JdbcAnalysisRunRepository(database)
                    .findEquivalent(DAY, selectionFingerprint(selection),
                            receiver.id(), profile.id());
            assertTrue(stored.isPresent());
            assertEquals(saved.runId(), stored.orElseThrow().run().id());
            assertTrue(stored.orElseThrow().active());
        }
    }

    @Test
    void loaderKeepsDiagnosticsButDoesNotPersistRawLines() throws Exception {
        Path log = temporaryDirectory.resolve("diagnostic.ais");
        Files.writeString(log,
                "not a log line\n" + line(
                        "20260904090000000", 34.6),
                StandardCharsets.UTF_8);
        HistoricalDaySelection selection = new HistoricalDaySelection(
                DAY, List.of(log), true);

        HistoricalReplayDataset dataset = new HistoricalReplayLoader(JAPAN)
                .load(selection, ignored -> { });

        assertEquals(1, dataset.events().size());
        assertFalse(dataset.diagnostics().isEmpty());
        assertEquals(2, dataset.inputRecordCount() +
                dataset.diagnostics().stream()
                        .filter(diagnostic -> diagnostic.code().name()
                                .equals("INVALID_LOG_LINE"))
                        .count());
    }

    @Test
    void batchSavesDatesInOrderSkipsEquivalentRunsAndKeepsReplay()
            throws Exception {
        LocalDate nextDay = DAY.plusDays(1);
        HistoricalDaySelection first = selection(
                "first.ais", DAY, "20260904");
        HistoricalDaySelection second = selection(
                "second.ais", nextDay, "20260905");
        SqliteDatabase database = configuredDatabase("batch.db");
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        List<HistoricalBatchProgress> progress = new ArrayList<>();

        try (HistoricalAnalysisService service =
                     new HistoricalAnalysisService(
                             receiver, profile, JAPAN,
                             new AnalysisResultStore(database))) {
            ReplayFrame displayed = service.load(first, ignored -> { })
                    .get(5, TimeUnit.SECONDS);

            HistoricalBatchResult saved = service.analyzeAndSaveBatch(
                            List.of(second, first), true, progress::add)
                    .get(10, TimeUnit.SECONDS);

            assertEquals(2, saved.savedDays());
            assertEquals(0, saved.skippedDays());
            assertEquals(0, saved.failedDays());
            assertFalse(saved.cancelled());
            JdbcAnalysisRunRepository runs =
                    new JdbcAnalysisRunRepository(database);
            assertTrue(runs.findEquivalent(
                    DAY, selectionFingerprint(first), receiver.id(),
                    profile.id()).isPresent());
            assertTrue(runs.findEquivalent(
                    nextDay, selectionFingerprint(second), receiver.id(),
                    profile.id()).isPresent());
            assertEquals(DAY, progress.getFirst().date());

            ReplayFrame afterBatch = service.pause()
                    .get(5, TimeUnit.SECONDS);
            assertEquals(DAY, afterBatch.dataset().selection().date());
            assertEquals(displayed.displayTime(), afterBatch.displayTime());

            HistoricalBatchResult skipped = service.analyzeAndSaveBatch(
                            List.of(first, second), true, ignored -> { })
                    .get(10, TimeUnit.SECONDS);
            assertEquals(0, skipped.savedDays());
            assertEquals(2, skipped.skippedDays());
            assertEquals(0, skipped.failedDays());
        }
    }

    @Test
    void batchContinuesAfterInvalidDayAndCanBeCancelled() throws Exception {
        Path invalidLog = temporaryDirectory.resolve("invalid.ais");
        Files.writeString(invalidLog, "not an AIS record\n",
                StandardCharsets.UTF_8);
        HistoricalDaySelection invalid = new HistoricalDaySelection(
                DAY, List.of(invalidLog), false);
        HistoricalDaySelection valid = selection(
                "valid.ais", DAY.plusDays(1), "20260905");
        SqliteDatabase database = configuredDatabase("continue.db");
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);

        try (HistoricalAnalysisService service =
                     new HistoricalAnalysisService(
                             receiver, profile, JAPAN,
                             new AnalysisResultStore(database))) {
            HistoricalBatchResult continued = service.analyzeAndSaveBatch(
                            List.of(invalid, valid), true, ignored -> { })
                    .get(10, TimeUnit.SECONDS);
            assertEquals(1, continued.savedDays());
            assertEquals(1, continued.failedDays());
            assertEquals(DAY, continued.failures().getFirst().date());

            HistoricalBatchResult cancelled = service.analyzeAndSaveBatch(
                            List.of(invalid, valid), false, progress ->
                                    service.cancelBatchAnalysis())
                    .get(10, TimeUnit.SECONDS);
            assertTrue(cancelled.cancelled());
            assertEquals(0, cancelled.processedDays());
            assertEquals(2, cancelled.unprocessedDays());
        }
    }

    private HistoricalDaySelection selection(
            String filename, LocalDate date, String compactDate)
            throws Exception {
        Path log = temporaryDirectory.resolve(filename);
        Files.writeString(log,
                line(compactDate + "090000000", 34.6000)
                        + line(compactDate + "090010000", 34.6005)
                        + line(compactDate + "090020000", 34.6010),
                StandardCharsets.UTF_8);
        return new HistoricalDaySelection(date, List.of(log), false);
    }

    private SqliteDatabase configuredDatabase(String filename) {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve(filename));
        new SchemaMigrator(database).migrate();
        return database;
    }

    private static String line(String timestamp, double latitude) {
        String payload = AisTestData.type1(
                1, 431000001, latitude, 135.2);
        return timestamp + " "
                + AisTestData.sentence(payload, 0) + "\n";
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(
                new ReceiverProfileId("lab"), "Laboratory",
                new GeoPosition(34.718983358515715,
                        135.29057866131427),
                null, null, null,
                LocalDate.of(2000, 1, 1), null, null);
    }

    private static ais.input.history.InputFingerprint selectionFingerprint(
            HistoricalDaySelection selection) throws Exception {
        return new ais.input.history.InputFingerprintCalculator()
                .calculate(selection.files());
    }
}
