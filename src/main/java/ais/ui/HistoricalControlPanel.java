package ais.ui;

import ais.app.ReplayFrame;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class HistoricalControlPanel extends JPanel {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Listener listener;
    private final JComboBox<LocalDate> date = new JComboBox<>();
    private final JButton load = new JButton("日付を読込");
    private final JButton direct = new JButton("ファイルを直接選択");
    private final JButton play = new JButton("再生");
    private final JButton restart = new JButton("先頭へ戻る");
    private final JButton analyze = new JButton("一日分を解析して保存");
    private final JButton batchAnalyze =
            new JButton("期間を一括解析・保存…");
    private final JButton csv = new JButton("現在の表をCSV保存");
    private final JButton chart = new JButton("現在のグラフを保存");
    private final JComboBox<ReplaySpeed> speed =
            new JComboBox<>(ReplaySpeed.values());
    private final JSlider slider = new JSlider(0, 0, 0);
    private final JLabel fileSummary = new JLabel("—");
    private final JLabel timeSummary = new JLabel("— / —");
    private Instant start;
    private Instant end;
    private boolean programmaticSlider;
    private boolean busy;
    private boolean batchRunning;

    public HistoricalControlPanel(List<LocalDate> dates,
                                  Listener listener) {
        this.listener = listener;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("過去ログ"));
        dates.forEach(date::addItem);
        if (!dates.isEmpty()) {
            date.setSelectedItem(dates.getLast());
        }
        JPanel dateRow = row();
        dateRow.add(new JLabel("日付:"));
        dateRow.add(date);
        dateRow.add(load);
        add(dateRow);
        JPanel directRow = row();
        directRow.add(direct);
        add(directRow);
        fileSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(fileSummary);
        slider.setEnabled(false);
        slider.setAlignmentX(Component.LEFT_ALIGNMENT);
        slider.setMaximumSize(new Dimension(
                Integer.MAX_VALUE, slider.getPreferredSize().height));
        add(slider);
        timeSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(timeSummary);
        JPanel playback = row();
        playback.add(play);
        playback.add(restart);
        speed.setSelectedItem(ReplaySpeed.X60);
        playback.add(speed);
        add(playback);
        JPanel saveRow = row();
        saveRow.add(analyze);
        saveRow.add(batchAnalyze);
        add(saveRow);
        JPanel exportRow = row();
        exportRow.add(csv);
        exportRow.add(chart);
        add(exportRow);

        updateEnabledState();
        load.addActionListener(event -> {
            LocalDate selected = (LocalDate) date.getSelectedItem();
            if (selected != null) {
                listener.onLoadDate(selected);
            }
        });
        direct.addActionListener(event -> chooseDirectFile());
        play.addActionListener(event -> listener.onPlayPause());
        restart.addActionListener(event -> listener.onRestart());
        analyze.addActionListener(event -> listener.onAnalyzeAndSave());
        batchAnalyze.addActionListener(event -> {
            if (batchRunning) {
                listener.onCancelBatch();
            } else {
                listener.onAnalyzeRange();
            }
        });
        csv.addActionListener(event -> listener.onExportCsv());
        chart.addActionListener(event -> listener.onExportChart());
        slider.addChangeListener(event -> {
            if (programmaticSlider || start == null || end == null) {
                return;
            }
            if (slider.getValueIsAdjusting()) {
                listener.onSeekStarted();
                return;
            }
            listener.onSeek(start.plusSeconds(slider.getValue()));
        });
        setMaximumSize(new Dimension(
                Integer.MAX_VALUE, getPreferredSize().height));
    }

    public Duration tickDuration(int timerMillis) {
        ReplaySpeed selected = (ReplaySpeed) speed.getSelectedItem();
        int multiplier = selected == null ? 60 : selected.multiplier;
        return Duration.ofMillis((long) timerMillis * multiplier);
    }

    public void updateFrame(ReplayFrame frame) {
        if (frame.dataset() != null && !frame.dataset().isEmpty()) {
            start = frame.dataset().startTime();
            end = frame.dataset().endTime();
            long duration = Math.max(1,
                    Duration.between(start, end).toSeconds());
            programmaticSlider = true;
            slider.setMaximum((int) Math.min(Integer.MAX_VALUE, duration));
            if (frame.displayTime() != null) {
                slider.setValue((int) Math.min(Integer.MAX_VALUE,
                        Math.max(0, Duration.between(
                                start, frame.displayTime()).toSeconds())));
            }
            programmaticSlider = false;
            fileSummary.setText(String.format(
                    "%s / %dファイル / %,d件",
                    frame.dataset().selection().date(),
                    frame.dataset().selection().files().size(),
                    frame.dataset().inputRecordCount()));
            timeSummary.setText(format(frame.displayTime())
                    + " / " + format(end));
            updateEnabledState();
        }
    }

    public void setPlaying(boolean playing) {
        play.setText(playing ? "一時停止" : "再生");
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
        updateEnabledState();
    }

    public void setBatchRunning(boolean running) {
        batchRunning = running;
        batchAnalyze.setText(running
                ? "一括処理を中止" : "期間を一括解析・保存…");
        updateEnabledState();
    }

    public void setBatchCancellationRequested() {
        if (batchRunning) {
            batchAnalyze.setText("中止中…");
            batchAnalyze.setEnabled(false);
        }
    }

    public LocalDate selectedDate() {
        return (LocalDate) date.getSelectedItem();
    }

    private void updateEnabledState() {
        boolean interactive = !busy && !batchRunning;
        boolean hasReplay = start != null;
        date.setEnabled(interactive);
        load.setEnabled(interactive && date.getItemCount() > 0);
        direct.setEnabled(interactive);
        play.setEnabled(interactive && hasReplay);
        restart.setEnabled(interactive && hasReplay);
        speed.setEnabled(interactive && hasReplay);
        slider.setEnabled(interactive && hasReplay);
        analyze.setEnabled(interactive && hasReplay);
        csv.setEnabled(interactive && hasReplay);
        chart.setEnabled(interactive && hasReplay);
        batchAnalyze.setEnabled(batchRunning
                || (!busy && date.getItemCount() > 0));
    }

    private void chooseDirectFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("AISログを選択");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "AIS log (*.ais, *.ais.gz)", "ais", "gz"));
        if (chooser.showOpenDialog(this)
                == JFileChooser.APPROVE_OPTION) {
            listener.onDirectFile(chooser.getSelectedFile().toPath());
        }
    }

    private static String format(Instant instant) {
        return instant == null ? "—" : TIME.format(instant.atZone(JAPAN));
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
        void onLoadDate(LocalDate date);

        void onDirectFile(Path file);

        void onPlayPause();

        void onRestart();

        void onSeekStarted();

        void onSeek(Instant time);

        void onAnalyzeAndSave();

        void onAnalyzeRange();

        void onCancelBatch();

        void onExportCsv();

        void onExportChart();
    }

    private enum ReplaySpeed {
        X1("1×", 1),
        X5("5×", 5),
        X10("10×", 10),
        X30("30×", 30),
        X60("60×", 60),
        X300("300×", 300);

        private final String label;
        private final int multiplier;

        ReplaySpeed(String label, int multiplier) {
            this.label = label;
            this.multiplier = multiplier;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
