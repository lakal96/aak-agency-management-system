package lk.aak.agency.dto.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Generic JSON error body returned by API endpoints (see ApiExceptionHandler). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiErrorResponse {

    private final String message;
    private final String requestId;

    public ApiErrorResponse(String message) {
        this(message, null);
    }

    /** requestId is the X-Request-Id of the failed request - only set for unexpected (500) errors. */
    public ApiErrorResponse(String message, String requestId) {
        this.message = message;
        this.requestId = requestId;
    }

    public String getMessage() {
        return message;
    }

    public String getRequestId() {
        return requestId;
    }
}
