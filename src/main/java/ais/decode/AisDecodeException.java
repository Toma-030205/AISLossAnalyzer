package ais.decode;

import ais.input.InputDiagnosticCode;

public final class AisDecodeException extends Exception {

    private final InputDiagnosticCode diagnosticCode;

    public AisDecodeException(
            InputDiagnosticCode diagnosticCode,
            String message) {
        super(message);
        this.diagnosticCode = diagnosticCode;
    }

    public InputDiagnosticCode diagnosticCode() {
        return diagnosticCode;
    }
}
