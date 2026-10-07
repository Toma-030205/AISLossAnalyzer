package ais.storage;

import ais.domain.AnalysisProfileId;
import ais.domain.AnalysisRunId;
import ais.domain.ReceiverProfileId;
import ais.domain.VesselClass;
import ais.simulation.calibration.CommunicationModelCode;
import ais.simulation.calibration.CommunicationModelDefinition;
import ais.simulation.calibration.CommunicationModelDraft;
import ais.simulation.calibration.CommunicationModelId;
import ais.simulation.calibration.CommunicationModelSnapshot;
import ais.simulation.calibration.CommunicationParameter;
import ais.simulation.calibration.ConfidenceInterval;
import ais.simulation.calibration.ParameterApplicability;
import ais.spatial.DistanceBand;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class JdbcCommunicationModelRepository
        implements CommunicationModelRepository {

    private static final String MODEL_COLUMNS = """
            SELECT id, model_code, revision, name,
                   receiver_profile_id, analysis_profile_id,
                   training_start_date, training_end_date,
                   formula_version, bootstrap_iterations, bootstrap_seed,
                   created_at, notes
              FROM communication_model
            """;

    private final SqliteDatabase database;
    private final TransactionRunner transactions;

    public JdbcCommunicationModelRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        transactions = new TransactionRunner(database);
    }

    @Override
    public CommunicationModelDefinition save(
            CommunicationModelDraft draft,
            String name,
            String notes) {
        Objects.requireNonNull(draft, "draft");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("モデル名を入力してください");
        }
        return transactions.execute(connection -> {
            verifySourceRuns(connection, draft);
            int revision = nextRevision(connection, draft);
            CommunicationModelDefinition definition =
                    new CommunicationModelDefinition(
                            CommunicationModelId.create(),
                            draft.request().modelCode(), revision,
                            name, draft.request().receiverProfileId(),
                            draft.request().analysisProfileId(),
                            draft.request().startDate(),
                            draft.request().endDate(),
                            draft.formulaVersion(),
                            draft.request().bootstrapIterations(),
                            draft.request().bootstrapSeed(),
                            Instant.now(), notes);
            insertDefinition(connection, definition);
            insertExcludedDates(connection, definition.id(), draft);
            insertSourceRuns(connection, definition.id(), draft);
            insertParameters(connection, definition.id(),
                    draft.parameters());
            return definition;
        });
    }

    @Override
    public List<CommunicationModelDefinition> findAll() {
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(
                        MODEL_COLUMNS + " ORDER BY created_at DESC, id");
                ResultSet results = statement.executeQuery()) {
            List<CommunicationModelDefinition> definitions =
                    new ArrayList<>();
            while (results.next()) {
                definitions.add(mapDefinition(results));
            }
            return List.copyOf(definitions);
        } catch (SQLException failure) {
            throw new StorageException(
                    "could not list communication models", failure);
        }
    }

    @Override
    public Optional<CommunicationModelSnapshot> findById(
            CommunicationModelId modelId) {
        Objects.requireNonNull(modelId, "modelId");
        try (Connection connection = database.open();
                PreparedStatement statement = connection.prepareStatement(
                        MODEL_COLUMNS + " WHERE id = ?")) {
            statement.setString(1, modelId.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                CommunicationModelDefinition definition =
                        mapDefinition(results);
                return Optional.of(new CommunicationModelSnapshot(
                        definition,
                        readExcludedDates(connection, modelId),
                        readSourceRuns(connection, modelId),
                        readParameters(connection, modelId)));
            }
        } catch (SQLException failure) {
            throw new StorageException(
                    "could not read communication model", failure);
        }
    }

    private static void verifySourceRuns(
            Connection connection,
            CommunicationModelDraft draft) throws SQLException {
        String sql = """
                SELECT id, target_date
                  FROM analysis_run
                 WHERE source_mode = 'HISTORICAL'
                   AND active = 1
                   AND status = 'COMPLETE'
                   AND receiver_profile_id = ?
                   AND analysis_profile_id = ?
                   AND target_date >= ?
                   AND target_date <= ?
                """;
        Set<AnalysisRunId> current = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1,
                    draft.request().receiverProfileId().value());
            statement.setString(2,
                    draft.request().analysisProfileId().value());
            statement.setString(3, draft.request().startDate().toString());
            statement.setString(4, draft.request().endDate().toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    LocalDate date = LocalDate.parse(
                            results.getString("target_date"));
                    if (!draft.request().excludedDates().contains(date)) {
                        current.add(AnalysisRunId.parse(
                                results.getString("id")));
                    }
                }
            }
        }
        if (!current.equals(new LinkedHashSet<>(draft.sourceRunIds()))) {
            throw new SQLException(
                    "analysis runs changed after preview; preview again");
        }
    }

    private static int nextRevision(
            Connection connection,
            CommunicationModelDraft draft) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(MAX(revision), 0) + 1
                  FROM communication_model
                 WHERE model_code = ?
                   AND receiver_profile_id = ?
                   AND analysis_profile_id = ?
                """)) {
            statement.setString(1, draft.request().modelCode().name());
            statement.setString(2,
                    draft.request().receiverProfileId().value());
            statement.setString(3,
                    draft.request().analysisProfileId().value());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new SQLException("could not allocate model revision");
                }
                return results.getInt(1);
            }
        }
    }

    private static void insertDefinition(
            Connection connection,
            CommunicationModelDefinition definition) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO communication_model (
                    id, model_code, revision, name,
                    receiver_profile_id, analysis_profile_id,
                    training_start_date, training_end_date,
                    formula_version, bootstrap_iterations, bootstrap_seed,
                    created_at, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, definition.id().toString());
            statement.setString(2, definition.modelCode().name());
            statement.setInt(3, definition.revision());
            statement.setString(4, definition.name());
            statement.setString(5,
                    definition.receiverProfileId().value());
            statement.setString(6,
                    definition.analysisProfileId().value());
            statement.setString(7,
                    definition.trainingStartDate().toString());
            statement.setString(8,
                    definition.trainingEndDate().toString());
            statement.setString(9, definition.formulaVersion());
            statement.setInt(10, definition.bootstrapIterations());
            statement.setLong(11, definition.bootstrapSeed());
            statement.setString(12,
                    JdbcSupport.instant(definition.createdAt()));
            statement.setString(13, definition.notes());
            statement.executeUpdate();
        }
    }

    private static void insertExcludedDates(
            Connection connection,
            CommunicationModelId modelId,
            CommunicationModelDraft draft) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO communication_model_excluded_date (
                    model_id, excluded_date, reason)
                VALUES (?, ?, ?)
                """)) {
            for (LocalDate date : draft.request().excludedDates().stream()
                    .sorted().toList()) {
                statement.setString(1, modelId.toString());
                statement.setString(2, date.toString());
                statement.setString(3, "利用者指定の学習除外日");
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertSourceRuns(
            Connection connection,
            CommunicationModelId modelId,
            CommunicationModelDraft draft) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO communication_model_source_run (
                    model_id, analysis_run_id)
                VALUES (?, ?)
                """)) {
            for (AnalysisRunId runId : draft.sourceRunIds()) {
                statement.setString(1, modelId.toString());
                statement.setString(2, runId.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertParameters(
            Connection connection,
            CommunicationModelId modelId,
            List<CommunicationParameter> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO communication_model_parameter (
                    model_id, distance_band_index,
                    lower_kilometers, upper_kilometers, vessel_class,
                    observed_count, missing_count, expected_count,
                    distinct_vessels, observed_days,
                    raw_loss_rate, raw_reception_rate,
                    jeffreys_reception_probability, ci_lower, ci_upper,
                    applicability, applied_reception_probability,
                    lower_source_band_index, upper_source_band_index)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?)
                """)) {
            for (CommunicationParameter parameter : parameters) {
                statement.setString(1, modelId.toString());
                statement.setInt(2, parameter.distanceBand().index());
                statement.setDouble(3,
                        parameter.distanceBand().lowerKilometers());
                statement.setDouble(4,
                        parameter.distanceBand().upperKilometers());
                statement.setString(5, parameter.vesselClass().name());
                statement.setLong(6, parameter.observedCount());
                statement.setLong(7, parameter.missingCount());
                statement.setLong(8, parameter.expectedCount());
                statement.setInt(9, parameter.distinctVesselCount());
                statement.setInt(10, parameter.observedDayCount());
                JdbcSupport.setNullableDouble(statement, 11,
                        parameter.rawLossRate());
                JdbcSupport.setNullableDouble(statement, 12,
                        parameter.rawReceptionRate());
                JdbcSupport.setNullableDouble(statement, 13,
                        parameter.jeffreysReceptionProbability());
                ConfidenceInterval interval =
                        parameter.confidenceInterval();
                JdbcSupport.setNullableDouble(statement, 14,
                        interval == null ? null : interval.lower());
                JdbcSupport.setNullableDouble(statement, 15,
                        interval == null ? null : interval.upper());
                statement.setString(16,
                        parameter.applicability().name());
                JdbcSupport.setNullableDouble(statement, 17,
                        parameter.appliedReceptionProbability());
                JdbcSupport.setNullableInteger(statement, 18,
                        parameter.lowerSourceBandIndex());
                JdbcSupport.setNullableInteger(statement, 19,
                        parameter.upperSourceBandIndex());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Map<LocalDate, String> readExcludedDates(
            Connection connection,
            CommunicationModelId modelId) throws SQLException {
        Map<LocalDate, String> values = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT excluded_date, reason
                  FROM communication_model_excluded_date
                 WHERE model_id = ?
                 ORDER BY excluded_date
                """)) {
            statement.setString(1, modelId.toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    values.put(LocalDate.parse(
                                    results.getString("excluded_date")),
                            results.getString("reason"));
                }
            }
        }
        return Map.copyOf(values);
    }

    private static List<AnalysisRunId> readSourceRuns(
            Connection connection,
            CommunicationModelId modelId) throws SQLException {
        List<AnalysisRunId> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT analysis_run_id
                  FROM communication_model_source_run
                 WHERE model_id = ?
                 ORDER BY analysis_run_id
                """)) {
            statement.setString(1, modelId.toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    values.add(AnalysisRunId.parse(
                            results.getString("analysis_run_id")));
                }
            }
        }
        return List.copyOf(values);
    }

    private static List<CommunicationParameter> readParameters(
            Connection connection,
            CommunicationModelId modelId) throws SQLException {
        List<CommunicationParameter> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT distance_band_index,
                       lower_kilometers, upper_kilometers, vessel_class,
                       observed_count, missing_count, distinct_vessels,
                       observed_days, raw_loss_rate, raw_reception_rate,
                       jeffreys_reception_probability, ci_lower, ci_upper,
                       applicability, applied_reception_probability,
                       lower_source_band_index, upper_source_band_index
                  FROM communication_model_parameter
                 WHERE model_id = ?
                 ORDER BY distance_band_index, vessel_class
                """)) {
            statement.setString(1, modelId.toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    Double lower = JdbcSupport.nullableDouble(
                            results, "ci_lower");
                    Double upper = JdbcSupport.nullableDouble(
                            results, "ci_upper");
                    values.add(new CommunicationParameter(
                            new DistanceBand(
                                    results.getInt("distance_band_index"),
                                    results.getDouble("lower_kilometers"),
                                    results.getDouble("upper_kilometers")),
                            VesselClass.valueOf(
                                    results.getString("vessel_class")),
                            results.getLong("observed_count"),
                            results.getLong("missing_count"),
                            results.getInt("distinct_vessels"),
                            results.getInt("observed_days"),
                            JdbcSupport.nullableDouble(
                                    results, "raw_loss_rate"),
                            JdbcSupport.nullableDouble(
                                    results, "raw_reception_rate"),
                            JdbcSupport.nullableDouble(results,
                                    "jeffreys_reception_probability"),
                            lower == null || upper == null ? null
                                    : new ConfidenceInterval(lower, upper),
                            ParameterApplicability.valueOf(
                                    results.getString("applicability")),
                            JdbcSupport.nullableDouble(results,
                                    "applied_reception_probability"),
                            JdbcSupport.nullableInteger(results,
                                    "lower_source_band_index"),
                            JdbcSupport.nullableInteger(results,
                                    "upper_source_band_index")));
                }
            }
        }
        return List.copyOf(values);
    }

    private static CommunicationModelDefinition mapDefinition(
            ResultSet results) throws SQLException {
        return new CommunicationModelDefinition(
                CommunicationModelId.parse(results.getString("id")),
                CommunicationModelCode.valueOf(
                        results.getString("model_code")),
                results.getInt("revision"),
                results.getString("name"),
                new ReceiverProfileId(
                        results.getString("receiver_profile_id")),
                new AnalysisProfileId(
                        results.getString("analysis_profile_id")),
                LocalDate.parse(results.getString("training_start_date")),
                LocalDate.parse(results.getString("training_end_date")),
                results.getString("formula_version"),
                results.getInt("bootstrap_iterations"),
                results.getLong("bootstrap_seed"),
                Instant.parse(results.getString("created_at")),
                results.getString("notes"));
    }
}
