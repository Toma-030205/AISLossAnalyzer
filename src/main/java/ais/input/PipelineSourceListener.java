package ais.input;

import ais.domain.NormalizedAisEvent;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class PipelineSourceListener implements SourceListener {

    private final AisInputPipeline pipeline;
    private final NormalizedEventListener delegate;
    private final Clock clock;

    private Instant lastReceivedAt;

    public PipelineSourceListener(
            AisInputPipeline pipeline,
            NormalizedEventListener delegate) {
        this(pipeline, delegate, Clock.systemUTC());
    }

    public PipelineSourceListener(
            AisInputPipeline pipeline,
            NormalizedEventListener delegate,
            Clock clock) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onRecord(ReceivedNmea record) {
        lastReceivedAt = record.receivedAt();
        dispatch(pipeline.accept(record));
    }

    @Override
    public void onDiagnostic(InputDiagnostic diagnostic) {
        delegate.onDiagnostic(diagnostic);
    }

    @Override
    public void onCompleted() {
        Instant completionTime = lastReceivedAt == null
                ? clock.instant()
                : lastReceivedAt;
        dispatch(pipeline.finish(completionTime));
        delegate.onCompleted();
    }

    @Override
    public void onFailure(Throwable error) {
        delegate.onFailure(error);
    }

    private void dispatch(PipelineResult result) {
        for (InputDiagnostic diagnostic : result.diagnostics()) {
            delegate.onDiagnostic(diagnostic);
        }
        for (NormalizedAisEvent event : result.events()) {
            delegate.onEvent(event);
        }
    }
}
