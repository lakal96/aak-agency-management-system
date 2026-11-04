package lk.aak.agency.service;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Cell parsing and friendly error text shared by the product and shop imports. Every message
 * names the column (the clerk looks at the column heading in Excel) and never contains SQL,
 * Hibernate or Java text. The import result adds the row number in front of each message.
 */
final class ImportValues {

    static final int MAX_SHORT_TEXT = 255;
    static final int MAX_LONG_TEXT = 10_000;
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999.99");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("9999999999999.99");

    private ImportValues() {
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Refuses text longer than the column can hold. */
    static String text(String value, String column, int maxLength) {
        String trimmed = blankToNull(value);
        if (trimmed != null && trimmed.length() > maxLength) {
            throw new IllegalArgumentException(
                    "the " + column + " column is too long (max " + maxLength + " characters).");
        }
        return trimmed;
    }

    /** A money amount: tolerates "Rs. 1,200.50", "1 200", stray spaces; two decimals, never negative. */
    static BigDecimal money(String value, String column) {
        return decimal(value, column, MAX_MONEY);
    }

    /** A quantity-like number (reorder level): same tolerance as money. */
    static BigDecimal quantity(String value, String column) {
        return decimal(value, column, MAX_QUANTITY);
    }

    private static BigDecimal decimal(String value, String column, BigDecimal max) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String cleaned = value
                .replace(' ', ' ')
                .toLowerCase(Locale.ROOT)
                .replace("lkr", "")
                .replace("rs.", "")
                .replace("rs", "")
                .replace(",", "")
                .replaceAll("\\s+", "");

        BigDecimal number;
        try {
            number = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "the " + column + " column has \"" + value.trim() + "\", which is not a valid number.");
        }

        if (number.signum() < 0) {
            throw new IllegalArgumentException("the " + column + " column cannot be negative.");
        }

        number = number.setScale(2, RoundingMode.HALF_UP);
        if (number.compareTo(max) > 0) {
            throw new IllegalArgumentException("the " + column + " column is too large.");
        }
        return number;
    }

    /** A whole number such as payment terms in days. */
    static Integer wholeNumber(String value, String column) {
        if (value == null || value.isBlank()) {
            return null;
        }

        BigDecimal number;
        try {
            number = new BigDecimal(value.replace(' ', ' ').replace(",", "").replaceAll("\\s+", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "the " + column + " column has \"" + value.trim() + "\", which is not a whole number.");
        }

        if (number.signum() < 0) {
            throw new IllegalArgumentException("the " + column + " column cannot be negative.");
        }
        if (number.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "the " + column + " column has \"" + value.trim() + "\", which is not a whole number.");
        }
        if (number.compareTo(new BigDecimal(3650)) > 0) {
            throw new IllegalArgumentException("the " + column + " column is too large.");
        }
        return number.intValueExact();
    }

    /** ACTIVE / INACTIVE (any case); blank gives null. */
    static String status(String value, String column) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!upper.equals("ACTIVE") && !upper.equals("INACTIVE")) {
            throw new IllegalArgumentException("the " + column + " column must be ACTIVE or INACTIVE.");
        }
        return upper;
    }

    /**
     * The message to show for a row that failed. Our own validation messages (IllegalArgumentException)
     * are already written for the clerk; anything from the database layer is replaced by plain text.
     */
    static String friendlyMessage(Exception e, String thing, Logger log, int row) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null) {
            return e.getMessage();
        }
        ConstraintViolationException violations = find(e, ConstraintViolationException.class);
        if (violations != null) {
            String details = violations.getConstraintViolations().stream()
                    .map(violation -> violation.getMessage())
                    .distinct()
                    .collect(Collectors.joining(" "));
            return details.isBlank() ? "some values in this row are not allowed." : details;
        }
        log.warn("Import row {} failed: {}", row, e.toString());
        if (find(e, DataIntegrityViolationException.class) != null) {
            return "this " + thing + " could not be saved - a value is too long, or it clashes with one that already exists.";
        }
        return "this " + thing + " could not be saved. Please check the values in this row and try again.";
    }

    private static <T extends Throwable> T find(Throwable error, Class<T> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }
}
