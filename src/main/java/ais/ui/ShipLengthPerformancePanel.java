package ais.ui;

import ais.app.AggregateQueryService;
import ais.app.ShipLengthPerformanceRequest;
import ais.app.ShipLengthPerformanceResult;
import ais.app.ShipLengthPerformanceRow;
import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;
import ais.domain.VesselClass;
import ais.export.ExportFileNamer;
import ais.export.ExportService;
import ais.export.ShipLengthPerformanceHeatmapRenderer;
import ais.ui.viewmodel.HeatmapMetric;
import org.jfree.chart.ChartPanel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ShipLengthPerformancePanel extends JPanel {

    private final AggregateQueryService service;
    private final ExportService exports;
    private final ReceiverProfile defaultReceiver;
    private final AnalysisProfile defaultProfile;
    private final StatusBar statusBar;
    private final JTextField startDate = new JTextField(10);
    private final JTextField endDate = new JTextField(10);
    private final JTextField excludedDates = new JTextField(18);
    private final JComboBox<ClassChoice> vesselClass =
            new JComboBox<>(ClassChoice.values());
    private final JComboBox<HeatmapMetric> metric =
            new JComboBox<>(HeatmapMetric.values());
    private final JComboBox<ReceiverChoice> receiverProfile =
            new JComboBox<>();
    private final JComboBox<AnalysisChoice> analysisProfile =
            new JComboBox<>();
    private final JButton display = new JButton("表示");
    private final JButton csv = new JButton("CSV保存");
    private final JButton png = new JButton("グラフPNG保存");
    private final JLabel summary = new JLabel(
            "期間を指定して表示してください");
    private final JPanel chartArea = new JPanel(new BorderLayout());
    private final PerformanceTableModel tableModel =
            new PerformanceTableModel();
    private ShipLengthPerformanceResult current;

    public ShipLengthPerformancePanel(
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
        selectReceiver(receiver);
        selectAnalysisProfile(profile);
        metric.setSelectedItem(HeatmapMetric.ESTIMATED_LOSS);
        LocalDate initial = initialDate == null ? LocalDate.now() : initialDate;
        startDate.setText(initial.toString());
        endDate.setText(initial.toString());

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
        conditions.add(new JLabel("Class"));
        conditions.add(vesselClass);
        conditions.add(new JLabel("指標"));
        conditions.add(metric);
        conditions.add(new JLabel("受信局"));
        conditions.add(receiverProfile);
        conditions.add(new JLabel("解析条件"));
        conditions.add(analysisProfile);
        conditions.add(display);
        add(conditions, BorderLayout.NORTH);

        chartArea.setBorder(BorderFactory.createTitledBorder(
                "船体長×距離帯×Class別性能"));
        chartArea.add(new JLabel(
                "新形式で保存済みの期間を指定してください",
                JLabel.CENTER), BorderLayout.CENTER);

        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        int[] widths = {105, 85, 70, 90, 90, 90, 100,
                95, 95, 115, 85, 75, 95};
        for (int index = 0; index < widths.length; index++) {
            table.getColumnModel().getColumn(index)
                    .setPreferredWidth(widths[index]);
        }

        JPanel tableHeading = new JPanel(new BorderLayout());
        summary.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        tableHeading.add(summary, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(csv);
        actions.add(png);
        tableHeading.add(actions, BorderLayout.EAST);

        JPanel tableArea = new JPanel(new BorderLayout(4, 4));
        tableArea.add(tableHeading, BorderLayout.NORTH);
        tableArea.add(new JScrollPane(table), BorderLayout.CENTER);
        JLabel note = new JLabel("<html>率は船体長帯・距離帯・Class内で"
                + "分子と分母を合算して算出します。船体長は各観測日までに"
                + "取得済みの同一Class・MMSIの最新Type 5/24値です。"
                + "船体長はアンテナ高の代理変数ですが、航路・船種・"
                + "運航範囲などの影響も含みます。旧形式の解析結果は、"
                + "過去ログ画面の期間一括解析で再解析してください。</html>");
        note.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
        tableArea.add(note, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, chartArea, tableArea);
        split.setResizeWeight(0.64);
        split.setDividerLocation(0.64);
        add(split, BorderLayout.CENTER);

        display.addActionListener(event -> query());
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
        ShipLengthPerformanceRequest request;
        try {
            request = request();
        } catch (RuntimeException failure) {
            statusBar.showFailure(failure);
            return;
        }
        setBusy(true);
        statusBar.showOperation(
                "船体長×距離帯×Class別性能を検索しています…");
        service.queryShipLengthPerformance(request)
                .whenComplete((result, failure) ->
                        SwingUtilities.invokeLater(() -> {
                            setBusy(false);
                            if (failure != null) {
                                statusBar.showFailure(failure);
                                return;
                            }
                            current = result;
                            tableModel.setRows(result.rows());
                            updateChart(result);
                            boolean available = !result.rows().isEmpty();
                            csv.setEnabled(available);
                            png.setEnabled(available);
                            updateSummary(result);
                            statusBar.showOperation(available
                                    ? "船体長別の欠落・鮮度性能を表示しました"
                                    : emptyStatus(result));
                        }));
    }

    private void updateSummary(ShipLengthPerformanceResult result) {
        summary.setText(String.format(Locale.ROOT,
                "新形式解析run %,d / 再解析必要run %,d / "
                        + "Class別MMSI %,d / 船体長既知 %,d（%.1f%%）",
                result.readyAnalysisRunCount(),
                result.reanalysisRequiredRunCount(),
                result.totalDistinctVesselCount(),
                result.knownLengthDistinctVesselCount(),
                result.knownLengthCoveragePercent()));
        summary.setForeground(result.reanalysisRequiredRunCount() > 0
                ? new Color(170, 65, 35) : Color.DARK_GRAY);
    }

    private void updateChart(ShipLengthPerformanceResult result) {
        chartArea.removeAll();
        if (result.rows().isEmpty()) {
            chartArea.add(new JLabel(emptyStatus(result), JLabel.CENTER),
                    BorderLayout.CENTER);
        } else {
            ChartPanel chart = new ChartPanel(
                    new ShipLengthPerformanceHeatmapRenderer().create(result));
            chart.setMouseWheelEnabled(true);
            chartArea.add(chart, BorderLayout.CENTER);
        }
        chartArea.revalidate();
        chartArea.repaint();
    }

    private static String emptyStatus(ShipLengthPerformanceResult result) {
        return result.reanalysisRequiredRunCount() > 0
                ? "対象日は旧形式のため、期間一括解析で再解析が必要です"
                : "指定期間に利用できる解析結果がありません";
    }

    private ShipLengthPerformanceRequest request() {
        ReceiverChoice receiver =
                (ReceiverChoice) receiverProfile.getSelectedItem();
        AnalysisChoice profile =
                (AnalysisChoice) analysisProfile.getSelectedItem();
        ClassChoice classes = (ClassChoice) vesselClass.getSelectedItem();
        HeatmapMetric selectedMetric =
                (HeatmapMetric) metric.getSelectedItem();
        return new ShipLengthPerformanceRequest(
                LocalDate.parse(startDate.getText().trim()),
                LocalDate.parse(endDate.getText().trim()),
                classes == null ? ClassChoice.ALL.classes : classes.classes,
                selectedMetric == null
                        ? HeatmapMetric.ESTIMATED_LOSS : selectedMetric,
                receiver == null ? defaultReceiver.id()
                        : receiver.profile().id(),
                profile == null ? defaultProfile.id()
                        : profile.profile().id(),
                ExcludedDateParser.parse(excludedDates.getText()));
    }

    private void exportCsv() {
        if (current == null) {
            return;
        }
        Path target = chooseTarget(
                "船体長別性能CSVを保存",
                new ExportFileNamer().shipLengthPerformanceCsv(current),
                ".csv");
        if (target == null) {
            return;
        }
        csv.setEnabled(false);
        exports.shipLengthPerformanceCsv(target, current)
                .whenComplete((saved, failure) ->
                        SwingUtilities.invokeLater(() -> {
                            csv.setEnabled(true);
                            showExportResult(saved, failure);
                        }));
    }

    private void exportPng() {
        if (current == null) {
            return;
        }
        Path target = chooseTarget(
                "船体長別性能グラフPNGを保存",
                new ExportFileNamer()
                        .shipLengthPerformanceChartPng(current),
                ".png");
        if (target == null) {
            return;
        }
        png.setEnabled(false);
        exports.shipLengthPerformanceChartPng(target, current)
                .whenComplete((saved, failure) ->
                        SwingUtilities.invokeLater(() -> {
                            png.setEnabled(true);
                            showExportResult(saved, failure);
                        }));
    }

    private Path chooseTarget(String title, String fileName,
                              String extension) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setSelectedFile(new java.io.File(fileName));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path selected = chooser.getSelectedFile().toPath();
        return selected.getFileName().toString().toLowerCase(Locale.ROOT)
                .endsWith(extension) ? selected
                : selected.resolveSibling(selected.getFileName() + extension);
    }

    private void showExportResult(Path saved, Throwable failure) {
        if (failure != null) {
            statusBar.showFailure(failure);
        } else {
            statusBar.showOperation("保存しました: " + saved);
        }
    }

    private void setBusy(boolean busy) {
        display.setEnabled(!busy);
        startDate.setEnabled(!busy);
        endDate.setEnabled(!busy);
        excludedDates.setEnabled(!busy);
        vesselClass.setEnabled(!busy);
        metric.setEnabled(!busy);
        receiverProfile.setEnabled(!busy);
        analysisProfile.setEnabled(!busy);
        if (busy) {
            csv.setEnabled(false);
            png.setEnabled(false);
        }
    }

    private void selectReceiver(ReceiverProfile receiver) {
        for (int index = 0; index < receiverProfile.getItemCount(); index++) {
            if (receiverProfile.getItemAt(index).profile().id()
                    .equals(receiver.id())) {
                receiverProfile.setSelectedIndex(index);
                return;
            }
        }
    }

    private void selectAnalysisProfile(AnalysisProfile profile) {
        for (int index = 0; index < analysisProfile.getItemCount(); index++) {
            if (analysisProfile.getItemAt(index).profile().id()
                    .equals(profile.id())) {
                analysisProfile.setSelectedIndex(index);
                return;
            }
        }
    }

    private enum ClassChoice {
        ALL("全船舶", Set.of(VesselClass.CLASS_A, VesselClass.CLASS_B)),
        CLASS_A("Class A", Set.of(VesselClass.CLASS_A)),
        CLASS_B("Class B", Set.of(VesselClass.CLASS_B));

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

    private record ReceiverChoice(ReceiverProfile profile) {
        @Override
        public String toString() {
            return profile.name();
        }
    }

    private record AnalysisChoice(AnalysisProfile profile) {
        @Override
        public String toString() {
            return profile.rulesVersion();
        }
    }

    private static final class PerformanceTableModel
            extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "船体長区分", "距離帯", "Class", "受信位置数",
                "推定欠落数", "期待位置数", "欠落率(%)",
                "観測秒", "鮮度違反秒", "鮮度違反率(%)",
                "MMSI数", "観測日数", "標本判定"};
        private List<ShipLengthPerformanceRow> rows = List.of();

        private void setRows(List<ShipLengthPerformanceRow> rows) {
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
            return column >= 3 && column <= 11 ? Number.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ShipLengthPerformanceRow row = rows.get(rowIndex);
            var evaluation = row.evaluation();
            var counts = evaluation.counts();
            return switch (columnIndex) {
                case 0 -> row.shipLengthBand().toString();
                case 1 -> row.distanceBand().label();
                case 2 -> row.vesselClass() == VesselClass.CLASS_A
                        ? "Class A" : "Class B";
                case 3 -> counts.observedCount();
                case 4 -> counts.missingCount();
                case 5 -> counts.expectedCount();
                case 6 -> evaluation.lossRatePercent();
                case 7 -> counts.observedSeconds();
                case 8 -> counts.staleSeconds();
                case 9 -> evaluation.freshnessViolationRatePercent();
                case 10 -> evaluation.distinctVesselCount();
                case 11 -> row.observationDayCount();
                case 12 -> evaluation.hasSufficientData()
                        ? "十分" : "データ不足";
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }
    }
}
