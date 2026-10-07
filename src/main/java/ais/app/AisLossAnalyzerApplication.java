package ais.app;

import ais.storage.SqliteDatabase;
import ais.ui.MainFrame;
import ais.input.history.HistoricalDaySelection;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class AisLossAnalyzerApplication {

    private AisLossAnalyzerApplication() {
    }

    public static void main(String[] args) {
        boolean commandLineMode = commandLineMode(args);
        try {
            Options options = Options.parse(args);
            ApplicationContext context = ApplicationContext.initialize(
                    options.sencRoot(), options.aisDataRoot(),
                    options.database());
            if (options.validateOnly()) {
                System.out.printf(
                        "Application data validated: mapFeatures=%,d, historicalDays=%,d, unclassifiedFiles=%,d%n",
                        context.mapDataset().features().size(),
                        context.historicalFilesByDate().size(),
                        context.unclassifiedHistoricalFileCount());
                context.close();
                return;
            }
            if (options.batchMode()) {
                try {
                    runHistoricalBatch(context, options);
                } finally {
                    context.close();
                }
                return;
            }
            SwingUtilities.invokeLater(() -> {
                useSystemLookAndFeel();
                new MainFrame(context).setVisible(true);
            });
        } catch (Exception failure) {
            failure.printStackTrace();
            if (commandLineMode) {
                throw new IllegalStateException(
                        "コマンドライン処理に失敗しました", failure);
            }
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    null,
                    failure.getMessage(),
                    "AISLossAnalyzerを起動できません",
                    JOptionPane.ERROR_MESSAGE));
        }
    }

    private static boolean commandLineMode(String[] args) {
        for (String argument : args) {
            if (argument.equals("--validate-only")
                    || argument.equals("--batch-from")
                    || argument.equals("--batch-to")) {
                return true;
            }
        }
        return false;
    }

    private static void runHistoricalBatch(
            ApplicationContext context,
            Options options) throws Exception {
        List<HistoricalDaySelection> selections = context
                .historicalFilesByDate().entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(
                        options.batchFrom()))
                .filter(entry -> !entry.getKey().isAfter(
                        options.batchTo()))
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> new HistoricalDaySelection(
                        entry.getKey(), entry.getValue(), false))
                .toList();
        if (selections.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "%sから%sに解析可能なAISログがありません",
                    options.batchFrom(), options.batchTo()));
        }
        System.out.printf(
                "Historical batch started: from=%s to=%s days=%,d%n",
                options.batchFrom(), options.batchTo(), selections.size());
        HistoricalBatchResult result = context
                .historicalAnalysisService()
                .analyzeAndSaveBatch(
                        selections, true,
                        AisLossAnalyzerApplication::printBatchProgress)
                .get(7, TimeUnit.DAYS);
        System.out.printf(
                "Historical batch finished: total=%,d saved=%,d skipped=%,d failed=%,d unprocessed=%,d cancelled=%s elapsed=%s%n",
                result.totalDays(), result.savedDays(),
                result.skippedDays(), result.failedDays(),
                result.unprocessedDays(), result.cancelled(),
                result.elapsed());
        result.failures().forEach(failure -> System.err.printf(
                "Historical batch failure: date=%s reason=%s%n",
                failure.date(), failure.message()));
        if (result.cancelled() || result.failedDays() > 0) {
            throw new IllegalStateException(
                    "一括解析が未完了です。上の失敗日を確認してください");
        }
    }

    private static void printBatchProgress(
            HistoricalBatchProgress progress) {
        if (progress.stage() == HistoricalBatchProgress.Stage.LOADING
                && progress.processedRecords() != 0
                && progress.processedRecords() % 250_000 != 0) {
            return;
        }
        System.out.printf(
                "Historical batch progress: day=%d/%d date=%s stage=%s records=%,d saved=%d skipped=%d failed=%d%n",
                progress.dayNumber(), progress.totalDays(),
                progress.date(), progress.stage().name(),
                progress.processedRecords(), progress.savedDays(),
                progress.skippedDays(), progress.failedDays());
    }

    private static void useSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(
                    UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Swing's cross-platform appearance remains usable.
        }
    }

    record Options(Path sencRoot, Path aisDataRoot,
                   SqliteDatabase database, boolean validateOnly,
                   LocalDate batchFrom, LocalDate batchTo) {

        static Options parse(String[] args) {
            Path working = Path.of("").toAbsolutePath().normalize();
            Path senc = working.resolve("senc");
            Path ais = working.resolve("ais");
            SqliteDatabase database = SqliteDatabase.defaultDatabase();
            boolean validateOnly = false;
            LocalDate batchFrom = null;
            LocalDate batchTo = null;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--senc" -> senc = Path.of(
                            requireValue(args, ++index, "--senc"));
                    case "--ais-data" -> ais = Path.of(
                            requireValue(args, ++index, "--ais-data"));
                    case "--database" -> database = new SqliteDatabase(
                            Path.of(requireValue(
                                    args, ++index, "--database")));
                    case "--validate-only" -> validateOnly = true;
                    case "--batch-from" -> batchFrom = parseDate(
                            requireValue(args, ++index, "--batch-from"),
                            "--batch-from");
                    case "--batch-to" -> batchTo = parseDate(
                            requireValue(args, ++index, "--batch-to"),
                            "--batch-to");
                    default -> throw new IllegalArgumentException(
                            "不明な起動引数: " + args[index]);
                }
            }
            if ((batchFrom == null) != (batchTo == null)) {
                throw new IllegalArgumentException(
                        "--batch-fromと--batch-toは両方指定してください");
            }
            if (batchFrom != null && batchTo.isBefore(batchFrom)) {
                throw new IllegalArgumentException(
                        "--batch-toは--batch-from以降にしてください");
            }
            if (validateOnly && batchFrom != null) {
                throw new IllegalArgumentException(
                        "--validate-onlyと一括解析は同時指定できません");
            }
            return new Options(senc, ais, database, validateOnly,
                    batchFrom, batchTo);
        }

        boolean batchMode() {
            return batchFrom != null;
        }

        private static LocalDate parseDate(String value, String option) {
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException failure) {
                throw new IllegalArgumentException(
                        option + "はyyyy-MM-dd形式で指定してください: "
                                + value,
                        failure);
            }
        }

        private static String requireValue(
                String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(
                        option + "にはパスが必要です");
            }
            return args[index];
        }
    }
}
