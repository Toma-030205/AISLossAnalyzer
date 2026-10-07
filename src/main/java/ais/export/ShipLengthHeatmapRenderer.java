package ais.export;

import ais.app.ShipLengthAnalysisResult;
import ais.app.ShipLengthAnalysisRow;
import ais.app.ShipLengthBand;
import ais.app.ShipLengthDistanceCell;
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

public final class ShipLengthHeatmapRenderer {

    private static final Font TITLE_FONT = japaneseFont(Font.BOLD, 18);
    private static final Font SUBTITLE_FONT = japaneseFont(Font.PLAIN, 11);
    private static final Font AXIS_FONT = japaneseFont(Font.BOLD, 12);
    private static final Font TICK_FONT = japaneseFont(Font.PLAIN, 10);
    private static final Color INSUFFICIENT = new Color(150, 155, 160);
    private static final Color THIRTY_KILOMETER =
            new Color(150, 45, 40, 210);

    public JFreeChart create(ShipLengthAnalysisResult result) {
        int distanceStep = result.analysisProfile().distanceBinKilometers();
        int maximumDistance =
                result.analysisProfile().maximumDistanceKilometers();
        String[] distanceBands = IntStream.iterate(0,
                        lower -> lower < maximumDistance,
                        lower -> lower + distanceStep)
                .mapToObj(lower -> lower + "-"
                        + Math.min(maximumDistance, lower + distanceStep)
                        + " km")
                .toArray(String[]::new);
        String[] lengthBands = ShipLengthBand.trendBands().stream()
                .map(ShipLengthBand::toString)
                .toArray(String[]::new);

        SymbolAxis distanceAxis = new SymbolAxis(
                "日別最遠距離帯（解析範囲内）", distanceBands);
        distanceAxis.setRange(-0.5, distanceBands.length - 0.5);
        distanceAxis.setGridBandsVisible(false);
        distanceAxis.setLabelFont(AXIS_FONT);
        distanceAxis.setTickLabelFont(TICK_FONT);
        CombinedRangeXYPlot combined = new CombinedRangeXYPlot(distanceAxis);
        combined.setGap(12.0);
        combined.setBackgroundPaint(Color.WHITE);

        HeatmapPaintScale paintScale = new HeatmapPaintScale();
        result.request().vesselClasses().stream()
                .sorted()
                .forEach(vesselClass -> addClassPlot(
                        combined, result, vesselClass,
                        lengthBands, distanceBands.length,
                        paintScale, distanceStep, maximumDistance));

        JFreeChart chart = new JFreeChart(
                "船体長別・日別最遠距離帯分布",
                TITLE_FONT, combined, false);
        chart.setBackgroundPaint(Color.WHITE);
        TextTitle period = new TextTitle(String.format(
                "期間（日本時間）: %s～%s%s / 受信局: %s / 解析条件: %s",
                result.request().startDate(), result.request().endDate(),
                ExportMetadataFormatter.excludedDates(
                        result.request().excludedDates()),
                result.receiverProfile().name(),
                result.analysisProfile().rulesVersion()));
        period.setFont(SUBTITLE_FONT);
        chart.addSubtitle(period);
        TextTitle coverage = new TextTitle(String.format(Locale.ROOT,
                "使用解析run %,d / Class別MMSI標本 %,d / "
                        + "船体長既知 %,d（%.1f%%）/ "
                        + "船舶日 %,d（船体長既知 %,d・%.1f%%）",
                result.analysisRunCount(),
                result.totalDistinctVesselCount(),
                result.knownLengthDistinctVesselCount(),
                result.knownLengthCoveragePercent(),
                result.totalVesselDayCount(),
                result.knownLengthVesselDayCount(),
                result.knownLengthVesselDayCoveragePercent()));
        coverage.setFont(SUBTITLE_FONT);
        chart.addSubtitle(coverage);
        TextTitle explanation = new TextTitle(
                "色=各船体長帯における船舶日構成比 / "
                        + "破線=30km基準 / "
                        + "灰色=標本不足（船舶日30未満または3隻未満） / "
                        + "空白=標本なし / 不明・範囲外は表とCSVのみ");
        explanation.setFont(SUBTITLE_FONT);
        chart.addSubtitle(explanation);
        TextTitle caution = new TextTitle(
                "船体長=各観測日終了までに取得済みの同一Class・MMSIの"
                        + "最新Type 5/24値。欠落率・情報鮮度違反率ではありません。"
                        + "船体長はアンテナ高の代理変数で、"
                        + "航路・船種・運航範囲の影響を含み、"
                        + "70km外だけの船舶日は含みません。");
        caution.setFont(SUBTITLE_FONT);
        caution.setPaint(new Color(120, 45, 35));
        chart.addSubtitle(caution);

        NumberAxis scaleAxis = new NumberAxis("船舶日構成比（%）");
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
            CombinedRangeXYPlot combined,
            ShipLengthAnalysisResult result,
            VesselClass vesselClass,
            String[] lengthBands,
            int distanceBandCount,
            PaintScale paintScale,
            int distanceStep,
            int maximumDistance) {
        Map<GroupKey, ShipLengthAnalysisRow> summaries = new HashMap<>();
        result.rows().stream()
                .filter(row -> row.vesselClass() == vesselClass)
                .filter(row -> row.shipLengthBand().usableForTrend())
                .forEach(row -> summaries.put(
                        new GroupKey(row.shipLengthBand()), row));
        Map<CellKey, ShipLengthDistanceCell> cells = new HashMap<>();
        result.cells().stream()
                .filter(cell -> cell.vesselClass() == vesselClass)
                .filter(cell -> cell.shipLengthBand().usableForTrend())
                .forEach(cell -> cells.put(new CellKey(
                        cell.shipLengthBand(),
                        cell.dailyMaximumDistanceBand().index()), cell));

        int itemCount = summaries.size() * distanceBandCount;
        double[][] data = new double[3][itemCount];
        Map<Coordinate, ToolTipData> toolTips = new HashMap<>();
        int item = 0;
        for (ShipLengthBand lengthBand : ShipLengthBand.trendBands()) {
            ShipLengthAnalysisRow summary = summaries.get(
                    new GroupKey(lengthBand));
            if (summary == null) {
                continue;
            }
            for (int distance = 0;
                    distance < distanceBandCount; distance++) {
                ShipLengthDistanceCell cell = cells.get(
                        new CellKey(lengthBand, distance));
                double share = cell == null ? 0.0 : cell.sharePercent();
                data[0][item] = lengthBand.order();
                data[1][item] = distance;
                data[2][item] = summary.sufficientData()
                        ? share : Double.NaN;
                toolTips.put(new Coordinate(lengthBand.order(), distance),
                        new ToolTipData(summary, cell, share));
                item++;
            }
        }
        DefaultXYZDataset dataset = new DefaultXYZDataset();
        dataset.addSeries(classLabel(vesselClass), data);

        SymbolAxis lengthAxis = new SymbolAxis(
                classLabel(vesselClass) + " / 船体長区分", lengthBands);
        lengthAxis.setRange(-0.5, lengthBands.length - 0.5);
        lengthAxis.setGridBandsVisible(false);
        lengthAxis.setLabelFont(AXIS_FONT);
        lengthAxis.setTickLabelFont(TICK_FONT);

        XYBlockRenderer renderer = new XYBlockRenderer();
        renderer.setBlockWidth(1.0);
        renderer.setBlockHeight(1.0);
        renderer.setPaintScale(paintScale);
        renderer.setDefaultToolTipGenerator((source, series, index) -> {
            int length = (int) Math.round(
                    source.getXValue(series, index));
            int distance = (int) Math.round(
                    source.getYValue(series, index));
            ToolTipData tooltip = toolTips.get(
                    new Coordinate(length, distance));
            if (tooltip == null) {
                return null;
            }
            ShipLengthAnalysisRow summary = tooltip.summary();
            long cellDays = tooltip.cell() == null
                    ? 0 : tooltip.cell().vesselDayCount();
            int cellVessels = tooltip.cell() == null
                    ? 0 : tooltip.cell().distinctVesselCount();
            return "<html>" + classLabel(vesselClass) + " / "
                    + summary.shipLengthBand() + " / "
                    + distance * distanceStep + "-"
                    + Math.min(maximumDistance,
                    (distance + 1) * distanceStep) + " km"
                    + "<br>船舶日構成比: "
                    + String.format(Locale.ROOT, "%.1f%%", tooltip.share())
                    + "<br>当該セル: "
                    + String.format(Locale.ROOT, "%,d船舶日 / %,d MMSI",
                    cellDays, cellVessels)
                    + "<br>船体長帯全体: "
                    + String.format(Locale.ROOT, "%,d船舶日 / %,d MMSI",
                    summary.vesselDayCount(),
                    summary.distinctVesselCount())
                    + (summary.sufficientData()
                    ? "" : "<br>データ不足（参考値）")
                    + "</html>";
        });

        XYPlot plot = new XYPlot(dataset, lengthAxis, null, renderer);
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

    private record GroupKey(ShipLengthBand lengthBand) {
    }

    private record CellKey(
            ShipLengthBand lengthBand,
            int distanceBandIndex) {
    }

    private record Coordinate(int lengthBandIndex, int distanceBandIndex) {
    }

    private record ToolTipData(
            ShipLengthAnalysisRow summary,
            ShipLengthDistanceCell cell,
            double share) {
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
