package ais.input;

import ais.domain.NormalizedAisEvent;

import java.util.List;

public record PipelineResult(
        List<NormalizedAisEvent> events,
        List<InputDiagnostic> diagnostics) {

    public PipelineResult {
        events = List.copyOf(events);
        diagnostics = List.copyOf(diagnostics);
    }

    public static PipelineResult empty() {
        return new PipelineResult(List.of(), List.of());
    }
}
