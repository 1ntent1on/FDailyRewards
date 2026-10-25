package com.fretka46.fDailyRewards.Listeners;

import com.fretka46.fDailyRewards.FDailyRewards;
import com.fretka46.fDailyRewards.Storage.Database;
import com.fretka46.fDailyRewards.Utils.Log;
import com.fretka46.fDailyRewards.Utils.Messages;
import com.fretka46.fDailyRewards.Utils.RewardActions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class LoginListener implements Listener {

    @EventHandler
    public void onLogin(PlayerJoinEvent event) {
        FDailyRewards plugin = FDailyRewards.get();
        Player player = event.getPlayer();
        var config = plugin.getConfig();

        try {
            // Premium Pass: bonus za první přihlášení v dni odměn (zápis je atomický, rychlý relog nedá bonus dvakrát)
            if (player.hasPermission("survival.login.tokeny")
                    && plugin.database().tryMarkLogin(player.getUniqueId(), plugin.time().today())) {
                for (String command : config.getStringList("login_commands")) {
                    String cmd = command.replace("%player_name%", player.getName());
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    } catch (RuntimeException ex) {
                        Log.severe("login_commands selhal (" + cmd + ")", ex);
                    }
                }
                Messages.sendTranslatedMessageTo(player, "login_rewarded");
                plugin.rewards().invalidate(player.getUniqueId());
            }
        } catch (Database.DbException ex) {
            Log.severe("Login bonus pro " + player.getName() + " selhal: " + ex.getMessage());
        }

        plugin.reminders().onJoin(player);

        // Dárky z fronty (giftnext / unlockmissed pro offline hráče) po krátké prodlevě, ať jsou ostatní pluginy připravené
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            try {
                RewardActions.deliverPending(player);
            } catch (RuntimeException ex) {
                Log.severe("Doručení dárků hráči " + player.getName() + " selhalo", ex);
            }
        }, 60L);
    }
}
