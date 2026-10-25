package com.fretka46.fDailyRewards.Core;

import com.fretka46.fDailyRewards.Core.ClaimState.DayState;
import com.fretka46.fDailyRewards.Core.ClaimState.Reason;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Čistá logika bez I/O: jediný zdroj pravdy o tom, co hráč může vyzvednout.
 *
 * <p>Pravidla (zachovávají původní chování pluginu):
 * <ul>
 *   <li>Další den = nejnižší nevyzvednutý den; hráč bez Passu přeskakuje VIP dny.</li>
 *   <li>Běžný den se dá vyzvednout nejvýš jednou za den odměn (cooldown se počítá jen z claimů se zdrojem
 *       {@code claim}, ne z {@code gift}/{@code unlock}).</li>
 *   <li>Hráč bez Passu čeká o den déle, když by mu předcházel (přeskočený) VIP den.</li>
 *   <li>VIP den s Passem jde vyzvednout kdykoliv (cooldown se na něj nevztahuje).</li>
 *   <li>Den nelze vyzvednout před kalendářním dnem odměn (den 12 nejdřív 12.) ani nad délku měsíce.</li>
 * </ul>
 */
public final class RewardEngine {
    private RewardEngine() {}

    /**
     * @param claimedDays dny vyzvednuté v aktuálním měsíci odměn
     * @param lastClaimDate datum posledního claimu se zdrojem {@code claim} (kdykoliv v historii), nebo null
     */
    public record Input(Set<Integer> claimedDays, LocalDate lastClaimDate, boolean pass) {}

    public static ClaimState evaluate(RewardCatalog catalog, TimeService time, Instant now, Input in) {
        LocalDate today = time.rewardDate(now);
        YearMonth month = YearMonth.from(today);
        int len = month.lengthOfMonth();
        TreeSet<Integer> sorted = new TreeSet<>(in.claimedDays());
        Set<Integer> claimed = Collections.unmodifiableSet(sorted);
        boolean pass = in.pass();

        int nextDay = 0;
        boolean vipBlocksOnly = false;
        for (var e : catalog.days().entrySet()) {
            int d = e.getKey();
            if (d > len || claimed.contains(d)) continue;
            if (e.getValue() && !pass) {
                vipBlocksOnly = true;
                continue;
            }
            nextDay = d;
            break;
        }

        boolean canClaim = false;
        Reason reason;
        Instant availableAt = null;
        int waitDays = 0;
        if (nextDay == 0) {
            reason = vipBlocksOnly ? Reason.VIP_ONLY_REMAINING : Reason.ALL_CLAIMED;
        } else if (nextDay > today.getDayOfMonth()) {
            reason = Reason.NOT_YET;
            Instant calendar = time.startOf(month.atDay(nextDay));
            LocalDate cooldownEnd = catalog.isVip(nextDay) || in.lastClaimDate() == null ? null
                    : in.lastClaimDate().plusDays(gap(catalog, nextDay, pass, claimed));
            Instant cooldown = cooldownEnd == null ? null : time.startOf(cooldownEnd);
            availableAt = cooldown != null && cooldown.isAfter(calendar) ? cooldown : calendar;
        } else if (catalog.isVip(nextDay)) {
            canClaim = true;
            reason = Reason.OK;
        } else {
            int gap = gap(catalog, nextDay, pass, claimed);
            long since = in.lastClaimDate() == null ? Long.MAX_VALUE
                    : Math.max(0, ChronoUnit.DAYS.between(in.lastClaimDate(), today));
            if (since >= gap) {
                canClaim = true;
                reason = Reason.OK;
            } else {
                waitDays = (int) (gap - since);
                reason = gap == 2 ? Reason.VIP_COOLDOWN : Reason.ALREADY_CLAIMED_TODAY;
                availableAt = time.startOf(today.plusDays(waitDays));
            }
        }

        Map<Integer, DayState> days = new LinkedHashMap<>();
        int maxClaimed = sorted.isEmpty() ? 0 : sorted.last();
        for (var e : catalog.days().entrySet()) {
            int d = e.getKey();
            DayState s;
            if (claimed.contains(d)) s = DayState.CLAIMED;
            else if (e.getValue() && !pass) s = DayState.LOCKED_VIP;
            else if (d == nextDay) {
                if (canClaim) s = (e.getValue() && maxClaimed > d) ? DayState.SKIPPED_VIP_NOW_CLAIMABLE : DayState.CLAIMABLE;
                else if (waitDays == 1) s = DayState.WAITING_TOMORROW;
                else if (waitDays == 2) s = DayState.WAITING_2_DAYS;
                else s = DayState.FUTURE;
            } else s = DayState.FUTURE;
            days.put(d, s);
        }

        boolean claimedToday = in.lastClaimDate() != null && in.lastClaimDate().equals(today);
        int missed = missedDays(catalog, today, claimed, pass).size();
        return new ClaimState(today, len, pass, nextDay, canClaim, reason, Collections.unmodifiableMap(days), claimed,
                maxClaimed, claimedToday, missed, availableAt);
    }

    /**
     * Kolik dní odměn musí uplynout od posledního claimu: 2, když hráč bez Passu přeskočil VIP den těsně před
     * {@code day} (ne když ho dřív s Passem vyzvedl), jinak 1.
     */
    private static int gap(RewardCatalog catalog, int day, boolean pass, Set<Integer> claimed) {
        boolean skippedVip = !pass && catalog.has(day - 1) && catalog.isVip(day - 1) && !claimed.contains(day - 1);
        return skippedVip ? 2 : 1;
    }

    /** Zameškané dny, které hráč smí dostat: nevyzvednuté, do dnešního dne včetně, VIP jen s Passem. */
    public static List<Integer> missedDays(RewardCatalog catalog, LocalDate today, Set<Integer> claimed, boolean pass) {
        int limit = Math.min(today.getDayOfMonth(), YearMonth.from(today).lengthOfMonth());
        List<Integer> out = new ArrayList<>();
        for (var e : catalog.days().entrySet()) {
            int d = e.getKey();
            if (d > limit || claimed.contains(d) || (e.getValue() && !pass)) continue;
            out.add(d);
        }
        return out;
    }
}
