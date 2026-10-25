package com.fretka46.fDailyRewards.Utils;

import com.fretka46.fDailyRewards.Core.BulkFilter;
import com.fretka46.fDailyRewards.FDailyRewards;
import com.fretka46.fDailyRewards.Storage.DailyRewardDay;
import com.fretka46.fDailyRewards.Storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;

/** Dodání odměny hráči: spuštění příkazů z konzole, zvuk, zpráva, fronta pro offline hráče. */
public final class RewardActions {
    private RewardActions() {}

    public static void runCommands(DailyRewardDay reward, Player player) {
        runCommands(reward, player, false);
    }

    /** @param bulk součást hromadného předání: vynechá příkazy podle {@code bulk_skip_command_prefixes} */
    private static void runCommands(DailyRewardDay reward, Player player, boolean bulk) {
        List<String> skip = FDailyRewards.get().getConfig().getStringList("bulk_skip_command_prefixes");
        for (String cmd : BulkFilter.commandsFor(reward.commands(), bulk, skip)) {
            if (cmd == null || cmd.isBlank()) continue;
            String resolved = cmd.replace("%player_name%", player.getName()).replace("{player}", player.getName());
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            } catch (RuntimeException ex) {
                Log.severe("Příkaz odměny dne " + reward.day() + " selhal (" + resolved + ")", ex);
            }
        }
    }

    public static void playSound(Player player, String key) {
        String sound = FDailyRewards.get().getConfig().getString(key, "");
        if (!sound.isBlank()) player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    /**
     * Den už je v DB zapsaný jako claim. Online hráč dostane odměnu hned, offline při příštím připojení.
     *
     * @return {@code true} = doručeno hned, {@code false} = zařazeno do fronty
     */
    public static boolean giveOrQueue(UUID uuid, int day, String messageKey) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            FDailyRewards.get().database().addPending(uuid, day);
            return false;
        }
        deliver(player, day, messageKey);
        return true;
    }

    /**
     * Víc dní najednou (unlockmissed give). Online hráč je dostane hromadně (1 den za tick, bez příkazů z
     * {@code bulk_skip_command_prefixes}, na konci zpráva {@code unlock_done}); offline hráč a dny, které by
     * nestihl, protože se mezitím odpojil, jdou do fronty.
     *
     * @return {@code true} = předává se hned, {@code false} = vše zařazeno do fronty
     */
    public static boolean giveOrQueueMany(UUID uuid, List<Integer> days) {
        Database db = FDailyRewards.get().database();
        if (Bukkit.getPlayer(uuid) == null) {
            for (int d : days) db.addPending(uuid, d);
            return false;
        }
        if (days.size() == 1) {
            deliver(Bukkit.getPlayer(uuid), days.get(0), null);
            sendUnlockDone(uuid, 1);
            return true;
        }
        schedule(uuid, days.size(),
                (player, i) -> give(player, days.get(i), true, null),
                i -> db.addPending(uuid, days.get(i)),
                () -> {
                    sendUnlockDone(uuid, days.size());
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) playSound(p, "sound_claimed");
                });
        return true;
    }

    private static void sendUnlockDone(UUID uuid, int count) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) Messages.sendTranslatedMessageTo(p, "unlock_done", "count", String.valueOf(count));
    }

    public static void deliver(Player player, int day, String messageKey) {
        give(player, day, false, messageKey);
    }

    /** @return {@code false}, pokud den v configu odměn neexistuje (nic se nepředalo) */
    private static boolean give(Player player, int day, boolean bulk, String messageKey) {
        DailyRewardDay reward = FDailyRewards.get().configManager().getRewardForDay(day);
        if (reward == null) {
            Log.warning("Odměna dne " + day + " už v configu neexistuje, hráči " + player.getName() + " nelze dodat.");
            return false;
        }
        runCommands(reward, player, bulk);
        if (!bulk) playSound(player, "sound_claimed");
        if (messageKey != null) Messages.sendTranslatedMessageTo(player, messageKey, "day", String.valueOf(day));
        FDailyRewards.get().reminders().onClaimed(player.getUniqueId());
        return true;
    }

    /**
     * Doručí dárky čekající ve frontě (volá se po připojení). Víc dárků najednou = hromadně (viz
     * {@link #giveOrQueueMany}) a jedna souhrnná zpráva {@code gifts_received}.
     *
     * <p>Každý dárek se nejdřív smaže z fronty a doručí se, jen když smazání opravdu proběhlo. Smazání tak slouží
     * jako zámek: při rychlém relogu běží dvě doručovací úlohy a bez toho by hráč dostal dárek dvakrát. Den, který
     * v configu chybí, zůstane ve frontě (s varováním) a nesmaže se.
     */
    public static void deliverPending(Player player) {
        FDailyRewards plugin = FDailyRewards.get();
        Database db = plugin.database();
        UUID uuid = player.getUniqueId();
        List<Database.PendingGift> gifts = db.pending(uuid);
        if (gifts.isEmpty()) return;
        boolean bulk = gifts.size() > 1;
        List<Integer> delivered = Collections.synchronizedList(new ArrayList<>());
        schedule(uuid, gifts.size(), (p, i) -> {
            Database.PendingGift g = gifts.get(i);
            if (plugin.configManager().getRewardForDay(g.day()) == null) {
                Log.warning("Dárek #" + g.id() + " (den " + g.day() + ") pro " + p.getName() + " nelze doručit: den není v configu. Zůstává ve frontě.");
                return;
            }
            if (!db.deletePending(g.id())) return; // už doručila jiná úloha
            if (give(p, g.day(), bulk, bulk ? null : "gift_received")) delivered.add(g.day());
        }, i -> {
            // hráč se odpojil: dárek zůstává ve frontě
        }, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (!bulk || p == null || delivered.isEmpty()) return;
            String days = delivered.stream().sorted().map(String::valueOf).collect(Collectors.joining(", "));
            Messages.sendTranslatedMessageTo(p, "gifts_received", "count", String.valueOf(delivered.size()), "days", days);
            playSound(p, "sound_claimed");
        });
    }

    /**
     * Krok {@code i} (0 až n-1) poběží v ticku {@code i} (krok 0 hned), {@code done} po posledním. Hráč se dohledává
     * znovu v každém kroku (po relogu je to jiný objekt); když není online, zavolá se {@code offline}.
     */
    private static void schedule(UUID uuid, int n, ObjIntConsumer<Player> step, IntConsumer offline, Runnable done) {
        FDailyRewards plugin = FDailyRewards.get();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Runnable r = () -> {
                Player p = Bukkit.getPlayer(uuid);
                try {
                    if (p == null) offline.accept(idx);
                    else step.accept(p, idx);
                } catch (RuntimeException ex) {
                    Log.severe("Hromadné předání odměn selhalo v kroku " + idx, ex);
                }
            };
            if (i == 0) r.run();
            else Bukkit.getScheduler().runTaskLater(plugin, r, i);
        }
        Bukkit.getScheduler().runTaskLater(plugin, done, n);
    }
}
