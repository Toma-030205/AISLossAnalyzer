package ais.export;

import ais.app.AggregateResult;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ExportService implements AutoCloseable {

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ais-export");
                thread.setDaemon(true);
                return thread;
            });

    public CompletableFuture<Path> csv(Path target, AggregateResult result) {
        return submit(target, () -> new CsvExporter().write(target, result));
    }

    public CompletableFuture<Path> chartPng(
            Path target, AggregateResult result) {
        return submit(target, () -> new ChartPngExporter().write(
                target, result, 1400, 800));
    }

    public CompletableFuture<Path> mapPng(
            Path target, BufferedImage image) {
        return submit(target, () -> new MapPngExporter().write(target, image));
    }

    private CompletableFuture<Path> submit(Path target, IoTask task) {
        Objects.requireNonNull(target, "target");
        return CompletableFuture.supplyAsync(() -> {
            try {
                task.run();
                return target.toAbsolutePath().normalize();
            } catch (Exception failure) {
                throw new IllegalStateException(failure.getMessage(), failure);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    private interface IoTask {
        void run() throws Exception;
    }
}
