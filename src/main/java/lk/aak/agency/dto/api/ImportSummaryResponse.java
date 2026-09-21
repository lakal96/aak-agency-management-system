package lk.aak.agency.dto.api;

import java.util.List;

/** Result of a bulk .xlsx import - always returns 200 with per-row detail, never fails the whole batch. */
public class ImportSummaryResponse {

    private final int totalRows;
    private final int successCount;
    private final int errorCount;
    private final List<RowError> errors;

    public ImportSummaryResponse(int totalRows, int successCount, List<RowError> errors) {
        this.totalRows = totalRows;
        this.successCount = successCount;
        this.errorCount = errors.size();
        this.errors = errors;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public List<RowError> getErrors() {
        return errors;
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
