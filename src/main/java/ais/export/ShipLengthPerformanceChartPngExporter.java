package ais.export;

import ais.app.ShipLengthPerformanceResult;
import org.jfree.chart.ChartUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ShipLengthPerformanceChartPngExporter {

    public void write(Path target, ShipLengthPerformanceResult result,
                      int width, int height) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(result, "result");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "PNG dimensions must be positive");
        }
        Path absolute = target.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        ChartUtils.saveChartAsPNG(absolute.toFile(),
                new ShipLengthPerformanceHeatmapRenderer().create(result),
                width, height);
    }
}
