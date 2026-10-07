package ais.ui;

import ais.app.CommunicationModelService;
import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;
import ais.domain.VesselClass;
import ais.export.ExportFileNamer;
import ais.export.ExportService;
import ais.simulation.calibration.CommunicationModelCode;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.calibration.CommunicationParameter;
import ais.simulation.calibration.CommunicationTrainingRequest;
import ais.simulation.calibration.ConfidenceInterval;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
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

public final class CommunicationModelPanel extends JPanel {

    private static final int DEFAULT_BOOTSTRAP_ITERATIONS = 1_000;
    private static final long DEFAULT_BOOTSTRAP_SEED = 20_251_006L;

    private final CommunicationModelService service;
    private final ExportService exports;
    private final ReceiverProfile defaultReceiver;
    private final AnalysisProfile defaultProfile;
    private final StatusBar statusBar;
    private final JTextField startDate = new JTextField(10);
    private final JTextField endDate = new JTextField(10);
    private final JTextField excludedDates = new JTextField(20);
    private final JTextField bootstrapIterations = new JTextField(6);
    private final JTextField bootstrapSeed = new JTextField(10);
    private final JComboBox<ReceiverChoice> receiverProfile =
            new JComboBox<>();
    private final JComboBox<AnalysisChoice> analysisProfile =
            new JComboBox<>();
    private final JComboBox<ModelChoice> savedModels = new JComboBox<>();
    private final JTextField modelName = new JTextField(22);
    private final JTextField notes = new JTextField(28);
    private final JButton preview = new JButton("試算");
    private final JButton save = new JButton("モデルを保存");
    private final JButton exportCsv = new JButton("パラメータCSV保存");
    private final JButton reload = new JButton("保存済み一覧を更新");
    private final JLabel summary = new JLabel(
            "条件を指定して「試算」を押してください");
    private final JTextArea warnings = new JTextArea(5, 80);
    private final ParameterTableModel tableModel =
            new ParameterTableModel();
    private CommunicationModelDraft currentDraft;
    private CommunicationModelSnapshot currentSnapshot;
    private boolean refreshingModels;

    public CommunicationModelPanel(
            CommunicationModelService service,
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
        LocalDate initial = initialDate == null
                ? LocalDate.now() : initialDate;
        startDate.setText(initial.withDayOfMonth(1).toString());
        endDate.setText(initial.toString());
        bootstrapIterations.setText(
                Integer.toString(DEFAULT_BOOTSTRAP_ITERATIONS));
        bootstrapSeed.setText(Long.toString(DEFAULT_BOOTSTRAP_SEED));
        excludedDates.setToolTipText(
                "任意。例: 2025-11-03, 2025-11-04");

        JPanel controls = new JPanel(new GridLayout(0, 1, 0, 2));
        controls.setBorder(BorderFactory.createTitledBorder(
                "CM-E1モデル作成条件"));
        JPanel periodRow = new JPanel(new FlowLayout(
                FlowLayout.LEFT, 6, 3));
        periodRow.add(new JLabel("モデル CM-E1 / 学習期間"));
        periodRow.add(startDate);
        periodRow.add(new JLabel("～"));
        periodRow.add(endDate);
        periodRow.add(new JLabel("除外日"));
        periodRow.add(excludedDates);
        periodRow.add(new JLabel("受信局"));
        periodRow.add(receiverProfile);
        controls.add(periodRow);

        JPanel calculationRow = new JPanel(new FlowLayout(
                FlowLayout.LEFT, 6, 3));
        calculationRow.add(new JLabel("解析条件"));
        calculationRow.add(analysisProfile);
        calculationRow.add(new JLabel("bootstrap反復"));
        calculationRow.add(bootstrapIterations);
        calculationRow.add(new JLabel("seed"));
        calculationRow.add(bootstrapSeed);
        calculationRow.add(preview);
        calculationRow.add(new JLabel("保存済み"));
        savedModels.setPrototypeDisplayValue(ModelChoice.prototype());
        calculationRow.add(savedModels);
        calculationRow.add(reload);
        controls.add(calculationRow);

        JPanel saveRow = new JPanel(new FlowLayout(
                FlowLayout.LEFT, 6, 3));
        saveRow.add(new JLabel("モデル名"));
        saveRow.add(modelName);
        saveRow.add(new JLabel("メモ"));
        saveRow.add(notes);
        saveRow.add(save);
        saveRow.add(exportCsv);
        controls.add(saveRow);
        add(controls, BorderLayout.NORTH);

        warnings.setEditable(false);
        warnings.setLineWrap(true);
        warnings.setWrapStyleWord(true);
        warnings.setBackground(getBackground());
        JPanel information = new JPanel(new BorderLayout(4, 4));
        summary.setBorder(BorderFactory.createEmptyBorder(4, 7, 2, 7));
        information.add(summary, BorderLayout.NORTH);
        JScrollPane warningScroll = new JScrollPane(warnings);
        warningScroll.setBorder(BorderFactory.createTitledBorder(
                "警告・試算条件"));
        information.add(warningScroll, BorderLayout.CENTER);

        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        int[] widths = {80, 70, 75, 75, 75, 65, 65, 85,
                85, 75, 75, 95, 85, 100};
        for (int index = 0; index < widths.length; index++) {
            table.getColumnModel().getColumn(index)
                    .setPreferredWidth(widths[index]);
        }
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder(
                "距離帯×Class別パラメータ"));
        JSplitPane split = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT, information, tableScroll);
        split.setResizeWeight(0.25);
        split.setDividerLocation(190);
        add(split, BorderLayout.CENTER);

        save.setEnabled(false);
        exportCsv.setEnabled(false);
        preview.addActionListener(event -> preview());
        save.addActionListener(event -> save());
        exportCsv.addActionListener(event -> exportCsv());
        reload.addActionListener(event -> refreshSavedModels());
        savedModels.addActionListener(event -> loadSelectedModel());
    }

    public void refreshIfEmpty() {
        if (savedModels.getItemCount() == 0) {
            refreshSavedModels();
        }
    }

    private void preview() {
        CommunicationTrainingRequest request;
        try {
            request = request();
        } catch (RuntimeException failure) {
            statusBar.showFailure(failure);
            return;
        }
        setBusy(true);
        statusBar.showOperation("CM-E1パラメータを試算しています…");
        service.preview(request).whenComplete((draft, failure) ->
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    if (failure != null) {
                        statusBar.showFailure(failure);
                        return;
                    }
                    showDraft(draft);
                    statusBar.showOperation(String.format(
                            "CM-E1を試算しました: 根拠run %,d件 / 直接%,d / 補間%,d",
                            draft.sourceRunIds().size(),
                            draft.directParameterCount(),
                            draft.interpolatedParameterCount()));
                }));
    }

    private CommunicationTrainingRequest request() {
        ReceiverChoice receiver =
                (ReceiverChoice) receiverProfile.getSelectedItem();
        AnalysisChoice profile =
                (AnalysisChoice) analysisProfile.getSelectedItem();
        return new CommunicationTrainingRequest(
                CommunicationModelCode.CM_E1,
                LocalDate.parse(startDate.getText().trim()),
                LocalDate.parse(endDate.getText().trim()),
                ExcludedDateParser.parse(excludedDates.getText()),
                receiver == null ? defaultReceiver.id()
                        : receiver.profile().id(),
                profile == null ? defaultProfile.id()
                        : profile.profile().id(),
                Integer.parseInt(bootstrapIterations.getText().trim()),
                Long.parseLong(bootstrapSeed.getText().trim()));
    }

    private void showDraft(CommunicationModelDraft draft) {
        currentDraft = draft;
        currentSnapshot = null;
        tableModel.setParameters(draft.parameters());
        summary.setText(String.format(
                "試算 / %s～%s / 根拠run %,d件 / 未解析%,d日 / "
                        + "直接%,d / 補間%,d / 適用外%,d",
                draft.request().startDate(), draft.request().endDate(),
                draft.sourceRunIds().size(), draft.missingDates().size(),
                draft.directParameterCount(),
                draft.interpolatedParameterCount(),
                draft.parameters().size() - draft.directParameterCount()
                        - draft.interpolatedParameterCount()));
        warnings.setText(draft.warnings().isEmpty()
                ? "警告はありません"
                : String.join(System.lineSeparator(), draft.warnings()));
        if (modelName.getText().isBlank()) {
            modelName.setText("CM-E1 " + draft.request().startDate()
                    + "～" + draft.request().endDate());
        }
        save.setEnabled(!draft.sourceRunIds().isEmpty()
                && draft.directParameterCount() > 0);
        exportCsv.setEnabled(true);
    }

    private void save() {
        if (currentDraft == null) {
            return;
        }
        String name = modelName.getText().trim();
        if (name.isEmpty()) {
            statusBar.showFailure(new IllegalArgumentException(
                    "モデル名を入力してください"));
            return;
        }
        setBusy(true);
        statusBar.showOperation("通信モデルを保存しています…");
        service.save(currentDraft, name, notes.getText())
                .whenComplete((definition, failure) ->
                        SwingUtilities.invokeLater(() -> {
                            setBusy(false);
                            if (failure != null) {
                                statusBar.showFailure(failure);
                                return;
                            }
                            currentDraft = null;
                            currentSnapshot = null;
                            save.setEnabled(false);
                            exportCsv.setEnabled(false);
                            statusBar.showOperation(String.format(
                                    "通信モデルを保存しました: %s rev.%d",
                                    definition.name(),
                                    definition.revision()));
                            refreshSavedModels(definition);
                        }));
    }

    private void refreshSavedModels() {
        refreshSavedModels(null);
    }

    private void refreshSavedModels(
            CommunicationModelDefinition selectAfterRefresh) {
        reload.setEnabled(false);
        service.findAll().whenComplete((definitions, failure) ->
                SwingUtilities.invokeLater(() -> {
                    reload.setEnabled(true);
                    if (failure != null) {
                        statusBar.showFailure(failure);
                        return;
                    }
                    refreshingModels = true;
                    savedModels.removeAllItems();
                    savedModels.addItem(ModelChoice.placeholder());
                    int selectedIndex = 0;
                    for (CommunicationModelDefinition definition
                            : definitions) {
                        savedModels.addItem(new ModelChoice(definition));
                        if (selectAfterRefresh != null
                                && definition.id().equals(
                                selectAfterRefresh.id())) {
                            selectedIndex = savedModels.getItemCount() - 1;
                        }
                    }
                    savedModels.setSelectedIndex(selectedIndex);
                    refreshingModels = false;
                    if (selectedIndex > 0) {
                        loadSelectedModel();
                    }
                }));
    }

    private void loadSelectedModel() {
        if (refreshingModels) {
            return;
        }
        ModelChoice choice = (ModelChoice) savedModels.getSelectedItem();
        if (choice == null || choice.definition() == null) {
            return;
        }
        setBusy(true);
        statusBar.showOperation("保存済み通信モデルを読み込んでいます…");
        service.load(choice.definition().id())
                .whenComplete((snapshot, failure) ->
                        SwingUtilities.invokeLater(() -> {
                            setBusy(false);
                            if (failure != null) {
                                statusBar.showFailure(failure);
                                return;
                            }
                            showSnapshot(snapshot);
                        }));
    }

    private void showSnapshot(CommunicationModelSnapshot snapshot) {
        currentDraft = null;
        currentSnapshot = snapshot;
        save.setEnabled(false);
        exportCsv.setEnabled(true);
        CommunicationModelDefinition definition = snapshot.definition();
        tableModel.setParameters(snapshot.parameters());
        summary.setText(String.format(
                "保存済み / %s rev.%d / %s～%s / 根拠run %,d件",
                definition.name(), definition.revision(),
                definition.trainingStartDate(),
                definition.trainingEndDate(),
                snapshot.sourceRunIds().size()));
        warnings.setText("モデルID: " + definition.id()
                + System.lineSeparator() + "formula: "
                + definition.formulaVersion()
                + System.lineSeparator() + "除外日: "
                + snapshot.excludedDates()
                + System.lineSeparator() + "作成日時: "
                + definition.createdAt()
                + System.lineSeparator() + "メモ: "
                + (definition.notes() == null ? "" : definition.notes()));
        modelName.setText(definition.name());
        notes.setText(definition.notes() == null ? "" : definition.notes());
        statusBar.showOperation(String.format(
                "保存済み通信モデルを表示しました: %s rev.%d",
                definition.name(), definition.revision()));
    }

    private void exportCsv() {
        if (currentDraft == null && currentSnapshot == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("通信モデルパラメータCSVを保存");
        String suggested = currentDraft != null
                ? new ExportFileNamer().communicationModelCsv(currentDraft)
                : new ExportFileNamer().communicationModelCsv(
                        currentSnapshot);
        chooser.setSelectedFile(new java.io.File(suggested));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path selected = chooser.getSelectedFile().toPath();
        Path target = selected.getFileName().toString().toLowerCase()
                .endsWith(".csv") ? selected
                : selected.resolveSibling(selected.getFileName() + ".csv");
        exportCsv.setEnabled(false);
        var future = currentDraft != null
                ? exports.communicationModelCsv(target, currentDraft)
                : exports.communicationModelCsv(target, currentSnapshot);
        future.whenComplete((saved, failure) ->
                SwingUtilities.invokeLater(() -> {
                    exportCsv.setEnabled(true);
                    if (failure != null) {
                        statusBar.showFailure(failure);
                    } else {
                        statusBar.showOperation("保存しました: " + saved);
                    }
                }));
    }

    private void setBusy(boolean busy) {
        preview.setEnabled(!busy);
        reload.setEnabled(!busy);
        startDate.setEnabled(!busy);
        endDate.setEnabled(!busy);
        excludedDates.setEnabled(!busy);
        bootstrapIterations.setEnabled(!busy);
        bootstrapSeed.setEnabled(!busy);
        receiverProfile.setEnabled(!busy);
        analysisProfile.setEnabled(!busy);
        savedModels.setEnabled(!busy);
        modelName.setEnabled(!busy);
        notes.setEnabled(!busy);
        if (busy) {
            save.setEnabled(false);
            exportCsv.setEnabled(false);
        } else if (currentDraft != null) {
            save.setEnabled(!currentDraft.sourceRunIds().isEmpty()
                    && currentDraft.directParameterCount() > 0);
            exportCsv.setEnabled(true);
        } else if (currentSnapshot != null) {
            exportCsv.setEnabled(true);
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

    private record ModelChoice(CommunicationModelDefinition definition) {
        private static ModelChoice placeholder() {
            return new ModelChoice(null);
        }

        private static ModelChoice prototype() {
            return new ModelChoice(new CommunicationModelDefinition(
                    ais.simulation.calibration.CommunicationModelId.create(),
                    CommunicationModelCode.CM_E1, 999,
                    "CM-E1 長いモデル名の表示幅",
                    new ais.domain.ReceiverProfileId("prototype"),
                    new ais.domain.AnalysisProfileId("prototype"),
                    LocalDate.of(2025, 1, 1),
                    LocalDate.of(2025, 12, 31),
                    "prototype", 1, 1,
                    java.time.Instant.EPOCH, null));
        }

        @Override
        public String toString() {
            return definition == null ? "選択してください"
                    : definition.name() + " / rev."
                    + definition.revision();
        }
    }

    private static final class ParameterTableModel
            extends AbstractTableModel {

        private static final String[] COLUMNS = {
                "距離帯", "Class", "受信", "推定欠落", "期待送信",
                "MMSI", "日数", "生欠落率%", "補正受信率%",
                "CI下限%", "CI上限%", "適用", "適用確率%", "補間元"};

        private List<CommunicationParameter> parameters = List.of();

        private void setParameters(List<CommunicationParameter> values) {
            parameters = List.copyOf(values);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return parameters.size();
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
            return column >= 2 && column <= 10 || column == 12
                    ? Number.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            CommunicationParameter parameter = parameters.get(rowIndex);
            ConfidenceInterval interval = parameter.confidenceInterval();
            return switch (columnIndex) {
                case 0 -> parameter.distanceBand().label();
                case 1 -> parameter.vesselClass() == VesselClass.CLASS_A
                        ? "Class A" : "Class B";
                case 2 -> parameter.observedCount();
                case 3 -> parameter.missingCount();
                case 4 -> parameter.expectedCount();
                case 5 -> parameter.distinctVesselCount();
                case 6 -> parameter.observedDayCount();
                case 7 -> percent(parameter.rawLossRate());
                case 8 -> percent(
                        parameter.jeffreysReceptionProbability());
                case 9 -> interval == null ? null
                        : interval.lower() * 100.0;
                case 10 -> interval == null ? null
                        : interval.upper() * 100.0;
                case 11 -> applicabilityLabel(parameter);
                case 12 -> percent(
                        parameter.appliedReceptionProbability());
                case 13 -> sourceBands(parameter);
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }

        private static Double percent(Double value) {
            return value == null ? null : value * 100.0;
        }

        private static String applicabilityLabel(
                CommunicationParameter parameter) {
            return switch (parameter.applicability()) {
                case DIRECT -> "直接";
                case INTERPOLATED -> "補間";
                case OUT_OF_MODEL -> "適用外";
            };
        }

        private static String sourceBands(
                CommunicationParameter parameter) {
            if (parameter.lowerSourceBandIndex() == null
                    || parameter.upperSourceBandIndex() == null) {
                return "";
            }
            return parameter.lowerSourceBandIndex() + " / "
                    + parameter.upperSourceBandIndex();
        }
    }
}
