package com.fretka46.fDailyRewards.Utils;

import com.fretka46.fDailyRewards.Core.ClaimState;
import com.fretka46.fDailyRewards.Core.TimeService;
import com.fretka46.fDailyRewards.FDailyRewards;
import com.fretka46.fDailyRewards.Storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jedna smyčka pro všechny online hráče (místo úlohy na hráče). Připomínka se pošle jen dokud je odměna
 * skutečně dostupná ({@code canClaimNow}); po připojení se zpožděním, po resetu dne s náhodným rozptylem,
 * jinak nejdřív po {@code reward_claim_available_interval} minutách. Čas poslední připomínky je v DB,
 * takže restart serveru nevyvolá další zprávu navíc.
 */
public final class ReminderService {
    /** dueAt: kdy nejdřív poslat (0 = zatím nenaplánováno); lastSent: epoch ms poslední připomínky (0 = nikdy). */
    private static final class St {
        long dueAt;
        long lastSent;
    }

    private final FDailyRewards plugin;
    private final Map<UUID, St> states = new ConcurrentHashMap<>();
    private BukkitTask task;

    public ReminderService(FDailyRewards plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        int tick = Math.max(2, plugin.getConfig().getInt("reminder_tick_seconds", 10));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * tick, 20L * tick);
        for (Player p : Bukkit.getOnlinePlayers()) onJoin(p);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        states.clear();
    }

    public void onJoin(Player player) {
        FileConfiguration cfg = plugin.getConfig();
        TimeService time = plugin.time();
        long now = time.now().toEpochMilli();
        St st = new St();
        try {
            st.lastSent = plugin.database().lastReminder(player.getUniqueId());
        } catch (Database.DbException ex) {
            Log.severe("Čtení času poslední připomínky selhalo: " + ex.getMessage());
        }
        long joinDelay = cfg.getLong("reminder_join_delay_seconds", 8) * 1000L;
        boolean sentToday = st.lastSent > 0 && time.rewardDate(Instant.ofEpochMilli(st.lastSent)).equals(time.rewardDate(Instant.ofEpochMilli(now)));
        st.dueAt = sentToday ? Math.max(now + joinDelay, st.lastSent + intervalMs()) : now + joinDelay;
        states.put(player.getUniqueId(), st);
    }

    public void onQuit(UUID uuid) {
        states.remove(uuid);
    }

    /** Hráč právě vyzvedl odměnu: naplánuj znovu až podle nového stavu. */
    public void onClaimed(UUID uuid) {
        St st = states.get(uuid);
        if (st != null) st.dueAt = 0;
    }

    private long intervalMs() {
        return Math.max(1, plugin.getConfig().getLong("reward_claim_available_interval", 30)) * 60_000L;
    }

    private void tick() {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getBoolean("show_reward_claim_available_message", true)) return;
        try {
            TimeService time = plugin.time();
            long now = time.now().toEpochMilli();
            long jitter = Math.max(0, cfg.getLong("reminder_rollover_jitter_seconds", 60)) * 1000L;
            for (Player p : Bukkit.getOnlinePlayers()) {
                St st = states.computeIfAbsent(p.getUniqueId(), u -> {
                    St s = new St();
                    s.dueAt = now;
                    return s;
                });
                ClaimState state = plugin.rewards().evaluate(p.getUniqueId());
                if (!state.canClaimNow()) {
                    st.dueAt = 0;
                    continue;
                }
                if (st.dueAt == 0) {
                    boolean sentToday = st.lastSent > 0 && time.rewardDate(Instant.ofEpochMilli(st.lastSent)).equals(state.rewardDate());
                    st.dueAt = sentToday ? st.lastSent + intervalMs() : now + (jitter > 0 ? ThreadLocalRandom.current().nextLong(jitter + 1) : 0);
                }
                if (now < st.dueAt) continue;
                send(p, cfg);
                st.lastSent = now;
                st.dueAt = now + intervalMs();
                plugin.database().setLastReminder(p.getUniqueId(), now);
            }
        } catch (RuntimeException ex) {
            Log.severe("Smyčka připomínek selhala", ex);
        }
    }

    private void send(Player p, FileConfiguration cfg) {
        String cmd = cfg.getString("reward_claim_available_command", "");
        if (cmd.isBlank()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player_name%", p.getName()));
    }
}
