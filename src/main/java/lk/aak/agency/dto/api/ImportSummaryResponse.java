package lk.aak.agency.dto.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a bulk .xlsx import - always returns 200 with per-row detail, never fails the whole batch.
 *
 * totalRows = every non-blank data row in the sheet. Each of them ends up in exactly one of:
 * created, updated, skipped (duplicate rows inside the sheet) or failed (errors). successCount is
 * kept for older screens and equals created + updated. Warnings do not stop a row from being imported.
 */
public class ImportSummaryResponse {

    private final int totalRows;
    private final int createdCount;
    private final int updatedCount;
    private final int successCount;
    private final int skippedCount;
    private final int errorCount;
    private final List<RowError> errors;
    private final List<RowError> skipped;
    private final List<RowError> warnings;

    /** Older form: everything that succeeded counted as created, nothing skipped or warned about. */
    public ImportSummaryResponse(int totalRows, int successCount, List<RowError> errors) {
        this(totalRows, successCount, 0, errors, List.of(), List.of());
    }

    public ImportSummaryResponse(
            int totalRows,
            int createdCount,
            int updatedCount,
            List<RowError> errors,
            List<RowError> skipped,
            List<RowError> warnings) {

        this.totalRows = totalRows;
        this.createdCount = createdCount;
        this.updatedCount = updatedCount;
        this.successCount = createdCount + updatedCount;
        this.errors = new ArrayList<>(errors);
        this.errorCount = this.errors.size();
        this.skipped = new ArrayList<>(skipped);
        this.skippedCount = this.skipped.size();
        this.warnings = new ArrayList<>(warnings);
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getCreatedCount() {
        return createdCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    /** Same as errorCount - the number of rows that could not be imported. */
    public int getFailedCount() {
        return errorCount;
    }

    public List<RowError> getErrors() {
        return errors;
    }

    public List<RowError> getSkipped() {
        return skipped;
    }

    public List<RowError> getWarnings() {
        return warnings;
    }

    /** One plain sentence for the clerk, e.g. "3 added, 2 updated, 1 skipped, 1 failed (of 7 rows)". */
    public String getSummary() {
        return createdCount + " added, " + updatedCount + " updated, " + skippedCount + " skipped, "
                + errorCount + " failed (of " + totalRows + " rows).";
    }

    public static class RowError {
        private final int row;
        private final String message;

        public RowError(int row, String message) {
            this.row = row;
            this.message = message;
        }

        public int getRow() {
            return row;
        }

        public String getMessage() {
            return message;
        }
    }
}
