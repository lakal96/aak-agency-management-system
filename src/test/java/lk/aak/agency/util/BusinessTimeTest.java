package lk.aak.agency.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessTimeTest {

    @AfterEach
    void putTheClockBack() {
        BusinessTime.resetClock();
    }

    private static void at(String utcInstant) {
        BusinessTime.setClockForTesting(Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC));
    }

    @Test
    void justAfterMidnightInSriLankaIsAlreadyTheNewDay() {
        // 00:30 on 3 November in Colombo is still 2 November, 19:00, in UTC - where the server runs.
        at("2026-11-02T19:00:00Z");

        assertThat(BusinessTime.today()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(BusinessTime.now()).isEqualTo(LocalDateTime.of(2026, 11, 3, 0, 30));
    }

    @Test
    void theWallClockIsFiveAndAHalfHoursAheadOfUtc() {
        at("2026-11-03T06:00:00Z");

        assertThat(BusinessTime.now()).isEqualTo(LocalDateTime.of(2026, 11, 3, 11, 30));
        assertThat(BusinessTime.today()).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void endOfMonthRollsOverInSriLankaBeforeUtc() {
        at("2026-11-30T20:00:00Z");   // 01:30 on 1 December in Colombo

        assertThat(BusinessTime.today()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void theClockIsBackToNormalAfterAReset() {
        at("2020-01-01T00:00:00Z");
        BusinessTime.resetClock();

        assertThat(BusinessTime.today().getYear()).isGreaterThanOrEqualTo(2026);
    }
}
