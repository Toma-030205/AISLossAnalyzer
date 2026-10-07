package ais.ui;

import ais.aggregate.RollupDimension;
import ais.app.AggregateAxis;
import ais.app.AggregateQueryService;
import ais.app.AggregateRequest;
import ais.app.AggregateResult;
import ais.app.AggregateRow;
import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;
import ais.domain.VesselClass;
import ais.export.ChartRenderer;
import ais.export.ExportFileNamer;
import ais.export.ExportService;
import ais.ui.viewmodel.HeatmapMetric;
import org.jfree.chart.ChartPanel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class AggregateScreenPanel extends JPanel {

    private final AggregateQueryService service;
    private final ExportService exports;
    private final ReceiverProfile defaultReceiver;
    private final AnalysisProfile defaultProfile;
    private final StatusBar statusBar;
    private final JTextField startDate = new JTextField(10);
    private final JTextField endDate = new JTextField(10);
    private final JTextField excludedDates = new JTextField(18);
    private final JComboBox<DimensionChoice> dimension =
            new JComboBox<>(DimensionChoice.values());
    private final JComboBox<ClassChoice> vesselClass =
            new JComboBox<>(ClassChoice.values());
    private final JComboBox<HeatmapMetric> metric =
            new JComboBox<>(HeatmapMetric.values());
    private final JComboBox<AggregateAxis> axis =
            new JComboBox<>(AggregateAxis.values());
    private final JComboBox<ReceiverChoice> receiverProfile =
            new JComboBox<>();
    private final JComboBox<AnalysisChoice> analysisProfile =
            new JComboBox<>();
    private final JButton display = new JButton("表示");
    private final JButton csv = new JButton("CSV保存");
    private final JButton png = new JButton("グラフPNG保存");
    private final JPanel chartArea = new JPanel(new BorderLayout());
    private final AggregateTableModel tableModel = new AggregateTableModel();
    private AggregateResult current;

    public AggregateScreenPanel(
            AggregateQueryService service,
            ExportService exports,
            ReceiverProfile receiver,
            AnalysisProfile profile,
            LocalDate initialDate,
            StatusBar statusBar) {
        super(new BorderLayout(6, 6));
        this.service = service;
        this.exports = exports;
        this.defaultReceiver = receiver;
        this.defaultProfile = profile;
        this.statusBar = statusBar;
        service.receiverProfiles().stream()
                .map(ReceiverChoice::new).forEach(receiverProfile::addItem);
        service.analysisProfiles().stream()
                .map(AnalysisChoice::new).forEach(analysisProfile::addItem);
        selectReceiver(receiver.id());
        selectAnalysisProfile(profile.id());
        LocalDate initial = initialDate == null ? LocalDate.now() : initialDate;
        startDate.setText(initial.toString());
        endDate.setText(initial.toString());
        metric.setSelectedItem(HeatmapMetric.FRESHNESS_VIOLATION);

        JPanel conditions = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 5));
        conditions.setBorder(BorderFactory.createTitledBorder("検索条件"));
        conditions.add(new JLabel("期間"));
        conditions.add(startDate);
        conditions.add(new JLabel("～"));
        conditions.add(endDate);
        conditions.add(new JLabel("除外日"));
        excludedDates.setToolTipText(
                "任意。例: 2025-11-03, 2025-11-04");
        conditions.add(excludedDates);
        conditions.add(new JLabel("集計単位"));
        conditions.add(dimension);
        conditions.add(new JLabel("Class"));
        conditions.add(vesselClass);
        conditions.add(new JLabel("指標"));
        conditions.add(metric);
        conditions.add(new JLabel("グラフ"));
        conditions.add(axis);
        conditions.add(display);
        conditions.add(new JLabel("受信局"));
        conditions.add(receiverProfile);
        conditions.add(new JLabel("解析条件"));
        conditions.add(analysisProfile);
        add(conditions, BorderLayout.NORTH);

        chartArea.setBorder(BorderFactory.createTitledBorder("グラフ"));
        chartArea.add(new JLabel("保存済み集計の期間を指定して表示してください",
                JLabel.CENTER), BorderLayout.CENTER);
        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        JPanel tableArea = new JPanel(new BorderLayout());
        JPanel tableActions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        tableActions.add(csv);
        tableActions.add(png);
        tableArea.add(tableActions, BorderLayout.NORTH);
        tableArea.add(new JScrollPane(table), BorderLayout.CENTER);
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT,
                chartArea, tableArea);
        split.setResizeWeight(0.58);
        split.setDividerLocation(0.58);
        add(split, BorderLayout.CENTER);

        display.addActionListener(event -> query());
        axis.addActionListener(event -> {
            updateDimensionEnabled();
            if (current != null) {
                query();
            }
        });
        dimension.setToolTipText(
                "時間帯別・距離帯×時間帯は選択期間全体を合算します");
        updateDimensionEnabled();
        csv.addActionListener(event -> exportCsv());
        png.addActionListener(event -> exportPng());
        csv.setEnabled(false);
        png.setEnabled(false);
    }

    public void refreshIfEmpty() {
        if (current == null) {
            query();
        }
    }

    private void query() {
        AggregateRequest request;
        try {
            request = request();
        } catch (RuntimeException failure) {
            statusBar.showFailure(failure);
            return;
        }
        setBusy(true);
        statusBar.showOperation("保存済み集計を検索しています…");
        service.query(request).whenComplete((result, failure) ->
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    if (failure != null) {
                        statusBar.showFailure(failure);
                        return;
                    }
                    current = result;
                    tableModel.setRows(result.rows());
                    chartArea.removeAll();
                    if (result.rows().isEmpty()) {
                        chartArea.add(new JLabel(
                                "この条件に保存済み集計はありません",
                                JLabel.CENTER), BorderLayout.CENTER);
                    } else {
                        chartArea.add(new ChartPanel(
                                new ChartRenderer().create(result)),
                                BorderLayout.CENTER);
                    }
                    chartArea.revalidate();
                    chartArea.repaint();
                    csv.setEnabled(!result.rows().isEmpty());
                    png.setEnabled(!result.rows().isEmpty());
                    statusBar.showOperation(String.format(
                            "解析実行%d件から集計行%,d件を表示しました",
                            result.analysisRunCount(), result.rows().size()));
                }));
    }

    private AggregateRequest request() {
        DimensionChoice selectedDimension =
                (DimensionChoice) dimension.getSelectedItem();
        ClassChoice selectedClass =
                (ClassChoice) vesselClass.getSelectedItem();
        ReceiverChoice selectedReceiver =
                (ReceiverChoice) receiverProfile.getSelectedItem();
        AnalysisChoice selectedProfile =
                (AnalysisChoice) analysisProfile.getSelectedItem();
        return new AggregateRequest(
                LocalDate.parse(startDate.getText().trim()),
                LocalDate.parse(endDate.getText().trim()),
                selectedDimension == null ? RollupDimension.DAY
                        : selectedDimension.dimension,
                selectedClass == null ? ClassChoice.ALL.classes
                        : selectedClass.classes,
                (HeatmapMetric) metric.getSelectedItem(),
                selectedReceiver == null ? defaultReceiver.id()
                        : selectedReceiver.profile.id(),
                selectedProfile == null ? defaultProfile.id()
                        : selectedProfile.profile.id(),
                (AggregateAxis) axis.getSelectedItem(),
                ExcludedDateParser.parse(excludedDates.getText()));
    }

    private void exportCsv() {
        if (current == null) {
            return;
        }
        Path target = chooseSave("CSVを保存",
                new ExportFileNamer().csv(current), "csv");
        if (target == null) {
            return;
        }
        csv.setEnabled(false);
        exports.csv(target, current).whenComplete((saved, failure) ->
                SwingUtilities.invokeLater(() -> {
                    csv.setEnabled(true);
                    showExportResult(saved, failure);
                }));
    }

    private void exportPng() {
        if (current == null) {
            return;
        }
        Path target = chooseSave("グラフPNGを保存",
                new ExportFileNamer().chartPng(current), "png");
        if (target == null) {
            return;
        }
        png.setEnabled(false);
        exports.chartPng(target, current).whenComplete((saved, failure) ->
                SwingUtilities.invokeLater(() -> {
                    png.setEnabled(true);
                    showExportResult(saved, failure);
                }));
    }

    private void showExportResult(Path saved, Throwable failure) {
        if (failure != null) {
            statusBar.showFailure(failure);
        } else {
            statusBar.showOperation("保存しました: " + saved);
        }
    }

    private Path chooseSave(String title, String filename, String extension) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setSelectedFile(new java.io.File(filename));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path selected = chooser.getSelectedFile().toPath();
        return selected.getFileName().toString().toLowerCase()
                .endsWith("." + extension) ? selected
                : selected.resolveSibling(selected.getFileName()
                + "." + extension);
    }

    private void setBusy(boolean busy) {
        display.setEnabled(!busy);
        startDate.setEnabled(!busy);
        endDate.setEnabled(!busy);
        excludedDates.setEnabled(!busy);
        dimension.setEnabled(!busy
                && axis.getSelectedItem() == AggregateAxis.DISTANCE_BAND);
        vesselClass.setEnabled(!busy);
        metric.setEnabled(!busy);
        axis.setEnabled(!busy);
        receiverProfile.setEnabled(!busy);
        analysisProfile.setEnabled(!busy);
    }

    private void updateDimensionEnabled() {
        dimension.setEnabled(
                axis.getSelectedItem() == AggregateAxis.DISTANCE_BAND
                        && display.isEnabled());
    }

    private void selectReceiver(ais.domain.ReceiverProfileId id) {
        for (int index = 0; index < receiverProfile.getItemCount(); index++) {
            if (receiverProfile.getItemAt(index).profile.id().equals(id)) {
                receiverProfile.setSelectedIndex(index);
                return;
            }
        }
    }

    private void selectAnalysisProfile(ais.domain.AnalysisProfileId id) {
        for (int index = 0; index < analysisProfile.getItemCount(); index++) {
            if (analysisProfile.getItemAt(index).profile.id().equals(id)) {
                analysisProfile.setSelectedIndex(index);
                return;
            }
        }
    }

    private record ReceiverChoice(ReceiverProfile profile) {
        @Override
        public String toString() {
            return profile.name() + "（" + profile.validFrom()
                    + "～" + (profile.validTo() == null
                    ? "現在" : profile.validTo()) + "）";
        }
    }

    private record AnalysisChoice(AnalysisProfile profile) {
        @Override
        public String toString() {
            return profile.rulesVersion();
        }
    }

    private enum DimensionChoice {
        DAY("日別", RollupDimension.DAY),
        DAY_OF_WEEK("曜日別", RollupDimension.DAY_OF_WEEK),
        MONTH("月別", RollupDimension.MONTH),
        YEAR("年別（1～12月）", RollupDimension.YEAR);

        private final String label;
        private final RollupDimension dimension;

        DimensionChoice(String label, RollupDimension dimension) {
            this.label = label;
            this.dimension = dimension;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum ClassChoice {
        ALL("全船舶", Set.of(VesselClass.CLASS_A, VesselClass.CLASS_B)),
        A("Class A", Set.of(VesselClass.CLASS_A)),
        B("Class B", Set.of(VesselClass.CLASS_B));

        private final String label;
        private final Set<VesselClass> classes;

        ClassChoice(String label, Set<VesselClass> classes) {
            this.label = label;
            this.classes = classes;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class AggregateTableModel
            extends AbstractTableModel {

        private static final String[] COLUMNS = {
                "系列", "距離帯/区分", "時間帯", "Class", "欠落率%",
                "鮮度違反率%", "受信", "欠落", "期待", "観測秒",
                "鮮度違反秒", "船舶数", "当該区分の観測日数",
                "データ判定"};
        private List<AggregateRow> rows = List.of();

        private void setRows(List<AggregateRow> rows) {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column >= 4 && column <= 12 ? Number.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AggregateRow row = rows.get(rowIndex);
            var evaluation = row.evaluation();
            var counts = evaluation.counts();
            return switch (columnIndex) {
                case 0 -> row.seriesLabel();
                case 1 -> row.categoryLabel();
                case 2 -> row.hourOfDay() == null ? ""
                        : String.format("%02d時", row.hourOfDay());
                case 3 -> row.vesselClass() == VesselClass.CLASS_A
                        ? "Class A" : "Class B";
                case 4 -> evaluation.lossRatePercent();
                case 5 -> evaluation.freshnessViolationRatePercent();
                case 6 -> counts.observedCount();
                case 7 -> counts.missingCount();
                case 8 -> counts.expectedCount();
                case 9 -> counts.observedSeconds();
                case 10 -> counts.staleSeconds();
                case 11 -> evaluation.distinctVesselCount();
                case 12 -> row.observationDayCount();
                case 13 -> evaluation.hasSufficientData()
                        ? "十分" : "データ不足";
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }
    }
}
