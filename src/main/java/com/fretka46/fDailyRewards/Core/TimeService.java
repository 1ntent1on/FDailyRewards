package com.fretka46.fDailyRewards.Core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Jediné místo, které ví, co je "den odměn". Den odměn začíná v {@code day_reset_time} (výchozí 04:15),
 * takže 00:00 až 04:14 ještě patří předchozímu dni. Měsíc, den v měsíci i všechny dotazy do DB se odvozují od
 * {@link #rewardDate(Instant)}, nikdy od kalendářního data.
 */
public final class TimeService {
    private final Clock clock;
    private final ZoneId zone;
    private final LocalTime resetTime;

    public TimeService(Clock clock, ZoneId zone, LocalTime resetTime) {
        this.clock = clock;
        this.zone = zone;
        this.resetTime = resetTime;
    }

    /** Parsuje "HH:mm"; při chybě vrátí výchozí 04:15 a zavolá {@code onError}. */
    public static LocalTime parseResetTime(String text, java.util.function.Consumer<String> onError) {
        try {
            return LocalTime.parse(text.trim().length() == 4 ? "0" + text.trim() : text.trim());
        } catch (RuntimeException ex) {
            onError.accept("Neplatné day_reset_time '" + text + "', používám 04:15");
            return LocalTime.of(4, 15);
        }
    }

    public Instant now() {
        return clock.instant();
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalTime resetTime() {
        return resetTime;
    }

    public LocalDate rewardDate(Instant instant) {
        return instant.atZone(zone).toLocalDateTime()
                .minusHours(resetTime.getHour()).minusMinutes(resetTime.getMinute()).toLocalDate();
    }

    public LocalDate today() {
        return rewardDate(now());
    }

    public YearMonth month(Instant instant) {
        return YearMonth.from(rewardDate(instant));
    }

    /** Okamžik, kdy den odměn {@code date} začíná. */
    public Instant startOf(LocalDate date) {
        return date.atTime(resetTime).atZone(zone).toInstant();
    }

    /** Okamžik, kdy končí poslední den odměn měsíce (= začátek prvního dne dalšího měsíce). */
    public Instant endOf(YearMonth month) {
        return startOf(month.plusMonths(1).atDay(1));
    }

    public Duration until(Instant target, Instant now) {
        return target.isAfter(now) ? Duration.between(now, target) : Duration.ZERO;
    }
}
