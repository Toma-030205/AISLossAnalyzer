package ais.export;

import ais.aggregate.MetricCounts;
import ais.app.AggregateResult;
import ais.app.AggregateRow;
import ais.domain.VesselClass;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYLineAnnotation;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.SymbolAxis;
import org.jfree.chart.labels.XYToolTipGenerator;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.DeviationRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.YIntervalSeries;
import org.jfree.data.xy.YIntervalSeriesCollection;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Polygon;
import java.awt.geom.Ellipse2D;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Presentation view for a multi-day distance chart.  The line is calculated
 * from pooled numerators and denominators; the band shows the daily IQR.
 */
final class DailyDistanceSummaryChartRenderer {

    private static final Font TITLE_FONT = japaneseFont(Font.BOLD, 18);
    private static final Font SUBTITLE_FONT = japaneseFont(Font.PLAIN, 11);
    private static final Font AXIS_FONT = japaneseFont(Font.BOLD, 13);
    private static final Font TICK_FONT = japaneseFont(Font.PLAIN, 11);
    private static final Font DETAIL_FONT = japaneseFont(Font.PLAIN, 9);
    private static final Color CLASS_A_COLOR = new Color(220, 65, 55);
    private static final Color CLASS_B_COLOR = new Color(55, 80, 220);
    private static final Color THIRTY_KILOMETER_COLOR =
            new Color(145, 45, 40, 210);

    JFreeChart create(AggregateResult result) {
        List<String> categories = categories(result);
        YIntervalSeriesCollection dataset = new YIntervalSeriesCollection();
        Map<ItemKey, SummaryPoint> points = new HashMap<>();

        result.request().vesselClasses().stream()
                .sorted()
                .forEach(vesselClass -> addSeries(
                        result, vesselClass, categories, dataset, points));

        SymbolAxis distanceAxis = new SymbolAxis(
                "距離帯", categories.toArray(String[]::new));
        distanceAxis.setRange(-0.5, categories.size() - 0.5);
        distanceAxis.setGridBandsVisible(false);
        distanceAxis.setLabelFont(AXIS_FONT);
        distanceAxis.setTickLabelFont(TICK_FONT);

        NumberAxis rateAxis = new NumberAxis("率（%）");
        rateAxis.setRange(0.0, 100.0);
        rateAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());
        rateAxis.setLabelFont(AXIS_FONT);
        rateAxis.setTickLabelFont(TICK_FONT);

        DeviationRenderer renderer = new DeviationRenderer(true, true);
        renderer.setAlpha(0.18f);
        renderer.setDefaultStroke(new BasicStroke(2.4f));
        renderer.setDefaultToolTipGenerator(
                new SummaryToolTipGenerator(points, categories));
        configureSeries(renderer, dataset);

        XYPlot plot = new XYPlot(
                dataset, distanceAxis, rateAxis, renderer);
        plot.setBackgroundPaint(new Color(250, 251, 252));
        plot.setRangeGridlinePaint(new Color(190, 195, 200));
        plot.setDomainGridlinePaint(new Color(225, 228, 232));
        plot.setDomainGridlinesVisible(true);
        addThirtyKilometerReference(plot, categories);

        JFreeChart chart = new JFreeChart(
                result.request().metric().toString(), TITLE_FONT,
                plot, true);
        if (chart.getLegend() != null) {
            chart.getLegend().setItemFont(TICK_FONT);
        }
        TextTitle metadata = new TextTitle(
                new ExportMetadataFormatter().summary(result));
        metadata.setFont(SUBTITLE_FONT);
        chart.addSubtitle(metadata);
        TextTitle explanation = new TextTitle(summary(result, points));
        explanation.setFont(SUBTITLE_FONT);
        chart.addSubtitle(explanation);
        return chart;
    }

    private static void addSeries(
            AggregateResult result,
            VesselClass vesselClass,
            List<String> categories,
            YIntervalSeriesCollection dataset,
            Map<ItemKey, SummaryPoint> points) {
        String label = classLabel(vesselClass);
        YIntervalSeries series = new YIntervalSeries(label);
        for (int index = 0; index < categories.size(); index++) {
            String category = categories.get(index);
            List<AggregateRow> rows = result.rows().stream()
                    .filter(row -> row.vesselClass() == vesselClass)
                    .filter(row -> row.categoryLabel().equals(category))
                    .toList();
            List<Double> dailyRates = rows.stream()
                    .filter(row -> row.evaluation().hasSufficientData())
                    .map(row -> row.displayedRate(result.request()))
                    .filter(java.util.Objects::nonNull)
                    .sorted()
                    .toList();
            if (dailyRates.isEmpty()) {
                continue;
            }
            MetricCounts totals = rows.stream()
                    .map(row -> row.evaluation().counts())
                    .reduce(MetricCounts.ZERO, MetricCounts::plus);
            Double pooledRate = displayedRate(result, totals);
            if (pooledRate == null) {
                continue;
            }
            double lower = percentile(dailyRates, 0.25);
            double upper = percentile(dailyRates, 0.75);
            series.add(index, pooledRate, lower, upper);
            points.put(new ItemKey(label, index), new SummaryPoint(
                    pooledRate, lower, upper, dailyRates.size(),
                    rows.size(), totals.expectedCount()));
        }
        if (series.getItemCount() > 0) {
            dataset.addSeries(series);
        }
    }

    private static Double displayedRate(
            AggregateResult result,
            MetricCounts counts) {
        if (result.request().metric()
                == ais.ui.viewmodel.HeatmapMetric.ESTIMATED_LOSS) {
            return counts.expectedCount() == 0 ? null
                    : counts.missingCount() * 100.0
                    / counts.expectedCount();
        }
        return counts.observedSeconds() == 0.0 ? null
                : counts.staleSeconds() * 100.0
                / counts.observedSeconds();
    }

    private static double percentile(List<Double> sorted, double fraction) {
        if (sorted.size() == 1) {
            return sorted.getFirst();
        }
        double position = fraction * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double weight = position - lower;
        return sorted.get(lower) * (1.0 - weight)
                + sorted.get(upper) * weight;
    }

    private static void configureSeries(
            DeviationRenderer renderer,
            YIntervalSeriesCollection dataset) {
        for (int index = 0; index < dataset.getSeriesCount(); index++) {
            String label = dataset.getSeriesKey(index).toString();
            boolean classB = label.contains("Class B");
            Color color = classB ? CLASS_B_COLOR : CLASS_A_COLOR;
            renderer.setSeriesPaint(index, color);
            renderer.setSeriesFillPaint(index, color);
            renderer.setSeriesStroke(index, classB
                    ? new BasicStroke(2.4f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 10.0f,
                    new float[]{7.0f, 5.0f}, 0.0f)
                    : new BasicStroke(2.4f));
            renderer.setSeriesShape(index, classB
                    ? new Polygon(new int[]{0, 5, -5},
                    new int[]{-6, 4, 4}, 3)
                    : new Ellipse2D.Double(-4, -4, 8, 8));
        }
    }

    private static void addThirtyKilometerReference(
            XYPlot plot, List<String> categories) {
        int left = categories.indexOf("25-30 km");
        int right = categories.indexOf("30-35 km");
        if (left < 0 || right < 0) {
            return;
        }
        double boundary = (left + right) / 2.0;
        BasicStroke stroke = new BasicStroke(
                1.5f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_BEVEL, 0.0f,
                new float[]{6.0f, 4.0f}, 0.0f);
        plot.addAnnotation(new XYLineAnnotation(
                boundary, 0.0, boundary, 100.0,
                stroke, THIRTY_KILOMETER_COLOR));
        XYTextAnnotation text = new XYTextAnnotation(
                "30 km基準", boundary - 0.08, 98.0);
        text.setFont(DETAIL_FONT.deriveFont(Font.BOLD, 10.0f));
        text.setPaint(THIRTY_KILOMETER_COLOR);
        text.setTextAnchor(TextAnchor.TOP_RIGHT);
        plot.addAnnotation(text);
    }

    private static String summary(
            AggregateResult result,
            Map<ItemKey, SummaryPoint> points) {
        int minimumDays = points.values().stream()
                .mapToInt(SummaryPoint::includedDayCount)
                .min().orElse(0);
        int maximumDays = points.values().stream()
                .mapToInt(SummaryPoint::includedDayCount)
                .max().orElse(0);
        String range = minimumDays == maximumDays
                ? Integer.toString(minimumDays)
                : minimumDays + "～" + maximumDays;
        return "線: 期間全体の分子・分母を合算した率 / 色帯: "
                + "十分判定の日別率の第1～第3四分位 "
                + "(各点 " + range + "日) / "
                + "日別有効標本がない点は省略 / 解析実行 "
                + result.analysisRunCount() + "日";
    }

    private static List<String> categories(AggregateResult result) {
        int step = result.analysisProfile().distanceBinKilometers();
        int maximum = result.analysisProfile().maximumDistanceKilometers();
        return IntStream.iterate(0, lower -> lower < maximum,
                        lower -> lower + step)
                .mapToObj(lower -> lower + "-"
                        + Math.min(maximum, lower + step) + " km")
                .toList();
    }

    private static String classLabel(VesselClass vesselClass) {
        return vesselClass == VesselClass.CLASS_A ? "Class A" : "Class B";
    }

    private static Font japaneseFont(int style, int size) {
        Set<String> available = Set.of(
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames(Locale.JAPANESE));
        for (String candidate : List.of(
                "Yu Gothic UI", "Yu Gothic", "Meiryo",
                "Noto Sans JP", "MS Gothic")) {
            if (available.contains(candidate)) {
                return new Font(candidate, style, size);
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    private record ItemKey(String series, int categoryIndex) {
    }

    private record SummaryPoint(
            double pooledRate,
            double lowerQuartile,
            double upperQuartile,
            int includedDayCount,
            int totalDayCount,
            long expectedCount) {
    }

    private static final class SummaryToolTipGenerator
            implements XYToolTipGenerator {

        private final Map<ItemKey, SummaryPoint> points;
        private final List<String> categories;

        private SummaryToolTipGenerator(
                Map<ItemKey, SummaryPoint> points,
                List<String> categories) {
            this.points = Map.copyOf(points);
            this.categories = List.copyOf(categories);
        }

        @Override
        public String generateToolTip(
                XYDataset dataset, int series, int item) {
            int categoryIndex = (int) Math.round(
                    dataset.getXValue(series, item));
            String seriesLabel = dataset.getSeriesKey(series).toString();
            SummaryPoint point = points.get(
                    new ItemKey(seriesLabel, categoryIndex));
            if (point == null) {
                return null;
            }
            return "<html>" + seriesLabel + " / "
                    + categories.get(categoryIndex)
                    + "<br>期間合算: "
                    + String.format(Locale.ROOT, "%.1f%%", point.pooledRate())
                    + "<br>日別IQR: "
                    + String.format(Locale.ROOT, "%.1f～%.1f%%",
                    point.lowerQuartile(), point.upperQuartile())
                    + "<br>IQR対象日: " + point.includedDayCount()
                    + "/" + point.totalDayCount()
                    + "<br>期待送信数: "
                    + String.format(Locale.ROOT, "%,d",
                    point.expectedCount()) + "</html>";
        }
    }
}
