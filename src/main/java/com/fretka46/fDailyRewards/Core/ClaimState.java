package com.fretka46.fDailyRewards.Core;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Úplný, neměnný výsledek vyhodnocení hráče k jednomu okamžiku. Menu, klik, připomínka i PlaceholderAPI
 * čtou stav jen odtud.
 */
public record ClaimState(
        LocalDate rewardDate,
        int monthLength,
        boolean pass,
        int nextDay,
        boolean canClaimNow,
        Reason reason,
        Map<Integer, DayState> days,
        Set<Integer> claimedDays,
        int lastClaimedDay,
        boolean claimedToday,
        int missedCount,
        Instant nextAvailableAt) {

    public enum DayState {
        CLAIMED, CLAIMABLE, WAITING_TOMORROW, WAITING_2_DAYS, LOCKED_VIP, FUTURE, SKIPPED_VIP_NOW_CLAIMABLE
    }

    public enum Reason {
        OK, ALREADY_CLAIMED_TODAY, VIP_COOLDOWN, ALL_CLAIMED, VIP_ONLY_REMAINING, NOT_YET, PREVIOUS_NOT_CLAIMED,
        VIP_ONLY, ALREADY_CLAIMED, UNKNOWN_DAY
    }

    public int claimedCount() {
        return claimedDays.size();
    }

    /** Proč by klik na konkrétní den uspěl nebo selhal (pořadí kontrol odpovídá původnímu pluginu). */
    public Reason reasonForDay(int day, RewardCatalog catalog) {
        if (!catalog.has(day)) return Reason.UNKNOWN_DAY;
        if (claimedDays.contains(day)) return Reason.ALREADY_CLAIMED;
        if (catalog.isVip(day) && !pass) return Reason.VIP_ONLY;
        if (day > rewardDate.getDayOfMonth() || day > monthLength) return Reason.NOT_YET;
        if (day != nextDay) return Reason.PREVIOUS_NOT_CLAIMED;
        return reason;
    }
}
