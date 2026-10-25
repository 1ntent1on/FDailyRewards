package com.fretka46.fDailyRewards.Core;

import com.fretka46.fDailyRewards.Storage.Database;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Login Pass po kalendářních měsících. Pass platí, když má hráč řádek pro aktuální měsíc odměn;
 * každý další nákup přidá měsíc za posledním zaplaceným.
 */
public final class PassService {
    /**
     * @param activeMonths souvislá řada zaplacených měsíců od prvního zaplaceného ≥ aktuální (podle ní se počítá konec)
     * @param laterMonths zaplacené měsíce po mezeře za touto řadou (např. po {@code removepass X YYYYMM} uprostřed)
     */
    public record Info(NavigableSet<YearMonth> activeMonths, NavigableSet<YearMonth> laterMonths, Instant expires,
                       boolean legacyPermission) {
        public boolean paid() {
            return !activeMonths.isEmpty();
        }

        /** Poslední den platnosti (poslední den posledního zaplaceného měsíce) nebo null. */
        public LocalDate lastDay() {
            return activeMonths.isEmpty() ? null : activeMonths.last().atEndOfMonth();
        }
    }

    private final Database db;
    private final TimeService time;
    private final Predicate<UUID> legacyPermission;
    private final IntSupplier lateDays;

    /**
     * @param legacyPermission přechodová kontrola staré permission (online hráč); {@code u -> false} = vypnuto
     * @param lateDays práh "koupě v posledních N dnech měsíce posune začátek na další měsíc" (0 = vypnuto)
     */
    public PassService(Database db, TimeService time, Predicate<UUID> legacyPermission, IntSupplier lateDays) {
        this.db = db;
        this.time = time;
        this.legacyPermission = legacyPermission;
        this.lateDays = lateDays;
    }

    public boolean hasPaidPass(UUID uuid) {
        return db.hasPass(uuid, time.month(time.now()));
    }

    /** Pass pro herní logiku: měsíc v DB, případně (jen po dobu přechodu) stará permission. */
    public boolean isActive(UUID uuid) {
        return hasPaidPass(uuid) || legacyPermission.test(uuid);
    }

    /** @return měsíce, které byly přidány (po sobě jdoucí) */
    public List<YearMonth> give(UUID uuid, int count) {
        Instant now = time.now();
        LocalDate today = time.rewardDate(now);
        YearMonth current = YearMonth.from(today);
        int remaining = current.lengthOfMonth() - today.getDayOfMonth() + 1;
        List<YearMonth> add = PassMath.monthsToAdd(db.passMonths(uuid), current, remaining, lateDays.getAsInt(), count);
        db.addPassMonths(uuid, add);
        return add;
    }

    /** Odebere zadaný měsíc, bez argumentu poslední zaplacený. @return odebraný měsíc nebo null */
    public YearMonth remove(UUID uuid, YearMonth month) {
        YearMonth target = month;
        if (target == null) {
            NavigableSet<YearMonth> all = db.passMonths(uuid);
            if (all.isEmpty()) return null;
            target = all.last();
        }
        return db.removePassMonth(uuid, target) ? target : null;
    }

    public Info info(UUID uuid) {
        YearMonth current = time.month(time.now());
        NavigableSet<YearMonth> paid = db.passMonths(uuid);
        NavigableSet<YearMonth> active = new TreeSet<>();
        for (YearMonth m = paid.ceiling(current); m != null && paid.contains(m); m = m.plusMonths(1)) active.add(m);
        NavigableSet<YearMonth> later = active.isEmpty() ? new TreeSet<>() : new TreeSet<>(paid.tailSet(active.last(), false));
        Instant expires = active.isEmpty() ? null : time.endOf(active.last());
        return new Info(active, later, expires, legacyPermission.test(uuid));
    }
}
