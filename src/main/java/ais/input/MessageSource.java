package ais.input;

import ais.domain.SourceMode;

public interface MessageSource extends AutoCloseable {

    SourceMode mode();

    SourceStatus status();

    void start(SourceListener listener);

    void stop();

    @Override
    default void close() {
        stop();
    }
}
