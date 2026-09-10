package ais.input.live;

import ais.domain.SourceMode;
import ais.input.InputDiagnostic;
import ais.input.ReceivedNmea;
import ais.input.SourceListener;
import ais.input.SourceStatus;
import ais.testutil.AisTestData;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpAisSourceTest {

    @Test
    void receivesUtf8DatagramAndSplitsNmeaLines() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        Instant arrival = Instant.parse("2026-09-04T03:04:05Z");
        UdpAisSource source = new UdpAisSource(
                new UdpSourceConfig(loopback, 0, 65_536),
                Clock.fixed(arrival, ZoneOffset.UTC));
        RecordingListener listener = new RecordingListener(2);

        try {
            source.start(listener);
            awaitRunning(source);

            String first = AisTestData.sentence(AisTestData.type1(
                    1, 431_000_001, 34.5, 135.2), 0);
            String second = AisTestData.sentence(AisTestData.type18(
                    431_000_002, 34.6, 135.3), 0);
            byte[] bytes = (first + "\r\n" + second + "\r\n")
                    .getBytes(StandardCharsets.UTF_8);

            try (DatagramSocket sender = new DatagramSocket()) {
                sender.send(new DatagramPacket(
                        bytes,
                        bytes.length,
                        loopback,
                        source.localPort()));
            }

            assertTrue(listener.recordsReceived.await(
                    5,
                    TimeUnit.SECONDS));
            assertEquals(List.of(first, second),
                    listener.records.stream()
                            .map(ReceivedNmea::sentence)
                            .toList());
            assertTrue(listener.records.stream()
                    .allMatch(record -> record.receivedAt().equals(arrival)));
            assertTrue(listener.records.stream()
                    .allMatch(record -> record.source().mode()
                            == SourceMode.LIVE));
            assertTrue(listener.failures.isEmpty());
        } finally {
            source.stop();
            awaitStopped(source);
            assertTrue(listener.completed.await(5, TimeUnit.SECONDS),
                    "stopping UDP reception must notify completion");
        }
    }

    private static void awaitRunning(UdpAisSource source)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (source.status() == SourceStatus.STARTING
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(SourceStatus.RUNNING, source.status());
        assertTrue(source.localPort() > 0);
    }

    private static void awaitStopped(UdpAisSource source)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((source.status() == SourceStatus.STOPPING
                || source.status() == SourceStatus.RUNNING)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(SourceStatus.STOPPED, source.status());
    }

    private static final class RecordingListener implements SourceListener {

        private final List<ReceivedNmea> records =
                Collections.synchronizedList(new ArrayList<>());
        private final List<Throwable> failures =
                Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch recordsReceived;
        private final CountDownLatch completed = new CountDownLatch(1);

        private RecordingListener(int expectedRecordCount) {
            recordsReceived = new CountDownLatch(expectedRecordCount);
        }

        @Override
        public void onRecord(ReceivedNmea record) {
            records.add(record);
            recordsReceived.countDown();
        }

        @Override
        public void onDiagnostic(InputDiagnostic diagnostic) {
            // UDP framing has no source-level diagnostics at present.
        }

        @Override
        public void onCompleted() {
            completed.countDown();
        }

        @Override
        public void onFailure(Throwable error) {
            failures.add(error);
            while (recordsReceived.getCount() > 0) {
                recordsReceived.countDown();
            }
        }
    }
}
