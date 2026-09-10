package ais.app;

import ais.domain.AnalysisProfile;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.SourceMode;
import ais.input.InputDiagnostic;
import ais.input.MessageSource;
import ais.input.ReceivedNmea;
import ais.input.SourceListener;
import ais.input.SourceReference;
import ais.input.SourceStatus;
import ais.input.live.UdpSourceConfig;
import ais.storage.AnalysisResultStore;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcAnalysisRunRepository;
import ais.storage.JdbcDiagnosticRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.SchemaMigrator;
import ais.storage.SqliteDatabase;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveAnalysisServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void startsStopsPersistsAndResumesOneLiveSession() throws Exception {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve("live.db"));
        new SchemaMigrator(database).migrate();
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        MutableClock clock = new MutableClock(
                Instant.parse("2026-09-05T00:00:00Z"));
        AtomicReference<FakeSource> latestSource = new AtomicReference<>();

        try (LiveAnalysisService service = new LiveAnalysisService(
                receiver, profile,
                new UdpSourceConfig(InetAddress.getLoopbackAddress(),
                        17020, 65_536),
                new AnalysisResultStore(database),
                ais.analysis.DefaultAnalysisEngine::new,
                () -> {
                    FakeSource value = new FakeSource();
                    latestSource.set(value);
                    return value;
                }, clock, 16)) {
            service.startOrResume().join();
            awaitState(service, LiveState.RUNNING);
            FakeSource firstSource = latestSource.get();
            firstSource.emit(record(clock.advanceSeconds(1), 0,
                    34.60));
            firstSource.emit(record(clock.advanceSeconds(10), 1,
                    34.61));
            firstSource.emit(record(clock.advanceSeconds(300), 2,
                    34.62));
            awaitDecoded(service, 3);
            awaitPendingCheckpoint(database);

            LiveFrame stopped = service.stop().join();
            assertEquals(LiveState.STOPPED, stopped.state());
            assertNotNull(stopped.snapshot());
            assertEquals(2, stopped.snapshot().acceptedIntervalCount());

            var runs = new JdbcAnalysisRunRepository(database).findCompleted(
                    Instant.parse("2026-09-04T15:00:00Z"),
                    Instant.parse("2026-09-06T15:00:00Z"),
                    receiver.id(), profile.id());
            assertEquals(1, runs.size());
            assertEquals(SourceMode.LIVE, runs.getFirst().run().sourceMode());

            service.startOrResume().join();
            awaitState(service, LiveState.RUNNING);
            assertFalse(firstSource == latestSource.get());
            latestSource.get().emit(record(clock.advanceSeconds(10), 3,
                    34.63));
            awaitDecoded(service, 4);
            assertEquals(LiveState.STOPPED, service.stop().join().state());
            assertEquals(1, new JdbcAnalysisRunRepository(database)
                    .findCompleted(null, null, receiver.id(), profile.id())
                    .size());
        }
    }

    @Test
    void stoppingDuringStartupCompletesWithoutStartingAReceiver()
            throws Exception {
        SqliteDatabase database = initializedDatabase("startup-stop.db");
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        MutableClock clock = new MutableClock(
                Instant.parse("2026-09-05T00:00:00Z"));
        AtomicReference<FakeSource> latestSource = new AtomicReference<>();

        try (LiveAnalysisService service = new LiveAnalysisService(
                receiver, profile,
                new UdpSourceConfig(InetAddress.getLoopbackAddress(),
                        17020, 65_536),
                new AnalysisResultStore(database),
                ais.analysis.DefaultAnalysisEngine::new,
                () -> {
                    FakeSource value = new FakeSource();
                    latestSource.set(value);
                    return value;
                }, clock, 16)) {
            service.startOrResume();
            LiveFrame stopped = service.stop().join();

            assertEquals(LiveState.STOPPED, stopped.state());
            assertTrue(latestSource.get() == null
                    || latestSource.get().status() == SourceStatus.STOPPED);
            assertEquals(1, new JdbcAnalysisRunRepository(database)
                    .findCompleted(null, null, receiver.id(), profile.id())
                    .size());
        }
    }

    @Test
    void queueOverflowIsStoredAsPcDelayAndNotJoinedToEarlierData()
            throws Exception {
        SqliteDatabase database = initializedDatabase("overflow.db");
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        MutableClock clock = new MutableClock(
                Instant.parse("2026-09-05T00:00:00Z"));

        try (LiveAnalysisService service = new LiveAnalysisService(
                receiver, profile,
                new UdpSourceConfig(InetAddress.getLoopbackAddress(),
                        17020, 65_536),
                new AnalysisResultStore(database),
                ais.analysis.DefaultAnalysisEngine::new,
                () -> new BurstSource(List.of(
                        record(clock.advanceSeconds(1), 0, 34.60),
                        record(clock.advanceSeconds(1), 1, 34.61),
                        record(clock.advanceSeconds(1), 2, 34.62))),
                clock, 1)) {
            service.startOrResume().join();
            awaitState(service, LiveState.RUNNING);
            LiveFrame stopped = service.stop().join();

            assertEquals(LiveState.STOPPED, stopped.state());
            assertEquals(2, stopped.diagnosticCount());
            assertTrue(stopped.message().contains("PC処理遅延"));
            var run = new JdbcAnalysisRunRepository(database)
                    .findCompleted(null, null, receiver.id(), profile.id())
                    .getFirst();
            var diagnostics = new JdbcDiagnosticRepository(database)
                    .findSummaries(run.run().id());
            assertEquals(2, diagnostics.stream()
                    .filter(value -> value.code().equals("SOURCE_OVERFLOW"))
                    .findFirst().orElseThrow().count());
            var exclusions = new JdbcDiagnosticRepository(database)
                    .findExclusionPeriods(run.run().id());
            assertEquals(1, exclusions.size());
            assertEquals(2, exclusions.getFirst().affectedCount());
        }
    }

    @Test
    void resetAfterSourceFailureFinalizesThePartialSession()
            throws Exception {
        SqliteDatabase database = initializedDatabase("failure-reset.db");
        ReceiverProfile receiver = receiver();
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        new JdbcReceiverProfileRepository(database).save(receiver);
        new JdbcAnalysisProfileRepository(database).save(profile);
        MutableClock clock = new MutableClock(
                Instant.parse("2026-09-05T00:00:00Z"));

        try (LiveAnalysisService service = new LiveAnalysisService(
                receiver, profile,
                new UdpSourceConfig(InetAddress.getLoopbackAddress(),
                        17020, 65_536),
                new AnalysisResultStore(database),
                ais.analysis.DefaultAnalysisEngine::new,
                () -> new FailureSource(record(
                        clock.advanceSeconds(1), 0, 34.60)),
                clock, 4)) {
            service.startOrResume().join();
            awaitState(service, LiveState.ERROR);

            LiveFrame reset = service.reset().join();

            assertEquals(LiveState.IDLE, reset.state());
            assertEquals(1, new JdbcAnalysisRunRepository(database)
                    .findCompleted(null, null, receiver.id(), profile.id())
                    .size());
        }
    }

    private SqliteDatabase initializedDatabase(String filename) {
        SqliteDatabase database = new SqliteDatabase(
                temporaryDirectory.resolve(filename));
        new SchemaMigrator(database).migrate();
        return database;
    }

    private static ReceivedNmea record(Instant at, long sequence,
                                       double latitude) {
        String sentence = AisTestData.sentence(AisTestData.type1(
                1, 431000001, latitude, 135.2), 0);
        return new ReceivedNmea(at, sequence, sentence,
                SourceReference.live("udp://test", sequence));
    }

    private static void awaitState(LiveAnalysisService service,
                                   LiveState state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.state() != state && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(state, service.state());
    }

    private static void awaitDecoded(LiveAnalysisService service,
                                     long count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.currentFrame().decodedEventCount() < count
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(service.currentFrame().decodedEventCount() >= count);
    }

    private static void awaitPendingCheckpoint(SqliteDatabase database)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long metricCount = 0;
        while (metricCount == 0 && System.nanoTime() < deadline) {
            try (Connection connection = database.open();
                    Statement statement = connection.createStatement();
                    ResultSet results = statement.executeQuery("""
                            SELECT COUNT(*)
                              FROM distance_metric_5m AS metric
                              JOIN analysis_run AS run
                                ON run.id = metric.analysis_run_id
                             WHERE run.status = 'PENDING'
                            """)) {
                metricCount = results.next() ? results.getLong(1) : 0;
            }
            if (metricCount == 0) {
                Thread.sleep(10);
            }
        }
        assertTrue(metricCount > 0,
                "a five-minute live checkpoint should be persisted");
    }

    private static ReceiverProfile receiver() {
        return new ReceiverProfile(new ReceiverProfileId("live-receiver"),
                "Live receiver", new GeoPosition(34.68, 135.19),
                30.0, "antenna", "receiver",
                LocalDate.of(2020, 1, 1), null, "test");
    }

    private static final class FakeSource implements MessageSource {
        private volatile SourceStatus status = SourceStatus.IDLE;
        private volatile SourceListener listener;

        @Override
        public SourceMode mode() {
            return SourceMode.LIVE;
        }

        @Override
        public SourceStatus status() {
            return status;
        }

        @Override
        public void start(SourceListener listener) {
            this.listener = listener;
            status = SourceStatus.RUNNING;
            listener.onStarted();
        }

        private void emit(ReceivedNmea record) {
            listener.onRecord(record);
        }

        @Override
        public void stop() {
            status = SourceStatus.STOPPED;
            listener.onCompleted();
        }
    }

    private static final class BurstSource implements MessageSource {
        private final List<ReceivedNmea> records;
        private volatile SourceStatus status = SourceStatus.IDLE;
        private volatile SourceListener listener;

        private BurstSource(List<ReceivedNmea> records) {
            this.records = List.copyOf(records);
        }

        @Override
        public SourceMode mode() {
            return SourceMode.LIVE;
        }

        @Override
        public SourceStatus status() {
            return status;
        }

        @Override
        public void start(SourceListener listener) {
            this.listener = listener;
            status = SourceStatus.RUNNING;
            listener.onStarted();
            records.forEach(listener::onRecord);
        }

        @Override
        public void stop() {
            status = SourceStatus.STOPPED;
            listener.onCompleted();
        }
    }

    private static final class FailureSource implements MessageSource {
        private final ReceivedNmea record;
        private volatile SourceStatus status = SourceStatus.IDLE;

        private FailureSource(ReceivedNmea record) {
            this.record = record;
        }

        @Override
        public SourceMode mode() {
            return SourceMode.LIVE;
        }

        @Override
        public SourceStatus status() {
            return status;
        }

        @Override
        public void start(SourceListener listener) {
            status = SourceStatus.RUNNING;
            listener.onStarted();
            listener.onRecord(record);
            status = SourceStatus.FAILED;
            listener.onFailure(new IllegalStateException("test failure"));
        }

        @Override
        public void stop() {
            status = SourceStatus.STOPPED;
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private Instant advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
