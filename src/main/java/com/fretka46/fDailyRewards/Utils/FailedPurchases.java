package com.fretka46.fDailyRewards.Utils;

import com.fretka46.fDailyRewards.Core.PurchaseFailure;
import com.fretka46.fDailyRewards.FDailyRewards;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Záznam neúspěšných nákupních příkazů ({@code givepass}, {@code setvip}: Login Pass, Premium + Login, Měsíční Bundle)
 * spuštěných z konzole (Tebex). Tebex na výsledek příkazu nereaguje, takže bez tohoto záznamu by se o nevydaném nákupu nikdo
 * nedozvěděl. Příkazy zadané hráčem (moderátor ve hře) se nezapisují, ten chybu vidí v chatu.
 */
public final class FailedPurchases {
    public static final String FILE = "failed-purchases.log";

    private FailedPurchases() {}

    public static void report(CommandSender sender, String command, String input, UUID uuid, String reason) {
        if (sender instanceof Player) return;
        FDailyRewards plugin = FDailyRewards.get();
        PurchaseFailure f = new PurchaseFailure(plugin.time().now(), command, input, uuid, reason);
        Log.warning("Nákupní příkaz '" + command + " " + input + "' selhal: " + reason + " (zapsáno do " + FILE + ")");
        try {
            Files.writeString(Path.of(plugin.getDataFolder().getPath(), FILE), f.toLogLine(plugin.time().zone()) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            Log.severe("Zápis do " + FILE + " selhal: " + ex.getMessage());
        }
        String template = plugin.getConfig().getString("failed_purchase_command", "");
        if (template != null && !template.isBlank()) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), f.applyTemplate(template));
            } catch (RuntimeException ex) {
                Log.severe("failed_purchase_command selhal", ex);
            }
        }
    }
}
