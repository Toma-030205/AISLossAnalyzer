package ais.export;

import ais.simulation.validation.ValidationMetric;
import ais.simulation.validation.ValidationResult;
import org.jfree.chart.ChartUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ValidationChartPngExporter {

    public void write(
            Path target,
            ValidationResult result,
            ValidationMetric metric,
            int width,
            int height) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(metric, "metric");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid PNG dimensions");
        }
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        ChartUtils.saveChartAsPNG(absolute.toFile(),
                new ValidationChartRenderer().create(result, metric),
                width, height);
    }
}
