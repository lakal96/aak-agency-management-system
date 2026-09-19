package lk.aak.agency.dto.api;

import java.util.Map;

/** JSON error body for @Valid failures - fieldErrors keys are field names. */
public class ApiValidationErrorResponse {

    private final String message;
    private final Map<String, String> fieldErrors;

    public ApiValidationErrorResponse(String message, Map<String, String> fieldErrors) {
        this.message = message;
        this.fieldErrors = fieldErrors;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
