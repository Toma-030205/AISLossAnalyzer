package ais.ui;

import ais.domain.FreshnessState;
import ais.domain.VesselClass;
import ais.ui.viewmodel.VesselDetailViewModel;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.Component;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import ais.simulation.calibration.CommunicationModelId;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PanelLayoutTest {

    @Test
    void historicalControlsKeepRowsCompact() {
        HistoricalControlPanel panel = new HistoricalControlPanel(
                List.of(LocalDate.of(2026, 6, 2)),
                new HistoricalControlPanel.Listener() {
                    @Override
                    public void onLoadDate(LocalDate date) {
                    }

                    @Override
                    public void onDirectFile(Path file) {
                    }

                    @Override
                    public void onPlayPause() {
                    }

                    @Override
                    public void onRestart() {
                    }

                    @Override
                    public void onSeekStarted() {
                    }

                    @Override
                    public void onSeek(Instant time) {
                    }

                    @Override
                    public void onAnalyzeAndSave() {
                    }

                    @Override
                    public void onAnalyzeRange() {
                    }

                    @Override
                    public void onCancelBatch() {
                    }

                    @Override
                    public void onExportCsv() {
                    }

                    @Override
                    public void onExportChart() {
                    }
                });

        assertTrue(panel.getPreferredSize().height < 300);
        for (Component component : panel.getComponents()) {
            if (component instanceof JPanel row) {
                assertTrue(row.getMaximumSize().height < 50);
            }
        }
    }

    @Test
    void selectionDetailsUseLargerTextAndReadableVerticalSpacing() {
        float standardSize = new JLabel().getFont().getSize2D();
        SelectionDetailPanel panel = new SelectionDetailPanel();
        panel.update(new VesselDetailViewModel(
                431_000_001, "TEST VESSEL", 123, VesselClass.CLASS_A,
                34.6, 135.2, 12.3, 91.0, 90.0, 0, 1,
                FreshnessState.NORMAL, 5, 12.4), null);

        assertEquals(2, panel.getComponentCount());
        JScrollPane vesselSection = (JScrollPane) panel.getComponent(0);
        JPanel vesselContents = (JPanel)
                vesselSection.getViewport().getView();
        JLabel firstLabel = (JLabel) vesselContents.getComponent(0);
        assertTrue(firstLabel.getFont().getSize2D() > standardSize);
        assertTrue(componentTexts(vesselContents).contains("全長"));
        assertTrue(componentTexts(vesselContents).contains("123 m"));
        JScrollPane gridSection = (JScrollPane) panel.getComponent(1);
        JPanel gridContents = (JPanel) gridSection.getViewport().getView();
        JLabel gridPlaceholder = (JLabel) gridContents.getComponent(0);
        assertEquals("地図上の色付き格子を選択してください",
                gridPlaceholder.getText());
        panel.setSize(420, 520);
        panel.doLayout();
        assertEquals(vesselSection.getHeight(), gridSection.getHeight());
        assertTrue(vesselSection.getHeight() > 0);
    }

    @Test
    void displayOptionsUseLargerControls() {
        float standardSize = new JLabel().getFont().getSize2D();
        DisplayOptionsPanel panel = new DisplayOptionsPanel(
                new DisplayOptionsPanel.Listener() {
                    @Override
                    public void onFilterChanged(
                            Set<VesselClass> vesselClasses,
                            Duration trailDuration) {
                    }

                    @Override
                    public void onLayersChanged(
                            boolean heatmap, boolean tenKilometers,
                            boolean thirtyKilometers, boolean receiver,
                            boolean vessels) {
                    }
                });

        JPanel classRow = (JPanel) panel.getComponent(0);
        assertTrue(classRow.getComponent(0).getFont().getSize2D()
                > standardSize);
        JPanel layerRow = (JPanel) panel.getComponent(1);
        assertTrue(layerRow.getComponent(0).getFont().getSize2D()
                > standardSize);
    }

    @Test
    void simulationControlsKeepRowsCompact() {
        SimulationControlPanel panel = new SimulationControlPanel(
                List.of(LocalDate.of(2026, 9, 4)),
                new SimulationControlPanel.Listener() {
                    @Override
                    public void onRefreshModels() {
                    }

                    @Override
                    public void onLoad(
                            LocalDate date,
                            CommunicationModelId modelId,
                            long seed) {
                    }

                    @Override
                    public void onPlayPause() {
                    }

                    @Override
                    public void onRestart() {
                    }

                    @Override
                    public void onSeekStarted() {
                    }

                    @Override
                    public void onSeek(Instant time) {
                    }
                });

        assertTrue(panel.getPreferredSize().height < 300);
        for (Component component : panel.getComponents()) {
            if (component instanceof JPanel row) {
                assertTrue(row.getMaximumSize().height < 50);
            }
        }
    }

    private static List<String> componentTexts(JPanel panel) {
        return java.util.Arrays.stream(panel.getComponents())
                .filter(JLabel.class::isInstance)
                .map(JLabel.class::cast)
                .map(JLabel::getText)
                .toList();
    }
}
