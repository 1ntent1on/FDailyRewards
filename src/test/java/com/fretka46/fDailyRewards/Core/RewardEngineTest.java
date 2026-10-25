package com.fretka46.fDailyRewards.Core;

import com.fretka46.fDailyRewards.Core.ClaimState.DayState;
import com.fretka46.fDailyRewards.Core.ClaimState.Reason;
import com.fretka46.fDailyRewards.Core.Fixtures.Env;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static com.fretka46.fDailyRewards.Core.Fixtures.at;
import static org.junit.jupiter.api.Assertions.*;

class RewardEngineTest {
    final UUID p = UUID.randomUUID();

    /** Hráč claimuje každý den: den 1,2,3 běžně, 4 je VIP. */
    @Test
    void freshPlayerCanClaimDayOne() {
        Env e = Fixtures.env(at(2026, 10, 1, 12, 0));
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(1, s.nextDay());
        assertTrue(s.canClaimNow());
        assertEquals(DayState.CLAIMABLE, s.days().get(1));
        assertEquals(DayState.FUTURE, s.days().get(2));
        assertEquals(DayState.LOCKED_VIP, s.days().get(4));
    }

    @Test
    void cooldownOnePerRewardDay() {
        Env e = Fixtures.env(at(2026, 10, 3, 12, 0));
        assertEquals(Reason.OK, e.svc().claim(p, 1));
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(2, s.nextDay());
        assertFalse(s.canClaimNow());
        assertEquals(Reason.ALREADY_CLAIMED_TODAY, s.reason());
        assertEquals(DayState.WAITING_TOMORROW, s.days().get(2));
        assertTrue(s.claimedToday());
        assertEquals(Reason.ALREADY_CLAIMED_TODAY, e.svc().claim(p, 2));
        e.clock().advanceDays(1);
        assertEquals(Reason.OK, e.svc().claim(p, 2));
    }

    @Test
    void inactivePlayerSeesSingleOldestDayClaimable() {
        Env e = Fixtures.env(at(2026, 10, 20, 12, 0));
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(1, s.nextDay());
        long claimable = s.days().values().stream().filter(d -> d == DayState.CLAIMABLE).count();
        assertEquals(1, claimable);
        assertEquals(Reason.PREVIOUS_NOT_CLAIMED, s.reasonForDay(5, e.svc().catalog()));
        assertEquals(Reason.VIP_ONLY, s.reasonForDay(4, e.svc().catalog()));
    }

    @Test
    void nonPassPlayerSkipsVipDayWithExtraWait() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0));
        for (int d = 1; d <= 3; d++) {
            assertEquals(Reason.OK, e.svc().claim(p, d));
            e.clock().advanceDays(1);
        }
        // 13.10. po claimu dne 3 (10.-12.), den 4 je VIP -> další je 5 s prodlouženým čekáním
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(5, s.nextDay());
        // dnes 13.10., poslední claim 12.10. -> chybí 1 den
        assertFalse(s.canClaimNow());
        assertEquals(Reason.VIP_COOLDOWN, s.reason());
        assertEquals(DayState.WAITING_TOMORROW, s.days().get(5));
        assertEquals(DayState.LOCKED_VIP, s.days().get(4));
        e.clock().advanceDays(1);
        assertTrue(e.svc().evaluateFresh(p).canClaimNow());
    }

    @Test
    void waitTwoDaysRightAfterClaimBeforeVipDay() {
        Env e = Fixtures.env(at(2026, 10, 12, 12, 0));
        // hráč claimne den 3 (12.10.) a hned se dívá na den 5 -> za 2 dny
        for (int d = 1; d <= 3; d++) e.db().tryInsertClaim(p, YearMonth.of(2026, 10), LocalDate.of(2026, 10, 9 + d), d, com.fretka46.fDailyRewards.Storage.Database.Source.CLAIM);
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(5, s.nextDay());
        assertEquals(DayState.WAITING_2_DAYS, s.days().get(5));
        assertNotNull(s.nextAvailableAt());
        assertEquals(LocalDate.of(2026, 10, 14), e.time().rewardDate(s.nextAvailableAt()));
    }

    @Test
    void passPlayerClaimsVipDayWithoutCooldownThenWaits() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0));
        e.pass().give(p, 1);
        for (int d = 1; d <= 3; d++) {
            assertEquals(Reason.OK, e.svc().claim(p, d));
            e.clock().advanceDays(1);
        }
        // den 4 je VIP: jde hned
        assertEquals(Reason.OK, e.svc().claim(p, 4));
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(5, s.nextDay());
        assertFalse(s.canClaimNow());
        assertEquals(Reason.ALREADY_CLAIMED_TODAY, s.reason());
    }

    @Test
    void skippedVipDayBecomesClaimableAfterBuyingPass() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0));
        for (int d = 1; d <= 3; d++) { e.svc().claim(p, d); e.clock().advanceDays(1); }
        e.clock().advanceDays(1); // 14.10., čekání splněno
        assertEquals(Reason.OK, e.svc().claim(p, 5));
        e.pass().give(p, 1);
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(4, s.nextDay());
        assertEquals(DayState.SKIPPED_VIP_NOW_CLAIMABLE, s.days().get(4));
        assertTrue(s.canClaimNow());
    }

    @Test
    void notYetBeforeCalendarDay() {
        Env e = Fixtures.env(at(2026, 10, 1, 12, 0));
        assertEquals(Reason.OK, e.svc().claim(p, 1));
        e.clock().advanceDays(1);
        assertEquals(Reason.OK, e.svc().claim(p, 2));
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(Reason.NOT_YET, s.reasonForDay(10, e.svc().catalog()));
    }

    @Test
    void beforeResetBelongsToPreviousDay() {
        Env e = Fixtures.env(at(2026, 10, 5, 12, 0));
        assertEquals(Reason.OK, e.svc().claim(p, 1));          // den odměn 5.10.
        e.clock().set(at(2026, 10, 6, 0, 30));                  // půlnoc, stále 5.10.
        assertEquals(LocalDate.of(2026, 10, 5), e.time().today());
        assertFalse(e.svc().evaluateFresh(p).canClaimNow());
        e.clock().set(at(2026, 10, 6, 4, 14));
        assertFalse(e.svc().evaluateFresh(p).canClaimNow());
        e.clock().set(at(2026, 10, 6, 4, 15));
        assertTrue(e.svc().evaluateFresh(p).canClaimNow());
    }

    @Test
    void noUnlockOfNextCalendarDayBetweenMidnightAndReset() {
        // 2026-10-02 02:00 je ještě den odměn 1.10. -> den 2 nejde vyzvednout (dříve šel, kalendářní den 2)
        Env e = Fixtures.env(at(2026, 10, 2, 2, 0));
        e.db().tryInsertClaim(p, YearMonth.of(2026, 9), LocalDate.of(2026, 9, 30), 31, com.fretka46.fDailyRewards.Storage.Database.Source.CLAIM);
        e.svc().claim(p, 1);
        e.db().deleteClaim(p, YearMonth.of(2026, 10), 1);
        e.db().tryInsertClaim(p, YearMonth.of(2026, 10), LocalDate.of(2026, 9, 30), 1, com.fretka46.fDailyRewards.Storage.Database.Source.UNLOCK);
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(2, s.nextDay());
        assertEquals(Reason.NOT_YET, s.reason());
    }

    @Test
    void monthRolloverBeforeResetDoesNotAllowDayOneTwice() {
        // claim 1.10. 02:00 patří do dne odměn 30.9. => měsíc 9; po resetu v říjnu se den 1 ještě nedá považovat za vyzvednutý
        Env e = Fixtures.env(at(2026, 10, 1, 2, 0));
        assertEquals(YearMonth.of(2026, 9), e.time().month(e.clock().instant()));
        assertEquals(Reason.OK, e.svc().claim(p, 1));
        // dotazy se dívají na stejný měsíc odměn jako zápis
        assertTrue(e.svc().evaluateFresh(p).claimedDays().contains(1));
        assertEquals(Reason.ALREADY_CLAIMED, e.svc().claim(p, 1));
    }

    @Test
    void allClaimedAndVipOnlyRemaining() {
        Env e = Fixtures.env(at(2026, 10, 31, 12, 0), 31, u -> false, 0);
        for (int d = 1; d <= 31; d++) {
            if (Fixtures.catalog(31).isVip(d)) continue;
            e.db().tryInsertClaim(p, YearMonth.of(2026, 10), LocalDate.of(2026, 10, 1), d, com.fretka46.fDailyRewards.Storage.Database.Source.UNLOCK);
        }
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(0, s.nextDay());
        assertEquals(Reason.VIP_ONLY_REMAINING, s.reason());
        e.pass().give(p, 1);
        assertEquals(4, e.svc().evaluateFresh(p).nextDay());
    }

    @Test
    void shortMonthForfeitsDaysAboveLength() {
        Env e = Fixtures.env(at(2026, 2, 28, 12, 0));
        for (int d = 1; d <= 28; d++)
            e.db().tryInsertClaim(p, YearMonth.of(2026, 2), LocalDate.of(2026, 2, 1), d, com.fretka46.fDailyRewards.Storage.Database.Source.UNLOCK);
        e.pass().give(p, 1);
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(0, s.nextDay());
        assertEquals(Reason.ALL_CLAIMED, s.reason());
        assertEquals(28, s.monthLength());
    }

    @Test
    void unknownDayInCatalogIsSkipped() {
        Env e = Fixtures.env(at(2026, 10, 20, 12, 0), 10, u -> false, 0);
        assertEquals(Reason.UNKNOWN_DAY, e.svc().evaluateFresh(p).reasonForDay(15, e.svc().catalog()));
    }

    @Test
    void doubleClaimIsRejectedAtomically() {
        Env e = Fixtures.env(at(2026, 10, 3, 12, 0));
        assertEquals(Reason.OK, e.svc().claim(p, 1));
        assertEquals(Reason.ALREADY_CLAIMED, e.svc().claim(p, 1));
    }

    @Test
    void giftNextSkipsVipForNonPassAndKeepsTodayClaimPossible() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0));
        for (int i = 1; i <= 3; i++) e.db().tryInsertClaim(p, YearMonth.of(2026, 10), LocalDate.of(2026, 10, 1), i, com.fretka46.fDailyRewards.Storage.Database.Source.UNLOCK);
        assertEquals(5, e.svc().reserveNextGift(p, GiftMode.CATCHUP).day());                 // 4 je VIP -> přeskočeno
        ClaimState s = e.svc().evaluateFresh(p);
        assertEquals(6, s.nextDay());
        assertTrue(s.canClaimNow(), "dar nesmí zablokovat dnešní claim");
        assertFalse(s.claimedToday());
        assertEquals(Reason.OK, e.svc().claim(p, 6));
    }

    @Test
    void giftNextReturnsZeroWhenNothingLeft() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0), 3, u -> false, 0);
        assertEquals(1, e.svc().reserveNextGift(p, GiftMode.CATCHUP).day());
        assertEquals(2, e.svc().reserveNextGift(p, GiftMode.CATCHUP).day());
        assertEquals(3, e.svc().reserveNextGift(p, GiftMode.CATCHUP).day());
        assertEquals(0, e.svc().reserveNextGift(p, GiftMode.CATCHUP).day());
    }

    @Test
    void unlockMissedGivesAllUntilTodayRespectingVipAndLimit() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0));
        var done = e.svc().unlockMissed(p, 31);
        assertEquals(java.util.List.of(1, 2, 3, 5, 6, 8, 9, 10), done);       // bez VIP 4, 7
        ClaimState s = e.svc().evaluateFresh(p);
        assertNull(e.db().lastCooldownClaim(p), "unlock nesmí zapsat cooldown");
        assertFalse(s.claimedToday());
        assertEquals(12, s.nextDay());                 // 11 je VIP; den 12 ještě nenastal
        assertEquals(Reason.NOT_YET, s.reason());
        assertEquals(java.util.List.of(), e.svc().unlockMissed(p, 31));
        // s Passem a limitem
        UUID q = UUID.randomUUID();
        e.pass().give(q, 1);
        assertEquals(java.util.List.of(1, 2, 3), e.svc().unlockMissed(q, 3));
    }

    @Test
    void legacyPermissionGrantsPassDuringTransition() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0), 31, u -> true, 0);
        assertTrue(e.svc().evaluateFresh(p).pass());
        assertFalse(e.pass().hasPaidPass(p));
    }

    @Test
    void missedCountMatchesUnlock() {
        Env e = Fixtures.env(at(2026, 10, 10, 12, 0));
        assertEquals(8, e.svc().evaluateFresh(p).missedCount());
    }
}
