package ais.ui;

import ais.ui.viewmodel.HeatmapMetric;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.util.function.Consumer;

public final class MapToolbar extends JPanel {

    private final JLabel zoomLabel = new JLabel("100%");
    private final JComboBox<HeatmapMetric> metric =
            new JComboBox<>(HeatmapMetric.values());

    public MapToolbar(MapCanvas canvas,
                      Consumer<HeatmapMetric> metricChanged,
                      Runnable exportMap) {
        super(new FlowLayout(FlowLayout.LEFT, 7, 5));
        JButton zoomIn = new JButton("＋");
        JButton zoomOut = new JButton("－");
        JButton reset = new JButton("100%");
        zoomIn.setToolTipText("地図を拡大");
        zoomOut.setToolTipText("地図を縮小");
        reset.setToolTipText("初期表示範囲へ戻す");
        zoomIn.addActionListener(event -> {
            canvas.zoomIn();
            refreshZoom(canvas);
        });
        zoomOut.addActionListener(event -> {
            canvas.zoomOut();
            refreshZoom(canvas);
        });
        reset.addActionListener(event -> {
            canvas.resetView();
            refreshZoom(canvas);
        });
        metric.setSelectedItem(HeatmapMetric.FRESHNESS_VIOLATION);
        metric.addActionListener(event -> metricChanged.accept(
                (HeatmapMetric) metric.getSelectedItem()));
        add(zoomIn);
        add(zoomOut);
        add(reset);
        add(zoomLabel);
        add(new JLabel("  指標:"));
        add(metric);
        JButton saveMap = new JButton("地図画像を保存");
        saveMap.addActionListener(event -> exportMap.run());
        add(saveMap);
    }

    private void refreshZoom(MapCanvas canvas) {
        zoomLabel.setText(String.format("%.0f%%", canvas.zoom() * 100.0));
    }
}
