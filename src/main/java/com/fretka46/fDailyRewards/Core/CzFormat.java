package com.fretka46.fDailyRewards.Core;

import java.time.Duration;
import java.time.LocalDate;

/** České formátování času pro placeholdery. */
public final class CzFormat {
    private CzFormat() {}

    public static String days(long n) {
        return n + " " + (n == 1 ? "den" : (n >= 2 && n <= 4) ? "dny" : "dní");
    }

    /** "12 dní 4 h" nebo "3 h 12 min" nebo "5 min"; pod minutu {@code lessThanMinute}. */
    public static String duration(Duration d, String lessThanMinute) {
        long totalMin = d.toMinutes();
        if (totalMin <= 0) return lessThanMinute;
        long days = totalMin / 1440, hours = (totalMin % 1440) / 60, min = totalMin % 60;
        if (days > 0) return days(days) + (hours > 0 ? " " + hours + " h" : "");
        if (hours > 0) return hours + " h" + (min > 0 ? " " + min + " min" : "");
        return min + " min";
    }

    public static String date(LocalDate d) {
        return d.getDayOfMonth() + ". " + d.getMonthValue() + ". " + d.getYear();
    }
}
