package ais.input.live;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public record UdpSourceConfig(
        InetAddress bindAddress,
        int port,
        int socketReceiveBufferBytes) {

    public static final int DEFAULT_PORT = 17_020;
    public static final int DEFAULT_RECEIVE_BUFFER_BYTES = 1_048_576;

    public UdpSourceConfig {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException(
                    "UDP port must be between 0 and 65535: " + port);
        }
        if (socketReceiveBufferBytes <= 0) {
            throw new IllegalArgumentException(
                    "receive buffer size must be greater than zero");
        }
    }

    public static UdpSourceConfig defaults() {
        return new UdpSourceConfig(
                null,
                DEFAULT_PORT,
                DEFAULT_RECEIVE_BUFFER_BYTES);
    }

    InetSocketAddress socketAddress() {
        return bindAddress == null
                ? new InetSocketAddress(port)
                : new InetSocketAddress(bindAddress, port);
    }
}
