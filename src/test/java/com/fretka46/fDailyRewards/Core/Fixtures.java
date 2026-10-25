package com.fretka46.fDailyRewards.Core;

import com.fretka46.fDailyRewards.Storage.Database;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Pomůcky testů: nastavitelné hodiny, katalog 31 dní s VIP dny jako v produkci (4,7,11,14,18,21,25,28). */
final class Fixtures {
    static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");
    static final int[] VIP_DAYS = {4, 7, 11, 14, 18, 21, 25, 28};

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(LocalDateTime local) {
            set(local);
        }

        void set(LocalDateTime local) {
            now = local.atZone(PRAGUE).toInstant();
        }

        void advanceDays(int d) {
            now = now.plusSeconds(d * 86400L);
        }

        @Override public ZoneId getZone() { return PRAGUE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static RewardCatalog catalog(int days) {
        Map<Integer, Boolean> m = new HashMap<>();
        for (int d = 1; d <= days; d++) m.put(d, false);
        for (int v : VIP_DAYS) if (v <= days) m.put(v, true);
        return new RewardCatalog(m);
    }

    static TimeService time(Clock c) {
        return new TimeService(c, PRAGUE, LocalTime.of(4, 15));
    }

    static LocalDateTime at(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi);
    }

    record Env(Database db, MutableClock clock, TimeService time, PassService pass, RewardService svc) {}

    static Env env(LocalDateTime start, int catalogDays, Predicate<UUID> legacy, int lateDays) {
        Database db = Database.openMemory();
        MutableClock clock = new MutableClock(start);
        TimeService t = time(clock);
        PassService pass = new PassService(db, t, legacy, () -> lateDays);
        RewardCatalog cat = catalog(catalogDays);
        RewardService svc = new RewardService(db, t, () -> cat, pass, () -> 0, m -> {});
        return new Env(db, clock, t, pass, svc);
    }

    static Env env(LocalDateTime start) {
        return env(start, 31, u -> false, 0);
    }
}
