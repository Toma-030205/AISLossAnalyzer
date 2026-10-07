package ais.ui;

import ais.app.ApplicationContext;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDate;

public final class MainFrame extends JFrame {

    private static final String MAP_CARD = "map";
    private static final String AGGREGATE_CARD = "aggregate";

    private final ApplicationContext applicationContext;
    private final MapScreenPanel mapScreen;
    private final AggregateScreenPanel aggregateScreen;
    private final ShipLengthAnalysisPanel shipLengthPanel;
    private final ShipLengthPerformancePanel shipLengthPerformancePanel;
    private final DailyDataQualityPanel dataQualityPanel;
    private final CommunicationModelPanel communicationModelPanel;
    private final ValidationPanel validationPanel;
    private final JTabbedPane aggregateTabs = new JTabbedPane();
    private final TopNavigationPanel navigation;
    private final JPanel content = new JPanel(new CardLayout());

    public MainFrame(ApplicationContext applicationContext) {
        super("AIS通信欠落・情報鮮度分析");
        this.applicationContext = applicationContext;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        StatusBar statusBar = new StatusBar();
        if (applicationContext.unclassifiedHistoricalFileCount() > 0) {
            statusBar.showOperation(String.format(
                    "日付を判定できないログ%d件を一覧から除外しました",
                    applicationContext.unclassifiedHistoricalFileCount()));
        }
        mapScreen = new MapScreenPanel(
                applicationContext.mapDataset(),
                applicationContext.historicalFilesByDate(),
                applicationContext.historicalAnalysisService(),
                applicationContext.liveAnalysisService(),
                applicationContext.simulationPlaybackService(),
                applicationContext.exportService(),
                statusBar);
        LocalDate initialDate = applicationContext.historicalFilesByDate()
                .keySet().stream().max(LocalDate::compareTo).orElse(
                        LocalDate.now());
        aggregateScreen = new AggregateScreenPanel(
                applicationContext.aggregateQueryService(),
                applicationContext.exportService(),
                applicationContext.receiverProfile(),
                applicationContext.analysisProfile(),
                initialDate, statusBar);
        dataQualityPanel = new DailyDataQualityPanel(
                applicationContext.aggregateQueryService(),
                applicationContext.exportService(),
                applicationContext.receiverProfile(),
                applicationContext.analysisProfile(),
                initialDate, statusBar);
        shipLengthPanel = new ShipLengthAnalysisPanel(
                applicationContext.aggregateQueryService(),
                applicationContext.exportService(),
                applicationContext.receiverProfile(),
                applicationContext.analysisProfile(),
                initialDate, statusBar);
        shipLengthPerformancePanel = new ShipLengthPerformancePanel(
                applicationContext.aggregateQueryService(),
                applicationContext.exportService(),
                applicationContext.receiverProfile(),
                applicationContext.analysisProfile(),
                initialDate, statusBar);
        communicationModelPanel = new CommunicationModelPanel(
                applicationContext.communicationModelService(),
                applicationContext.exportService(),
                applicationContext.receiverProfile(),
                applicationContext.analysisProfile(),
                initialDate, statusBar);
        validationPanel = new ValidationPanel(
                applicationContext.simulationValidationService(),
                applicationContext.exportService(), statusBar);
        aggregateTabs.addTab("性能集計", aggregateScreen);
        aggregateTabs.addTab("船体長別性能", shipLengthPerformancePanel);
        aggregateTabs.addTab("船体長別分析", shipLengthPanel);
        aggregateTabs.addTab("日別データ品質", dataQualityPanel);
        aggregateTabs.addTab("通信モデル", communicationModelPanel);
        aggregateTabs.addTab("妥当性確認", validationPanel);
        aggregateTabs.addChangeListener(event -> refreshAggregateTab());
        navigation = new TopNavigationPanel(
                applicationContext.receiverProfile().name(),
                this::navigate);
        mapScreen.setLiveActivityListener(
                receiving -> navigation.setSwitchingEnabled(!receiving));
        content.add(mapScreen, MAP_CARD);
        content.add(aggregateTabs, AGGREGATE_CARD);
        add(navigation, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);
        setMinimumSize(new Dimension(1200, 720));
        setPreferredSize(new Dimension(1440, 900));
        pack();
        setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                requestClose();
            }
        });
    }

    private void navigate(TopNavigationPanel.Screen screen) {
        CardLayout cards = (CardLayout) content.getLayout();
        switch (screen) {
            case HISTORY -> {
                mapScreen.setMode(MapScreenPanel.Mode.HISTORY);
                cards.show(content, MAP_CARD);
            }
            case LIVE -> {
                mapScreen.setMode(MapScreenPanel.Mode.LIVE);
                cards.show(content, MAP_CARD);
            }
            case SIMULATION -> {
                mapScreen.setMode(MapScreenPanel.Mode.SIMULATION);
                cards.show(content, MAP_CARD);
            }
            case AGGREGATE -> {
                mapScreen.stopPlayback();
                cards.show(content, AGGREGATE_CARD);
                refreshAggregateTab();
            }
            case RECEIVER, SETTINGS -> {
                // These buttons remain disabled until their later iteration.
            }
        }
    }

    private void refreshAggregateTab() {
        if (aggregateTabs.getSelectedComponent() == dataQualityPanel) {
            dataQualityPanel.refreshIfEmpty();
        } else if (aggregateTabs.getSelectedComponent() == shipLengthPanel) {
            shipLengthPanel.refreshIfEmpty();
        } else if (aggregateTabs.getSelectedComponent()
                == shipLengthPerformancePanel) {
            shipLengthPerformancePanel.refreshIfEmpty();
        } else if (aggregateTabs.getSelectedComponent()
                == communicationModelPanel) {
            communicationModelPanel.refreshIfEmpty();
        } else if (aggregateTabs.getSelectedComponent()
                == validationPanel) {
            validationPanel.refreshIfEmpty();
        } else {
            aggregateScreen.refreshIfEmpty();
        }
    }

    private void requestClose() {
        mapScreen.stopPlayback();
        if (mapScreen.isHistoricalBatchRunning()) {
            int answer = JOptionPane.showConfirmDialog(
                    this,
                    "期間の一括解析を中止しますか？\n"
                            + "保存が完了した日付の結果は残ります。"
                            + "中止完了後に、もう一度終了してください。",
                    "一括解析の中止",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) {
                mapScreen.cancelHistoricalBatch();
            }
            return;
        }
        if (!mapScreen.isLiveReceiving()) {
            finishClose();
            return;
        }
        Object[] options = {"停止して終了", "キャンセル"};
        int answer = JOptionPane.showOptionDialog(
                this,
                "受信を停止して現在の集計を保存し、アプリを終了しますか？",
                "AISLossAnalyzerの終了",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        if (answer != 0) {
            return;
        }
        setEnabled(false);
        mapScreen.stopLive().whenComplete((frame, failure) ->
                SwingUtilities.invokeLater(() -> {
                    if (failure != null) {
                        setEnabled(true);
                        JOptionPane.showMessageDialog(this,
                                "リアルタイム集計を保存できませんでした: "
                                        + failure.getMessage(),
                                "終了できません",
                                JOptionPane.ERROR_MESSAGE);
                    } else {
                        finishClose();
                    }
                }));
    }

    private void finishClose() {
        applicationContext.close();
        dispose();
    }
}
