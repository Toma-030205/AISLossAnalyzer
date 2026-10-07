package ais.ui;

import ais.app.SimulationValidationService;
import ais.export.ExportFileNamer;
import ais.export.ExportService;
import ais.export.ValidationChartRenderer;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.validation.StatisticalSummary;
import ais.simulation.validation.ValidationCellResult;
import ais.simulation.validation.ValidationMetric;
import ais.simulation.validation.ValidationMetricComparison;
import ais.simulation.validation.ValidationMetricSummary;
import ais.simulation.validation.ValidationRequest;
import ais.simulation.validation.ValidationResult;
import org.jfree.chart.ChartPanel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class ValidationPanel extends JPanel {

    private static final int DEFAULT_ITERATIONS = 30;
    private static final long DEFAULT_SEED_BASE = 42L;

    private final SimulationValidationService service;
    private final ExportService exports;
    private final StatusBar statusBar;
    private final JComboBox<ModelChoice> models = new JComboBox<>();
    private final JTextField startDate = new JTextField("2025-12-01", 10);
    private final JTextField endDate = new JTextField("2025-12-31", 10);
    private final JTextField excludedDates = new JTextField(21);
    private final JTextField iterations = new JTextField(
            Integer.toString(DEFAULT_ITERATIONS), 5);
    private final JTextField seedBase = new JTextField(
            Long.toString(DEFAULT_SEED_BASE), 10);
    private final JCheckBox sensitivity = new JCheckBox(
            "感度確認（12/20・12/26を除外）");
    private final JComboBox<ValidationMetric> metric =
            new JComboBox<>(ValidationMetric.values());
    private final JButton refreshModels = new JButton("モデル一覧更新");
    private final JButton run = new JButton("妥当性確認を実行");
    private final JButton cancel = new JButton("中止");
    private final JButton exportCsv = new JButton("比較表CSV保存");
    private final JButton exportPng = new JButton("現在のグラフPNG保存");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea summary = new JTextArea(4, 100);
    private final JPanel chartArea = new JPanel(new BorderLayout());
    private final ValidationTableModel tableModel =
            new ValidationTableModel();
    private ValidationResult current;
    private boolean refreshing;

    public ValidationPanel(
            SimulationValidationService service,
            ExportService exports,
            StatusBar statusBar) {
        super(new BorderLayout(6, 6));
        this.service = service;
        this.exports = exports;
        this.statusBar = statusBar;

        JPanel top = new JPanel(new GridLayout(0, 1, 0, 2));
        top.setBorder(BorderFactory.createTitledBorder(
                "CM-E1 妥当性確認条件"));
        JPanel period = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        period.add(new JLabel("保存済みモデル"));
        models.setPrototypeDisplayValue(ModelChoice.prototype());
        period.add(models);
        period.add(refreshModels);
        period.add(new JLabel("検証期間"));
        period.add(startDate);
        period.add(new JLabel("～"));
        period.add(endDate);
        period.add(new JLabel("除外日"));
        period.add(excludedDates);
        top.add(period);

        JPanel action = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        action.add(new JLabel("反復回数"));
        action.add(iterations);
        action.add(new JLabel("seed開始値"));
        action.add(seedBase);
        action.add(sensitivity);
        action.add(run);
        action.add(cancel);
        progress.setStringPainted(true);
        progress.setPreferredSize(new java.awt.Dimension(230, 22));
        action.add(progress);
        action.add(new JLabel("表示指標"));
        action.add(metric);
        action.add(exportCsv);
        action.add(exportPng);
        top.add(action);

        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setBackground(getBackground());
        summary.setText("保存済みCM-E1を選び、検証期間を学習期間と重ならないように指定してください。"
                + "正式評価は2025-12-01～2025-12-31・30回です。");
        JScrollPane summaryScroll = new JScrollPane(summary);
        summaryScroll.setBorder(BorderFactory.createTitledBorder(
                "評価要約・警告"));
        top.add(summaryScroll);
        add(top, BorderLayout.NORTH);

        chartArea.add(new JLabel(
                "妥当性確認を実行すると、実測・CM-E1・距離なし基準を表示します",
                JLabel.CENTER), BorderLayout.CENTER);
        chartArea.setBorder(BorderFactory.createTitledBorder(
                "距離帯別比較（エラーバーは固定seed間95%相当範囲）"));

        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        int[] widths = {75, 70, 82, 82, 105, 105, 88, 88,
                82, 82, 90, 76, 76, 76, 92};
        for (int index = 0; index < widths.length; index++) {
            table.getColumnModel().getColumn(index)
                    .setPreferredWidth(widths[index]);
        }
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder(
                "距離帯×Class別比較表"));
        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, chartArea, tableScroll);
        split.setResizeWeight(0.58);
        split.setDividerLocation(410);
        add(split, BorderLayout.CENTER);

        cancel.setEnabled(false);
        exportCsv.setEnabled(false);
        exportPng.setEnabled(false);
        refreshModels.addActionListener(event -> refreshModels());
        run.addActionListener(event -> runValidation());
        cancel.addActionListener(event -> cancelValidation());
        metric.addActionListener(event -> showResult());
        exportCsv.addActionListener(event -> exportCsv());
        exportPng.addActionListener(event -> exportPng());
        sensitivity.addActionListener(event -> applySensitivityPreset());
    }

    public void refreshIfEmpty() {
        if (models.getItemCount() == 0) {
            refreshModels();
        }
    }

    private void refreshModels() {
        refreshModels.setEnabled(false);
        refreshing = true;
        service.findModels().whenComplete((definitions, failure) ->
                SwingUtilities.invokeLater(() -> {
                    refreshing = false;
                    refreshModels.setEnabled(true);
                    if (failure != null) {
                        statusBar.showFailure(failure);
                        return;
                    }
                    models.removeAllItems();
                    models.addItem(ModelChoice.placeholder());
                    definitions.forEach(definition ->
                            models.addItem(new ModelChoice(definition)));
                    if (!definitions.isEmpty()) {
                        models.setSelectedIndex(1);
                    }
                    statusBar.showOperation(
                            "保存済み通信モデルを" + definitions.size()
                                    + "件読み込みました");
                }));
    }

    private void runValidation() {
        ValidationRequest request;
        try {
            request = request();
        } catch (RuntimeException failure) {
            statusBar.showFailure(failure);
            return;
        }
        setRunning(true);
        current = null;
        tableModel.setRows(List.of(), selectedMetric());
        summary.setText("検証を開始しました。1日分の理想送信列を生成し、"
                + "各seedでCM-E1と距離なし基準を順次実行します。");
        statusBar.showOperation("妥当性確認を実行しています…");
        service.validate(request, value -> SwingUtilities.invokeLater(() -> {
            progress.setValue(value.percent());
            progress.setString(String.format(
                    "%d%%  %s  %d/%d回  %s",
                    value.percent(), value.currentDate(),
                    value.iteration(), request.iterationCount(),
                    value.variant()));
        })).whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
            setRunning(false);
            if (failure != null) {
                Throwable cause = rootCause(failure);
                if (cause instanceof CancellationException) {
                    summary.setText("妥当性確認を中止しました。"
                            + "途中結果は正式な検証結果として保存していません。");
                    statusBar.showOperation("妥当性確認を中止しました");
                } else {
                    statusBar.showFailure(failure);
                    summary.setText("妥当性確認に失敗しました: "
                            + cause.getMessage());
                }
                return;
            }
            current = result;
            progress.setValue(100);
            progress.setString("完了");
            showResult();
            statusBar.showOperation("妥当性確認を完了し、SQLiteへ保存しました: "
                    + result.experimentId());
        }));
    }

    private ValidationRequest request() {
        ModelChoice selected = (ModelChoice) models.getSelectedItem();
        if (selected == null || selected.definition == null) {
            throw new IllegalArgumentException(
                    "保存済み通信モデルを選択してください");
        }
        return new ValidationRequest(
                selected.definition.id(),
                LocalDate.parse(startDate.getText().trim()),
                LocalDate.parse(endDate.getText().trim()),
                ExcludedDateParser.parse(excludedDates.getText()),
                Integer.parseInt(iterations.getText().trim()),
                Long.parseLong(seedBase.getText().trim()),
                sensitivity.isSelected());
    }

    private void showResult() {
        if (current == null) {
            return;
        }
        ValidationMetric selected = selectedMetric();
        tableModel.setRows(current.cells(), selected);
        chartArea.removeAll();
        chartArea.add(new ChartPanel(
                new ValidationChartRenderer().create(current, selected)),
                BorderLayout.CENTER);
        chartArea.revalidate();
        chartArea.repaint();
        ValidationMetricSummary value = current.summary(selected);
        String warningText = current.warnings().isEmpty()
                ? "なし" : String.join(" / ", current.warnings());
        summary.setText(String.format(
                "%s / CM-E1重み付きMAE %s pp / 距離なし基準 %s pp / "
                        + "改善率 %s%% / 実測変動範囲内 %d/%dセル / "
                        + "25～35km移行 %s / Class差 %s%n"
                        + "実験ID %s / fingerprint %s%n警告: %s",
                selected,
                number(value.modelWeightedMaePoints()),
                number(value.baselineWeightedMaePoints()),
                number(value.improvementPercent()),
                value.withinObservedVariationCount(),
                value.comparableCellCount(),
                value.transitionTrend(), value.classDifferenceTrend(),
                current.experimentId(), current.inputFingerprint(),
                warningText));
        exportCsv.setEnabled(true);
        exportPng.setEnabled(true);
    }

    private void cancelValidation() {
        if (service.cancel()) {
            cancel.setEnabled(false);
            progress.setString("中止処理中…");
            statusBar.showOperation("妥当性確認を中止しています…");
        }
    }

    private void applySensitivityPreset() {
        if (sensitivity.isSelected()
                && startDate.getText().trim().equals("2025-12-01")
                && endDate.getText().trim().equals("2025-12-31")) {
            excludedDates.setText("2025-12-20, 2025-12-26");
        } else if (!sensitivity.isSelected()
                && excludedDates.getText().trim().equals(
                "2025-12-20, 2025-12-26")) {
            excludedDates.setText("");
        }
    }

    private void exportCsv() {
        if (current == null) {
            return;
        }
        Path target = chooseSave("妥当性確認CSVを保存",
                new ExportFileNamer().validationCsv(current), "csv");
        if (target != null) {
            exportCsv.setEnabled(false);
            exports.validationCsv(target, current)
                    .whenComplete((saved, failure) ->
                            exportFinished(saved, failure));
        }
    }

    private void exportPng() {
        if (current == null) {
            return;
        }
        ValidationMetric selected = selectedMetric();
        Path target = chooseSave("妥当性確認グラフPNGを保存",
                new ExportFileNamer().validationChartPng(
                        current, selected), "png");
        if (target != null) {
            exportPng.setEnabled(false);
            exports.validationChartPng(target, current, selected)
                    .whenComplete((saved, failure) ->
                            exportFinished(saved, failure));
        }
    }

    private void exportFinished(Path saved, Throwable failure) {
        SwingUtilities.invokeLater(() -> {
            exportCsv.setEnabled(current != null);
            exportPng.setEnabled(current != null);
            if (failure != null) {
                statusBar.showFailure(failure);
            } else {
                statusBar.showOperation("保存しました: " + saved);
            }
        });
    }

    private Path chooseSave(String title, String suggested, String extension) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setSelectedFile(new java.io.File(suggested));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path selected = chooser.getSelectedFile().toPath();
        return selected.getFileName().toString().toLowerCase()
                .endsWith("." + extension) ? selected
                : selected.resolveSibling(
                selected.getFileName() + "." + extension);
    }

    private void setRunning(boolean value) {
        models.setEnabled(!value);
        startDate.setEnabled(!value);
        endDate.setEnabled(!value);
        excludedDates.setEnabled(!value);
        iterations.setEnabled(!value);
        seedBase.setEnabled(!value);
        sensitivity.setEnabled(!value);
        refreshModels.setEnabled(!value && !refreshing);
        run.setEnabled(!value);
        cancel.setEnabled(value);
        exportCsv.setEnabled(!value && current != null);
        exportPng.setEnabled(!value && current != null);
        if (value) {
            progress.setValue(0);
            progress.setString("準備中…");
        }
    }

    private ValidationMetric selectedMetric() {
        ValidationMetric selected =
                (ValidationMetric) metric.getSelectedItem();
        return selected == null ? ValidationMetric.ESTIMATED_LOSS : selected;
    }

    private static String number(Double value) {
        return value == null ? "—" : String.format("%.3f", value);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record ModelChoice(CommunicationModelDefinition definition) {
        private static ModelChoice placeholder() {
            return new ModelChoice(null);
        }

        private static ModelChoice prototype() {
            return new ModelChoice(new CommunicationModelDefinition(
                    ais.simulation.calibration.CommunicationModelId.parse(
                            "00000000-0000-0000-0000-000000000000"),
                    ais.simulation.calibration.CommunicationModelCode.CM_E1,
                    999, "CM-E1 検証用保存モデル（長い表示幅）",
                    new ais.domain.ReceiverProfileId("receiver"),
                    new ais.domain.AnalysisProfileId("profile"),
                    LocalDate.of(2025, 11, 1),
                    LocalDate.of(2025, 11, 30), "formula", 1000, 42,
                    java.time.Instant.EPOCH, null));
        }

        @Override
        public String toString() {
            return definition == null ? "選択してください"
                    : definition.name() + " / rev."
                    + definition.revision() + " / 学習 "
                    + definition.trainingStartDate() + "～"
                    + definition.trainingEndDate();
        }
    }

    private static final class ValidationTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {
                "距離帯", "Class", "実測%", "CM-E1平均%",
                "CM-E1 95%下限", "CM-E1 95%上限", "差pp",
                "基準平均%", "基準差pp", "期待送信数", "MMSI数",
                "観測日数", "実測日別下限", "実測日別上限", "判定"};
        private List<ValidationCellResult> rows = List.of();
        private ValidationMetric metric = ValidationMetric.ESTIMATED_LOSS;

        private void setRows(
                List<ValidationCellResult> rows,
                ValidationMetric metric) {
            this.rows = List.copyOf(rows);
            this.metric = metric;
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
            return column >= 2 && column <= 13 ? Number.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ValidationCellResult row = rows.get(rowIndex);
            ValidationMetricComparison value = row.comparison(metric);
            StatisticalSummary simulation = value.simulation();
            StatisticalSummary baseline = value.baseline();
            StatisticalSummary observed = value.observedDailyRange();
            return switch (columnIndex) {
                case 0 -> row.key().distanceBand().label();
                case 1 -> row.key().vesselClass() ==
                        ais.domain.VesselClass.CLASS_A ? "Class A" : "Class B";
                case 2 -> value.observedPercent();
                case 3 -> simulation == null ? null : simulation.mean();
                case 4 -> simulation == null ? null : simulation.lower95();
                case 5 -> simulation == null ? null : simulation.upper95();
                case 6 -> value.simulationDifferencePoints();
                case 7 -> baseline == null ? null : baseline.mean();
                case 8 -> value.baselineDifferencePoints();
                case 9 -> row.observedCounts().expectedCount();
                case 10 -> row.distinctVesselCount();
                case 11 -> row.observationDayCount();
                case 12 -> observed == null ? null : observed.lower95();
                case 13 -> observed == null ? null : observed.upper95();
                case 14 -> value.status().toString();
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }
    }
}
