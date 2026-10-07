package ais.export;

import ais.app.AggregateAxis;
import ais.app.AggregateResult;
import ais.app.AggregateRow;
import ais.domain.VesselClass;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.AbstractAnnotation;
import org.jfree.chart.annotations.CategoryAnnotation;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.labels.CategoryItemLabelGenerator;
import org.jfree.chart.labels.CategoryToolTipGenerator;
import org.jfree.chart.labels.ItemLabelAnchor;
import org.jfree.chart.labels.ItemLabelPosition;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.LineAndShapeRenderer;
import org.jfree.chart.text.TextUtils;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.chart.util.ShapeUtils;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.category.DefaultCategoryDataset;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Paint;
import java.awt.Polygon;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

public final class ChartRenderer {

    private static final Font TITLE_FONT = japaneseFont(Font.BOLD, 18);
    private static final Font SUBTITLE_FONT = japaneseFont(Font.PLAIN, 11);
    private static final Font AXIS_FONT = japaneseFont(Font.BOLD, 13);
    private static final Font TICK_FONT = japaneseFont(Font.PLAIN, 11);
    private static final Font DETAIL_FONT = japaneseFont(Font.PLAIN, 9);
    private static final Color CLASS_A_COLOR = new Color(225, 70, 65);
    private static final Color CLASS_B_COLOR = new Color(65, 80, 235);
    private static final Color INSUFFICIENT_COLOR = new Color(100, 105, 110);
    private static final Color THIRTY_KILOMETER_COLOR =
            new Color(150, 45, 40, 210);
    private static final int POINT_LABEL_LIMIT = 28;

    public JFreeChart create(AggregateResult result) {
        if (result.request().axis() == AggregateAxis.DISTANCE_BY_HOUR) {
            return new DistanceHourHeatmapRenderer().create(result);
        }
        if (usesDailyDistanceSummary(result)) {
            return new DailyDistanceSummaryChartRenderer().create(result);
        }
        DefaultCategoryDataset values = new DefaultCategoryDataset();
        DefaultCategoryDataset insufficient = new DefaultCategoryDataset();
        Map<ItemKey, AggregateRow> rowLookup = new HashMap<>();
        Set<String> series = new LinkedHashSet<>();
        for (AggregateRow row : result.rows()) {
            String label = seriesLabel(result, row);
            series.add(label);
            rowLookup.put(new ItemKey(label, row.categoryLabel()), row);
        }
        List<String> categories = categories(result);
        for (String seriesLabel : series) {
            for (String category : categories) {
                values.addValue(null, seriesLabel, category);
            }
        }
        for (AggregateRow row : result.rows()) {
            String label = seriesLabel(result, row);
            Number rate = row.displayedRate(result.request());
            if (row.evaluation().hasSufficientData()) {
                values.addValue(rate, label, row.categoryLabel());
            } else if (rate != null) {
                insufficient.addValue(rate, label, row.categoryLabel());
            }
        }

        String axis = result.request().axis() == AggregateAxis.DISTANCE_BAND
                ? "距離帯" : "時間帯";
        JFreeChart chart = ChartFactory.createLineChart(
                result.request().metric().toString(), axis, "率（%）",
                values, PlotOrientation.VERTICAL, true, true, false);
        configureFonts(chart);

        TextTitle metadata = new TextTitle(
                new ExportMetadataFormatter().summary(result));
        metadata.setFont(SUBTITLE_FONT);
        chart.addSubtitle(metadata);
        if (!result.rows().isEmpty()) {
            TextTitle sampleSummary = new TextTitle(baseSummary(result));
            sampleSummary.setFont(SUBTITLE_FONT);
            chart.addSubtitle(sampleSummary);
        }

        CategoryPlot plot = chart.getCategoryPlot();
        plot.getRangeAxis().setRange(0.0, 100.0);
        plot.setBackgroundPaint(new Color(250, 251, 252));
        plot.setRangeGridlinePaint(new Color(190, 195, 200));

        boolean showPointCounts = result.rows().size() <= POINT_LABEL_LIMIT;
        LineAndShapeRenderer renderer = (LineAndShapeRenderer)
                plot.getRenderer();
        configureValueRenderer(renderer, values, rowLookup, showPointCounts);

        if (insufficient.getRowCount() > 0) {
            LineAndShapeRenderer insufficientRenderer =
                    new LineAndShapeRenderer(false, true);
            insufficientRenderer.setDefaultSeriesVisibleInLegend(false);
            configureItemInformation(insufficientRenderer, rowLookup,
                    showPointCounts);
            configureInsufficientSeries(
                    insufficientRenderer, insufficient);
            plot.setDataset(1, insufficient);
            plot.setRenderer(1, insufficientRenderer);
        }

        if (result.request().axis() == AggregateAxis.DISTANCE_BAND) {
            plot.addAnnotation(new ThirtyKilometerAnnotation());
        }
        return chart;
    }

    private static boolean usesDailyDistanceSummary(
            AggregateResult result) {
        return result.request().axis() == AggregateAxis.DISTANCE_BAND
                && result.request().dimension()
                == ais.aggregate.RollupDimension.DAY
                && result.request().startDate().isBefore(
                result.request().endDate());
    }

    private static void configureFonts(JFreeChart chart) {
        chart.getTitle().setFont(TITLE_FONT);
        if (chart.getLegend() != null) {
            chart.getLegend().setItemFont(TICK_FONT);
        }
        CategoryPlot plot = chart.getCategoryPlot();
        plot.getDomainAxis().setLabelFont(AXIS_FONT);
        plot.getDomainAxis().setTickLabelFont(TICK_FONT);
        plot.getRangeAxis().setLabelFont(AXIS_FONT);
        plot.getRangeAxis().setTickLabelFont(TICK_FONT);
    }

    private static void configureValueRenderer(
            LineAndShapeRenderer renderer,
            CategoryDataset dataset,
            Map<ItemKey, AggregateRow> rowLookup,
            boolean showPointCounts) {
        renderer.setDefaultShapesVisible(true);
        renderer.setDefaultStroke(new BasicStroke(2.0f));
        configureItemInformation(renderer, rowLookup, showPointCounts);
        for (int index = 0; index < dataset.getRowCount(); index++) {
            String label = dataset.getRowKey(index).toString();
            if (label.contains("Class B")) {
                renderer.setSeriesPaint(index, CLASS_B_COLOR);
                renderer.setSeriesStroke(index, new BasicStroke(
                        2.0f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND, 10.0f,
                        new float[]{7.0f, 5.0f}, 0.0f));
                renderer.setSeriesShape(index,
                        new Polygon(new int[]{0, 5, -5},
                                new int[]{-6, 4, 4}, 3));
            } else {
                renderer.setSeriesPaint(index, CLASS_A_COLOR);
                renderer.setSeriesShape(index,
                        new Ellipse2D.Double(-4, -4, 8, 8));
            }
            renderer.setSeriesPositiveItemLabelPosition(index,
                    labelPosition(label));
        }
    }

    private static void configureInsufficientSeries(
            LineAndShapeRenderer renderer, CategoryDataset dataset) {
        for (int index = 0; index < dataset.getRowCount(); index++) {
            renderer.setSeriesPaint(index, INSUFFICIENT_COLOR);
            renderer.setSeriesShape(index,
                    ShapeUtils.createDiagonalCross(5.0f, 1.5f));
            renderer.setSeriesPositiveItemLabelPosition(index,
                    labelPosition(dataset.getRowKey(index).toString()));
        }
    }

    private static void configureItemInformation(
            LineAndShapeRenderer renderer,
            Map<ItemKey, AggregateRow> rowLookup,
            boolean showPointCounts) {
        renderer.setDefaultToolTipGenerator(
                new AggregateToolTipGenerator(rowLookup));
        renderer.setDefaultItemLabelGenerator(
                new ExpectedCountLabelGenerator(rowLookup));
        renderer.setDefaultItemLabelsVisible(showPointCounts);
        renderer.setDefaultItemLabelFont(DETAIL_FONT);
        renderer.setDefaultItemLabelPaint(new Color(55, 60, 65));
    }

    private static ItemLabelPosition labelPosition(String label) {
        return label.contains("Class B")
                ? new ItemLabelPosition(ItemLabelAnchor.OUTSIDE1,
                        TextAnchor.BOTTOM_LEFT)
                : new ItemLabelPosition(ItemLabelAnchor.OUTSIDE11,
                        TextAnchor.BOTTOM_RIGHT);
    }

    private static String baseSummary(AggregateResult result) {
        long minimumExpected = result.rows().stream()
                .mapToLong(row -> row.evaluation().counts().expectedCount())
                .min().orElse(0);
        long maximumExpected = result.rows().stream()
                .mapToLong(row -> row.evaluation().counts().expectedCount())
                .max().orElse(0);
        int minimumVessels = result.rows().stream()
                .mapToInt(row -> row.evaluation().distinctVesselCount())
                .min().orElse(0);
        int maximumVessels = result.rows().stream()
                .mapToInt(row -> row.evaluation().distinctVesselCount())
                .max().orElse(0);
        int minimumDays = result.rows().stream()
                .mapToInt(AggregateRow::observationDayCount)
                .min().orElse(0);
        int maximumDays = result.rows().stream()
                .mapToInt(AggregateRow::observationDayCount)
                .max().orElse(0);
        String pointLabels = result.rows().size() <= POINT_LABEL_LIMIT
                ? " / 点ラベル n=期待送信数" : "";
        return "各点の母数範囲: 期待送信数 "
                + range(minimumExpected, maximumExpected)
                + " / 船舶数 " + range(minimumVessels, maximumVessels)
                + " / 観測日数 " + range(minimumDays, maximumDays)
                + pointLabels
                + "（灰色×はデータ不足の参考値・線へ接続しません）";
    }

    private static String range(long minimum, long maximum) {
        return minimum == maximum
                ? Long.toString(minimum)
                : minimum + "～" + maximum;
    }

    private static String seriesLabel(AggregateResult result,
                                      AggregateRow row) {
        String classLabel = row.vesselClass() == VesselClass.CLASS_A
                ? "Class A" : "Class B";
        if (result.request().axis() == AggregateAxis.HOUR_OF_DAY
                || row.seriesLabel().equals(classLabel)) {
            return classLabel;
        }
        return row.seriesLabel() + " / " + classLabel;
    }

    private static List<String> categories(AggregateResult result) {
        if (result.request().axis() == AggregateAxis.HOUR_OF_DAY) {
            return IntStream.range(0, 24)
                    .mapToObj(hour -> String.format("%02d時", hour))
                    .toList();
        }
        int step = result.analysisProfile().distanceBinKilometers();
        int maximum = result.analysisProfile().maximumDistanceKilometers();
        return IntStream.iterate(0, lower -> lower < maximum,
                        lower -> lower + step)
                .mapToObj(lower -> lower + "-"
                        + Math.min(maximum, lower + step) + " km")
                .toList();
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

    private record ItemKey(String series, String category) {
    }

    private static final class ExpectedCountLabelGenerator
            implements CategoryItemLabelGenerator {

        private final Map<ItemKey, AggregateRow> rows;

        private ExpectedCountLabelGenerator(
                Map<ItemKey, AggregateRow> rows) {
            this.rows = Map.copyOf(rows);
        }

        @Override
        public String generateRowLabel(CategoryDataset dataset, int row) {
            return dataset.getRowKey(row).toString();
        }

        @Override
        public String generateColumnLabel(CategoryDataset dataset,
                                          int column) {
            return dataset.getColumnKey(column).toString();
        }

        @Override
        public String generateLabel(CategoryDataset dataset,
                                    int row, int column) {
            AggregateRow value = find(rows, dataset, row, column);
            return value == null ? null : String.format("n=%,d",
                    value.evaluation().counts().expectedCount());
        }
    }

    private static final class AggregateToolTipGenerator
            implements CategoryToolTipGenerator {

        private final Map<ItemKey, AggregateRow> rows;

        private AggregateToolTipGenerator(Map<ItemKey, AggregateRow> rows) {
            this.rows = Map.copyOf(rows);
        }

        @Override
        public String generateToolTip(CategoryDataset dataset,
                                     int row, int column) {
            AggregateRow value = find(rows, dataset, row, column);
            if (value == null) {
                return null;
            }
            Number plotted = dataset.getValue(row, column);
            Double rate = plotted == null ? null : plotted.doubleValue();
            return "<html>" + dataset.getRowKey(row) + " / "
                    + dataset.getColumnKey(column) + "<br>率: "
                    + (rate == null ? "—" : String.format("%.1f%%", rate))
                    + "<br>期待送信数: "
                    + String.format("%,d",
                    value.evaluation().counts().expectedCount())
                    + " / 船舶数: "
                    + value.evaluation().distinctVesselCount()
                    + " / 観測日数: " + value.observationDayCount()
                    + (value.evaluation().hasSufficientData()
                    ? "" : "<br>データ不足（参考値）") + "</html>";
        }
    }

    private static AggregateRow find(
            Map<ItemKey, AggregateRow> rows,
            CategoryDataset dataset,
            int row,
            int column) {
        return rows.get(new ItemKey(
                dataset.getRowKey(row).toString(),
                dataset.getColumnKey(column).toString()));
    }

    private static final class ThirtyKilometerAnnotation
            extends AbstractAnnotation implements CategoryAnnotation {

        private static final String LEFT_CATEGORY = "25-30 km";
        private static final String RIGHT_CATEGORY = "30-35 km";

        @Override
        public void draw(Graphics2D graphics, CategoryPlot plot,
                         Rectangle2D dataArea, CategoryAxis domainAxis,
                         ValueAxis rangeAxis) {
            List<?> categories = plot.getCategoriesForAxis(domainAxis);
            int left = categories.indexOf(LEFT_CATEGORY);
            int right = categories.indexOf(RIGHT_CATEGORY);
            if (left < 0 || right < 0) {
                return;
            }
            double leftMiddle = domainAxis.getCategoryMiddle(
                    left, categories.size(), dataArea,
                    plot.getDomainAxisEdge());
            double rightMiddle = domainAxis.getCategoryMiddle(
                    right, categories.size(), dataArea,
                    plot.getDomainAxisEdge());
            double boundary = (leftMiddle + rightMiddle) / 2.0;

            Paint oldPaint = graphics.getPaint();
            Stroke oldStroke = graphics.getStroke();
            Font oldFont = graphics.getFont();
            graphics.setPaint(THIRTY_KILOMETER_COLOR);
            graphics.setStroke(new BasicStroke(
                    1.5f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL, 0.0f,
                    new float[]{6.0f, 4.0f}, 0.0f));
            graphics.draw(new Line2D.Double(
                    boundary, dataArea.getMinY(),
                    boundary, dataArea.getMaxY()));
            graphics.setFont(DETAIL_FONT.deriveFont(Font.BOLD, 10.0f));
            TextUtils.drawAlignedString("30 km基準", graphics,
                    (float) boundary - 4.0f,
                    (float) dataArea.getMinY() + 13.0f,
                    TextAnchor.TOP_RIGHT);
            graphics.setPaint(oldPaint);
            graphics.setStroke(oldStroke);
            graphics.setFont(oldFont);
        }
    }
}
