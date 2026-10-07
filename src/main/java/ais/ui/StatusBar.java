package ais.ui;

import ais.app.ReplayFrame;
import ais.app.LiveFrame;
import ais.app.SimulationFrame;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class StatusBar extends JPanel {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final JLabel state = new JLabel("過去ログ・未選択");
    private final JLabel time = new JLabel("時刻 —");
    private final JLabel processed = new JLabel("0件処理");
    private final JLabel vessels = new JLabel("表示 0隻");
    private final JLabel diagnostics = new JLabel("診断 0");
    private final JLabel message = new JLabel("");

    public StatusBar() {
        super(new FlowLayout(FlowLayout.LEFT, 13, 4));
        setBorder(BorderFactory.createEtchedBorder());
        add(state);
        add(time);
        add(processed);
        add(vessels);
        add(diagnostics);
        add(message);
    }

    public void update(ReplayFrame frame, int visibleVesselCount,
                       long diagnosticCount) {
        state.setText("過去ログ・" + stateLabel(frame));
        time.setText(frame.displayTime() == null ? "時刻 —"
                : "再生 " + TIME.format(frame.displayTime().atZone(JAPAN)));
        processed.setText(String.format("%,d件処理",
                frame.processedEventCount()));
        vessels.setText("表示 " + visibleVesselCount + "隻");
        diagnostics.setText("診断 " + diagnosticCount);
        if (!frame.message().isBlank()) {
            message.setText(frame.message());
        }
    }

    public void showOperation(String text) {
        message.setText(text == null ? "" : text);
    }

    public void showSimulationIdle() {
        state.setText("シミュレーション・未選択");
        time.setText("時刻 —");
        processed.setText("送信0 / 受信0 / 欠落0");
        vessels.setText("真位置 0隻");
        diagnostics.setText("適用外 0");
    }

    public void update(LiveFrame frame, int visibleVesselCount) {
        state.setText("リアルタイム・" + switch (frame.state()) {
            case IDLE -> "停止中";
            case STARTING -> "開始中";
            case RUNNING -> "受信中";
            case STOPPING -> "停止・保存中";
            case STOPPED -> "停止";
            case ERROR -> "エラー";
        });
        time.setText(frame.displayTime() == null ? "時刻 —"
                : "現在 " + TIME.format(frame.displayTime().atZone(JAPAN)));
        long accepted = frame.snapshot() == null ? 0
                : frame.snapshot().acceptedIntervalCount();
        processed.setText(String.format(
                "受信%,d / デコード%,d / 採用%,d",
                frame.receivedRecordCount(), frame.decodedEventCount(),
                accepted));
        vessels.setText("表示 " + visibleVesselCount + "隻");
        diagnostics.setText(String.format("エラー%,d / 重複%,d",
                frame.errorDiagnosticCount(), frame.duplicateCount()));
        if (!frame.message().isBlank()) {
            message.setText(frame.message());
        }
    }

    public void update(
            SimulationFrame frame,
            int visibleTruthVesselCount) {
        state.setText("シミュレーション・" + switch (frame.state()) {
            case NO_FILE -> "未選択";
            case LOADING -> "読込中";
            case READY -> "準備完了";
            case PLAYING -> "再生中";
            case PAUSED -> "一時停止";
            case SEEKING -> "再計算中";
            case END -> "終端";
            case ERROR -> "エラー";
        });
        time.setText(frame.displayTime() == null ? "時刻 —"
                : "再生 " + TIME.format(
                frame.displayTime().atZone(JAPAN)));
        var counts = frame.diagnostics();
        processed.setText(String.format(
                "送信%,d / 受信%,d / 欠落%,d",
                counts.processedTransmissionCount(),
                counts.receivedCount(),
                counts.lostCount()));
        vessels.setText("真位置 " + visibleTruthVesselCount + "隻");
        diagnostics.setText("適用外 " + counts.outOfModelCount());
        if (!frame.message().isBlank()) {
            message.setText(frame.message());
        }
    }

    public void showFailure(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        message.setText("エラー: " + (cause.getMessage() == null
                ? cause.getClass().getSimpleName() : cause.getMessage()));
    }

    private static String stateLabel(ReplayFrame frame) {
        return switch (frame.state()) {
            case NO_FILE -> "未選択";
            case LOADING -> "読込中";
            case READY -> "準備完了";
            case PLAYING -> "再生中";
            case PAUSED -> "一時停止";
            case SEEKING -> "再計算中";
            case ANALYZING_DAY -> "全日解析中";
            case END -> "終端";
            case ERROR -> "エラー";
        };
    }
}
