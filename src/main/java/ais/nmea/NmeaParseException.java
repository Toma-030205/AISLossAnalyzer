package ais.nmea;

import ais.input.InputDiagnosticCode;

public final class NmeaParseException extends Exception {

    private final InputDiagnosticCode diagnosticCode;

    public NmeaParseException(
            InputDiagnosticCode diagnosticCode,
            String message) {
        super(message);
        this.diagnosticCode = diagnosticCode;
    }

    public InputDiagnosticCode diagnosticCode() {
        return diagnosticCode;
    }
}
