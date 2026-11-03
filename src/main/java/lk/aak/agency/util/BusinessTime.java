package lk.aak.agency.util;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * "Today" and "now" for the agency, which works on Sri Lanka time (UTC+5:30).
 *
 * The server runs on UTC, so {@code LocalDate.now()} is yesterday's date from midnight to 05:30 in Sri Lanka
 * and {@code LocalDateTime.now()} is 5.5 hours behind the wall clock the staff see. Use these instead; a test
 * ({@code NoServerClockTest}) fails the build if the plain calls come back.
 *
 * Instants (such as JWT expiry) are time-zone independent and may keep using {@code Instant.now()}.
 */
public final class BusinessTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    private static volatile Clock clock = Clock.system(ZONE);

    private BusinessTime() {
    }

    /** Today's date in Sri Lanka. */
    public static LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The current date and time as a clock on the wall in Sri Lanka shows it. */
    public static LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** For tests only: freeze or move the clock. Always pair with {@link #resetClock()}. */
    public static void setClockForTesting(Clock fixed) {
        clock = fixed.withZone(ZONE);
    }

    public static void resetClock() {
        clock = Clock.system(ZONE);
    }
}
