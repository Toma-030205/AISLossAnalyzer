package ais.ui;

import ais.app.SimulationFrame;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelId;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class SimulationControlPanel extends JPanel {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Listener listener;
    private final JComboBox<LocalDate> date = new JComboBox<>();
    private final JComboBox<ModelChoice> model = new JComboBox<>();
    private final JSpinner seed = new JSpinner(new SpinnerNumberModel(
            42L, Long.MIN_VALUE, Long.MAX_VALUE, 1L));
    private final JButton refreshModels = new JButton("モデル更新");
    private final JButton load = new JButton("読込");
    private final JButton play = new JButton("再生");
    private final JButton restart = new JButton("先頭へ戻る");
    private final JComboBox<ReplaySpeed> speed =
            new JComboBox<>(ReplaySpeed.values());
    private final JSlider slider = new JSlider(0, 0, 0);
    private final JLabel timeSummary = new JLabel("— / —");
    private final JLabel resultSummary = new JLabel("モデル未選択");

    private Instant start;
    private Instant end;
    private boolean programmaticSlider;
    private boolean busy;

    public SimulationControlPanel(
            List<LocalDate> dates,
            Listener listener) {
        this.listener = listener;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("シミュレーション"));
        dates.forEach(date::addItem);
        if (!dates.isEmpty()) {
            date.setSelectedItem(dates.getLast());
        }

        JPanel dateRow = row();
        dateRow.add(new JLabel("日付:"));
        dateRow.add(date);
        add(dateRow);
        JPanel modelRow = row();
        modelRow.add(new JLabel("モデル:"));
        model.setPreferredSize(new Dimension(210,
                model.getPreferredSize().height));
        modelRow.add(model);
        modelRow.add(refreshModels);
        add(modelRow);
        JPanel seedRow = row();
        seedRow.add(new JLabel("seed:"));
        seed.setPreferredSize(new Dimension(130,
                seed.getPreferredSize().height));
        seedRow.add(seed);
        seedRow.add(load);
        add(seedRow);

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
        resultSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(resultSummary);

        refreshModels.addActionListener(event -> listener.onRefreshModels());
        load.addActionListener(event -> {
            LocalDate selectedDate = (LocalDate) date.getSelectedItem();
            ModelChoice selectedModel = (ModelChoice) model.getSelectedItem();
            if (selectedDate != null && selectedModel != null) {
                listener.onLoad(
                        selectedDate,
                        selectedModel.definition.id(),
                        ((Number) seed.getValue()).longValue());
            }
        });
        play.addActionListener(event -> listener.onPlayPause());
        restart.addActionListener(event -> listener.onRestart());
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
        updateEnabledState();
        setMaximumSize(new Dimension(
                Integer.MAX_VALUE, getPreferredSize().height));
    }

    public void updateModels(List<CommunicationModelDefinition> definitions) {
        CommunicationModelId selected = selectedModelId();
        model.removeAllItems();
        definitions.stream()
                .sorted(java.util.Comparator
                        .comparing(CommunicationModelDefinition::createdAt)
                        .reversed())
                .map(ModelChoice::new)
                .forEach(model::addItem);
        if (selected != null) {
            for (int index = 0; index < model.getItemCount(); index++) {
                if (model.getItemAt(index).definition.id().equals(selected)) {
                    model.setSelectedIndex(index);
                    break;
                }
            }
        }
        resultSummary.setText(definitions.isEmpty()
                ? "保存済み通信モデルがありません"
                : definitions.size() + "モデル選択可能");
        updateEnabledState();
    }

    public void updateFrame(SimulationFrame frame) {
        if (frame.day() == null) {
            return;
        }
        start = frame.startTime();
        end = frame.endTime();
        long duration = Math.max(1,
                Duration.between(start, end).toSeconds());
        programmaticSlider = true;
        slider.setMaximum((int) Math.min(Integer.MAX_VALUE, duration));
        slider.setValue((int) Math.min(Integer.MAX_VALUE,
                Math.max(0, Duration.between(
                        start, frame.displayTime()).toSeconds())));
        programmaticSlider = false;
        timeSummary.setText(format(frame.displayTime())
                + " / " + format(end));
        var diagnostics = frame.diagnostics();
        resultSummary.setText(String.format(
                "%s v%d / 送信%,d 受信%,d 欠落%,d 適用外%,d",
                frame.model().modelCode().label(),
                frame.model().revision(),
                diagnostics.processedTransmissionCount(),
                diagnostics.receivedCount(),
                diagnostics.lostCount(),
                diagnostics.outOfModelCount()));
        updateEnabledState();
    }

    public Duration tickDuration(int timerMillis) {
        ReplaySpeed selected = (ReplaySpeed) speed.getSelectedItem();
        int multiplier = selected == null ? 60 : selected.multiplier;
        return Duration.ofMillis((long) timerMillis * multiplier);
    }

    public void setPlaying(boolean playing) {
        play.setText(playing ? "一時停止" : "再生");
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
        updateEnabledState();
    }

    private CommunicationModelId selectedModelId() {
        ModelChoice selected = (ModelChoice) model.getSelectedItem();
        return selected == null ? null : selected.definition.id();
    }

    private void updateEnabledState() {
        boolean hasReplay = start != null;
        date.setEnabled(!busy);
        model.setEnabled(!busy);
        seed.setEnabled(!busy);
        refreshModels.setEnabled(!busy);
        load.setEnabled(!busy && date.getItemCount() > 0
                && model.getItemCount() > 0);
        play.setEnabled(!busy && hasReplay);
        restart.setEnabled(!busy && hasReplay);
        speed.setEnabled(!busy && hasReplay);
        slider.setEnabled(!busy && hasReplay);
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
        void onRefreshModels();

        void onLoad(LocalDate date, CommunicationModelId modelId, long seed);

        void onPlayPause();

        void onRestart();

        void onSeekStarted();

        void onSeek(Instant time);
    }

    private record ModelChoice(CommunicationModelDefinition definition) {
        @Override
        public String toString() {
            return definition.name() + " / "
                    + definition.modelCode().label()
                    + " v" + definition.revision();
        }
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
