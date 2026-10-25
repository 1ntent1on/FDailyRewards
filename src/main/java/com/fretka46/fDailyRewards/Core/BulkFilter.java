package com.fretka46.fDailyRewards.Core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Výběr příkazů odměny při hromadném předání (víc dní najednou), ať jeden příkaz nepošle 15 zpráv na Discord. */
public final class BulkFilter {
    private BulkFilter() {}

    /**
     * @param bulk {@code false} = vrátí všechny příkazy beze změny
     * @param skipPrefixes příkazy začínající některým prefixem (bez ohledu na velikost písmen a úvodní {@code /}) se vynechají
     */
    public static List<String> commandsFor(List<String> commands, boolean bulk, List<String> skipPrefixes) {
        if (!bulk || skipPrefixes == null || skipPrefixes.isEmpty()) return commands;
        List<String> prefixes = new ArrayList<>();
        for (String p : skipPrefixes) if (p != null && !normalize(p).isEmpty()) prefixes.add(normalize(p));
        List<String> out = new ArrayList<>();
        for (String cmd : commands) {
            if (cmd == null) continue;
            String n = normalize(cmd);
            if (prefixes.stream().noneMatch(n::startsWith)) out.add(cmd);
        }
        return out;
    }

    private static String normalize(String s) {
        String t = s.strip();
        if (t.startsWith("/")) t = t.substring(1);
        return t.toLowerCase(Locale.ROOT);
    }
}
