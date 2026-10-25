package com.fretka46.fDailyRewards.UI;

import com.fretka46.fDailyRewards.Core.ClaimState.Reason;
import com.fretka46.fDailyRewards.FDailyRewards;
import com.fretka46.fDailyRewards.Storage.DailyRewardDay;
import com.fretka46.fDailyRewards.Storage.Database;
import com.fretka46.fDailyRewards.Utils.Log;
import com.fretka46.fDailyRewards.Utils.Messages;
import com.fretka46.fDailyRewards.Utils.RewardActions;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Klikání v menu: zakáže veškerou manipulaci s itemy a vyhodnocuje klik na den přes RewardService. */
public class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu holder)) return;
        e.setCancelled(true);

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= e.getView().getTopInventory().getSize()) return;
        if (!(e.getWhoClicked() instanceof Player player)) return;

        FDailyRewards plugin = FDailyRewards.get();
        Integer day = holder.getDayAt(raw);
        if (day == null) {
            DailyRewardDay custom = holder.getCustomItemAt(raw);
            if (custom == null) return;
            try {
                if (custom.vip() && !plugin.rewards().pass().isActive(player.getUniqueId()))
                    Messages.sendTranslatedMessageTo(player, "non_functional_vip_only");
                else
                    RewardActions.runCommands(custom, player);
            } catch (Database.DbException ex) {
                Log.severe("Kontrola Passu selhala: " + ex.getMessage());
            }
            return;
        }

        Reason reason;
        try {
            reason = plugin.rewards().claim(player.getUniqueId(), day);
        } catch (Database.DbException ex) {
            Log.severe("Vyzvednutí dne " + day + " pro " + player.getName() + " selhalo: " + ex.getMessage());
            Messages.sendMessage(player, "<red>Chyba databáze, zkus to prosím za chvíli.");
            return;
        }

        if (reason != Reason.OK) {
            Messages.sendTranslatedMessageTo(player, messageKey(reason));
            RewardActions.playSound(player, "sound_denied");
            return;
        }

        RewardActions.deliver(player, day, null);
        holder.render();
    }

    private static String messageKey(Reason reason) {
        return switch (reason) {
            case ALREADY_CLAIMED -> "reward_already_claimed";
            case VIP_ONLY -> "reward_vip_only";
            case NOT_YET -> "reward_not_yet_available";
            case PREVIOUS_NOT_CLAIMED -> "reward_previous_not_claimed";
            case ALREADY_CLAIMED_TODAY -> "reward_already_claimed_today";
            default -> "reward_cannot_yet";
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
