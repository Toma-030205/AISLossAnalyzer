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
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.renderer.category.LineAndShapeRenderer;
import org.jfree.data.category.CategoryDataset;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
