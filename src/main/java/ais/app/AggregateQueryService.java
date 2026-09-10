package ais.app;

import ais.aggregate.AggregateKey;
import ais.aggregate.AggregateMetric;
import ais.aggregate.AggregationSnapshot;
import ais.aggregate.MetricCalculator;
import ais.aggregate.MetricCounts;
import ais.aggregate.PeriodRollupService;
import ais.aggregate.RollupDimension;
import ais.aggregate.RollupKey;
import ais.aggregate.RollupMetric;
import ais.domain.AnalysisProfile;
import ais.domain.ReceiverProfile;
import ais.domain.VesselClass;
import ais.spatial.DistanceBand;
import ais.spatial.GridCellId;
import ais.storage.AggregateQuery;
import ais.storage.JdbcAggregateRepository;
import ais.storage.JdbcAnalysisProfileRepository;
import ais.storage.JdbcAnalysisRunRepository;
import ais.storage.JdbcReceiverProfileRepository;
import ais.storage.SqliteDatabase;
import ais.storage.StoredAnalysisRun;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AggregateQueryService implements AutoCloseable {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final JdbcAnalysisRunRepository runs;
    private final JdbcAggregateRepository aggregates;
    private final JdbcReceiverProfileRepository receivers;
    private final JdbcAnalysisProfileRepository profiles;
    private final ExecutorService executor;

    public AggregateQueryService(SqliteDatabase database) {
        Objects.requireNonNull(database, "database");
        runs = new JdbcAnalysisRunRepository(database);
        aggregates = new JdbcAggregateRepository(database);
        receivers = new JdbcReceiverProfileRepository(database);
        profiles = new JdbcAnalysisProfileRepository(database);
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
        AggregationSnapshot combined = AggregationSnapshot.empty();
        for (StoredAnalysisRun stored : matching) {
            AggregationSnapshot snapshot = aggregates.query(
                    new AggregateQuery(stored.run().id(), from, to,
                            request.vesselClasses()));
            combined = merge(combined, snapshot);
        }
        List<AggregateRow> rows = request.axis()
                == AggregateAxis.DISTANCE_BAND
                ? distanceRows(combined, request, profile)
                : hourRows(combined, request, profile);
        return new AggregateResult(request, receiver, profile,
                matching.size(), rows);
    }

    private static List<AggregateRow> distanceRows(
            AggregationSnapshot snapshot,
            AggregateRequest request,
            AnalysisProfile profile) {
        Map<RollupKey<DistanceBand>, RollupMetric> rollups =
                new PeriodRollupService(JAPAN).rollup(
                        snapshot.distanceMetrics(), request.dimension());
        MetricCalculator calculator = new MetricCalculator(profile);
        return rollups.entrySet().stream()
                .map(entry -> new AggregateRow(
                        periodLabel(entry.getKey().periodValue(),
                                request.dimension()),
                        entry.getKey().spatialKey().label(),
                        entry.getKey().vesselClass(),
                        calculator.evaluate(entry.getValue().metric()),
                        entry.getValue().observationDayCount(),
                        entry.getKey().spatialKey().index()))
                .sorted(rowComparator(request.dimension()))
                .toList();
    }

    private static List<AggregateRow> hourRows(
            AggregationSnapshot snapshot,
            AggregateRequest request,
            AnalysisProfile profile) {
        Map<AggregateKey<String>, MutableMetric> byBucket = new HashMap<>();
        snapshot.distanceMetrics().forEach((key, value) -> {
            AggregateKey<String> target = new AggregateKey<>(
                    key.bucketStart(), "全距離帯", key.vesselClass());
            byBucket.computeIfAbsent(target, ignored -> new MutableMetric())
                    .add(value);
        });
        Map<AggregateKey<String>, AggregateMetric> metrics =
                new LinkedHashMap<>();
        byBucket.forEach((key, value) -> metrics.put(key, value.toMetric()));
        Map<RollupKey<String>, RollupMetric> rollups =
                new PeriodRollupService(JAPAN).rollup(
                        metrics, RollupDimension.HOUR_OF_DAY);
        MetricCalculator calculator = new MetricCalculator(profile);
        return rollups.entrySet().stream()
                .map(entry -> new AggregateRow(
                        classLabel(entry.getKey().vesselClass()),
                        String.format("%02d時", Integer.parseInt(
                                entry.getKey().periodValue())),
                        entry.getKey().vesselClass(),
                        calculator.evaluate(entry.getValue().metric()),
                        entry.getValue().observationDayCount(),
                        Integer.parseInt(entry.getKey().periodValue())))
                .sorted(Comparator.comparingInt(AggregateRow::categoryOrder)
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

    static AggregationSnapshot merge(AggregationSnapshot left,
                                     AggregationSnapshot right) {
        return new AggregationSnapshot(
                mergeMetrics(left.gridMetrics(), right.gridMetrics()),
                mergeMetrics(left.distanceMetrics(), right.distanceMetrics()),
                Math.addExact(left.outsideDistanceRangeCount(),
                        right.outsideDistanceRangeCount()));
    }

    private static <S> Map<AggregateKey<S>, AggregateMetric> mergeMetrics(
            Map<AggregateKey<S>, AggregateMetric> left,
            Map<AggregateKey<S>, AggregateMetric> right) {
        Map<AggregateKey<S>, MutableMetric> result = new HashMap<>();
        left.forEach((key, value) -> result
                .computeIfAbsent(key, ignored -> new MutableMetric())
                .add(value));
        right.forEach((key, value) -> result
                .computeIfAbsent(key, ignored -> new MutableMetric())
                .add(value));
        Map<AggregateKey<S>, AggregateMetric> immutable = new HashMap<>();
        result.forEach((key, value) -> immutable.put(key, value.toMetric()));
        return Map.copyOf(immutable);
    }

    private static final class MutableMetric {
        private MetricCounts counts = MetricCounts.ZERO;
        private final Set<Integer> vessels = new HashSet<>();

        private void add(AggregateMetric metric) {
            counts = counts.plus(metric.counts());
            vessels.addAll(metric.vesselMmsis());
        }

        private AggregateMetric toMetric() {
            return new AggregateMetric(counts, vessels);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
