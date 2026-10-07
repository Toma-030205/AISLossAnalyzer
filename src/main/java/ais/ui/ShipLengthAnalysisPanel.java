package ais.ui;

import ais.app.AggregateQueryService;
import ais.app.ShipLengthAnalysisRequest;
import ais.app.ShipLengthAnalysisResult;
import ais.app.ShipLengthAnalysisRow;
import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;
import ais.domain.VesselClass;
import ais.export.ExportFileNamer;
import ais.export.ExportService;
import ais.export.ShipLengthHeatmapRenderer;
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
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ShipLengthAnalysisPanel extends JPanel {

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
    private final ShipLengthTableModel tableModel =
            new ShipLengthTableModel();
    private ShipLengthAnalysisResult current;

    public ShipLengthAnalysisPanel(
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
        conditions.add(new JLabel("受信局"));
        conditions.add(receiverProfile);
        conditions.add(new JLabel("解析条件"));
        conditions.add(analysisProfile);
        conditions.add(display);
        add(conditions, BorderLayout.NORTH);

        chartArea.setBorder(BorderFactory.createTitledBorder(
                "船体長別・日別最遠距離帯分布"));
        chartArea.add(new JLabel(
                "保存済み集計の期間を指定して表示してください",
                JLabel.CENTER), BorderLayout.CENTER);

        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        int[] widths = {105, 70, 95, 95, 125, 135,
                120, 110, 120, 110, 90};
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
        JLabel note = new JLabel("<html>1標本はClass別MMSIの1日分です。"
                + "0～70km内の補間・推定位置を含む解析航跡の最遠距離帯を"
                + "比較します。船体長は各観測日までに取得済みのType 5/24"
                + "最新値を使用します。欠落率・鮮度違反率ではなく、船体長は"
                + "アンテナ高の代理変数です。航路・船種・運航範囲の影響を"
                + "含むため、因果関係の証明にはなりません。"
                + "70km外だけに存在した船舶日は含みません。</html>");
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
        ShipLengthAnalysisRequest request;
        try {
            request = request();
        } catch (RuntimeException failure) {
            statusBar.showFailure(failure);
            return;
        }
        setBusy(true);
        statusBar.showOperation("船体長別の解析航跡分布を検索しています…");
        service.queryShipLength(request).whenComplete((result, failure) ->
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
                    summary.setText(String.format(Locale.ROOT,
                            "使用解析run %,d / Class別MMSI標本 %,d / "
                                    + "船体長既知 %,d（%.1f%%）/ "
                                    + "船舶日 %,d（既知 %,d・%.1f%%）",
                            result.analysisRunCount(),
                            result.totalDistinctVesselCount(),
                            result.knownLengthDistinctVesselCount(),
                            result.knownLengthCoveragePercent(),
                            result.totalVesselDayCount(),
                            result.knownLengthVesselDayCount(),
                            result.knownLengthVesselDayCoveragePercent()));
                    statusBar.showOperation(String.format(Locale.ROOT,
                            "船体長別分析を表示しました（%,d船舶日）",
                            result.totalVesselDayCount()));
                }));
    }

    private void updateChart(ShipLengthAnalysisResult result) {
        chartArea.removeAll();
        if (result.rows().isEmpty()) {
            chartArea.add(new JLabel(
                    "指定期間に利用できる保存済み距離データがありません",
                    JLabel.CENTER), BorderLayout.CENTER);
        } else {
            ChartPanel chart = new ChartPanel(
                    new ShipLengthHeatmapRenderer().create(result));
            chart.setMouseWheelEnabled(true);
            chartArea.add(chart, BorderLayout.CENTER);
        }
        chartArea.revalidate();
        chartArea.repaint();
    }

    private ShipLengthAnalysisRequest request() {
        ReceiverChoice receiver =
                (ReceiverChoice) receiverProfile.getSelectedItem();
        AnalysisChoice profile =
                (AnalysisChoice) analysisProfile.getSelectedItem();
        ClassChoice classes = (ClassChoice) vesselClass.getSelectedItem();
        return new ShipLengthAnalysisRequest(
                LocalDate.parse(startDate.getText().trim()),
                LocalDate.parse(endDate.getText().trim()),
                classes == null ? ClassChoice.ALL.classes : classes.classes,
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
                "船体長別分析CSVを保存",
                new ExportFileNamer().shipLengthCsv(current), ".csv");
        if (target == null) {
            return;
        }
        csv.setEnabled(false);
        exports.shipLengthCsv(target, current)
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
                "船体長別グラフPNGを保存",
                new ExportFileNamer().shipLengthChartPng(current), ".png");
        if (target == null) {
            return;
        }
        png.setEnabled(false);
        exports.shipLengthChartPng(target, current)
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

    private static final class ShipLengthTableModel
            extends AbstractTableModel {

        private static final String[] COLUMNS = {
                "船体長区分", "Class", "MMSI数", "船舶日数",
                "最遠帯下限平均(km)", "最遠帯中央値",
                "30km以上船舶日", "30km以上率(%)",
                "50km以上船舶日", "50km以上率(%)", "標本判定"};

        private List<ShipLengthAnalysisRow> rows = List.of();

        private void setRows(List<ShipLengthAnalysisRow> rows) {
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
            return switch (column) {
                case 2, 3, 4, 6, 7, 8, 9 -> Number.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ShipLengthAnalysisRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.shipLengthBand().toString();
                case 1 -> row.vesselClass() == VesselClass.CLASS_A
                        ? "Class A" : "Class B";
                case 2 -> row.distinctVesselCount();
                case 3 -> row.vesselDayCount();
                case 4 -> row.averageDailyMaximumLowerKilometers();
                case 5 -> row.medianDailyMaximumDistanceBand();
                case 6 -> row.atLeastThirtyKilometerVesselDays();
                case 7 -> row.atLeastThirtyKilometerRatePercent();
                case 8 -> row.atLeastFiftyKilometerVesselDays();
                case 9 -> row.atLeastFiftyKilometerRatePercent();
                case 10 -> row.sufficientData() ? "十分" : "データ不足";
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }
    }
}
