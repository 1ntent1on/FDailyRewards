package com.fretka46.fDailyRewards.Listeners;

import com.fretka46.fDailyRewards.FDailyRewards;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class LeftListener implements Listener {
    @EventHandler
    public void onPlayerLeft(PlayerQuitEvent ev) {
        FDailyRewards.get().reminders().onQuit(ev.getPlayer().getUniqueId());
        FDailyRewards.get().rewards().invalidate(ev.getPlayer().getUniqueId());
    }
}
