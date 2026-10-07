package ais.export;

import ais.domain.VesselClass;
import ais.simulation.validation.StatisticalSummary;
import ais.simulation.validation.ValidationCellResult;
import ais.simulation.validation.ValidationMetric;
import ais.simulation.validation.ValidationMetricComparison;
import ais.simulation.validation.ValidationResult;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYErrorRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.xy.YIntervalSeries;
import org.jfree.data.xy.YIntervalSeriesCollection;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Objects;

public final class ValidationChartRenderer {

    private static final Color A_COLOR = new Color(220, 65, 55);
    private static final Color B_COLOR = new Color(45, 80, 225);
    private static final Color BASELINE_COLOR = new Color(105, 105, 105);

    public JFreeChart create(
            ValidationResult result,
            ValidationMetric metric) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(metric, "metric");
        YIntervalSeriesCollection dataset = dataset(result.cells(), metric);
        NumberAxis xAxis = new NumberAxis("受信局からの距離 (km)");
        xAxis.setRange(0.0, 70.0);
        xAxis.setTickUnit(new org.jfree.chart.axis.NumberTickUnit(5.0));
        NumberAxis yAxis = new NumberAxis(metric + " (%)");
        yAxis.setRange(0.0, 100.0);
        XYErrorRenderer renderer = renderer();
        XYPlot plot = new XYPlot(dataset, xAxis, yAxis, renderer);
        plot.setBackgroundPaint(new Color(250, 251, 252));
        plot.setRangeGridlinePaint(new Color(210, 215, 220));
        plot.setDomainGridlinePaint(new Color(225, 228, 232));
        ValueMarker boundary = new ValueMarker(
                30.0, new Color(175, 60, 45),
                new BasicStroke(1.2f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_BEVEL, 0.0f,
                        new float[]{6.0f, 5.0f}, 0.0f));
        boundary.setLabel("30 km基準");
        plot.addDomainMarker(boundary);

        JFreeChart chart = new JFreeChart(
                "通信モデル妥当性確認：" + metric,
                new Font(Font.SANS_SERIF, Font.BOLD, 18), plot, true);
        chart.addSubtitle(new TextTitle(String.format(
                "モデル %s rev.%d / 学習 %s～%s / 検証 %s～%s / %d seeds%s",
                result.model().modelCode().label(),
                result.model().revision(),
                result.model().trainingStartDate(),
                result.model().trainingEndDate(),
                result.request().startDate(), result.request().endDate(),
                result.request().iterationCount(),
                result.request().excludedDates().isEmpty() ? ""
                        : " / 除外 " + result.request().excludedDates())));
        return chart;
    }

    private static YIntervalSeriesCollection dataset(
            List<ValidationCellResult> cells,
            ValidationMetric metric) {
        YIntervalSeriesCollection dataset = new YIntervalSeriesCollection();
        for (VesselClass vesselClass : List.of(
                VesselClass.CLASS_A, VesselClass.CLASS_B)) {
            YIntervalSeries observed = new YIntervalSeries(
                    "実測 / " + label(vesselClass));
            YIntervalSeries simulation = new YIntervalSeries(
                    "CM-E1平均・95%範囲 / " + label(vesselClass));
            YIntervalSeries baseline = new YIntervalSeries(
                    "距離なし基準 / " + label(vesselClass));
            for (ValidationCellResult cell : cells) {
                if (cell.key().vesselClass() != vesselClass) {
                    continue;
                }
                double x = (cell.key().distanceBand().lowerKilometers()
                        + cell.key().distanceBand().upperKilometers()) / 2.0;
                ValidationMetricComparison comparison =
                        cell.comparison(metric);
                if (comparison.observedPercent() != null) {
                    observed.add(x, comparison.observedPercent(),
                            comparison.observedPercent(),
                            comparison.observedPercent());
                }
                StatisticalSummary simulated = comparison.simulation();
                if (simulated != null) {
                    simulation.add(x, simulated.mean(),
                            simulated.lower95(), simulated.upper95());
                }
                StatisticalSummary base = comparison.baseline();
                if (base != null) {
                    baseline.add(x, base.mean(),
                            base.lower95(), base.upper95());
                }
            }
            dataset.addSeries(observed);
            dataset.addSeries(simulation);
            dataset.addSeries(baseline);
        }
        return dataset;
    }

    private static XYErrorRenderer renderer() {
        XYErrorRenderer renderer = new XYErrorRenderer();
        renderer.setDrawXError(false);
        renderer.setDrawYError(true);
        renderer.setCapLength(5.0);
        renderer.setErrorPaint(new Color(95, 95, 95, 150));
        for (int series = 0; series < 6; series++) {
            boolean classA = series < 3;
            int kind = series % 3;
            Color classColor = classA ? A_COLOR : B_COLOR;
            renderer.setSeriesPaint(series,
                    kind == 2 ? BASELINE_COLOR : classColor);
            renderer.setSeriesLinesVisible(series, kind != 0);
            renderer.setSeriesShapesVisible(series, true);
            renderer.setSeriesStroke(series, kind == 2
                    ? new BasicStroke(1.4f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 0.0f,
                    new float[]{6.0f, 5.0f}, 0.0f)
                    : new BasicStroke(kind == 0 ? 0.0f : 2.0f));
            renderer.setSeriesShape(series, classA
                    ? new Ellipse2D.Double(-4, -4, 8, 8)
                    : triangle());
            if (kind == 0) {
                renderer.setSeriesShapesFilled(series, false);
            }
        }
        return renderer;
    }

    private static Path2D triangle() {
        Path2D shape = new Path2D.Double();
        shape.moveTo(0.0, -5.0);
        shape.lineTo(5.0, 4.0);
        shape.lineTo(-5.0, 4.0);
        shape.closePath();
        return shape;
    }

    private static String label(VesselClass vesselClass) {
        return vesselClass == VesselClass.CLASS_A ? "Class A" : "Class B";
    }
}
