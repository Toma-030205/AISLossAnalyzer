package ais.ui;

import ais.app.LiveFrame;
import ais.app.LiveState;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class LiveControlPanel extends JPanel {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final JLabel state = new JLabel("停止中");
    private final JLabel endpoint = new JLabel();
    private final JLabel session = new JLabel("—");
    private final JLabel rate = new JLabel("0件/秒");
    private final JButton start = new JButton("受信・分析開始");
    private final JButton stop = new JButton("停止");
    private final JButton reset = new JButton("集計リセット");
    private final JButton csv = new JButton("現在の表をCSV保存");
    private final JButton chart = new JButton("現在のグラフを保存");

    public LiveControlPanel(String endpointLabel, Listener listener) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("リアルタイム"));
        endpoint.setText(endpointLabel);
        add(labelRow("状態:", state));
        add(labelRow("待受:", endpoint));
        add(labelRow("セッション:", session));
        add(labelRow("受信レート:", rate));
        JPanel actions = row();
        actions.add(start);
        actions.add(stop);
        actions.add(reset);
        add(actions);
        JPanel exports = row();
        exports.add(csv);
        exports.add(chart);
        add(exports);
        start.addActionListener(event -> listener.onStartOrResume());
        stop.addActionListener(event -> listener.onStop());
        reset.addActionListener(event -> listener.onReset());
        csv.addActionListener(event -> listener.onExportCsv());
        chart.addActionListener(event -> listener.onExportChart());
        updateButtons(LiveState.IDLE, false);
        setMaximumSize(new Dimension(
                Integer.MAX_VALUE, getPreferredSize().height));
    }

    public void update(LiveFrame frame) {
        state.setText(switch (frame.state()) {
            case IDLE -> "停止中";
            case STARTING -> "開始中";
            case RUNNING -> "● 受信中";
            case STOPPING -> "停止・保存中";
            case STOPPED -> "停止中（保存済み）";
            case ERROR -> "エラー";
        });
        if (frame.sessionStartedAt() == null) {
            session.setText("—");
        } else {
            long seconds = Math.max(0, Duration.between(
                    frame.sessionStartedAt(), frame.displayTime()).toSeconds());
            session.setText(DATE_TIME.format(
                    frame.sessionStartedAt().atZone(JAPAN))
                    + String.format(" / %02d:%02d:%02d",
                    seconds / 3600, seconds / 60 % 60, seconds % 60));
        }
        rate.setText(String.format("%.0f件/秒",
                frame.receivedRecordsPerSecond()));
        updateButtons(frame.state(), frame.snapshot() != null);
    }

    private void updateButtons(LiveState value, boolean hasData) {
        start.setEnabled(value == LiveState.IDLE
                || value == LiveState.STOPPED);
        start.setText(value == LiveState.STOPPED ? "再開" : "受信・分析開始");
        stop.setEnabled(value == LiveState.STARTING
                || value == LiveState.RUNNING);
        reset.setEnabled(value == LiveState.STOPPED
                || value == LiveState.ERROR);
        boolean exportable = hasData && (value == LiveState.RUNNING
                || value == LiveState.STOPPED || value == LiveState.ERROR);
        csv.setEnabled(exportable);
        chart.setEnabled(exportable);
    }

    private static JPanel labelRow(String label, Component value) {
        JPanel row = row();
        row.add(new JLabel(label));
        row.add(value);
        return row;
    }

    private static JPanel row() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1)) {
            @Override
            public Dimension getMaximumSize() {
                Dimension preferred = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, preferred.height);
            }
        };
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    public interface Listener {
        void onStartOrResume();

        void onStop();

        void onReset();

        void onExportCsv();

        void onExportChart();
    }
}
