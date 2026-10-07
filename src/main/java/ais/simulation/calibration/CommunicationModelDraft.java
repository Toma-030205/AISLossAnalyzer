package ais.simulation.calibration;

import ais.domain.AnalysisProfile;
import ais.domain.AnalysisRunId;
import ais.domain.ReceiverProfile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record CommunicationModelDraft(
        CommunicationTrainingRequest request,
        ReceiverProfile receiverProfile,
        AnalysisProfile analysisProfile,
        String formulaVersion,
        List<CommunicationParameter> parameters,
        List<AnalysisRunId> sourceRunIds,
        List<LocalDate> missingDates,
        List<String> warnings,
        Instant generatedAt) {

    public CommunicationModelDraft {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receiverProfile, "receiverProfile");
        Objects.requireNonNull(analysisProfile, "analysisProfile");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(sourceRunIds, "sourceRunIds");
        Objects.requireNonNull(missingDates, "missingDates");
        Objects.requireNonNull(warnings, "warnings");
        Objects.requireNonNull(generatedAt, "generatedAt");
        if (formulaVersion == null || formulaVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "formulaVersion must not be blank");
        }
        formulaVersion = formulaVersion.trim();
        parameters = List.copyOf(parameters);
        sourceRunIds = List.copyOf(sourceRunIds);
        missingDates = List.copyOf(missingDates);
        warnings = List.copyOf(warnings);
    }

    public long directParameterCount() {
        return parameters.stream().filter(parameter ->
                parameter.applicability() == ParameterApplicability.DIRECT)
                .count();
    }

    public long interpolatedParameterCount() {
        return parameters.stream().filter(parameter ->
                parameter.applicability()
                        == ParameterApplicability.INTERPOLATED)
                .count();
    }
}
