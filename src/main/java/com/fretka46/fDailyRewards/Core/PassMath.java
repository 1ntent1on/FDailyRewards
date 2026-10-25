package com.fretka46.fDailyRewards.Core;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;

/** Výpočet měsíců Login Passu (čistá logika, bez DB). */
public final class PassMath {
    private PassMath() {}

    /**
     * Měsíce, které přidat pro {@code count} nových měsíců: navazují na poslední zaplacený měsíc, jinak začínají
     * aktuálním. Při {@code lateDays > 0} a koupi bez aktivního Passu v posledních {@code lateDays} dnech měsíce
     * (včetně dnešního) se začíná až dalším měsícem.
     */
    public static List<YearMonth> monthsToAdd(NavigableSet<YearMonth> paid, YearMonth current, int remainingDaysInMonth,
                                              int lateDays, int count) {
        YearMonth start = current;
        if (!paid.isEmpty() && !paid.last().isBefore(current)) {
            start = paid.last().plusMonths(1);
        } else if (lateDays > 0 && remainingDaysInMonth <= lateDays) {
            start = current.plusMonths(1);
        }
        List<YearMonth> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(start.plusMonths(i));
        return out;
    }

    public static int toInt(YearMonth m) {
        return m.getYear() * 100 + m.getMonthValue();
    }

    public static YearMonth fromInt(int v) {
        return YearMonth.of(v / 100, v % 100);
    }
}
