package ais.export;

import ais.app.AggregateResult;
import ais.app.DailyDataQualityResult;
import ais.app.ShipLengthAnalysisResult;
import ais.app.ShipLengthPerformanceResult;
import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.validation.ValidationMetric;
import ais.simulation.validation.ValidationResult;

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

    public CompletableFuture<Path> dataQualityCsv(
            Path target, DailyDataQualityResult result) {
        return submit(target,
                () -> new DailyDataQualityCsvExporter().write(
                        target, result));
    }

    public CompletableFuture<Path> shipLengthCsv(
            Path target, ShipLengthAnalysisResult result) {
        return submit(target,
                () -> new ShipLengthCsvExporter().write(target, result));
    }

    public CompletableFuture<Path> shipLengthChartPng(
            Path target, ShipLengthAnalysisResult result) {
        return submit(target,
                () -> new ShipLengthChartPngExporter().write(
                        target, result, 1400, 850));
    }

    public CompletableFuture<Path> shipLengthPerformanceCsv(
            Path target, ShipLengthPerformanceResult result) {
        return submit(target,
                () -> new ShipLengthPerformanceCsvExporter().write(
                        target, result));
    }

    public CompletableFuture<Path> shipLengthPerformanceChartPng(
            Path target, ShipLengthPerformanceResult result) {
        return submit(target,
                () -> new ShipLengthPerformanceChartPngExporter().write(
                        target, result, 1400, 850));
    }

    public CompletableFuture<Path> mapPng(
            Path target, BufferedImage image) {
        return submit(target, () -> new MapPngExporter().write(target, image));
    }

    public CompletableFuture<Path> communicationModelCsv(
            Path target, CommunicationModelDraft draft) {
        return submit(target, () -> new CommunicationModelCsvExporter()
                .write(target, draft));
    }

    public CompletableFuture<Path> validationCsv(
            Path target, ValidationResult result) {
        return submit(target, () -> new ValidationCsvExporter()
                .write(target, result));
    }

    public CompletableFuture<Path> validationChartPng(
            Path target,
            ValidationResult result,
            ValidationMetric metric) {
        return submit(target, () -> new ValidationChartPngExporter()
                .write(target, result, metric, 1500, 850));
    }

    public CompletableFuture<Path> communicationModelCsv(
            Path target, CommunicationModelSnapshot snapshot) {
        return submit(target, () -> new CommunicationModelCsvExporter()
                .write(target, snapshot));
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
