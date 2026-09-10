package ais.storage;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AggregateWriteQueue implements AutoCloseable {

    private final ExecutorService executor;

    public AggregateWriteQueue() {
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                    "ais-sqlite-aggregate-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public <T> CompletableFuture<T> submit(WriteTask<T> task) {
        Objects.requireNonNull(task, "task");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.execute();
            } catch (Exception failure) {
                throw new StorageException(
                        "queued SQLite write failed", failure);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
        boolean interrupted = false;
        try {
            while (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                // Keep waiting so accepted writes are flushed before exit.
            }
        } catch (InterruptedException failure) {
            interrupted = true;
            executor.shutdownNow();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface WriteTask<T> {
        T execute() throws Exception;
    }
}
