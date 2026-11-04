package lk.aak.agency.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lk.aak.agency.dto.api.ApiErrorResponse;
import lk.aak.agency.dto.api.ApiValidationErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.time.temporal.Temporal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * JSON error responses for the REST API - keeps the API's error contract independent of the Thymeleaf app's.
 *
 * Every answer is JSON (even when the browser asked for a PDF or image, which would otherwise turn a plain
 * "not found" into a 500), written for office staff, and never contains SQL, class names or stack traces.
 */
@RestControllerAdvice(basePackages = "lk.aak.agency.controller.api")
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static final String DUPLICATE_MESSAGE = "That already exists - someone may have just saved it.";
    static final String LOCK_MESSAGE = "Another person is changing this at the same moment, please try again.";

    private static <T> ResponseEntity<T> json(HttpStatusCode status, T body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatusCode status, String message) {
        return json(status, new ApiErrorResponse(message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiValidationErrorResponse> handleValidation(MethodArgumentNotValidException exception) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(
                error -> fieldErrors.put(error.getField(), error.getDefaultMessage())
        );

        return json(HttpStatus.BAD_REQUEST, new ApiValidationErrorResponse("Validation failed.", fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiValidationErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return constraintViolations(exception);
    }

    private ResponseEntity<ApiValidationErrorResponse> constraintViolations(ConstraintViolationException exception) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            String field = violation.getPropertyPath().toString();
            field = field.contains(".") ? field.substring(field.lastIndexOf('.') + 1) : field;
            fieldErrors.put(field, violation.getMessage());
        }

        String message = fieldErrors.isEmpty()
                ? "Some values are not allowed."
                : "Please check: " + String.join(" ", fieldErrors.values());

        return json(HttpStatus.BAD_REQUEST, new ApiValidationErrorResponse(message, fieldErrors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodValidation(HandlerMethodValidationException exception) {
        StringBuilder message = new StringBuilder("Please check the values you entered.");
        exception.getParameterValidationResults().forEach(result ->
                result.getResolvableErrors().forEach(error -> {
                    if (error.getDefaultMessage() != null) {
                        message.append(' ').append(error.getDefaultMessage());
                    }
                }));
        return error(HttpStatus.BAD_REQUEST, message.toString());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {

        Class<?> required = exception.getRequiredType();
        String expected = "a valid value";
        if (required != null) {
            if (Number.class.isAssignableFrom(required) || required.isPrimitive()) {
                expected = "a whole number";
            } else if (Temporal.class.isAssignableFrom(required)) {
                expected = "a date written like 2026-11-04";
            } else if (required.isEnum()) {
                expected = "one of the allowed values";
            }
        }

        String value = exception.getValue() == null ? "" : String.valueOf(exception.getValue());
        if (value.length() > 40) {
            value = value.substring(0, 40) + "...";
        }

        return error(HttpStatus.BAD_REQUEST,
                "\"" + value + "\" is not " + expected + " for \"" + exception.getName() + "\".");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException exception) {
        return error(HttpStatus.BAD_REQUEST, "The \"" + exception.getParameterName() + "\" value is required.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingPart(MissingServletRequestPartException exception) {
        return error(HttpStatus.BAD_REQUEST, "Please choose a file to upload.");
    }

    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<ApiErrorResponse> handleMultipart(org.springframework.web.multipart.MultipartException exception) {
        return error(HttpStatus.BAD_REQUEST, "Please choose a file to upload.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleTooLarge(MaxUploadSizeExceededException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "That file is too large to upload.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {

        String field = null;
        if (exception.getCause() instanceof tools.jackson.core.JacksonException jackson && !jackson.getPath().isEmpty()) {
            field = jackson.getPath().get(jackson.getPath().size() - 1).getPropertyName();
        }

        String message = field == null
                ? "The information sent could not be read. Check that numbers and dates are written correctly."
                : "The value for \"" + field + "\" could not be read. Check that it is written correctly (a number, a date like 2026-11-04, or text).";

        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "That action is not available here.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The information was sent in a format that is not accepted.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException exception) {
        return error(HttpStatus.NOT_ACCEPTABLE, "That cannot be provided in the format requested.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "Not found.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        String reason = exception.getReason();
        return error(exception.getStatusCode(), reason == null || reason.isBlank() ? "The request could not be completed." : reason);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NoSuchElementException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException exception) {
        return error(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ApiErrorResponse> handleLocked(LockedException exception) {
        return error(HttpStatus.LOCKED, exception.getMessage());
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiErrorResponse> handleDisabled(DisabledException exception) {
        return error(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException exception) {

        LOGGER.warn("Data integrity violation: {}", rootMessage(exception));

        ConstraintViolationException beanValidation = find(exception, ConstraintViolationException.class);
        if (beanValidation != null) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiErrorResponse("Please check: " + joinViolations(beanValidation)));
        }

        String state = sqlState(exception);
        String text = rootMessage(exception).toLowerCase();

        if ("23505".equals(state) || text.contains("duplicate entry") || text.contains("unique index")
                || text.contains("unique constraint") || text.contains("primary key violation")) {
            return error(HttpStatus.CONFLICT, DUPLICATE_MESSAGE);
        }
        if ("22001".equals(state) || text.contains("data too long") || text.contains("value too long")) {
            return error(HttpStatus.BAD_REQUEST, "One of the values is too long - please shorten it and try again.");
        }
        if ("23503".equals(state) || text.contains("foreign key")) {
            return error(HttpStatus.CONFLICT,
                    "That cannot be done because it is still used by other records, or refers to something that no longer exists.");
        }
        return error(HttpStatus.CONFLICT,
                "That change could not be saved because it conflicts with information already stored. Please check the values and try again.");
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, OptimisticLockingFailureException.class})
    public ResponseEntity<ApiErrorResponse> handleLocking(RuntimeException exception) {
        LOGGER.warn("Lock conflict: {}", rootMessage(exception));
        return error(HttpStatus.CONFLICT, LOCK_MESSAGE);
    }

    @ExceptionHandler(TransactionSystemException.class)
    public ResponseEntity<ApiErrorResponse> handleTransactionSystem(TransactionSystemException exception) {
        return classifyOrUnexpected(exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        return classifyOrUnexpected(exception);
    }

    /** Wrapped database errors (deadlock, bean validation at commit) still get their friendly answer. */
    private ResponseEntity<ApiErrorResponse> classifyOrUnexpected(Exception exception) {

        ConstraintViolationException beanValidation = find(exception, ConstraintViolationException.class);
        if (beanValidation != null) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiErrorResponse("Please check: " + joinViolations(beanValidation)));
        }

        DataIntegrityViolationException integrity = find(exception, DataIntegrityViolationException.class);
        if (integrity != null) {
            return handleDataIntegrity(integrity);
        }

        String state = sqlState(exception);
        if (find(exception, PessimisticLockingFailureException.class) != null
                || find(exception, OptimisticLockingFailureException.class) != null
                || "40001".equals(state) || "40P01".equals(state)
                || find(exception, jakarta.persistence.PessimisticLockException.class) != null
                || find(exception, jakarta.persistence.LockTimeoutException.class) != null) {
            LOGGER.warn("Lock conflict: {}", rootMessage(exception));
            return error(HttpStatus.CONFLICT, LOCK_MESSAGE);
        }

        LOGGER.error("Unhandled API error", exception);
        String requestId = MDC.get("requestId");
        String message = requestId == null
                ? "Something went wrong on our side. Please try again."
                : "Something went wrong on our side. Please try again - if it keeps happening, tell the office admin reference " + requestId + ".";
        return json(HttpStatus.INTERNAL_SERVER_ERROR, new ApiErrorResponse(message, requestId));
    }

    private static String joinViolations(ConstraintViolationException exception) {
        return exception.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .reduce((a, b) -> a + " " + b)
                .orElse("some values are not allowed.");
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }

    private static String sqlState(Throwable error) {
        SQLException sql = find(error, SQLException.class);
        return sql == null ? null : sql.getSQLState();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
