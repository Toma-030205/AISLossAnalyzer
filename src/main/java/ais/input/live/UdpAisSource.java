package ais.input.live;

import ais.domain.SourceMode;
import ais.input.MessageSource;
import ais.input.ReceivedNmea;
import ais.input.SourceListener;
import ais.input.SourceReference;
import ais.input.SourceStatus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class UdpAisSource implements MessageSource {

    private static final int MAX_UDP_DATAGRAM_BYTES = 65_535;

    private final UdpSourceConfig config;
    private final Clock clock;
    private final AtomicReference<SourceStatus> status =
            new AtomicReference<>(SourceStatus.IDLE);

    private volatile boolean stopRequested;
    private volatile Thread worker;
    private volatile DatagramSocket socket;
    private volatile int localPort = -1;

    public UdpAisSource(UdpSourceConfig config) {
        this(config, Clock.systemUTC());
    }

    public UdpAisSource(UdpSourceConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SourceMode mode() {
        return SourceMode.LIVE;
    }

    @Override
    public SourceStatus status() {
        return status.get();
    }

    public int localPort() {
        return localPort;
    }

    @Override
    public synchronized void start(SourceListener listener) {
        Objects.requireNonNull(listener, "listener");
        SourceStatus current = status.get();
        if (current == SourceStatus.STARTING
                || current == SourceStatus.RUNNING
                || current == SourceStatus.STOPPING) {
            throw new IllegalStateException("UDP source is already active");
        }

        stopRequested = false;
        localPort = -1;
        status.set(SourceStatus.STARTING);
        worker = Thread.ofPlatform()
                .daemon(true)
                .name("ais-udp-source")
                .start(() -> runSource(listener));
    }

    @Override
    public synchronized void stop() {
        SourceStatus current = status.get();
        if (current != SourceStatus.STARTING
                && current != SourceStatus.RUNNING) {
            return;
        }

        stopRequested = true;
        status.set(SourceStatus.STOPPING);
        DatagramSocket currentSocket = socket;
        if (currentSocket != null) {
            currentSocket.close();
        }
    }

    private void runSource(SourceListener listener) {
        try (DatagramSocket openedSocket = new DatagramSocket(null)) {
            socket = openedSocket;
            openedSocket.setReceiveBufferSize(
                    config.socketReceiveBufferBytes());
            openedSocket.bind(config.socketAddress());
            localPort = openedSocket.getLocalPort();

            if (stopRequested) {
                status.set(SourceStatus.STOPPED);
                listener.onCompleted();
                return;
            }

            status.set(SourceStatus.RUNNING);
            listener.onStarted();
            receiveLoop(openedSocket, listener);
            status.set(SourceStatus.STOPPED);
            listener.onCompleted();
        } catch (SocketException exception) {
            if (stopRequested) {
                status.set(SourceStatus.STOPPED);
                listener.onCompleted();
            } else {
                fail(listener, exception);
            }
        } catch (IOException | RuntimeException exception) {
            fail(listener, exception);
        } finally {
            socket = null;
            worker = null;
        }
    }

    private void receiveLoop(
            DatagramSocket openedSocket,
            SourceListener listener) throws IOException {
        byte[] buffer = new byte[MAX_UDP_DATAGRAM_BYTES];
        long sequence = 0;

        while (!stopRequested) {
            DatagramPacket packet = new DatagramPacket(
                    buffer,
                    buffer.length);
            openedSocket.receive(packet);
            Instant receivedAt = clock.instant();
            String endpoint = endpoint(packet.getAddress(), packet.getPort());
            String contents = new String(
                    packet.getData(),
                    packet.getOffset(),
                    packet.getLength(),
                    StandardCharsets.UTF_8);

            for (String line : contents.split("\\R")) {
                if (!line.isBlank()) {
                    listener.onRecord(new ReceivedNmea(
                            receivedAt,
                            sequence,
                            line,
                            SourceReference.live(endpoint, sequence)));
                    sequence++;
                }
            }
        }
    }

    private void fail(SourceListener listener, Throwable error) {
        status.set(SourceStatus.FAILED);
        listener.onFailure(error);
    }

    private static String endpoint(InetAddress address, int port) {
        return "udp://" + address.getHostAddress() + ":" + port;
    }
}
