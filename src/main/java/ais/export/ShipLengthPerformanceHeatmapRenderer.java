package ais.export;

import ais.app.ShipLengthBand;
import ais.app.ShipLengthPerformanceResult;
import ais.app.ShipLengthPerformanceRow;
import ais.domain.VesselClass;
import ais.ui.HeatmapColorScale;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.SymbolAxis;
import org.jfree.chart.plot.CombinedDomainXYPlot;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.IntStream;

public final class ShipLengthPerformanceHeatmapRenderer {

    private static final Font TITLE_FONT = japaneseFont(Font.BOLD, 18);
    private static final Font SUBTITLE_FONT = japaneseFont(Font.PLAIN, 11);
    private static final Font AXIS_FONT = japaneseFont(Font.BOLD, 12);
    private static final Font TICK_FONT = japaneseFont(Font.PLAIN, 10);
    private static final Color INSUFFICIENT = new Color(150, 155, 160);
    private static final Color THIRTY_KILOMETER =
            new Color(150, 45, 40, 210);

    public JFreeChart create(ShipLengthPerformanceResult result) {
        int step = result.analysisProfile().distanceBinKilometers();
        int maximum = result.analysisProfile().maximumDistanceKilometers();
        String[] distanceLabels = IntStream.iterate(0,
                        lower -> lower < maximum,
                        lower -> lower + step)
                .mapToObj(lower -> lower + "-"
                        + Math.min(maximum, lower + step) + " km")
                .toArray(String[]::new);
        String[] lengthLabels = ShipLengthBand.trendBands().stream()
                .map(ShipLengthBand::toString)
                .toArray(String[]::new);

        SymbolAxis distanceAxis = new SymbolAxis("距離帯", distanceLabels);
        distanceAxis.setRange(-0.5, distanceLabels.length - 0.5);
        distanceAxis.setGridBandsVisible(false);
        distanceAxis.setLabelFont(AXIS_FONT);
        distanceAxis.setTickLabelFont(TICK_FONT);
        CombinedDomainXYPlot combined = new CombinedDomainXYPlot(
                distanceAxis);
        combined.setGap(12.0);
        combined.setBackgroundPaint(Color.WHITE);

        PerformancePaintScale paintScale = new PerformancePaintScale();
        result.request().vesselClasses().stream().sorted()
                .forEach(vesselClass -> addClassPlot(
                        combined, result, vesselClass,
                        lengthLabels, paintScale, step, maximum));

        JFreeChart chart = new JFreeChart(
                "船体長×距離帯×Class別 " + result.request().metric(),
                TITLE_FONT, combined, false);
        chart.setBackgroundPaint(Color.WHITE);
        addSubtitle(chart, String.format(
                "期間（日本時間）: %s～%s%s / 受信局: %s / 解析条件: %s",
                result.request().startDate(), result.request().endDate(),
                ExportMetadataFormatter.excludedDates(
                        result.request().excludedDates()),
                result.receiverProfile().name(),
                result.analysisProfile().rulesVersion()), Color.DARK_GRAY);
        addSubtitle(chart, String.format(Locale.ROOT,
                "新形式解析run %,d / 再解析必要run %,d / "
                        + "Class別MMSI %,d / 船体長既知 %,d（%.1f%%）",
                result.readyAnalysisRunCount(),
                result.reanalysisRequiredRunCount(),
                result.totalDistinctVesselCount(),
                result.knownLengthDistinctVesselCount(),
                result.knownLengthCoveragePercent()), Color.DARK_GRAY);
        addSubtitle(chart,
                "色=選択した率 / 破線=30km基準 / "
                        + "灰色=標本不足 / 空白=データなし / "
                        + "不明・範囲外の船体長は表とCSVのみ",
                Color.DARK_GRAY);
        addSubtitle(chart,
                "船体長はアンテナ高の代理変数です。航路・船種・運航範囲"
                        + "などの影響を含むため、この図だけで因果関係は"
                        + "確定できません。",
                new Color(120, 45, 35));

        NumberAxis scaleAxis = new NumberAxis(
                result.request().metric() + "（%）");
        scaleAxis.setRange(0.0, 100.0);
        scaleAxis.setLabelFont(AXIS_FONT);
        scaleAxis.setTickLabelFont(TICK_FONT);
        PaintScaleLegend legend = new PaintScaleLegend(
                paintScale, scaleAxis);
        legend.setPosition(RectangleEdge.RIGHT);
        chart.addSubtitle(legend);
        return chart;
    }

    private static void addClassPlot(
            CombinedDomainXYPlot combined,
            ShipLengthPerformanceResult result,
            VesselClass vesselClass,
            String[] lengthLabels,
            PaintScale paintScale,
            int distanceStep,
            int maximumDistance) {
        List<ShipLengthPerformanceRow> rows = result.rows().stream()
                .filter(row -> row.vesselClass() == vesselClass)
                .filter(row -> row.shipLengthBand().usableForTrend())
                .toList();
        double[][] data = new double[3][rows.size()];
        for (int index = 0; index < rows.size(); index++) {
            ShipLengthPerformanceRow row = rows.get(index);
            data[0][index] = row.distanceBand().index();
            data[1][index] = row.shipLengthBand().order();
            Double rate = row.selectedRatePercent(result.request().metric());
            data[2][index] = row.evaluation().hasSufficientData()
                    && rate != null ? rate : Double.NaN;
        }
        DefaultXYZDataset dataset = new DefaultXYZDataset();
        dataset.addSeries(classLabel(vesselClass), data);

        SymbolAxis lengthAxis = new SymbolAxis(
                classLabel(vesselClass) + " / 船体長区分", lengthLabels);
        lengthAxis.setRange(-0.5, lengthLabels.length - 0.5);
        lengthAxis.setGridBandsVisible(false);
        lengthAxis.setLabelFont(AXIS_FONT);
        lengthAxis.setTickLabelFont(TICK_FONT);

        XYBlockRenderer renderer = new XYBlockRenderer();
        renderer.setBlockWidth(1.0);
        renderer.setBlockHeight(1.0);
        renderer.setPaintScale(paintScale);
        renderer.setDefaultToolTipGenerator((source, series, item) -> {
            ShipLengthPerformanceRow row = rows.get(item);
            Double selected = row.selectedRatePercent(
                    result.request().metric());
            var counts = row.evaluation().counts();
            return "<html>" + classLabel(vesselClass) + " / "
                    + row.shipLengthBand() + " / "
                    + row.distanceBand().label()
                    + "<br>" + result.request().metric() + ": "
                    + rate(selected)
                    + "<br>推定欠落率: "
                    + rate(row.evaluation().lossRatePercent())
                    + "（" + counts.missingCount() + "/"
                    + counts.expectedCount() + "）"
                    + "<br>情報鮮度違反率: "
                    + rate(row.evaluation()
                    .freshnessViolationRatePercent())
                    + "（" + decimal(counts.staleSeconds()) + "/"
                    + decimal(counts.observedSeconds()) + "秒）"
                    + "<br>" + row.evaluation().distinctVesselCount()
                    + " MMSI / " + row.observationDayCount() + "日"
                    + (row.evaluation().hasSufficientData()
                    ? "" : "<br>データ不足（参考値）")
                    + "</html>";
        });

        XYPlot plot = new XYPlot(dataset, null, lengthAxis, renderer);
        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(new Color(215, 218, 220));
        plot.setRangeGridlinePaint(new Color(215, 218, 220));
        if (30 < maximumDistance && 30 % distanceStep == 0) {
            plot.addDomainMarker(new ValueMarker(
                    30.0 / distanceStep - 0.5,
                    THIRTY_KILOMETER,
                    new BasicStroke(1.5f, BasicStroke.CAP_BUTT,
                            BasicStroke.JOIN_BEVEL, 0.0f,
                            new float[]{6.0f, 4.0f}, 0.0f)));
        }
        combined.add(plot, 1);
    }

    private static void addSubtitle(
            JFreeChart chart, String text, Color color) {
        TextTitle subtitle = new TextTitle(text);
        subtitle.setFont(SUBTITLE_FONT);
        subtitle.setPaint(color);
        chart.addSubtitle(subtitle);
    }

    private static String rate(Double value) {
        return value == null ? "算出不可"
                : String.format(Locale.ROOT, "%.2f%%", value);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
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

    private static final class PerformancePaintScale implements PaintScale {
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
            Color color = colors.colorFor(value);
            return new Color(color.getRed(), color.getGreen(),
                    color.getBlue());
        }
    }
}
