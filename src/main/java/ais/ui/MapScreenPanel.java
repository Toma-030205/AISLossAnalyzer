package ais.ui;

import ais.analysis.AnalysisFilter;
import ais.app.HistoricalAnalysisService;
import ais.app.HistoricalBatchProgress;
import ais.app.HistoricalBatchResult;
import ais.app.LiveAnalysisService;
import ais.app.LiveFrame;
import ais.app.LiveState;
import ais.app.CurrentAggregateFactory;
import ais.app.ReplayFrame;
import ais.app.ReplayState;
import ais.app.SimulationFrame;
import ais.app.SimulationPlaybackService;
import ais.app.SimulationPlaybackState;
import ais.domain.VesselClass;
import ais.input.history.HistoricalDaySelection;
import ais.input.history.HistoricalSelectionFactory;
import ais.spatial.GridCellId;
import ais.ui.viewmodel.HeatmapMetric;
import ais.ui.viewmodel.MapViewModel;
import ais.ui.viewmodel.MapViewModelFactory;
import ais.ui.viewmodel.SimulationOverlayViewModel;
import ais.ui.viewmodel.SimulationOverlayViewModelFactory;
import ais.export.ExportFileNamer;
import ais.export.ExportService;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class MapScreenPanel extends JPanel {

    public enum Mode {
        HISTORY,
        LIVE,
        SIMULATION
    }

    private static final int PLAYBACK_TICK_MILLIS = 250;
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final HistoricalAnalysisService service;
    private final LiveAnalysisService liveService;
    private final SimulationPlaybackService simulationService;
    private final ExportService exports;
    private final Map<LocalDate, List<Path>> filesByDate;
    private final StatusBar statusBar;
    private final MapCanvas canvas;
    private final HistoricalControlPanel historicalControls;
    private final LiveControlPanel liveControls;
    private final SimulationControlPanel simulationControls;
    private final JPanel modeControls = new JPanel(new CardLayout());
    private final DisplayOptionsPanel displayOptions;
    private final SelectionDetailPanel selectionDetails =
            new SelectionDetailPanel();
    private final MapViewModelFactory viewModels =
            new MapViewModelFactory();
    private final SimulationOverlayViewModelFactory simulationViewModels =
            new SimulationOverlayViewModelFactory();
    private final Timer playbackTimer;

    private ReplayFrame currentFrame;
    private LiveFrame currentLiveFrame;
    private SimulationFrame currentSimulationFrame;
    private HeatmapMetric metric = HeatmapMetric.FRESHNESS_VIOLATION;
    private Integer selectedMmsi;
    private GridCellId selectedCell;
    private boolean playing;
    private boolean tickInFlight;
    private Mode mode = Mode.HISTORY;
    private Consumer<Boolean> liveActivityListener = ignored -> { };

    public MapScreenPanel(
            ais.map.MapDataset mapDataset,
            Map<LocalDate, List<Path>> filesByDate,
            HistoricalAnalysisService service,
            LiveAnalysisService liveService,
            SimulationPlaybackService simulationService,
            ExportService exports,
            StatusBar statusBar) {
        super(new BorderLayout());
        this.filesByDate = Map.copyOf(filesByDate);
        this.service = service;
        this.liveService = liveService;
        this.simulationService = simulationService;
        this.exports = exports;
        this.statusBar = statusBar;
        canvas = new MapCanvas(mapDataset);
        historicalControls = new HistoricalControlPanel(
                filesByDate.keySet().stream().sorted().toList(),
                new HistoricalActions());
        liveControls = new LiveControlPanel(
                liveService.endpointLabel(), new LiveActions());
        simulationControls = new SimulationControlPanel(
                filesByDate.keySet().stream().sorted().toList(),
                new SimulationActions());
        displayOptions = new DisplayOptionsPanel(new DisplayActions());
        displayOptions.setTruthVisibilityListener(ignored ->
                refreshViewModel());
        MapToolbar toolbar = new MapToolbar(canvas, selected -> {
            metric = selected;
            refreshViewModel();
        }, this::exportMap);

        JPanel mapArea = new JPanel(new BorderLayout());
        mapArea.add(toolbar, BorderLayout.NORTH);
        mapArea.add(canvas, BorderLayout.CENTER);

        JPanel fixedControls = new JPanel();
        fixedControls.setLayout(new BoxLayout(
                fixedControls, BoxLayout.Y_AXIS));
        modeControls.add(historicalControls, Mode.HISTORY.name());
        modeControls.add(liveControls, Mode.LIVE.name());
        modeControls.add(simulationControls, Mode.SIMULATION.name());
        modeControls.setMaximumSize(new Dimension(
                Integer.MAX_VALUE, modeControls.getPreferredSize().height));
        fixedControls.add(modeControls);
        fixedControls.add(displayOptions);

        JPanel right = new JPanel(new BorderLayout());
        right.setPreferredSize(new Dimension(420, 700));
        right.setMinimumSize(new Dimension(360, 300));
        right.add(fixedControls, BorderLayout.NORTH);
        right.add(selectionDetails, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, mapArea, right);
        split.setResizeWeight(0.70);
        split.setDividerLocation(0.70);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);

        canvas.setSelectionListeners(
                this::selectVessel, this::selectGrid, this::clearSelection);
        playbackTimer = new Timer(
                PLAYBACK_TICK_MILLIS, event -> playbackTick());
        currentLiveFrame = liveService.currentFrame();
        liveControls.update(currentLiveFrame);
        liveService.setFrameListener(frame -> SwingUtilities.invokeLater(
                () -> applyLiveFrame(frame)));
    }

    public void stopPlayback() {
        playing = false;
        playbackTimer.stop();
        historicalControls.setPlaying(false);
        simulationControls.setPlaying(false);
    }

    public void setMode(Mode mode) {
        if (mode != Mode.LIVE && liveService.isReceiving()) {
            throw new IllegalStateException(
                    "受信を停止してから画面を切り替えてください");
        }
        this.mode = mode;
        stopPlayback();
        ((CardLayout) modeControls.getLayout()).show(
                modeControls, mode.name());
        displayOptions.setSimulationMode(mode == Mode.SIMULATION);
        if (mode == Mode.SIMULATION) {
            refreshSimulationModels();
            if (currentSimulationFrame == null) {
                statusBar.showSimulationIdle();
            }
        }
        selectedMmsi = null;
        selectedCell = null;
        refreshViewModel();
    }

    public Mode mode() {
        return mode;
    }

    public boolean isLiveReceiving() {
        return liveService.isReceiving();
    }

    public boolean isHistoricalBatchRunning() {
        return service.isBatchAnalysisRunning();
    }

    public void cancelHistoricalBatch() {
        cancelBatch();
    }

    public CompletableFuture<LiveFrame> stopLive() {
        return liveService.stop();
    }

    public void setLiveActivityListener(Consumer<Boolean> listener) {
        liveActivityListener = listener;
    }

    private void loadSelection(HistoricalDaySelection selection) {
        stopPlayback();
        historicalControls.setBusy(true);
        statusBar.showOperation("AISログを読み込んでいます…");
        service.load(selection, count -> SwingUtilities.invokeLater(
                        () -> statusBar.showOperation(
                                String.format("%,d件読込中…", count))))
                .whenComplete((frame, failure) -> SwingUtilities.invokeLater(
                        () -> {
                            historicalControls.setBusy(false);
                            if (failure != null) {
                                statusBar.showFailure(failure);
                                return;
                            }
                            selectedMmsi = null;
                            selectedCell = null;
                            applyFrame(frame);
                        }));
    }

    private void applyFrame(ReplayFrame frame) {
        currentFrame = frame;
        historicalControls.updateFrame(frame);
        if (frame.state() == ReplayState.END) {
            stopPlayback();
        }
        refreshViewModel();
    }

    private void refreshSimulationModels() {
        simulationService.findModels().whenComplete((models, failure) ->
                SwingUtilities.invokeLater(() -> {
                    if (failure != null) {
                        statusBar.showFailure(failure);
                    } else {
                        simulationControls.updateModels(models);
                    }
                }));
    }

    private void loadSimulation(
            HistoricalDaySelection selection,
            ais.simulation.calibration.CommunicationModelId modelId,
            long seed) {
        stopPlayback();
        simulationControls.setBusy(true);
        statusBar.showOperation(
                "AISログから理想送信列を生成しています…");
        simulationService.load(
                        selection, modelId, seed,
                        count -> SwingUtilities.invokeLater(() ->
                                statusBar.showOperation(String.format(
                                        "シミュレーション入力%,d件読込中…",
                                        count))))
                .whenComplete((frame, failure) ->
                        SwingUtilities.invokeLater(() -> {
                            simulationControls.setBusy(false);
                            if (failure != null) {
                                statusBar.showFailure(failure);
                                return;
                            }
                            selectedMmsi = null;
                            selectedCell = null;
                            applySimulationFrame(frame);
                        }));
    }

    private void applySimulationFrame(SimulationFrame frame) {
        currentSimulationFrame = frame;
        simulationControls.updateFrame(frame);
        if (frame.state() == SimulationPlaybackState.END) {
            stopPlayback();
        }
        refreshViewModel();
    }

    private void refreshViewModel() {
        ais.analysis.AnalysisSnapshot snapshot = switch (mode) {
            case HISTORY -> currentFrame == null
                    ? null : currentFrame.snapshot();
            case LIVE -> currentLiveFrame == null
                    ? null : currentLiveFrame.snapshot();
            case SIMULATION -> currentSimulationFrame == null
                    ? null : currentSimulationFrame.receivedSnapshot();
        };
        if (snapshot == null) {
            canvas.setViewModel(null);
            canvas.setSimulationOverlay(null);
            selectionDetails.update(null, null);
            if (mode == Mode.LIVE && currentLiveFrame != null) {
                statusBar.update(currentLiveFrame, 0);
            } else if (mode == Mode.SIMULATION) {
                statusBar.showSimulationIdle();
            }
            return;
        }
        long diagnosticCount = switch (mode) {
            case HISTORY -> currentFrame.dataset() == null ? 0
                    : currentFrame.dataset().diagnostics().size();
            case LIVE -> currentLiveFrame.diagnosticCount();
            case SIMULATION -> currentSimulationFrame.diagnostics()
                    .outOfModelCount();
        };
        MapViewModel model = viewModels.create(
                snapshot, metric, selectedMmsi,
                selectedCell, diagnosticCount);
        boolean selectedTruthExists = selectedMmsi != null
                && mode == Mode.SIMULATION
                && currentSimulationFrame.truthStates()
                .containsKey(selectedMmsi);
        if (selectedMmsi != null && model.selectedMmsi() == null
                && !selectedTruthExists) {
            selectedMmsi = null;
            statusBar.showOperation(
                    "10分以上更新がないため船舶を非表示にしました");
            model = viewModels.create(
                    snapshot, metric, null,
                    selectedCell, diagnosticCount);
        }
        if (selectedCell != null && model.selectedCell() == null) {
            selectedCell = null;
        }
        canvas.setViewModel(model);
        SimulationOverlayViewModel overlay = mode == Mode.SIMULATION
                && displayOptions.isTruthVisible()
                ? simulationViewModels.create(
                        currentSimulationFrame, selectedMmsi)
                : null;
        canvas.setSimulationOverlay(overlay);
        selectionDetails.update(
                model.selectedVessel(), model.selectedGrid(),
                overlay == null ? null : overlay.selectedVessel());
        switch (mode) {
            case HISTORY -> statusBar.update(
                    currentFrame, model.vessels().size(), diagnosticCount);
            case LIVE -> statusBar.update(
                    currentLiveFrame, model.vessels().size());
            case SIMULATION -> statusBar.update(
                    currentSimulationFrame,
                    currentSimulationFrame.truthStates().size());
        }
    }

    private void applyLiveFrame(LiveFrame frame) {
        currentLiveFrame = frame;
        liveControls.update(frame);
        liveActivityListener.accept(liveService.isReceiving());
        if (mode == Mode.LIVE) {
            refreshViewModel();
        }
    }

    private void playbackTick() {
        if (!playing || tickInFlight) {
            return;
        }
        tickInFlight = true;
        if (mode == Mode.SIMULATION) {
            simulationService.advance(simulationControls.tickDuration(
                            PLAYBACK_TICK_MILLIS))
                    .whenComplete((frame, failure) ->
                            SwingUtilities.invokeLater(() -> {
                                tickInFlight = false;
                                if (failure != null) {
                                    stopPlayback();
                                    statusBar.showFailure(failure);
                                } else {
                                    applySimulationFrame(frame);
                                }
                            }));
        } else {
            service.advance(historicalControls.tickDuration(
                            PLAYBACK_TICK_MILLIS))
                    .whenComplete((frame, failure) ->
                            SwingUtilities.invokeLater(() -> {
                                tickInFlight = false;
                                if (failure != null) {
                                    stopPlayback();
                                    statusBar.showFailure(failure);
                                } else {
                                    applyFrame(frame);
                                }
                            }));
        }
    }

    private void togglePlayback() {
        if (mode == Mode.SIMULATION) {
            toggleSimulationPlayback();
            return;
        }
        if (currentFrame == null || currentFrame.snapshot() == null) {
            return;
        }
        if (playing) {
            stopPlayback();
            service.pause().whenComplete((frame, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        if (failure != null) {
                            statusBar.showFailure(failure);
                        } else {
                            applyFrame(frame);
                        }
                    }));
            return;
        }
        if (currentFrame.state() == ReplayState.END) {
            historicalControls.setBusy(true);
            service.restart().whenComplete((frame, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        historicalControls.setBusy(false);
                        if (failure != null) {
                            statusBar.showFailure(failure);
                        } else {
                            applyFrame(frame);
                            startPlayback();
                        }
                    }));
        } else {
            startPlayback();
        }
    }

    private void toggleSimulationPlayback() {
        if (currentSimulationFrame == null
                || currentSimulationFrame.receivedSnapshot() == null) {
            return;
        }
        if (playing) {
            stopPlayback();
            simulationService.pause().whenComplete((frame, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        if (failure != null) {
                            statusBar.showFailure(failure);
                        } else {
                            applySimulationFrame(frame);
                        }
                    }));
            return;
        }
        if (currentSimulationFrame.state() == SimulationPlaybackState.END) {
            simulationControls.setBusy(true);
            simulationService.restart().whenComplete((frame, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        simulationControls.setBusy(false);
                        if (failure != null) {
                            statusBar.showFailure(failure);
                        } else {
                            applySimulationFrame(frame);
                            startPlayback();
                        }
                    }));
        } else {
            startPlayback();
        }
    }

    private void startPlayback() {
        playing = true;
        if (mode == Mode.SIMULATION) {
            simulationControls.setPlaying(true);
        } else {
            historicalControls.setPlaying(true);
        }
        playbackTimer.start();
    }

    private void seek(Instant time) {
        if (mode == Mode.SIMULATION) {
            seekSimulation(time);
            return;
        }
        stopPlayback();
        historicalControls.setBusy(true);
        statusBar.showOperation("指定時刻まで再計算しています…");
        service.seek(time).whenComplete((frame, failure) ->
                SwingUtilities.invokeLater(() -> {
                    historicalControls.setBusy(false);
                    if (failure != null) {
                        statusBar.showFailure(failure);
                    } else {
                        applyFrame(frame);
                    }
                }));
    }

    private void seekSimulation(Instant time) {
        stopPlayback();
        simulationControls.setBusy(true);
        statusBar.showOperation(
                "指定時刻まで同じseedで再計算しています…");
        simulationService.seek(time).whenComplete((frame, failure) ->
                SwingUtilities.invokeLater(() -> {
                    simulationControls.setBusy(false);
                    if (failure != null) {
                        statusBar.showFailure(failure);
                    } else {
                        applySimulationFrame(frame);
                    }
                }));
    }

    private void analyzeAndSave() {
        stopPlayback();
        historicalControls.setBusy(true);
        statusBar.showOperation(
                "表示位置を維持して一日分を解析・保存しています…");
        service.analyzeAndSave().whenComplete((result, failure) ->
                SwingUtilities.invokeLater(() -> {
                    historicalControls.setBusy(false);
                    if (failure != null) {
                        statusBar.showFailure(failure);
                    } else {
                        statusBar.showOperation(String.format(
                                "%sの集計を保存しました（受信区間%,d件）",
                                currentFrame.dataset().selection().date(),
                                result.summary().acceptedIntervalCount()));
                    }
                }));
    }

    private void analyzeRange() {
        List<LocalDate> availableDates = filesByDate.keySet().stream()
                .sorted()
                .toList();
        if (availableDates.isEmpty()) {
            statusBar.showOperation("一括解析できる過去ログがありません");
            return;
        }

        JComboBox<LocalDate> from = new JComboBox<>(
                availableDates.toArray(LocalDate[]::new));
        JComboBox<LocalDate> to = new JComboBox<>(
                availableDates.toArray(LocalDate[]::new));
        LocalDate selected = historicalControls.selectedDate();
        LocalDate defaultEnd = selected == null
                ? availableDates.getLast() : selected;
        LocalDate monthStart = defaultEnd.withDayOfMonth(1);
        LocalDate defaultStart = availableDates.stream()
                .filter(value -> !value.isBefore(monthStart)
                        && !value.isAfter(defaultEnd))
                .findFirst()
                .orElse(defaultEnd);
        from.setSelectedItem(defaultStart);
        to.setSelectedItem(defaultEnd);
        JCheckBox skipSaved = new JCheckBox(
                "同じ入力・受信局・解析条件で保存済みの日はスキップ",
                true);
        JPanel fields = new JPanel(new GridLayout(0, 2, 8, 6));
        fields.add(new JLabel("開始日:"));
        fields.add(from);
        fields.add(new JLabel("終了日:"));
        fields.add(to);
        fields.add(new JLabel("保存済み:"));
        fields.add(skipSaved);

        int answer = JOptionPane.showConfirmDialog(
                this, fields, "期間を一括解析・保存",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        LocalDate startDate = (LocalDate) from.getSelectedItem();
        LocalDate endDate = (LocalDate) to.getSelectedItem();
        if (startDate == null || endDate == null
                || endDate.isBefore(startDate)) {
            JOptionPane.showMessageDialog(this,
                    "開始日は終了日以前にしてください。",
                    "日付範囲を確認してください",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<HistoricalDaySelection> selections = availableDates.stream()
                .filter(value -> !value.isBefore(startDate)
                        && !value.isAfter(endDate))
                .map(value -> new HistoricalDaySelection(
                        value, filesByDate.get(value), false))
                .toList();
        if (selections.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "指定期間内に解析できるログがありません。",
                    "対象なし", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int confirmation = JOptionPane.showConfirmDialog(
                this,
                String.format(
                        "%s ～ %s のログ%d日分を日付順に解析し、"
                                + "SQLiteへ保存します。\n"
                                + "完了した日は途中で中止しても残ります。",
                        startDate, endDate, selections.size()),
                "一括解析を開始しますか？",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (confirmation != JOptionPane.OK_OPTION) {
            return;
        }

        stopPlayback();
        historicalControls.setBatchRunning(true);
        liveActivityListener.accept(true);
        statusBar.showOperation(String.format(
                "一括解析を開始しました（%d日分）", selections.size()));
        service.analyzeAndSaveBatch(
                        selections, skipSaved.isSelected(),
                        progress -> SwingUtilities.invokeLater(
                                () -> showBatchProgress(progress)))
                .whenComplete((result, failure) ->
                        SwingUtilities.invokeLater(() -> {
                            historicalControls.setBatchRunning(false);
                            liveActivityListener.accept(
                                    liveService.isReceiving());
                            if (failure != null) {
                                statusBar.showFailure(unwrap(failure));
                                return;
                            }
                            showBatchResult(result, startDate, endDate);
                        }));
    }

    private void showBatchProgress(HistoricalBatchProgress progress) {
        String records = progress.processedRecords() == 0 ? ""
                : String.format(" / %,d件", progress.processedRecords());
        statusBar.showOperation(String.format(
                "一括解析 %d/%d日: %s %s%s"
                        + "（保存%d・スキップ%d・失敗%d）",
                progress.dayNumber(), progress.totalDays(),
                progress.date(), progress.stage(), records,
                progress.savedDays(), progress.skippedDays(),
                progress.failedDays()));
    }

    private void showBatchResult(HistoricalBatchResult result,
                                 LocalDate startDate,
                                 LocalDate endDate) {
        StringBuilder message = new StringBuilder(String.format(
                "%s ～ %s の一括解析が%s。\n\n"
                        + "対象: %d日\n保存: %d日\n保存済みスキップ: %d日\n"
                        + "失敗: %d日\n未処理: %d日\n処理時間: %s",
                startDate, endDate,
                result.cancelled() ? "中止されました" : "完了しました",
                result.totalDays(), result.savedDays(), result.skippedDays(),
                result.failedDays(), result.unprocessedDays(),
                formatElapsed(result.elapsed())));
        if (!result.failures().isEmpty()) {
            message.append("\n\n失敗した日:");
            result.failures().stream().limit(8).forEach(failure ->
                    message.append("\n・").append(failure.date())
                            .append(": ").append(failure.message()));
            if (result.failures().size() > 8) {
                message.append("\n・ほか")
                        .append(result.failures().size() - 8)
                        .append("日");
            }
        }
        statusBar.showOperation(String.format(
                "一括解析%s（保存%d・スキップ%d・失敗%d）",
                result.cancelled() ? "中止" : "完了",
                result.savedDays(), result.skippedDays(),
                result.failedDays()));
        JOptionPane.showMessageDialog(this, message.toString(),
                "一括解析結果",
                result.failedDays() == 0
                        ? JOptionPane.INFORMATION_MESSAGE
                        : JOptionPane.WARNING_MESSAGE);
    }

    private void cancelBatch() {
        service.cancelBatchAnalysis();
        historicalControls.setBatchCancellationRequested();
        statusBar.showOperation(
                "一括解析の中止を要求しました。安全な位置で停止します…");
    }

    private static String formatElapsed(Duration elapsed) {
        long seconds = elapsed.toSeconds();
        return String.format("%d分%02d秒", seconds / 60, seconds % 60);
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private void selectVessel(int mmsi) {
        selectedMmsi = mmsi;
        refreshViewModel();
    }

    private void selectGrid(GridCellId cell) {
        selectedCell = cell;
        refreshViewModel();
    }

    private void clearSelection() {
        selectedMmsi = null;
        selectedCell = null;
        refreshViewModel();
    }

    private final class HistoricalActions
            implements HistoricalControlPanel.Listener {

        @Override
        public void onLoadDate(LocalDate date) {
            List<Path> files = filesByDate.getOrDefault(date, List.of());
            if (files.isEmpty()) {
                statusBar.showOperation("選択日にAISログがありません");
                return;
            }
            loadSelection(new HistoricalDaySelection(
                    date, files, false));
        }

        @Override
        public void onDirectFile(Path file) {
            try {
                loadSelection(new HistoricalSelectionFactory()
                        .direct(file, JAPAN));
            } catch (Exception failure) {
                statusBar.showFailure(failure);
            }
        }

        @Override
        public void onPlayPause() {
            togglePlayback();
        }

        @Override
        public void onRestart() {
            stopPlayback();
            historicalControls.setBusy(true);
            service.restart().whenComplete((frame, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        historicalControls.setBusy(false);
                        if (failure != null) {
                            statusBar.showFailure(failure);
                        } else {
                            applyFrame(frame);
                        }
                    }));
        }

        @Override
        public void onSeekStarted() {
            stopPlayback();
        }

        @Override
        public void onSeek(Instant time) {
            seek(time);
        }

        @Override
        public void onAnalyzeAndSave() {
            analyzeAndSave();
        }

        @Override
        public void onAnalyzeRange() {
            analyzeRange();
        }

        @Override
        public void onCancelBatch() {
            cancelBatch();
        }

        @Override
        public void onExportCsv() {
            exportCurrent(false);
        }

        @Override
        public void onExportChart() {
            exportCurrent(true);
        }
    }

    private final class LiveActions implements LiveControlPanel.Listener {

        @Override
        public void onStartOrResume() {
            liveService.startOrResume().whenComplete((frame, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        if (failure != null) {
                            statusBar.showFailure(failure);
                        } else {
                            applyLiveFrame(frame);
                        }
                    }));
        }

        @Override
        public void onStop() {
            liveService.stop().whenComplete((frame, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        if (failure != null) {
                            statusBar.showFailure(failure);
                        } else {
                            applyLiveFrame(frame);
                        }
                    }));
        }

        @Override
        public void onReset() {
            int answer = JOptionPane.showConfirmDialog(
                    MapScreenPanel.this,
                    "現在の表示を空にして新しい集計を開始できる状態にします。\n"
                            + "SQLiteへ保存済みの結果は削除しません。",
                    "集計リセット",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
            liveService.reset().whenComplete((frame, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        if (failure != null) {
                            statusBar.showFailure(failure);
                        } else {
                            selectedMmsi = null;
                            selectedCell = null;
                            applyLiveFrame(frame);
                        }
                    }));
        }

        @Override
        public void onExportCsv() {
            exportCurrent(false);
        }

        @Override
        public void onExportChart() {
            exportCurrent(true);
        }
    }

    private final class SimulationActions
            implements SimulationControlPanel.Listener {

        @Override
        public void onRefreshModels() {
            refreshSimulationModels();
        }

        @Override
        public void onLoad(
                LocalDate date,
                ais.simulation.calibration.CommunicationModelId modelId,
                long seed) {
            List<Path> files = filesByDate.getOrDefault(date, List.of());
            if (files.isEmpty()) {
                statusBar.showOperation(
                        "選択日にAISログがありません");
                return;
            }
            loadSimulation(
                    new HistoricalDaySelection(date, files, false),
                    modelId,
                    seed);
        }

        @Override
        public void onPlayPause() {
            togglePlayback();
        }

        @Override
        public void onRestart() {
            stopPlayback();
            simulationControls.setBusy(true);
            simulationService.restart().whenComplete((frame, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        simulationControls.setBusy(false);
                        if (failure != null) {
                            statusBar.showFailure(failure);
                        } else {
                            applySimulationFrame(frame);
                        }
                    }));
        }

        @Override
        public void onSeekStarted() {
            stopPlayback();
        }

        @Override
        public void onSeek(Instant time) {
            seek(time);
        }
    }

    private final class DisplayActions
            implements DisplayOptionsPanel.Listener {

        @Override
        public void onFilterChanged(Set<VesselClass> vesselClasses,
                                    Duration trailDuration) {
            ais.analysis.AnalysisSnapshot activeSnapshot =
                    activeSnapshot();
            if (selectedMmsi != null && activeSnapshot != null) {
                var selected = activeSnapshot.vessels()
                        .get(selectedMmsi);
                if (selected != null
                        && !vesselClasses.contains(selected.vesselClass())) {
                    selectedMmsi = null;
                    statusBar.showOperation(
                            "Class表示条件の変更により船舶選択を解除しました");
                }
            }
            AnalysisFilter filter = new AnalysisFilter(
                    vesselClasses, trailDuration);
            CompletableFuture<?> update = switch (mode) {
                case HISTORY -> service.setFilter(filter);
                case LIVE -> liveService.setFilter(filter);
                case SIMULATION -> simulationService.setFilter(filter);
            };
            update
                    .whenComplete((frame, failure) ->
                            SwingUtilities.invokeLater(() -> {
                                if (failure != null) {
                                    statusBar.showFailure(failure);
                                } else if (frame instanceof ReplayFrame replay) {
                                    applyFrame(replay);
                                } else if (frame
                                        instanceof SimulationFrame simulation) {
                                    applySimulationFrame(simulation);
                                } else {
                                    applyLiveFrame((LiveFrame) frame);
                                }
                            }));
        }

        @Override
        public void onLayersChanged(
                boolean heatmap, boolean tenKilometers,
                boolean thirtyKilometers, boolean receiver,
                boolean vessels) {
            canvas.setLayers(heatmap, tenKilometers,
                    thirtyKilometers, receiver, vessels);
        }
    }

    private ais.analysis.AnalysisSnapshot activeSnapshot() {
        return switch (mode) {
            case HISTORY -> currentFrame == null
                    ? null : currentFrame.snapshot();
            case LIVE -> currentLiveFrame == null
                    ? null : currentLiveFrame.snapshot();
            case SIMULATION -> currentSimulationFrame == null
                    ? null : currentSimulationFrame.receivedSnapshot();
        };
    }

    private void exportCurrent(boolean chart) {
        ais.analysis.AnalysisSnapshot snapshot = activeSnapshot();
        if (snapshot == null) {
            statusBar.showOperation("出力できる解析結果がありません");
            return;
        }
        var result = new CurrentAggregateFactory().create(snapshot, metric);
        String filename = chart
                ? new ExportFileNamer().chartPng(result)
                : new ExportFileNamer().csv(result);
        Path target = chooseSave(
                chart ? "現在のグラフを保存" : "現在の表を保存",
                filename, chart ? "png" : "csv");
        if (target == null) {
            return;
        }
        statusBar.showOperation("出力しています…");
        CompletableFuture<Path> operation = chart
                ? exports.chartPng(target, result)
                : exports.csv(target, result);
        operation.whenComplete((saved, failure) ->
                SwingUtilities.invokeLater(() -> {
                    if (failure != null) {
                        statusBar.showFailure(failure);
                    } else {
                        statusBar.showOperation("保存しました: " + saved);
                    }
                }));
    }

    private void exportMap() {
        ais.analysis.AnalysisSnapshot snapshot = activeSnapshot();
        if (snapshot == null) {
            statusBar.showOperation("出力できる地図解析結果がありません");
            return;
        }
        var result = new CurrentAggregateFactory().create(snapshot, metric);
        Path target = chooseSave("地図画像を保存",
                new ExportFileNamer().mapPng(result), "png");
        if (target == null) {
            return;
        }
        BufferedImage image = canvas.renderForExport();
        statusBar.showOperation("地図画像を出力しています…");
        exports.mapPng(target, image).whenComplete((saved, failure) ->
                SwingUtilities.invokeLater(() -> {
                    if (failure != null) {
                        statusBar.showFailure(failure);
                    } else {
                        statusBar.showOperation("保存しました: " + saved);
                    }
                }));
    }

    private Path chooseSave(String title, String filename,
                            String extension) {
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
}
