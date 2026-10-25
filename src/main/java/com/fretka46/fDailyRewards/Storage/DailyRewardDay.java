package com.fretka46.fDailyRewards.Storage;

import java.util.List;

/**
 * Jeden den odměn (nebo položka bez funkce v menu, pak {@code day == -1}). Neměnné.
 *
 * @param day den v měsíci (1-31)
 * @param vip odměna jen pro Login Pass
 * @param item položka zobrazená v menu
 * @param commands příkazy spouštěné z konzole při vyzvednutí
 */
public record DailyRewardDay(int day, boolean vip, DailyRewardItem item, List<String> commands, int inventorySlot) {
    public DailyRewardDay {
        commands = commands == null ? List.of() : List.copyOf(commands);
    }
}
