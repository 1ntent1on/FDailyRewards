package com.fretka46.fDailyRewards.Core;

import java.util.Locale;
import java.util.function.Consumer;

/** Režim příkazu {@code giftnext} (klíč {@code giftnext_mode}). */
public enum GiftMode {
    /** Jen zameškaný den (další den k vyzvednutí nesmí být po dnešním dni odměn). */
    CATCHUP,
    /** I den dopředu: hráč ho dostane dřív, ale za měsíc nezíská víc. */
    ANY;

    /** Neplatná nebo prázdná hodnota = {@link #CATCHUP} a zavolá {@code onError}. */
    public static GiftMode parse(String text, Consumer<String> onError) {
        if (text != null) {
            switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "catchup": return CATCHUP;
                case "any": return ANY;
                default: break;
            }
        }
        onError.accept("Neplatné giftnext_mode '" + text + "', používám catchup");
        return CATCHUP;
    }
}
