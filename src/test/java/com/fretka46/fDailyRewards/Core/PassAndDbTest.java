package com.fretka46.fDailyRewards.Core;

import com.fretka46.fDailyRewards.Core.Fixtures.Env;
import com.fretka46.fDailyRewards.Storage.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.fretka46.fDailyRewards.Core.Fixtures.at;
import static org.junit.jupiter.api.Assertions.*;

class PassAndDbTest {
    final UUID p = UUID.randomUUID();

    @Test
    void threePurchasesInSameMonthGiveThreeConsecutiveMonths() {
        Env e = Fixtures.env(at(2026, 10, 17, 12, 0));
        assertEquals(List.of(YearMonth.of(2026, 10)), e.pass().give(p, 1));
        assertEquals(List.of(YearMonth.of(2026, 11)), e.pass().give(p, 1));
        assertEquals(List.of(YearMonth.of(2026, 12)), e.pass().give(p, 1));
        var info = e.pass().info(p);
        assertEquals(3, info.activeMonths().size());
        assertEquals(LocalDate.of(2026, 12, 31), info.lastDay());
        // konec platnosti = 1.1.2027 04:15 pražského času
        assertEquals(LocalDate.of(2027, 1, 1), e.time().rewardDate(info.expires()));
        assertEquals(e.time().startOf(LocalDate.of(2027, 1, 1)), info.expires());
        assertTrue(e.pass().isActive(p));
    }

    @Test
    void multiMonthPurchaseAndRemoveLast() {
        Env e = Fixtures.env(at(2026, 11, 20, 12, 0));
        assertEquals(List.of(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1)), e.pass().give(p, 3));
        assertEquals(YearMonth.of(2027, 1), e.pass().remove(p, null));
        assertEquals(YearMonth.of(2026, 11), e.pass().remove(p, YearMonth.of(2026, 11)));
        assertNull(e.pass().remove(p, YearMonth.of(2026, 11)));
    }

    @Test
    void passEndsAtResetOnFirstOfMonth() {
        Env e = Fixtures.env(at(2026, 10, 31, 23, 0));
        e.pass().give(p, 1);
        assertTrue(e.pass().isActive(p));
        e.clock().set(at(2026, 11, 1, 4, 14));
        assertTrue(e.pass().isActive(p));
        e.clock().set(at(2026, 11, 1, 4, 15));
        assertFalse(e.pass().isActive(p));
    }

    @Test
    void latePurchaseThresholdMovesStartToNextMonth() {
        Env e = Fixtures.env(at(2026, 10, 29, 12, 0), 31, u -> false, 3); // zbývají 3 dny (29,30,31)
        assertEquals(List.of(YearMonth.of(2026, 11)), e.pass().give(p, 1));
        assertFalse(e.pass().isActive(p));
        Env e2 = Fixtures.env(at(2026, 10, 28, 12, 0), 31, u -> false, 3);  // zbývají 4 dny
        assertEquals(List.of(YearMonth.of(2026, 10)), e2.pass().give(p, 1));
    }

    @Test
    void pastUnpaidMonthsAreIgnoredForStart() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0));
        e.db().addPassMonths(p, List.of(YearMonth.of(2026, 8)));
        assertEquals(List.of(YearMonth.of(2026, 10)), e.pass().give(p, 1));
    }

    @Test
    void pendingGiftsAndReminderState() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0));
        e.db().addPending(p, 5);
        e.db().addPending(p, 6);
        var list = e.db().pending(p);
        assertEquals(List.of(5, 6), list.stream().map(Database.PendingGift::day).toList());
        assertTrue(e.db().deletePending(list.get(0).id()));
        assertEquals(1, e.db().pending(p).size());
        assertEquals(0, e.db().lastReminder(p));
        e.db().setLastReminder(p, 123);
        e.db().setLastReminder(p, 456);
        assertEquals(456, e.db().lastReminder(p));
    }

    @Test
    void loginBonusIsAtomicPerRewardDay() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0));
        LocalDate d = e.time().today();
        assertTrue(e.db().tryMarkLogin(p, d));
        assertFalse(e.db().tryMarkLogin(p, d));
        assertTrue(e.db().hasLoggedIn(p, d));
    }

    /** Migrace kopie produkční databáze (verze 1, bez meta) na verzi 2 se zálohou. */
    @Test
    void migratesLegacyDatabaseKeepingData(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("database.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE rewards_claimed (id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL,date TEXT NOT NULL,day INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE daily_logins (id INTEGER PRIMARY KEY AUTOINCREMENT,uuid TEXT NOT NULL,date TEXT NOT NULL)");
            st.executeUpdate("INSERT INTO rewards_claimed (uuid,date,day) VALUES ('" + p + "','2026-10-16',3),('" + p + "','2026-10-17',4),"
                    + "('" + p + "','2026-10-17',4),('" + p + "','2026-09-30',31)");
            st.executeUpdate("INSERT INTO daily_logins (uuid,date) VALUES ('" + p + "','2026-10-17'),('" + p + "','2026-10-17')");
        }
        StringBuilder log = new StringBuilder();
        try (Database d = Database.openFile(db, log::append)) {
            assertEquals(java.util.Set.of(3, 4), d.claimedDays(p, YearMonth.of(2026, 10)));   // duplicita 4 sloučena
            assertEquals(java.util.Set.of(31), d.claimedDays(p, YearMonth.of(2026, 9)));
            assertEquals(LocalDate.of(2026, 10, 17), d.lastCooldownClaim(p));                 // staré řádky = claim
            assertFalse(d.tryMarkLogin(p, LocalDate.of(2026, 10, 17)));
        }
        try (Stream<Path> s = Files.list(dir)) {
            assertTrue(s.anyMatch(f -> f.getFileName().toString().startsWith("database.db.bak-v1-")), "záloha chybí");
        }
        assertTrue(log.toString().contains("Záloha"));
        // druhé otevření už nemigruje a nezakládá další zálohu
        try (Database d = Database.openFile(db, s -> fail("nemělo migrovat"))) {
            assertEquals(java.util.Set.of(3, 4), d.claimedDays(p, YearMonth.of(2026, 10)));
        }
    }

    @Test
    void migratesRealProductionCopyIfPresent(@TempDir Path dir) throws Exception {
        Path prod = Path.of(System.getProperty("fdr.prodDb", ""));
        org.junit.jupiter.api.Assumptions.assumeTrue(!prod.toString().isEmpty() && Files.exists(prod));
        Path copy = dir.resolve("database.db");
        Files.copy(prod, copy);
        try (Database d = Database.openFile(copy, s -> {})) {
            d.cleanup(LocalDate.of(2026, 10, 17), YearMonth.of(2026, 10), s -> {});
        }
    }

    @Test
    void czechFormats() {
        assertEquals("1 den", CzFormat.days(1));
        assertEquals("3 dny", CzFormat.days(3));
        assertEquals("12 dní", CzFormat.days(12));
        assertEquals("12 dní 4 h", CzFormat.duration(java.time.Duration.ofHours(12 * 24 + 4), "-"));
        assertEquals("3 h 12 min", CzFormat.duration(java.time.Duration.ofMinutes(192), "-"));
        assertEquals("5 min", CzFormat.duration(java.time.Duration.ofMinutes(5), "-"));
        assertEquals("-", CzFormat.duration(java.time.Duration.ofSeconds(20), "-"));
        assertEquals("30. 11. 2026", CzFormat.date(LocalDate.of(2026, 11, 30)));
    }
}
