package ais.input;

import ais.domain.NormalizedAisEvent;

public interface NormalizedEventListener {

    void onEvent(NormalizedAisEvent event);

    void onDiagnostic(InputDiagnostic diagnostic);

    void onCompleted();

    void onFailure(Throwable error);
}
