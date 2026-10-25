package com.fretka46.fDailyRewards.Utils;

import com.fretka46.fDailyRewards.Core.ClaimState;
import com.fretka46.fDailyRewards.Core.ClaimState.Reason;
import com.fretka46.fDailyRewards.Core.CzFormat;
import com.fretka46.fDailyRewards.FDailyRewards;
import com.fretka46.fDailyRewards.Storage.DailyRewardDay;
import com.fretka46.fDailyRewards.Storage.Database;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Locale;
import java.util.UUID;

/**
 * Expanze {@code %fdr_...%}. Všechny hodnoty se berou z {@code RewardService.evaluate} (cache pár sekund),
 * protože DeluxeMenus a další volají placeholdery z hlavního vlákna velmi často. Pro offline hráče vrací prázdný řetězec.
 */
public class PapiExpansion extends PlaceholderExpansion {
    private final FDailyRewards plugin;

    public PapiExpansion(FDailyRewards plugin) {
        this.plugin = plugin;
    }

    @Override
    @NotNull
    public String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    @NotNull
    public String getIdentifier() {
        return "fdr";
    }

    @Override
    @NotNull
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    private String yn(boolean b) {
        FileConfiguration c = plugin.getConfig();
        // klíče nejsou "yes"/"no": YAML 1.1 je čte jako boolean (true/false), takže by se nikdy nenašly
        return b ? c.getString("placeholders.yes_text", "yes") : c.getString("placeholders.no_text", "no");
    }

    private String dash() {
        return plugin.getConfig().getString("placeholders.dash", "–");
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || !player.isOnline() || plugin.rewards() == null) return "";
        UUID uuid = player.getUniqueId();
        try {
            return resolve(uuid, params.toLowerCase(Locale.ROOT));
        } catch (Database.DbException ex) {
            Log.severe("PAPI %fdr_" + params + "% selhalo: " + ex.getMessage());
            return "";
        }
    }

    private String resolve(UUID uuid, String key) {
        var svc = plugin.rewards();
        var time = svc.time();
        Instant now = time.now();
        ClaimState s = svc.evaluate(uuid);
        switch (key) {
            case "claimed_day": return String.valueOf(s.lastClaimedDay());
            case "current_day": return String.valueOf(s.nextDay());
            case "claimed_count": return String.valueOf(s.claimedCount());
            case "progress": return s.claimedCount() + "/" + daysInMonth(s);
            case "calendar_day": return String.valueOf(s.rewardDate().getDayOfMonth());
            case "days_in_month": return String.valueOf(daysInMonth(s));
            case "can_claim": return yn(s.canClaimNow());
            case "claimed_today": return yn(s.claimedToday());
            case "state": return state(s);
            case "next_in": {
                if (s.canClaimNow() || s.nextAvailableAt() == null) return dash();
                return CzFormat.duration(time.until(s.nextAvailableAt(), now), plugin.getConfig().getString("placeholders.less_than_minute", "méně než minuta"));
            }
            case "next_reward_name": {
                DailyRewardDay d = plugin.configManager().getRewardForDay(s.nextDay());
                if (d == null || d.item().name() == null || d.item().name().isBlank()) return dash();
                return MiniMessage.miniMessage().stripTags(d.item().name());
            }
            case "missed_count": return String.valueOf(s.missedCount());
            case "vip_active": return yn(s.pass());
            case "vip_months_left": return String.valueOf(svc.passInfo(uuid).activeMonths().size());
            case "vip_expires": {
                var last = svc.passInfo(uuid).lastDay();
                return last == null ? dash() : CzFormat.date(last);
            }
            case "vip_expires_unix": {
                Instant e = svc.passInfo(uuid).expires();
                return e == null ? "0" : String.valueOf(e.getEpochSecond());
            }
            case "vip_left": {
                Instant e = svc.passInfo(uuid).expires();
                if (e == null) return dash();
                return CzFormat.duration(time.until(e, now), plugin.getConfig().getString("placeholders.less_than_minute", "méně než minuta"));
            }
            case "login_bonus_today": return yn(svc.loginBonusToday(uuid));
            default: return null;
        }
    }

    /** Počet dní odměn dostupných v tomto měsíci (dny nad délku měsíce propadají). */
    private int daysInMonth(ClaimState s) {
        int len = YearMonth.from(s.rewardDate()).lengthOfMonth();
        return (int) plugin.rewards().catalog().days().keySet().stream().filter(d -> d <= len).count();
    }

    private static String state(ClaimState s) {
        if (s.canClaimNow()) return "ready";
        return switch (s.reason()) {
            case ALL_CLAIMED -> "done";
            case VIP_ONLY_REMAINING -> "vip_locked";
            case ALREADY_CLAIMED_TODAY -> "claimed_today";
            default -> "waiting";
        };
    }
}
