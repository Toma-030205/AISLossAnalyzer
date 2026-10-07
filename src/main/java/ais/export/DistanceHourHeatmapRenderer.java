package ais.export;

import ais.app.AggregateResult;
import ais.app.AggregateRow;
import ais.domain.VesselClass;
import ais.ui.HeatmapColorScale;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.SymbolAxis;
import org.jfree.chart.plot.CombinedRangeXYPlot;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.PaintScale;
import org.jfree.chart.renderer.xy.XYBlockRenderer;
import org.jfree.chart.title.PaintScaleLegend;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.data.xy.DefaultXYZDataset;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

public final class DistanceHourHeatmapRenderer {

    private static final Font TITLE_FONT = japaneseFont(Font.BOLD, 18);
    private static final Font SUBTITLE_FONT = japaneseFont(Font.PLAIN, 11);
    private static final Font AXIS_FONT = japaneseFont(Font.BOLD, 12);
    private static final Font TICK_FONT = japaneseFont(Font.PLAIN, 10);
    private static final Color INSUFFICIENT = new Color(150, 155, 160);
    private static final Color THIRTY_KILOMETER =
            new Color(150, 45, 40, 210);

    public JFreeChart create(AggregateResult result) {
        int distanceStep = result.analysisProfile().distanceBinKilometers();
        int maximumDistance =
                result.analysisProfile().maximumDistanceKilometers();
        String[] hours = IntStream.range(0, 24)
                .mapToObj(hour -> String.format("%02d時", hour))
                .toArray(String[]::new);
        String[] bands = IntStream.iterate(0,
                        lower -> lower < maximumDistance,
                        lower -> lower + distanceStep)
                .mapToObj(lower -> lower + "-"
                        + Math.min(maximumDistance, lower + distanceStep)
                        + " km")
                .toArray(String[]::new);

        SymbolAxis distanceAxis = new SymbolAxis("距離帯", bands);
        distanceAxis.setRange(-0.5, bands.length - 0.5);
        distanceAxis.setGridBandsVisible(false);
        distanceAxis.setLabelFont(AXIS_FONT);
        distanceAxis.setTickLabelFont(TICK_FONT);
        CombinedRangeXYPlot combined = new CombinedRangeXYPlot(distanceAxis);
        combined.setGap(12.0);

        HeatmapPaintScale paintScale = new HeatmapPaintScale();
        List<VesselClass> classes = result.request().vesselClasses().stream()
                .sorted().toList();
        for (VesselClass vesselClass : classes) {
            addClassPlot(combined, result, vesselClass, hours,
                    paintScale, distanceStep, maximumDistance);
        }

        JFreeChart chart = new JFreeChart(
                result.request().metric() + "（距離帯×時間帯）",
                TITLE_FONT, combined, false);
        TextTitle metadata = new TextTitle(
                new ExportMetadataFormatter().summary(result));
        metadata.setFont(SUBTITLE_FONT);
        chart.addSubtitle(metadata);
        TextTitle explanation = new TextTitle(
                "横軸=日本時間 / 縦軸=受信局からの距離 / "
                        + "灰色=データ不足または率算出不可 / 空白=データなし");
        explanation.setFont(SUBTITLE_FONT);
        chart.addSubtitle(explanation);
        if (!result.rows().isEmpty()) {
            TextTitle sampleSummary = new TextTitle(sampleSummary(result));
            sampleSummary.setFont(SUBTITLE_FONT);
            chart.addSubtitle(sampleSummary);
        }

        NumberAxis scaleAxis = new NumberAxis("率（%）");
        scaleAxis.setRange(0.0, 100.0);
        scaleAxis.setLabelFont(AXIS_FONT);
        scaleAxis.setTickLabelFont(TICK_FONT);
        PaintScaleLegend legend = new PaintScaleLegend(
                paintScale, scaleAxis);
        legend.setPosition(RectangleEdge.RIGHT);
        chart.addSubtitle(legend);
        return chart;
    }

    private static String sampleSummary(AggregateResult result) {
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
        return String.format(
                "各セルの母数範囲: 期待送信数 %,d～%,d / 船舶数 %,d～%,d / 観測日数 %,d～%,d",
                minimumExpected, maximumExpected,
                minimumVessels, maximumVessels,
                minimumDays, maximumDays);
    }

    private static void addClassPlot(
            CombinedRangeXYPlot combined,
            AggregateResult result,
            VesselClass vesselClass,
            String[] hours,
            PaintScale paintScale,
            int distanceStep,
            int maximumDistance) {
        List<AggregateRow> rows = result.rows().stream()
                .filter(row -> row.vesselClass() == vesselClass)
                .filter(row -> row.hourOfDay() != null)
                .toList();
        double[][] data = new double[3][rows.size()];
        Map<CellKey, AggregateRow> lookup = new HashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            AggregateRow row = rows.get(index);
            data[0][index] = row.hourOfDay();
            data[1][index] = row.categoryOrder();
            Double rate = row.displayedRate(result.request());
            data[2][index] = row.evaluation().hasSufficientData()
                    && rate != null ? rate : Double.NaN;
            lookup.put(new CellKey(
                    row.hourOfDay(), row.categoryOrder()), row);
        }
        DefaultXYZDataset dataset = new DefaultXYZDataset();
        dataset.addSeries(classLabel(vesselClass), data);

        SymbolAxis hourAxis = new SymbolAxis(
                classLabel(vesselClass) + " / 時間帯", hours);
        hourAxis.setRange(-0.5, 23.5);
        hourAxis.setGridBandsVisible(false);
        hourAxis.setLabelFont(AXIS_FONT);
        hourAxis.setTickLabelFont(TICK_FONT);

        XYBlockRenderer renderer = new XYBlockRenderer();
        renderer.setBlockWidth(1.0);
        renderer.setBlockHeight(1.0);
        renderer.setPaintScale(paintScale);
        renderer.setDefaultToolTipGenerator((source, series, item) -> {
            int hour = (int) Math.round(source.getXValue(series, item));
            int band = (int) Math.round(source.getYValue(series, item));
            AggregateRow row = lookup.get(new CellKey(hour, band));
            if (row == null) {
                return null;
            }
            Double rate = row.displayedRate(result.request());
            return "<html>" + classLabel(vesselClass) + " / "
                    + String.format("%02d時", hour) + " / "
                    + row.categoryLabel() + "<br>率: "
                    + (rate == null ? "—" : String.format("%.1f%%", rate))
                    + "<br>期待送信数: "
                    + String.format("%,d",
                    row.evaluation().counts().expectedCount())
                    + " / 船舶数: "
                    + row.evaluation().distinctVesselCount()
                    + " / 当該区分の観測日数: "
                    + row.observationDayCount()
                    + (row.evaluation().hasSufficientData()
                    ? "" : "<br>データ不足（参考値）")
                    + "</html>";
        });

        XYPlot plot = new XYPlot(dataset, hourAxis, null, renderer);
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(new Color(215, 218, 220));
        plot.setRangeGridlinePaint(new Color(215, 218, 220));
        if (30 < maximumDistance && 30 % distanceStep == 0) {
            ValueMarker marker = new ValueMarker(
                    30.0 / distanceStep - 0.5,
                    THIRTY_KILOMETER,
                    new BasicStroke(1.5f, BasicStroke.CAP_BUTT,
                            BasicStroke.JOIN_BEVEL, 0.0f,
                            new float[]{6.0f, 4.0f}, 0.0f));
            marker.setLabel("30 km基準");
            marker.setLabelFont(TICK_FONT.deriveFont(Font.BOLD));
            plot.addRangeMarker(marker);
        }
        combined.add(plot, 1);
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

    private record CellKey(int hour, int distanceBandIndex) {
    }

    private static final class HeatmapPaintScale implements PaintScale {

        private final HeatmapColorScale colors = new HeatmapColorScale();

        @Override
        public double getLowerBound() {
            return 0.0;
        }

        @Override
        public double getUpperBound() {
            return 100.0;
        }

        @Override
        public java.awt.Paint getPaint(double value) {
            if (!Double.isFinite(value)) {
                return INSUFFICIENT;
            }
            Color base = colors.colorFor(value);
            return new Color(base.getRed(), base.getGreen(), base.getBlue());
        }
    }
}
