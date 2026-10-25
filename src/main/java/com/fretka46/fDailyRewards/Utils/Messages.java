package com.fretka46.fDailyRewards.Utils;

import com.fretka46.fDailyRewards.FDailyRewards;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

/** Zprávy s prefixem; prefix i texty se čtou z configu při každém použití (funguje s reloadem). */
public final class Messages {
    public static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private Messages() {}

    private static String prefix() {
        return FDailyRewards.get().getConfig().getString("message_prefix", "");
    }

    /** Text z config klíče; {@code {day}}, {@code {count}} apod. se nahradí zadanými páry klíč, hodnota. */
    public static String text(String path, String... replacements) {
        String s = FDailyRewards.get().getConfig().getString(path, "ERR: " + path + " not found in config.");
        for (int i = 0; i + 1 < replacements.length; i += 2) s = s.replace("{" + replacements[i] + "}", replacements[i + 1]);
        return s;
    }

    public static void sendTranslatedMessageTo(CommandSender to, String path, String... replacements) {
        sendMessage(to, text(path, replacements));
    }

    public static void sendMessage(CommandSender to, String message) {
        to.sendMessage(MINI_MESSAGE.deserialize(prefix() + " " + message));
    }

    /** Hodnota z uživatelského vstupu (jméno hráče) do MiniMessage bez interpretace značek. */
    public static String escape(String s) {
        return MINI_MESSAGE.escapeTags(s);
    }
}
