package ais.parser;

public class FileLoadStatistics {

    public final long totalRows;
    public final long exactDuplicateRows;
    public final long targetMessages;
    public final long invalidOrNonTargetRows;

    public FileLoadStatistics(
            long totalRows,
            long exactDuplicateRows,
            long targetMessages,
            long invalidOrNonTargetRows) {

        this.totalRows = totalRows;
        this.exactDuplicateRows = exactDuplicateRows;
        this.targetMessages = targetMessages;
        this.invalidOrNonTargetRows = invalidOrNonTargetRows;
    }
}
