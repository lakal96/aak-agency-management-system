package lk.aak.agency.dto.api;

/** Generic JSON error body returned by API endpoints (see ApiExceptionHandler). */
public class ApiErrorResponse {

    private final String message;

    public ApiErrorResponse(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
