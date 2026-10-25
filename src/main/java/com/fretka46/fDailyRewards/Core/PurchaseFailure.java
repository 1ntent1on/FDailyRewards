package com.fretka46.fDailyRewards.Core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Neúspěšný nákupní příkaz (Tebex nabije vždy, ať příkaz uspěje, nebo ne). Čistá data a formátování,
 * zápis do souboru a na Discord řeší {@code Utils.FailedPurchases}.
 *
 * @param uuid UUID hráče, pokud je známé, jinak null
 */
public record PurchaseFailure(Instant at, String command, String input, UUID uuid, String reason) {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Jeden řádek do {@code failed-purchases.log}, oddělený tabulátory. */
    public String toLogLine(ZoneId zone) {
        return TS.format(at.atZone(zone)) + "\t" + command + "\t" + clean(input) + "\t"
                + (uuid == null ? "-" : uuid.toString()) + "\t" + clean(reason);
    }

    /** Dosadí {@code {player}}, {@code {command}}, {@code {reason}} do příkazu z configu. */
    public String applyTemplate(String template) {
        return template.replace("{player}", clean(input)).replace("{command}", command).replace("{reason}", clean(reason));
    }

    private static String clean(String s) {
        return s == null ? "-" : s.replaceAll("[\\t\\r\\n]", " ");
    }
}
