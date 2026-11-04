package lk.aak.agency.controller;

import jakarta.validation.ConstraintViolationException;
import lk.aak.agency.config.ApiExceptionHandler;
import lk.aak.agency.dto.api.ApiErrorResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.TransactionSystemException;

import java.sql.SQLException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void duplicateKeyIsA409WithAFriendlyMessage_noSqlInTheBody() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "could not execute statement [Duplicate entry 'X' for key 'customers.customer_code'] [insert into customers ...]",
                new SQLException("Duplicate entry 'X' for key 'customers.customer_code'", "23000", 1062));

        ResponseEntity<ApiErrorResponse> response = handler.handleDataIntegrity(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getMessage()).isEqualTo("That already exists - someone may have just saved it.");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    void otherIntegrityProblemsGetAGenericFriendlyAnswer() {
        ResponseEntity<ApiErrorResponse> foreignKey = handler.handleDataIntegrity(new DataIntegrityViolationException(
                "x", new SQLException("Cannot delete or update a parent row: a foreign key constraint fails (`aak`.`t`)", "23000", 1451)));
        assertThat(foreignKey.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(foreignKey.getBody().getMessage()).doesNotContain("constraint fails").doesNotContain("`");

        ResponseEntity<ApiErrorResponse> tooLong = handler.handleDataIntegrity(new DataIntegrityViolationException(
                "x", new SQLException("Data too long for column 'area' at row 1", "22001", 1406)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody().getMessage()).contains("too long").doesNotContain("column");

        ResponseEntity<ApiErrorResponse> other = handler.handleDataIntegrity(new DataIntegrityViolationException("weird"));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void lockAndDeadlockProblemsAreA409AskingToTryAgain() {
        for (RuntimeException exception : new RuntimeException[]{
                new CannotAcquireLockException("Lock wait timeout exceeded; try restarting transaction"),
                new DeadlockLoserDataAccessException("Deadlock found when trying to get lock", new SQLException("deadlock", "40001", 1213))}) {
            ResponseEntity<ApiErrorResponse> response = handler.handleLocking(exception);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().getMessage())
                    .isEqualTo("Another person is changing this at the same moment, please try again.");
        }
    }

    @Test
    void aLockProblemWrappedInAnotherExceptionStillGetsTheFriendlyAnswer() {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpected(
                new JpaSystemException(new RuntimeException("wrapped", new SQLException("deadlock", "40001", 1213))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void beanValidationFailureAtCommitIsA400NamingTheProblem() {
        ConstraintViolationException violations = new ConstraintViolationException(
                "Validation failed for classes [...] ", Set.of());
        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpected(
                new TransactionSystemException("Could not commit JPA transaction", violations));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).doesNotContain("Validation failed for classes");
    }

    @Test
    void unexpectedErrorIsA500WithAShortMessageAndTheRequestId_noInternals() {
        MDC.put("requestId", "ab12cd34");

        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpected(
                new NullPointerException("secret internal detail at lk.aak.agency.service.Something.java:42"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getRequestId()).isEqualTo("ab12cd34");
        assertThat(response.getBody().getMessage()).contains("ab12cd34")
                .doesNotContain("NullPointer").doesNotContain("secret").doesNotContain("lk.aak");
    }
}
