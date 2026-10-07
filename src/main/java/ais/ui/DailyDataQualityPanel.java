package ais.ui;

import ais.app.AggregateQueryService;
import ais.app.DailyDataQualityRequest;
import ais.app.DailyDataQualityResult;
import ais.app.DailyDataQualityRow;
import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;
import ais.export.ExportFileNamer;
import ais.export.ExportService;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class DailyDataQualityPanel extends JPanel {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm");

    private final AggregateQueryService service;
    private final ExportService exports;
    private final ReceiverProfile defaultReceiver;
    private final AnalysisProfile defaultProfile;
    private final StatusBar statusBar;
    private final JTextField startDate = new JTextField(10);
    private final JTextField endDate = new JTextField(10);
    private final JComboBox<ReceiverChoice> receiverProfile =
            new JComboBox<>();
    private final JComboBox<AnalysisChoice> analysisProfile =
            new JComboBox<>();
    private final JButton display = new JButton("表示");
    private final JButton csv = new JButton("品質一覧CSV保存");
    private final JLabel summary = new JLabel("期間を指定して表示してください");
    private final QualityTableModel tableModel = new QualityTableModel();
    private DailyDataQualityResult current;

    public DailyDataQualityPanel(
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
        conditions.add(new JLabel("受信局"));
        conditions.add(receiverProfile);
        conditions.add(new JLabel("解析条件"));
        conditions.add(analysisProfile);
        conditions.add(display);
        add(conditions, BorderLayout.NORTH);

        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        int[] widths = {95, 85, 220, 55, 85, 70, 70, 85, 95, 105,
                105, 90, 75, 95, 85, 125, 75, 75, 280};
        for (int index = 0; index < widths.length; index++) {
            table.getColumnModel().getColumn(index)
                    .setPreferredWidth(widths[index]);
        }

        JPanel heading = new JPanel(new BorderLayout());
        summary.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        heading.add(summary, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(csv);
        heading.add(actions, BorderLayout.EAST);

        JPanel contents = new JPanel(new BorderLayout(4, 4));
        contents.add(heading, BorderLayout.NORTH);
        contents.add(new JScrollPane(table), BorderLayout.CENTER);
        JLabel note = new JLabel("<html>解析5分枠は0～70 kmの保存済み距離集計を基準にします。"
                + "「30分以上間隔」は船舶ごとの受信間隔であり、"
                + "受信局停止を直接示す値ではありません。</html>");
        note.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
        contents.add(note, BorderLayout.SOUTH);
        add(contents, BorderLayout.CENTER);

        display.addActionListener(event -> query());
        csv.addActionListener(event -> exportCsv());
        csv.setEnabled(false);
    }

    public void refreshIfEmpty() {
        if (current == null) {
            query();
        }
    }

    private void query() {
        DailyDataQualityRequest request;
        try {
            request = request();
        } catch (RuntimeException failure) {
            statusBar.showFailure(failure);
            return;
        }
        setBusy(true);
        statusBar.showOperation("日別データ品質を検索しています…");
        service.queryQuality(request).whenComplete((result, failure) ->
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    if (failure != null) {
                        statusBar.showFailure(failure);
                        return;
                    }
                    current = result;
                    tableModel.setRows(result.rows());
                    csv.setEnabled(!result.rows().isEmpty());
                    summary.setText(String.format(
                            "対象%,d日 / 解析済み%,d日 / 未解析%,d日 / 要確認%,d日",
                            result.rows().size(), result.analyzedDayCount(),
                            result.missingDayCount(), result.reviewDayCount()));
                    statusBar.showOperation(String.format(
                            "日別データ品質%,d日分を表示しました",
                            result.rows().size()));
                }));
    }

    private DailyDataQualityRequest request() {
        ReceiverChoice receiver =
                (ReceiverChoice) receiverProfile.getSelectedItem();
        AnalysisChoice profile =
                (AnalysisChoice) analysisProfile.getSelectedItem();
        return new DailyDataQualityRequest(
                LocalDate.parse(startDate.getText().trim()),
                LocalDate.parse(endDate.getText().trim()),
                receiver == null ? defaultReceiver.id()
                        : receiver.profile().id(),
                profile == null ? defaultProfile.id()
                        : profile.profile().id());
    }

    private void exportCsv() {
        if (current == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("日別データ品質CSVを保存");
        chooser.setSelectedFile(new java.io.File(
                new ExportFileNamer().dataQualityCsv(current)));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path selected = chooser.getSelectedFile().toPath();
        Path target = selected.getFileName().toString().toLowerCase()
                .endsWith(".csv") ? selected
                : selected.resolveSibling(selected.getFileName() + ".csv");
        csv.setEnabled(false);
        exports.dataQualityCsv(target, current).whenComplete((saved, failure) ->
                SwingUtilities.invokeLater(() -> {
                    csv.setEnabled(true);
                    if (failure != null) {
                        statusBar.showFailure(failure);
                    } else {
                        statusBar.showOperation("保存しました: " + saved);
                    }
                }));
    }

    private void setBusy(boolean busy) {
        display.setEnabled(!busy);
        startDate.setEnabled(!busy);
        endDate.setEnabled(!busy);
        receiverProfile.setEnabled(!busy);
        analysisProfile.setEnabled(!busy);
        if (busy) {
            csv.setEnabled(false);
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

    private static final class QualityTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {
                "日付", "5分枠判定", "入力ファイル", "数", "展開MB",
                "解析枠開始", "解析枠終了", "5分枠", "0-70km船舶数",
                "正常デコードAIS", "採用区間", "推定欠落", "重複",
                "入力形式/復号", "位置利用不可", "船舶別30分以上間隔",
                "距離飛び", "70km外", "注意"};

        private List<DailyDataQualityRow> rows = List.of();

        private void setRows(List<DailyDataQualityRow> rows) {
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
            return column >= 3 && column <= 17
                    && column != 5 && column != 6 && column != 7
                    ? Number.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DailyDataQualityRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.date().toString();
                case 1 -> row.state().toString();
                case 2 -> row.inputName();
                case 3 -> row.inputFileCount();
                case 4 -> row.inputUncompressedBytes() / 1_048_576.0;
                case 5 -> localTime(row.firstAggregateBucket());
                case 6 -> localTime(row.lastAggregateBucket());
                case 7 -> row.aggregateBucketCount() + "/"
                        + DailyDataQualityRow.EXPECTED_BUCKETS_PER_DAY;
                case 8 -> row.distinctVesselCount();
                case 9 -> row.decodedAisEventCount();
                case 10 -> row.acceptedIntervalCount();
                case 11 -> row.estimatedMissingCount();
                case 12 -> row.duplicateCount();
                case 13 -> row.inputAnomalyCount();
                case 14 -> row.invalidPositionCount();
                case 15 -> row.thirtyMinuteGapCount();
                case 16 -> row.distanceJumpCount();
                case 17 -> row.outsideDistanceRangeCount();
                case 18 -> row.note();
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }

        private static String localTime(Instant value) {
            return value == null ? "" : TIME.format(value.atZone(JAPAN));
        }
    }
}
