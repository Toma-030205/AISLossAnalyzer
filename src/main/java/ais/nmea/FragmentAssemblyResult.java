package ais.nmea;

import ais.input.InputDiagnostic;

import java.util.List;

public record FragmentAssemblyResult(
        List<CompletedAisPayload> completedPayloads,
        List<InputDiagnostic> diagnostics) {

    public FragmentAssemblyResult {
        completedPayloads = List.copyOf(completedPayloads);
        diagnostics = List.copyOf(diagnostics);
    }
}
