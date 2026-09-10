package ais.ui;

import ais.analysis.AnalysisFilter;
import ais.app.HistoricalAnalysisService;
import ais.app.LiveAnalysisService;
import ais.app.LiveFrame;
import ais.app.LiveState;
import ais.app.CurrentAggregateFactory;
import ais.app.ReplayFrame;
import ais.app.ReplayState;
import ais.domain.VesselClass;
import ais.input.history.HistoricalDaySelection;
import ais.input.history.HistoricalSelectionFactory;
import ais.spatial.GridCellId;
import ais.ui.viewmodel.HeatmapMetric;
import ais.ui.viewmodel.MapViewModel;
import ais.ui.viewmodel.MapViewModelFactory;
import ais.export.ExportFileNamer;
import ais.export.ExportService;

import javax.swing.BoxLayout;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
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
        LIVE
    }

    private static final int PLAYBACK_TICK_MILLIS = 250;
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final HistoricalAnalysisService service;
    private final LiveAnalysisService liveService;
    private final ExportService exports;
    private final Map<LocalDate, List<Path>> filesByDate;
    private final StatusBar statusBar;
    private final MapCanvas canvas;
    private final HistoricalControlPanel historicalControls;
    private final LiveControlPanel liveControls;
    private final JPanel modeControls = new JPanel(new CardLayout());
    private final DisplayOptionsPanel displayOptions;
    private final SelectionDetailPanel selectionDetails =
            new SelectionDetailPanel();
    private final MapViewModelFactory viewModels =
            new MapViewModelFactory();
    private final Timer playbackTimer;

    private ReplayFrame currentFrame;
    private LiveFrame currentLiveFrame;
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
            ExportService exports,
            StatusBar statusBar) {
        super(new BorderLayout());
        this.filesByDate = Map.copyOf(filesByDate);
        this.service = service;
        this.liveService = liveService;
        this.exports = exports;
        this.statusBar = statusBar;
        canvas = new MapCanvas(mapDataset);
        historicalControls = new HistoricalControlPanel(
                filesByDate.keySet().stream().sorted().toList(),
                new HistoricalActions());
        liveControls = new LiveControlPanel(
                liveService.endpointLabel(), new LiveActions());
        displayOptions = new DisplayOptionsPanel(new DisplayActions());
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
    }

    public void setMode(Mode mode) {
        if (mode == Mode.HISTORY && liveService.isReceiving()) {
            throw new IllegalStateException(
                    "受信を停止してから過去ログへ切り替えてください");
        }
        this.mode = mode;
        stopPlayback();
        ((CardLayout) modeControls.getLayout()).show(
                modeControls, mode.name());
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

    private void refreshViewModel() {
        ais.analysis.AnalysisSnapshot snapshot = mode == Mode.HISTORY
                ? currentFrame == null ? null : currentFrame.snapshot()
                : currentLiveFrame == null ? null : currentLiveFrame.snapshot();
        if (snapshot == null) {
            canvas.setViewModel(null);
            selectionDetails.update(null, null);
            if (mode == Mode.LIVE && currentLiveFrame != null) {
                statusBar.update(currentLiveFrame, 0);
            }
            return;
        }
        long diagnosticCount = mode == Mode.HISTORY
                ? currentFrame.dataset() == null ? 0
                : currentFrame.dataset().diagnostics().size()
                : currentLiveFrame.diagnosticCount();
        MapViewModel model = viewModels.create(
                snapshot, metric, selectedMmsi,
                selectedCell, diagnosticCount);
        if (selectedMmsi != null && model.selectedMmsi() == null) {
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
        selectionDetails.update(
                model.selectedVessel(), model.selectedGrid());
        if (mode == Mode.HISTORY) {
            statusBar.update(currentFrame, model.vessels().size(),
                    diagnosticCount);
        } else {
            statusBar.update(currentLiveFrame, model.vessels().size());
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
        service.advance(historicalControls.tickDuration(
                        PLAYBACK_TICK_MILLIS))
                .whenComplete((frame, failure) -> SwingUtilities.invokeLater(
                        () -> {
                            tickInFlight = false;
                            if (failure != null) {
                                stopPlayback();
                                statusBar.showFailure(failure);
                            } else {
                                applyFrame(frame);
                            }
                        }));
    }

    private void togglePlayback() {
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

    private void startPlayback() {
        playing = true;
        historicalControls.setPlaying(true);
        playbackTimer.start();
    }

    private void seek(Instant time) {
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
            CompletableFuture<?> update = mode == Mode.HISTORY
                    ? service.setFilter(new AnalysisFilter(
                            vesselClasses, trailDuration))
                    : liveService.setFilter(new AnalysisFilter(
                            vesselClasses, trailDuration));
            update
                    .whenComplete((frame, failure) ->
                            SwingUtilities.invokeLater(() -> {
                                if (failure != null) {
                                    statusBar.showFailure(failure);
                                } else if (frame instanceof ReplayFrame replay) {
                                    applyFrame(replay);
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
        if (mode == Mode.HISTORY) {
            return currentFrame == null ? null : currentFrame.snapshot();
        }
        return currentLiveFrame == null ? null : currentLiveFrame.snapshot();
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
