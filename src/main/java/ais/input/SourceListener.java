package ais.input;

public interface SourceListener {

    default void onStarted() {
    }

    void onRecord(ReceivedNmea record);

    void onDiagnostic(InputDiagnostic diagnostic);

    void onCompleted();

    void onFailure(Throwable error);
}
