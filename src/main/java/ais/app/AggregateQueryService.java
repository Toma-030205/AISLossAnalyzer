package ais.app;

import ais.aggregate.MetricCalculator;
import ais.aggregate.RollupDimension;
import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;
import ais.domain.VesselClass;
import ais.storage.AggregateRollupQuery;
import ais.storage.DailyDataQualityQuery;
import ais.storage.JdbcAggregateRepository;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcAnalysisRunRepository;
import ais.storage.JdbcDailyDataQualityRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.JdbcShipLengthAnalysisRepository;
import ais.storage.JdbcShipLengthPerformanceRepository;
import ais.storage.ShipLengthAnalysisQuery;
import ais.storage.ShipLengthPerformanceQuery;
import ais.storage.SqliteDatabase;
import ais.storage.StoredDailyDataQuality;
import ais.storage.StoredDistanceHourRollup;
import ais.storage.StoredDistanceRollup;
import ais.storage.StoredHourRollup;
import ais.storage.StoredAnalysisRun;
import ais.storage.StoredShipLengthAnalysis;
import ais.storage.StoredShipLengthCell;
import ais.storage.StoredShipLengthPerformance;
import ais.spatial.DistanceBand;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AggregateQueryService implements AutoCloseable {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");
    private static final long MINIMUM_LENGTH_VESSEL_DAYS = 30;
    private static final int MINIMUM_LENGTH_DISTINCT_VESSELS = 3;

    private final JdbcAnalysisRunRepository runs;
    private final JdbcAggregateRepository aggregates;
    private final JdbcReceiverProfileRepository receivers;
    private final JdbcAnalysisProfileRepository profiles;
    private final JdbcDailyDataQualityRepository dataQuality;
    private final JdbcShipLengthAnalysisRepository shipLengths;
    private final JdbcShipLengthPerformanceRepository shipLengthPerformance;
    private final ExecutorService executor;

    public AggregateQueryService(SqliteDatabase database) {
        Objects.requireNonNull(database, "database");
        runs = new JdbcAnalysisRunRepository(database);
        aggregates = new JdbcAggregateRepository(database);
        receivers = new JdbcReceiverProfileRepository(database);
        profiles = new JdbcAnalysisProfileRepository(database);
        dataQuality = new JdbcDailyDataQualityRepository(database);
        shipLengths = new JdbcShipLengthAnalysisRepository(database);
        shipLengthPerformance =
                new JdbcShipLengthPerformanceRepository(database);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ais-aggregate-query");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<AggregateResult> query(AggregateRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(() -> queryNow(request), executor);
    }

    public CompletableFuture<DailyDataQualityResult> queryQuality(
            DailyDataQualityRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(
                () -> queryQualityNow(request), executor);
    }

    public CompletableFuture<ShipLengthAnalysisResult> queryShipLength(
            ShipLengthAnalysisRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(
                () -> queryShipLengthNow(request), executor);
    }

    public CompletableFuture<ShipLengthPerformanceResult>
            queryShipLengthPerformance(
                    ShipLengthPerformanceRequest request) {
        Objects.requireNonNull(request, "request");
        return CompletableFuture.supplyAsync(
                () -> queryShipLengthPerformanceNow(request), executor);
    }

    public List<ReceiverProfile> receiverProfiles() {
        return receivers.findAll();
    }

    public List<AnalysisProfile> analysisProfiles() {
        return profiles.findAll();
    }

    AggregateResult queryNow(AggregateRequest request) {
        Instant from = request.startDate().atStartOfDay(JAPAN).toInstant();
        Instant to = request.endDate().plusDays(1)
                .atStartOfDay(JAPAN).toInstant();
        ReceiverProfile receiver = receivers.findById(
                        request.receiverProfileId())
                .orElseThrow(() -> new IllegalStateException(
                        "受信局プロファイルが見つかりません"));
        AnalysisProfile profile = profiles.findById(
                        request.analysisProfileId())
                .orElseThrow(() -> new IllegalStateException(
                        "解析条件が見つかりません"));
        List<StoredAnalysisRun> matching = runs.findCompleted(
                from, to, request.receiverProfileId(),
                request.analysisProfileId());
        AggregateRollupQuery rollupQuery = new AggregateRollupQuery(
                from, to, request.dimension(), request.vesselClasses(),
                request.receiverProfileId(), request.analysisProfileId(),
                request.excludedDates());
        List<AggregateRow> rows;
        if (matching.isEmpty()) {
            rows = List.of();
        } else {
            rows = switch (request.axis()) {
                case DISTANCE_BAND -> distanceRows(
                        aggregates.queryDistanceRollups(rollupQuery),
                        request, profile);
                case HOUR_OF_DAY -> hourRows(
                        aggregates.queryHourRollups(rollupQuery), profile);
                case DISTANCE_BY_HOUR -> distanceHourRows(
                        aggregates.queryDistanceHourRollups(rollupQuery),
                        profile);
            };
        }
        int includedRunCount = (int) matching.stream()
                .filter(stored -> stored.run().targetDate() == null
                        || !request.excludedDates().contains(
                        stored.run().targetDate()))
                .count();
        return new AggregateResult(request, receiver, profile,
                includedRunCount, rows);
    }

    DailyDataQualityResult queryQualityNow(
            DailyDataQualityRequest request) {
        ReceiverProfile receiver = receivers.findById(
                        request.receiverProfileId())
                .orElseThrow(() -> new IllegalStateException(
                        "受信局プロファイルが見つかりません"));
        AnalysisProfile profile = profiles.findById(
                        request.analysisProfileId())
                .orElseThrow(() -> new IllegalStateException(
                        "解析条件が見つかりません"));
        List<StoredDailyDataQuality> stored = dataQuality.query(
                new DailyDataQualityQuery(
                        request.startDate(), request.endDate(),
                        request.receiverProfileId(),
                        request.analysisProfileId()));
        Map<LocalDate, StoredDailyDataQuality> byDate = new HashMap<>();
        stored.forEach(row -> byDate.put(row.date(), row));
        List<DailyDataQualityRow> rows = new ArrayList<>();
        for (LocalDate date = request.startDate();
                !date.isAfter(request.endDate());
                date = date.plusDays(1)) {
            StoredDailyDataQuality value = byDate.get(date);
            rows.add(value == null ? missingQualityRow(date)
                    : qualityRow(value));
        }
        return new DailyDataQualityResult(
                request, receiver, profile, rows);
    }

    ShipLengthAnalysisResult queryShipLengthNow(
            ShipLengthAnalysisRequest request) {
        ReceiverProfile receiver = receivers.findById(
                        request.receiverProfileId())
                .orElseThrow(() -> new IllegalStateException(
                        "受信局プロファイルが見つかりません"));
        AnalysisProfile profile = profiles.findById(
                        request.analysisProfileId())
                .orElseThrow(() -> new IllegalStateException(
                        "解析条件が見つかりません"));
        StoredShipLengthAnalysis stored = shipLengths.query(
                new ShipLengthAnalysisQuery(
                        request.startDate(), request.endDate(),
                        request.vesselClasses(),
                        request.receiverProfileId(),
                        request.analysisProfileId(),
                        request.excludedDates()));
        List<ShipLengthDistanceCell> cells = shipLengthCells(
                stored.cells(), profile);
        List<ShipLengthAnalysisRow> rows = shipLengthRows(
                stored.cells(), profile);
        return new ShipLengthAnalysisResult(
                request, receiver, profile,
                stored.coverage().analysisRunCount(),
                stored.coverage().totalDistinctVesselCount(),
                stored.coverage().knownLengthDistinctVesselCount(),
                stored.coverage().totalVesselDayCount(),
                stored.coverage().knownLengthVesselDayCount(),
                rows, cells);
    }

    ShipLengthPerformanceResult queryShipLengthPerformanceNow(
            ShipLengthPerformanceRequest request) {
        ReceiverProfile receiver = receivers.findById(
                        request.receiverProfileId())
                .orElseThrow(() -> new IllegalStateException(
                        "受信局プロファイルが見つかりません"));
        AnalysisProfile profile = profiles.findById(
                        request.analysisProfileId())
                .orElseThrow(() -> new IllegalStateException(
                        "解析条件が見つかりません"));
        StoredShipLengthPerformance stored = shipLengthPerformance.query(
                new ShipLengthPerformanceQuery(
                        request.startDate(), request.endDate(),
                        request.vesselClasses(),
                        request.receiverProfileId(),
                        request.analysisProfileId(),
                        request.excludedDates()));
        MetricCalculator calculator = new MetricCalculator(profile);
        List<ShipLengthPerformanceRow> rows = stored.rows().stream()
                .map(row -> new ShipLengthPerformanceRow(
                        ShipLengthBand.fromOrder(
                                row.shipLengthBandOrder()),
                        row.distanceBand(),
                        row.vesselClass(),
                        calculator.evaluate(row.counts(),
                                row.distinctVesselCount()),
                        row.observationDayCount()))
                .toList();
        return new ShipLengthPerformanceResult(
                request, receiver, profile,
                stored.readyAnalysisRunCount(),
                stored.reanalysisRequiredRunCount(),
                stored.totalDistinctVesselCount(),
                stored.knownLengthDistinctVesselCount(),
                rows);
    }

    private static List<ShipLengthDistanceCell> shipLengthCells(
            List<StoredShipLengthCell> stored,
            AnalysisProfile profile) {
        return stored.stream()
                .map(cell -> {
                    ShipLengthBand lengthBand = ShipLengthBand.fromOrder(
                            cell.shipLengthBandOrder());
                    boolean sufficient = sufficientLengthSample(cell);
                    return new ShipLengthDistanceCell(
                            lengthBand,
                            cell.vesselClass(),
                            distanceBand(
                                    cell.dailyMaximumDistanceBandIndex(),
                                    profile),
                            cell.vesselDayCount(),
                            cell.cellDistinctVesselCount(),
                            cell.vesselDayCount() * 100.0
                                    / cell.bandVesselDayCount(),
                            sufficient);
                })
                .toList();
    }

    private static List<ShipLengthAnalysisRow> shipLengthRows(
            List<StoredShipLengthCell> stored,
            AnalysisProfile profile) {
        Map<ShipLengthGroupKey, List<StoredShipLengthCell>> groups =
                new LinkedHashMap<>();
        stored.stream()
                .sorted(Comparator
                        .comparingInt(
                                StoredShipLengthCell::shipLengthBandOrder)
                        .thenComparing(StoredShipLengthCell::vesselClass)
                        .thenComparingInt(StoredShipLengthCell::
                                dailyMaximumDistanceBandIndex))
                .forEach(cell -> groups.computeIfAbsent(
                        new ShipLengthGroupKey(
                                cell.shipLengthBandOrder(),
                                cell.vesselClass()),
                        ignored -> new ArrayList<>()).add(cell));
        List<ShipLengthAnalysisRow> rows = new ArrayList<>();
        for (Map.Entry<ShipLengthGroupKey,
                List<StoredShipLengthCell>> entry : groups.entrySet()) {
            List<StoredShipLengthCell> cells = entry.getValue();
            StoredShipLengthCell first = cells.getFirst();
            long vesselDays = first.bandVesselDayCount();
            int distinctVessels = first.bandDistinctVesselCount();
            double weightedLowerKilometers = cells.stream()
                    .mapToDouble(cell ->
                            cell.dailyMaximumDistanceBandIndex()
                                    * profile.distanceBinKilometers()
                                    * (double) cell.vesselDayCount())
                    .sum();
            long thirty = cells.stream()
                    .filter(cell -> lowerKilometers(cell, profile) >= 30.0)
                    .mapToLong(StoredShipLengthCell::vesselDayCount)
                    .sum();
            long fifty = cells.stream()
                    .filter(cell -> lowerKilometers(cell, profile) >= 50.0)
                    .mapToLong(StoredShipLengthCell::vesselDayCount)
                    .sum();
            rows.add(new ShipLengthAnalysisRow(
                    ShipLengthBand.fromOrder(
                            entry.getKey().shipLengthBandOrder()),
                    entry.getKey().vesselClass(),
                    distinctVessels,
                    vesselDays,
                    weightedLowerKilometers / vesselDays,
                    medianDistanceBand(cells, vesselDays, profile),
                    thirty,
                    thirty * 100.0 / vesselDays,
                    fifty,
                    fifty * 100.0 / vesselDays,
                    sufficientLengthSample(first)));
        }
        return List.copyOf(rows);
    }

    private static String medianDistanceBand(
            List<StoredShipLengthCell> cells,
            long vesselDays,
            AnalysisProfile profile) {
        long target = (vesselDays + 1) / 2;
        long cumulative = 0;
        for (StoredShipLengthCell cell : cells) {
            cumulative += cell.vesselDayCount();
            if (cumulative >= target) {
                return distanceBand(
                        cell.dailyMaximumDistanceBandIndex(), profile)
                        .label();
            }
        }
        throw new IllegalStateException(
                "船体長別分析の中央値を計算できません");
    }

    private static double lowerKilometers(
            StoredShipLengthCell cell,
            AnalysisProfile profile) {
        return cell.dailyMaximumDistanceBandIndex()
                * (double) profile.distanceBinKilometers();
    }

    private static DistanceBand distanceBand(
            int index,
            AnalysisProfile profile) {
        double lower = index
                * (double) profile.distanceBinKilometers();
        return new DistanceBand(index, lower,
                lower + profile.distanceBinKilometers());
    }

    private static boolean sufficientLengthSample(
            StoredShipLengthCell cell) {
        return cell.bandVesselDayCount() >= MINIMUM_LENGTH_VESSEL_DAYS
                && cell.bandDistinctVesselCount()
                >= MINIMUM_LENGTH_DISTINCT_VESSELS;
    }

    private record ShipLengthGroupKey(
            int shipLengthBandOrder,
            VesselClass vesselClass) {
    }

    private static DailyDataQualityRow missingQualityRow(LocalDate date) {
        return new DailyDataQualityRow(
                date, DailyDataQualityState.NOT_ANALYZED,
                "", 0, 0, null, null, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                "SQLiteに保存された解析結果がありません");
    }

    private static DailyDataQualityRow qualityRow(
            StoredDailyDataQuality value) {
        int buckets = value.aggregateBucketCount();
        DailyDataQualityState state;
        if (value.sourceFailureCount() > 0
                || value.acceptedIntervalCount() == 0
                || buckets <= 275
                || buckets > DailyDataQualityRow.EXPECTED_BUCKETS_PER_DAY) {
            state = DailyDataQualityState.REVIEW_REQUIRED;
        } else if (buckets
                == DailyDataQualityRow.EXPECTED_BUCKETS_PER_DAY) {
            state = DailyDataQualityState.ALL_BUCKETS_PRESENT;
        } else {
            state = DailyDataQualityState.PARTIAL;
        }
        long decoded = Math.addExact(
                Math.addExact(value.acceptedIntervalCount(),
                        value.metadataUpdateCount()),
                value.intervalEventCount());
        return new DailyDataQualityRow(
                value.date(), state, value.inputName(),
                value.inputFileCount(), value.inputUncompressedBytes(),
                value.firstAggregateBucket(), value.lastAggregateBucket(),
                buckets, value.distinctVesselCount(), decoded,
                value.acceptedIntervalCount(),
                value.estimatedMissingCount(), value.duplicateCount(),
                value.inputAnomalyCount(), value.invalidPositionCount(),
                value.thirtyMinuteGapCount(), value.distanceJumpCount(),
                value.outsideDistanceRangeCount(), qualityNote(value));
    }

    private static String qualityNote(StoredDailyDataQuality value) {
        List<String> notes = new ArrayList<>();
        int missingBuckets = DailyDataQualityRow.EXPECTED_BUCKETS_PER_DAY
                - value.aggregateBucketCount();
        if (missingBuckets > 0) {
            notes.add("解析5分枠が" + missingBuckets + "枠不足");
        } else if (missingBuckets < 0) {
            notes.add("解析5分枠が期待値を"
                    + (-missingBuckets) + "枠超過");
        }
        if (value.acceptedIntervalCount() == 0) {
            notes.add("採用区間なし");
        }
        if (value.sourceFailureCount() > 0) {
            notes.add("入力読込/処理遅延診断 "
                    + value.sourceFailureCount() + "件");
        }
        if (value.inputAnomalyCount() > 0) {
            notes.add("入力形式・復号診断 "
                    + value.inputAnomalyCount() + "件");
        }
        if (value.invalidPositionCount() > 0) {
            notes.add("位置利用不可 "
                    + value.invalidPositionCount() + "件");
        }
        return notes.isEmpty() ? "記録上の注意事項なし"
                : String.join(" / ", notes);
    }

    private static List<AggregateRow> distanceRows(
            List<StoredDistanceRollup> rollups,
            AggregateRequest request,
            AnalysisProfile profile) {
        MetricCalculator calculator = new MetricCalculator(profile);
        return rollups.stream()
                .map(rollup -> new AggregateRow(
                        periodLabel(rollup.periodValue(),
                                request.dimension()),
                        rollup.distanceBand().label(),
                        rollup.vesselClass(),
                        calculator.evaluate(rollup.counts(),
                                rollup.distinctVesselCount()),
                        rollup.observationDayCount(),
                        rollup.distanceBand().index()))
                .sorted(rowComparator(request.dimension()))
                .toList();
    }

    private static List<AggregateRow> hourRows(
            List<StoredHourRollup> rollups,
            AnalysisProfile profile) {
        MetricCalculator calculator = new MetricCalculator(profile);
        return rollups.stream()
                .map(rollup -> new AggregateRow(
                        classLabel(rollup.vesselClass()),
                        String.format("%02d時", rollup.hour()),
                        rollup.vesselClass(),
                        calculator.evaluate(rollup.counts(),
                                rollup.distinctVesselCount()),
                        rollup.observationDayCount(),
                        rollup.hour()))
                .sorted(Comparator.comparingInt(AggregateRow::categoryOrder)
                        .thenComparing(row -> row.vesselClass().name()))
                .toList();
    }

    private static List<AggregateRow> distanceHourRows(
            List<StoredDistanceHourRollup> rollups,
            AnalysisProfile profile) {
        MetricCalculator calculator = new MetricCalculator(profile);
        return rollups.stream()
                .map(rollup -> new AggregateRow(
                        classLabel(rollup.vesselClass()),
                        rollup.distanceBand().label(),
                        rollup.vesselClass(),
                        calculator.evaluate(rollup.counts(),
                                rollup.distinctVesselCount()),
                        rollup.observationDayCount(),
                        rollup.distanceBand().index(),
                        rollup.hour()))
                .sorted(Comparator
                        .comparingInt((AggregateRow row) -> row.hourOfDay())
                        .thenComparingInt(AggregateRow::categoryOrder)
                        .thenComparing(row -> row.vesselClass().name()))
                .toList();
    }

    private static Comparator<AggregateRow> rowComparator(
            RollupDimension dimension) {
        return Comparator
                .comparingInt((AggregateRow row) ->
                        periodOrder(row.seriesLabel(), dimension))
                .thenComparing(AggregateRow::seriesLabel)
                .thenComparingInt(AggregateRow::categoryOrder)
                .thenComparing(row -> row.vesselClass().name());
    }

    private static int periodOrder(String value, RollupDimension dimension) {
        if (dimension == RollupDimension.DAY_OF_WEEK) {
            return switch (value) {
                case "MONDAY", "月曜" -> 1;
                case "TUESDAY", "火曜" -> 2;
                case "WEDNESDAY", "水曜" -> 3;
                case "THURSDAY", "木曜" -> 4;
                case "FRIDAY", "金曜" -> 5;
                case "SATURDAY", "土曜" -> 6;
                case "SUNDAY", "日曜" -> 7;
                default -> 99;
            };
        }
        return 0;
    }

    private static String periodLabel(String value,
                                      RollupDimension dimension) {
        if (dimension != RollupDimension.DAY_OF_WEEK) {
            return value;
        }
        return switch (value) {
            case "MONDAY" -> "月曜";
            case "TUESDAY" -> "火曜";
            case "WEDNESDAY" -> "水曜";
            case "THURSDAY" -> "木曜";
            case "FRIDAY" -> "金曜";
            case "SATURDAY" -> "土曜";
            case "SUNDAY" -> "日曜";
            default -> value;
        };
    }

    private static String classLabel(VesselClass vesselClass) {
        return vesselClass == VesselClass.CLASS_A ? "Class A" : "Class B";
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
