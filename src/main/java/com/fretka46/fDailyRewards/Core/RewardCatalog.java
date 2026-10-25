package com.fretka46.fDailyRewards.Core;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Neměnný popis dnů odměn pro logiku (bez Bukkitu): číslo dne -> je jen pro Login Pass. */
public final class RewardCatalog {
    private final NavigableMap<Integer, Boolean> vipByDay;

    public RewardCatalog(Map<Integer, Boolean> vipByDay) {
        this.vipByDay = Collections.unmodifiableNavigableMap(new TreeMap<>(vipByDay));
    }

    public NavigableMap<Integer, Boolean> days() {
        return vipByDay;
    }

    public boolean has(int day) {
        return vipByDay.containsKey(day);
    }

    public boolean isVip(int day) {
        return vipByDay.getOrDefault(day, false);
    }

    public int size() {
        return vipByDay.size();
    }
}
