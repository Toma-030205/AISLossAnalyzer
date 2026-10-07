package ais.export;

import ais.aggregate.InsufficientDataReason;
import ais.aggregate.MetricCounts;
import ais.aggregate.MetricEvaluation;
import ais.aggregate.RollupDimension;
import ais.app.AggregateAxis;
import ais.app.AggregateRequest;
import ais.app.AggregateResult;
import ais.app.AggregateRow;
import ais.domain.AnalysisProfile;
import ais.domain.GeoPosition;
import ais.domain.ReceiverProfile;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;
import ais.ui.viewmodel.HeatmapMetric;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.CombinedRangeXYPlot;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.category.LineAndShapeRenderer;
import org.jfree.chart.renderer.xy.XYBlockRenderer;
import org.jfree.chart.renderer.xy.DeviationRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.category.CategoryDataset;
import org.jfree.data.xy.IntervalXYDataset;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartRendererTest {

    @Test
    void rendersJapaneseThresholdAndInsufficientSamplesClearly() {
        JFreeChart chart = new ChartRenderer().create(result());
        CategoryPlot plot = chart.getCategoryPlot();
        CategoryDataset values = plot.getDataset(0);
        CategoryDataset insufficient = plot.getDataset(1);

        assertEquals(-1, chart.getTitle().getFont()
                .canDisplayUpTo("情報鮮度違反率"));
        assertEquals(-1, plot.getDomainAxis().getLabelFont()
                .canDisplayUpTo("距離帯"));
        assertEquals(-1, plot.getRangeAxis().getLabelFont()
                .canDisplayUpTo("率（%）"));
        assertEquals(List.of("Class A", "Class B"), values.getRowKeys());
        assertEquals(1, plot.getAnnotations().size());
        assertNotNull(insufficient);
        assertEquals(55.0,
                insufficient.getValue("Class B", "30-35 km"));
        LineAndShapeRenderer insufficientRenderer =
                (LineAndShapeRenderer) plot.getRenderer(1);
        assertFalse(insufficientRenderer.getDefaultSeriesVisibleInLegend());

        LineAndShapeRenderer valueRenderer =
                (LineAndShapeRenderer) plot.getRenderer(0);
        int category = values.getColumnIndex("25-30 km");
        assertEquals("n=100", valueRenderer.getDefaultItemLabelGenerator()
                .generateLabel(values, 0, category));
        assertTrue(valueRenderer.getDefaultToolTipGenerator()
                .generateToolTip(values, 0, category)
                .contains("期待送信数: 100"));
        var preview = chart.createBufferedImage(1_400, 800);
        assertEquals(1_400, preview.getWidth());
    }

    @Test
    void rendersDistanceHourClassesAsComparableHeatmaps() {
        JFreeChart chart = new ChartRenderer().create(distanceHourResult());
        CombinedRangeXYPlot combined = assertInstanceOf(
                CombinedRangeXYPlot.class, chart.getPlot());
        assertEquals(2, combined.getSubplots().size());
        XYPlot classB = (XYPlot) combined.getSubplots().get(1);
        XYBlockRenderer renderer = assertInstanceOf(
                XYBlockRenderer.class, classB.getRenderer());
        assertEquals(new Color(150, 155, 160),
                renderer.getPaintScale().getPaint(Double.NaN));
        assertTrue(chart.getSubtitles().stream()
                .filter(TextTitle.class::isInstance)
                .map(TextTitle.class::cast)
                .anyMatch(title -> title.getText().contains("各セルの母数範囲")));
        assertEquals(1_400, chart.createBufferedImage(1_400, 800)
                .getWidth());
    }

    @Test
    void summarizesMultiDayDistanceChartWithPooledLinesAndDailyIqr() {
        JFreeChart chart = new ChartRenderer().create(dailySummaryResult());
        XYPlot plot = assertInstanceOf(XYPlot.class, chart.getPlot());
        DeviationRenderer renderer = assertInstanceOf(
                DeviationRenderer.class, plot.getRenderer());
        IntervalXYDataset dataset = assertInstanceOf(
                IntervalXYDataset.class, plot.getDataset());

        assertEquals(List.of("Class A", "Class B"),
                List.of(dataset.getSeriesKey(0), dataset.getSeriesKey(1)));
        assertEquals(30.0, dataset.getYValue(0, 0), 0.0001);
        assertEquals(25.0, dataset.getStartYValue(0, 0), 0.0001);
        assertEquals(35.0, dataset.getEndYValue(0, 0), 0.0001);
        assertEquals(2, plot.getAnnotations().size());
        assertTrue(renderer.getDefaultToolTipGenerator()
                .generateToolTip(dataset, 0, 0)
                .contains("IQR対象日: 2/2"));
        assertTrue(chart.getSubtitles().stream()
                .filter(TextTitle.class::isInstance)
                .map(TextTitle.class::cast)
                .anyMatch(title -> title.getText().contains(
                        "色帯: 十分判定の日別率")));
        assertEquals(1_400, chart.createBufferedImage(1_400, 800)
                .getWidth());
    }

    private static AggregateResult result() {
        AnalysisProfile profile = AnalysisProfile.phaseOneDefaults();
        ReceiverProfile receiver = new ReceiverProfile(
                new ReceiverProfileId("chart-test"), "研究室受信局",
                new GeoPosition(34.68, 135.19), null, null, null,
                LocalDate.of(2020, 1, 1), null, null);
        AggregateRequest request = new AggregateRequest(
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 8),
                RollupDimension.DAY,
                Set.of(VesselClass.CLASS_A, VesselClass.CLASS_B),
                HeatmapMetric.FRESHNESS_VIOLATION,
                receiver.id(), profile.id(),
                AggregateAxis.DISTANCE_BAND);
        MetricEvaluation sufficient = new MetricEvaluation(
                new MetricCounts(90, 10, 1_000, 200),
                6, 10.0, 20.0, Set.of());
        MetricEvaluation tooSmall = new MetricEvaluation(
                new MetricCounts(9, 1, 100, 55),
                2, 10.0, 55.0,
                Set.of(InsufficientDataReason.EXPECTED_COUNT_BELOW_MINIMUM,
                        InsufficientDataReason.DISTINCT_VESSELS_BELOW_MINIMUM));
        return new AggregateResult(request, receiver, profile, 1,
                List.of(
                        new AggregateRow("Class A", "25-30 km",
                                VesselClass.CLASS_A, sufficient, 1, 5),
                        new AggregateRow("Class B", "30-35 km",
                                VesselClass.CLASS_B, tooSmall, 1, 6)));
    }

    private static AggregateResult distanceHourResult() {
        AggregateResult base = result();
        AggregateRequest request = new AggregateRequest(
                base.request().startDate(), base.request().endDate(),
                RollupDimension.MONTH,
                Set.of(VesselClass.CLASS_A, VesselClass.CLASS_B),
                HeatmapMetric.FRESHNESS_VIOLATION,
                base.receiverProfile().id(), base.analysisProfile().id(),
                AggregateAxis.DISTANCE_BY_HOUR);
        MetricEvaluation sufficient = new MetricEvaluation(
                new MetricCounts(90, 10, 1_000, 200),
                6, 10.0, 20.0, Set.of());
        MetricEvaluation tooSmall = new MetricEvaluation(
                new MetricCounts(9, 1, 100, 55),
                2, 10.0, 55.0,
                Set.of(InsufficientDataReason.EXPECTED_COUNT_BELOW_MINIMUM,
                        InsufficientDataReason.DISTINCT_VESSELS_BELOW_MINIMUM));
        return new AggregateResult(
                request, base.receiverProfile(), base.analysisProfile(), 1,
                List.of(
                        new AggregateRow("Class A", "25-30 km",
                                VesselClass.CLASS_A, sufficient,
                                2, 5, 0),
                        new AggregateRow("Class B", "30-35 km",
                                VesselClass.CLASS_B, tooSmall,
                                2, 6, 0)));
    }

    private static AggregateResult dailySummaryResult() {
        AggregateResult base = result();
        AggregateRequest request = new AggregateRequest(
                LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 9),
                RollupDimension.DAY,
                Set.of(VesselClass.CLASS_A, VesselClass.CLASS_B),
                HeatmapMetric.FRESHNESS_VIOLATION,
                base.receiverProfile().id(), base.analysisProfile().id(),
                AggregateAxis.DISTANCE_BAND);
        MetricEvaluation aFirst = new MetricEvaluation(
                new MetricCounts(90, 10, 1_000, 200),
                6, 10.0, 20.0, Set.of());
        MetricEvaluation aSecond = new MetricEvaluation(
                new MetricCounts(80, 20, 1_000, 400),
                6, 20.0, 40.0, Set.of());
        MetricEvaluation bFirst = new MetricEvaluation(
                new MetricCounts(70, 30, 1_000, 500),
                6, 30.0, 50.0, Set.of());
        MetricEvaluation bSecond = new MetricEvaluation(
                new MetricCounts(60, 40, 1_000, 700),
                6, 40.0, 70.0, Set.of());
        return new AggregateResult(
                request, base.receiverProfile(), base.analysisProfile(), 2,
                List.of(
                        new AggregateRow("2026-09-08", "25-30 km",
                                VesselClass.CLASS_A, aFirst, 1, 5),
                        new AggregateRow("2026-09-09", "25-30 km",
                                VesselClass.CLASS_A, aSecond, 1, 5),
                        new AggregateRow("2026-09-08", "25-30 km",
                                VesselClass.CLASS_B, bFirst, 1, 5),
                        new AggregateRow("2026-09-09", "25-30 km",
                                VesselClass.CLASS_B, bSecond, 1, 5)));
    }
}
