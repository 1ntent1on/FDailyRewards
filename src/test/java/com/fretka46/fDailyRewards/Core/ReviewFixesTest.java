package com.fretka46.fDailyRewards.Core;

import com.fretka46.fDailyRewards.Core.ClaimState.Reason;
import com.fretka46.fDailyRewards.Core.Fixtures.Env;
import com.fretka46.fDailyRewards.Storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.fretka46.fDailyRewards.Core.Fixtures.at;
import static org.junit.jupiter.api.Assertions.*;

/** Opravy podle zadani-2b (review verze 1.1.0). */
class ReviewFixesTest {
    final UUID p = UUID.randomUUID();
    final YearMonth oct = YearMonth.of(2026, 10);

    // ------------------------------------------------------------------ A2 giftnext

    @Test
    void giftNextCatchupRefusesCaughtUpPlayerAndWritesNothing() {
        Env e = Fixtures.env(at(2026, 10, 3, 12, 0));
        for (int d = 1; d <= 3; d++) e.db().tryInsertClaim(p, oct, LocalDate.of(2026, 10, d), d, Database.Source.CLAIM);
        var r = e.svc().reserveNextGift(p, GiftMode.CATCHUP);       // další den je 5 (4 VIP), kalendář je 3.
        assertFalse(r.ok());
        assertEquals(Reason.NOT_YET, r.reason());
        assertEquals(Set.of(1, 2, 3), e.db().claimedDays(p, oct));
    }

    @Test
    void giftNextAnyGivesDayAhead() {
        Env e = Fixtures.env(at(2026, 10, 3, 12, 0));
        for (int d = 1; d <= 3; d++) e.db().tryInsertClaim(p, oct, LocalDate.of(2026, 10, d), d, Database.Source.CLAIM);
        var r = e.svc().reserveNextGift(p, GiftMode.ANY);
        assertTrue(r.ok());
        assertEquals(5, r.day());
    }

    @Test
    void giftNextCatchupGivesOldestMissedDayAndKeepsTodayClaim() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0));
        var r = e.svc().reserveNextGift(p, GiftMode.CATCHUP);
        assertEquals(1, r.day());
        assertEquals(Reason.OK, e.svc().claim(p, 2));
    }

    @Test
    void giftNextReportsWhyNothingLeft() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0), 4, u -> false, 0);
        for (int d = 1; d <= 3; d++) e.db().tryInsertClaim(p, oct, LocalDate.of(2026, 10, 1), d, Database.Source.UNLOCK);
        assertEquals(Reason.VIP_ONLY_REMAINING, e.svc().reserveNextGift(p, GiftMode.ANY).reason());
    }

    @Test
    void giftModeParsing() {
        List<String> errors = new ArrayList<>();
        assertEquals(GiftMode.CATCHUP, GiftMode.parse("catchup", errors::add));
        assertEquals(GiftMode.ANY, GiftMode.parse(" ANY ", errors::add));
        assertTrue(errors.isEmpty());
        assertEquals(GiftMode.CATCHUP, GiftMode.parse("nesmysl", errors::add));
        assertEquals(GiftMode.CATCHUP, GiftMode.parse(null, errors::add));
        assertEquals(2, errors.size());
    }

    @Test
    void purchaseFailureLineAndTemplate() {
        UUID u = UUID.fromString("2d61aae2-2078-4f68-a780-c23cead06fe2");
        Instant at = LocalDate.of(2026, 10, 17).atTime(14, 5, 9).atZone(ZoneId.of("Europe/Prague")).toInstant();
        var f = new PurchaseFailure(at, "giftnext", "Hrac\t1", u, "nic\nk darování");
        assertEquals("2026-10-17 14:05:09\tgiftnext\tHrac 1\t" + u + "\tnic k darování", f.toLogLine(ZoneId.of("Europe/Prague")));
        assertEquals("x Hrac 1 giftnext nic k darování", f.applyTemplate("x {player} {command} {reason}"));
        assertTrue(new PurchaseFailure(at, "givepass", "Nikdo", null, "neznámý").toLogLine(ZoneId.of("UTC")).contains("\t-\t"));
    }

    // ------------------------------------------------------------------ B1 měsíc

    @Test
    void monthHookSeesNewMonthRightAfterReset() {
        Database db = Database.openMemory();
        var clock = new Fixtures.MutableClock(at(2026, 11, 1, 4, 14));
        TimeService t = Fixtures.time(clock);
        PassService pass = new PassService(db, t, u -> false, () -> 0);
        RewardCatalog cat = Fixtures.catalog(31);
        List<YearMonth> seen = new ArrayList<>();
        RewardService svc = new RewardService(db, t, () -> cat, pass, () -> 0, seen::add);
        svc.evaluateFresh(p);
        clock.set(at(2026, 11, 1, 4, 16));
        svc.evaluate(p);
        assertEquals(List.of(oct, YearMonth.of(2026, 11)), seen);
    }

    // ------------------------------------------------------------------ B2 / B3 engine

    @Test
    void noExtraWaitWhenVipDayWasClaimedWithPassThatLaterExpired() {
        boolean[] pass = {true};
        Env e = Fixtures.env(at(2026, 10, 1, 12, 0), 31, u -> pass[0], 0);
        for (int d = 1; d <= 4; d++) {
            assertEquals(Reason.OK, e.svc().claim(p, d), "den " + d);
            e.clock().advanceDays(1);
        }
        pass[0] = false;                                            // 5. října: stará permission vypršela
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(5, s.nextDay());
        assertTrue(s.canClaimNow(), "VIP den 4 byl vyzvednutý, čekání navíc se nemá přidat: " + s.reason());
    }

    @Test
    void nextAvailableIncludesVipWaitWhenNotYet() {
        Env e = Fixtures.env(at(2026, 10, 1, 12, 0));
        assertEquals(Reason.OK, e.svc().claim(p, 1));
        e.clock().advanceDays(1);
        assertEquals(Reason.OK, e.svc().claim(p, 2));
        e.clock().advanceDays(2);                                   // 4. října, den 3 pozdě
        assertEquals(Reason.OK, e.svc().claim(p, 3));
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(5, s.nextDay());
        assertEquals(Reason.NOT_YET, s.reason());
        assertEquals(e.time().startOf(LocalDate.of(2026, 10, 6)), s.nextAvailableAt());
        e.clock().advanceDays(1);                                   // 5. října
        s = e.svc().evaluateFresh(p);
        assertEquals(Reason.VIP_COOLDOWN, s.reason());
        assertEquals(e.time().startOf(LocalDate.of(2026, 10, 6)), s.nextAvailableAt());
    }

    @Test
    void notYetWhenCalendarAndCooldownEndTogether() {
        Env e = Fixtures.env(at(2026, 10, 1, 12, 0));
        assertEquals(Reason.OK, e.svc().claim(p, 1));
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(Reason.NOT_YET, s.reason());
        assertEquals(e.time().startOf(LocalDate.of(2026, 10, 2)), s.nextAvailableAt());
    }

    // ------------------------------------------------------------------ B4 fronta

    @Test
    void cleanupNeverDeletesPendingGifts() {
        Env e = Fixtures.env(at(2026, 10, 17, 12, 0));
        e.db().addPending(p, 3);
        e.db().cleanup(LocalDate.of(2027, 10, 17), YearMonth.of(2027, 10), s -> {});
        var gifts = e.db().pending(p);
        assertEquals(1, gifts.size());
        assertEquals(3, gifts.get(0).day());
        assertTrue(gifts.get(0).createdAtMillis() > 0);
        assertTrue(e.db().deletePending(gifts.get(0).id()));
        assertFalse(e.db().deletePending(gifts.get(0).id()), "druhé smazání musí vrátit false (zámek proti dvojímu doručení)");
    }

    // ------------------------------------------------------------------ B5 hromadně

    @Test
    void bulkFilterSkipsBroadcastsOnlyInBulk() {
        List<String> cmds = List.of("cmi ctext daily %player_name%", "cmi money give %player_name% 2500 -s",
                "discordsrv broadcast #1 Hráč", "/CMI bossbarmsg %player_name% x");
        List<String> skip = List.of("discordsrv broadcast", "cmi ctext", "cmi bossbarmsg", "");
        assertEquals(cmds, BulkFilter.commandsFor(cmds, false, skip));
        assertEquals(List.of("cmi money give %player_name% 2500 -s"), BulkFilter.commandsFor(cmds, true, skip));
        assertEquals(cmds, BulkFilter.commandsFor(cmds, true, List.of()));
    }

    // ------------------------------------------------------------------ B6 cache

    @Test
    void passInfoIsCachedAndInvalidated() {
        Database db = Database.openMemory();
        var clock = new Fixtures.MutableClock(at(2026, 10, 17, 12, 0));
        TimeService t = Fixtures.time(clock);
        PassService pass = new PassService(db, t, u -> false, () -> 0);
        RewardCatalog cat = Fixtures.catalog(31);
        RewardService svc = new RewardService(db, t, () -> cat, pass, () -> 60_000, m -> {});
        pass.give(p, 1);
        assertEquals(1, svc.passInfo(p).activeMonths().size());
        pass.give(p, 1);
        assertEquals(1, svc.passInfo(p).activeMonths().size(), "v cache");
        svc.invalidate(p);
        assertEquals(2, svc.passInfo(p).activeMonths().size());
        assertFalse(svc.loginBonusToday(p));
        db.tryMarkLogin(p, t.today());
        assertFalse(svc.loginBonusToday(p), "v cache");
        svc.invalidate(p);
        assertTrue(svc.loginBonusToday(p));
    }

    // ------------------------------------------------------------------ C1 / C2

    @Test
    void yamlYesNoKeysAreBooleansSoConfigUsesYesText() throws Exception {
        YamlConfiguration broken = YamlConfiguration.loadConfiguration(new java.io.StringReader("placeholders:\n  yes: \"ano\"\n  no: \"ne\"\n"));
        assertNull(broken.getString("placeholders.yes"), "YAML 1.1: klíč yes je boolean, proto přejmenováno na yes_text");

        try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in);
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertEquals("yes", cfg.getString("placeholders.yes_text"));
            assertEquals("no", cfg.getString("placeholders.no_text"));
            assertEquals("catchup", cfg.getString("giftnext_mode"));
            assertTrue(cfg.getStringList("bulk_skip_command_prefixes").contains("discordsrv broadcast"));
        }
    }

    @Test
    void passInfoStopsAtGapInPaidMonths() {
        Env e = Fixtures.env(at(2026, 10, 17, 12, 0));
        e.pass().give(p, 3);                                        // 10, 11, 12
        assertEquals(YearMonth.of(2026, 11), e.pass().remove(p, YearMonth.of(2026, 11)));
        var info = e.pass().info(p);
        assertEquals(Set.of(oct), info.activeMonths());
        assertEquals(Set.of(YearMonth.of(2026, 12)), info.laterMonths());
        assertEquals(e.time().endOf(oct), info.expires());
        assertEquals(LocalDate.of(2026, 10, 31), info.lastDay());
    }

    @Test
    void passInfoForLatePurchaseStartingNextMonth() {
        Env e = Fixtures.env(at(2026, 10, 29, 12, 0), 31, u -> false, 5);
        assertEquals(List.of(YearMonth.of(2026, 11)), e.pass().give(p, 1));
        var info = e.pass().info(p);
        assertEquals(Set.of(YearMonth.of(2026, 11)), info.activeMonths());
        assertFalse(e.pass().isActive(p), "letos ještě ne");
    }
}
