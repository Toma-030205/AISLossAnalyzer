package ais.input.history;

import ais.input.InputDiagnostic;
import ais.input.InputDiagnosticCode;
import ais.input.SourceListener;
import ais.input.SourceReference;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.PriorityQueue;

public final class ReplayTimeline implements AutoCloseable {

    private final List<RecordCursor> cursors = new ArrayList<>();
    private final PriorityQueue<RecordCursor> queue = new PriorityQueue<>(
            Comparator.comparing(
                            (RecordCursor cursor) ->
                                    cursor.current().receivedAt())
                    .thenComparingInt(RecordCursor::fileOrder)
                    .thenComparingLong(cursor ->
                            cursor.current().source().recordNumber()));

    private RecordCursor pendingAdvance;

    public ReplayTimeline(
            HistoricalDaySelection selection,
            ZoneId sourceZone,
            SourceListener listener) throws IOException {
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(sourceZone, "sourceZone");
        Objects.requireNonNull(listener, "listener");

        HistoricalLineParser parser = new HistoricalLineParser(sourceZone);
        Instant diagnosticFallback = selection.date()
                .atStartOfDay(sourceZone)
                .toInstant();

        try {
            for (int index = 0; index < selection.files().size(); index++) {
                RecordCursor cursor = new RecordCursor(
                        selection.files().get(index),
                        index,
                        parser,
                        sourceZone,
                        selection.date(),
                        diagnosticFallback,
                        listener);
                cursors.add(cursor);
                if (cursor.advance()) {
                    queue.add(cursor);
                }
            }
        } catch (IOException | RuntimeException exception) {
            try {
                close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    public boolean hasNext() throws IOException {
        advancePendingCursor();
        return !queue.isEmpty();
    }

    public HistoricalRecord next() throws IOException {
        if (!hasNext()) {
            throw new NoSuchElementException(
                    "the historical replay timeline is exhausted");
        }

        RecordCursor cursor = queue.remove();
        HistoricalRecord result = cursor.current();
        pendingAdvance = cursor;
        return result;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (RecordCursor cursor : cursors) {
            try {
                cursor.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        cursors.clear();
        queue.clear();
        pendingAdvance = null;
        if (failure != null) {
            throw failure;
        }
    }

    private void advancePendingCursor() throws IOException {
        if (pendingAdvance == null) {
            return;
        }

        RecordCursor cursor = pendingAdvance;
        pendingAdvance = null;
        if (cursor.advance()) {
            queue.add(cursor);
        }
    }

    private static final class RecordCursor implements AutoCloseable {

        private final Path file;
        private final int fileOrder;
        private final HistoricalLineParser parser;
        private final ZoneId sourceZone;
        private final LocalDate selectedDate;
        private final Instant diagnosticFallback;
        private final SourceListener listener;
        private final BufferedReader reader;

        private long lineNumber;
        private HistoricalRecord current;

        private RecordCursor(
                Path file,
                int fileOrder,
                HistoricalLineParser parser,
                ZoneId sourceZone,
                LocalDate selectedDate,
                Instant diagnosticFallback,
                SourceListener listener) throws IOException {
            this.file = file;
            this.fileOrder = fileOrder;
            this.parser = parser;
            this.sourceZone = sourceZone;
            this.selectedDate = selectedDate;
            this.diagnosticFallback = diagnosticFallback;
            this.listener = listener;
            reader = HistoricalReaders.open(file);
        }

        private boolean advance() throws IOException {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                SourceReference source =
                        SourceReference.historical(file, lineNumber);

                try {
                    HistoricalRecord candidate =
                            parser.parse(line, file, lineNumber);
                    LocalDate recordDate = candidate.receivedAt()
                            .atZone(sourceZone)
                            .toLocalDate();

                    if (!recordDate.equals(selectedDate)) {
                        listener.onDiagnostic(new InputDiagnostic(
                                candidate.receivedAt(),
                                InputDiagnosticCode.OUTSIDE_SELECTED_DAY,
                                source,
                                "record date does not match the selected day"));
                        continue;
                    }

                    current = candidate;
                    return true;
                } catch (HistoricalLineException exception) {
                    listener.onDiagnostic(new InputDiagnostic(
                            diagnosticFallback,
                            InputDiagnosticCode.INVALID_LOG_LINE,
                            source,
                            exception.getMessage()));
                }
            }

            current = null;
            return false;
        }

        private HistoricalRecord current() {
            return current;
        }

        private int fileOrder() {
            return fileOrder;
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
