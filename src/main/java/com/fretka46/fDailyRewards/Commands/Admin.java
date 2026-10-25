package com.fretka46.fDailyRewards.Commands;

import com.fretka46.fDailyRewards.Core.ClaimState;
import com.fretka46.fDailyRewards.Core.ClaimState.Reason;
import com.fretka46.fDailyRewards.Core.CzFormat;
import com.fretka46.fDailyRewards.Core.GiftMode;
import com.fretka46.fDailyRewards.Core.PassMath;
import com.fretka46.fDailyRewards.Core.RewardService.GiftResult;
import com.fretka46.fDailyRewards.FDailyRewards;
import com.fretka46.fDailyRewards.Storage.Database;
import com.fretka46.fDailyRewards.Utils.FailedPurchases;
import com.fretka46.fDailyRewards.Utils.Log;
import com.fretka46.fDailyRewards.Utils.Messages;
import com.fretka46.fDailyRewards.Utils.RewardActions;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin příkazy. Konzole má právo vždy (Tebex); všechny příkazy fungují i pro offline hráče (jen hráči,
 * kteří se na server někdy připojili). Odměny offline hráče se zařadí do fronty a dodají při připojení.
 * Nákupní příkazy z Tebexu jsou jen {@code givepass}/{@code setvip}; jejich neúspěch z konzole se zapíše do
 * {@code failed-purchases.log} (viz {@link FailedPurchases}). {@code giftnext}/{@code giftday} jsou nástroje pro AT.
 */
public final class Admin {
    private Admin() {}

    private record Target(UUID uuid, String name) {}

    /** Výsledek hledání hráče: buď {@code target}, nebo {@code error} (text pro odesílatele i log nákupu). */
    private record Resolved(Target target, String error) {}

    private static final String UNKNOWN_PLAYER = "hráč není serveru znám (nikdy se nepřipojil)";

    private static String arg(CommandContext<CommandSourceStack> ctx, String name) {
        return ctx.getArgument(name, String.class);
    }

    /**
     * Společná obálka: oprávnění, chyby databáze, jednotné logování.
     *
     * @param purchase název nákupního příkazu pro {@code failed-purchases.log}, nebo null
     */
    private static int run(CommandContext<CommandSourceStack> ctx, String permission, String purchase, Function<CommandSender, Integer> body) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission(permission)) {
            sender.sendMessage("You do not have permission to use this command.");
            return 0;
        }
        try {
            return body.apply(sender);
        } catch (Database.DbException ex) {
            Log.severe("Admin příkaz selhal: " + ex.getMessage(), ex);
            Messages.sendMessage(sender, "<red>Chyba databáze, viz konzole.");
            if (purchase != null) FailedPurchases.report(sender, purchase, arg(ctx, "player"), null, "chyba databáze: " + ex.getMessage());
            return 0;
        }
    }

    /**
     * Online hráč, jinak hráč, který se na server někdy připojil (podle jména z cache serveru, nebo podle UUID).
     * Na Mojang se nedotazuje.
     */
    private static Resolved resolve(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) return new Resolved(new Target(online.getUniqueId(), online.getName()), null);
        OfflinePlayer op;
        try {
            op = Bukkit.getOfflinePlayer(UUID.fromString(input));
        } catch (IllegalArgumentException notUuid) {
            op = Bukkit.getOfflinePlayerIfCached(input);
        }
        if (op == null || (!op.isOnline() && !op.hasPlayedBefore())) return new Resolved(null, UNKNOWN_PLAYER);
        return new Resolved(new Target(op.getUniqueId(), op.getName() != null ? op.getName() : input), null);
    }

    /** {@link #resolve} pro běžné příkazy: při chybě pošle zprávu a vrátí null. */
    private static Target target(CommandSender sender, String input) {
        Resolved r = resolve(input);
        if (r.target() == null) Messages.sendMessage(sender, "<red>Hráč <white>" + Messages.escape(input) + "<red>: " + r.error() + ". Zadej jméno nebo UUID.");
        return r.target();
    }

    /** {@link #target} pro nákupní příkazy: chybu navíc zapíše do {@code failed-purchases.log}. */
    private static Target purchaseTarget(CommandSender sender, String purchase, String input) {
        Target t = target(sender, input);
        if (t == null) FailedPurchases.report(sender, purchase, input, null, UNKNOWN_PLAYER);
        return t;
    }

    private static String who(Target t) {
        return Messages.escape(t.name());
    }

    private static String deliveredText(boolean now) {
        return now ? "předáno hned" : "hráč je offline, předá se při připojení";
    }

    private static String reasonText(Reason r) {
        return switch (r) {
            case ALREADY_CLAIMED -> "den už je vyzvednutý";
            case VIP_ONLY -> "den je jen pro Login Pass a hráč ho nemá";
            case UNKNOWN_DAY -> "den není v configu odměn";
            case ALL_CLAIMED -> "vše je vyzvednuto";
            case VIP_ONLY_REMAINING -> "zbývají jen VIP dny a hráč nemá Login Pass";
            case NOT_YET -> "hráč nemá zameškaný den (giftnext_mode: catchup dá jen zameškaný den)";
            default -> r.name();
        };
    }

    // ------------------------------------------------------------------ dny

    public static int ResetDayExecutor(CommandContext<CommandSourceStack> ctx) {
        return run(ctx, "fdailyrewards.admin.resetday", null, sender -> {
            Target t = target(sender, arg(ctx, "player"));
            if (t == null) return 0;
            int day = ctx.getArgument("day", Integer.class);
            int rows = FDailyRewards.get().rewards().resetDay(t.uuid(), day);
            Messages.sendMessage(sender, "Den " + day + " resetován pro " + who(t) + " (aktuální měsíc), upraveno záznamů: " + rows);
            return 1;
        });
    }

    public static int SetDayExecutor(CommandContext<CommandSourceStack> ctx) {
        return run(ctx, "fdailyrewards.admin.setday", null, sender -> {
            Target t = target(sender, arg(ctx, "player"));
            if (t == null) return 0;
            int day = ctx.getArgument("day", Integer.class);
            Reason r = FDailyRewards.get().rewards().markDay(t.uuid(), day);
            if (r != Reason.OK) {
                Messages.sendMessage(sender, "<red>Nelze označit den " + day + ": " + reasonText(r));
                return 0;
            }
            Messages.sendMessage(sender, "Den " + day + " označen jako vyzvednutý pro " + who(t) + " (bez odměny, cooldown se nemění).");
            return 1;
        });
    }

    public static int GiftDayExecutor(CommandContext<CommandSourceStack> ctx) {
        return run(ctx, "fdailyrewards.admin.giftday", null, sender -> {
            Target t = target(sender, arg(ctx, "player"));
            if (t == null) return 0;
            int day = ctx.getArgument("day", Integer.class);
            Reason r = FDailyRewards.get().rewards().reserveGiftDay(t.uuid(), day);
            if (r != Reason.OK) {
                Messages.sendMessage(sender, "<red>Den " + day + " nelze darovat: " + reasonText(r));
                return 0;
            }
            boolean now = RewardActions.giveOrQueue(t.uuid(), day, "gift_received");
            Messages.sendMessage(sender, "Odměna dne " + day + " darována hráči " + who(t) + " (" + deliveredText(now) + ").");
            return 1;
        });
    }

    /**
     * Nástroj pro AT na požádání hráče (není to Tebex balíček): dá další den, který hráč smí vyzvednout, mimo
     * cooldown (režim podle {@code giftnext_mode}).
     */
    public static int GiftNextExecutor(CommandContext<CommandSourceStack> ctx) {
        return run(ctx, "fdailyrewards.admin.giftnext", null, sender -> {
            Target t = target(sender, arg(ctx, "player"));
            if (t == null) return 0;
            FDailyRewards plugin = FDailyRewards.get();
            GiftMode mode = GiftMode.parse(plugin.getConfig().getString("giftnext_mode", "catchup"), Log::warning);
            GiftResult res = plugin.rewards().reserveNextGift(t.uuid(), mode);
            if (!res.ok()) {
                Messages.sendMessage(sender, "<red>Hráči " + who(t) + " nelze dát další den: " + reasonText(res.reason()) + ". Nic nebylo předáno.");
                return 0;
            }
            boolean now = RewardActions.giveOrQueue(t.uuid(), res.day(), "gift_received");
            Messages.sendMessage(sender, "Další den (" + res.day() + ") dán hráči " + who(t) + " (" + deliveredText(now) + ").");
            return 1;
        });
    }

    public static int CheckDayExecutor(CommandContext<CommandSourceStack> ctx) {
        return run(ctx, "fdailyrewards.admin.checkday", null, sender -> {
            Target t = target(sender, arg(ctx, "player"));
            if (t == null) return 0;
            ClaimState s = FDailyRewards.get().rewards().evaluateFresh(t.uuid());
            Messages.sendMessage(sender, who(t) + ": poslední vyzvednutý den " + s.lastClaimedDay() + ", vyzvednuto " + s.claimedCount()
                    + ", další den " + s.nextDay() + ", lze vyzvednout teď: " + (s.canClaimNow() ? "ano" : "ne (" + s.reason() + ")")
                    + ", Pass: " + (s.pass() ? "ano" : "ne") + ", zameškané: " + s.missedCount());
            return 1;
        });
    }

    public static int UnlockMissedExecutor(CommandContext<CommandSourceStack> ctx, boolean give) {
        return run(ctx, "fdailyrewards.admin.unlockmissed", null, sender -> {
            Target t = target(sender, arg(ctx, "player"));
            if (t == null) return 0;
            FDailyRewards plugin = FDailyRewards.get();
            int max = Math.max(1, plugin.getConfig().getInt("unlock_max_days", 31));
            List<Integer> days = plugin.rewards().unlockMissed(t.uuid(), max);
            if (days.isEmpty()) {
                Messages.sendMessage(sender, "Hráč " + who(t) + " nemá žádné zameškané dny k odemčení.");
                return 1;
            }
            String list = days.stream().map(String::valueOf).collect(Collectors.joining(", "));
            if (!give) {
                Messages.sendMessage(sender, "Označeno " + days.size() + " dní jako vyzvednuté pro " + who(t) + " (bez odměn): " + list);
                return 1;
            }
            boolean now = RewardActions.giveOrQueueMany(t.uuid(), days);
            Messages.sendMessage(sender, "Odemčeno " + days.size() + " dní pro " + who(t) + " (" + deliveredText(now) + "): " + list);
            return 1;
        });
    }

    // ------------------------------------------------------------------ fronta dárků

    public static int PendingExecutor(CommandContext<CommandSourceStack> ctx, boolean clear) {
        return run(ctx, "fdailyrewards.admin.pending", null, sender -> {
            Target t = target(sender, arg(ctx, "player"));
            if (t == null) return 0;
            FDailyRewards plugin = FDailyRewards.get();
            Database db = plugin.database();
            List<Database.PendingGift> gifts = db.pending(t.uuid());
            if (gifts.isEmpty()) {
                Messages.sendMessage(sender, "Hráč " + who(t) + " nemá žádné čekající dárky.");
                return 1;
            }
            String list = gifts.stream()
                    .map(g -> "#" + g.id() + " den " + g.day() + " (" + CzFormat.date(plugin.time().rewardDate(Instant.ofEpochMilli(g.createdAtMillis()))) + ")")
                    .collect(Collectors.joining(", "));
            if (!clear) {
                Messages.sendMessage(sender, "Čekající dárky hráče " + who(t) + " (" + gifts.size() + "): " + list);
                return 1;
            }
            int n = 0;
            for (Database.PendingGift g : gifts) if (db.deletePending(g.id())) n++;
            Log.info("Fronta dárků hráče " + t.name() + " (" + t.uuid() + ") ručně smazána: " + list);
            Messages.sendMessage(sender, "Smazáno " + n + " čekajících dárků hráče " + who(t) + ": " + list);
            return 1;
        });
    }

    // ------------------------------------------------------------------ Login Pass

    public static int GivePassExecutor(CommandContext<CommandSourceStack> ctx, boolean hasCount) {
        return run(ctx, "fdailyrewards.admin.givepass", "givepass", sender -> {
            Target t = purchaseTarget(sender, "givepass", arg(ctx, "player"));
            if (t == null) return 0;
            int count = hasCount ? ctx.getArgument("months", Integer.class) : 1;
            FDailyRewards plugin = FDailyRewards.get();
            List<YearMonth> added = plugin.rewards().pass().give(t.uuid(), count);
            plugin.rewards().invalidate(t.uuid());
            Messages.sendMessage(sender, "Login Pass pro " + who(t) + ": přidáno " + added.size() + " měs. (" + monthRange(added) + ").");
            return 1;
        });
    }

    /** Zpětně kompatibilní {@code setvip} = givepass 1. */
    public static int SetVipExecutor(CommandContext<CommandSourceStack> ctx) {
        return run(ctx, "fdailyrewards.admin.setvip", "setvip", sender -> {
            Target t = purchaseTarget(sender, "setvip", arg(ctx, "player"));
            if (t == null) return 0;
            FDailyRewards plugin = FDailyRewards.get();
            List<YearMonth> added = plugin.rewards().pass().give(t.uuid(), 1);
            plugin.rewards().invalidate(t.uuid());
            Messages.sendMessage(sender, "Login Pass pro " + who(t) + ": přidán měsíc " + monthRange(added) + ".");
            return 1;
        });
    }

    public static int RemovePassExecutor(CommandContext<CommandSourceStack> ctx, boolean hasMonth) {
        return run(ctx, "fdailyrewards.admin.removepass", null, sender -> {
            Target t = target(sender, arg(ctx, "player"));
            if (t == null) return 0;
            YearMonth month = null;
            if (hasMonth) {
                int v = ctx.getArgument("month", Integer.class);
                try {
                    month = PassMath.fromInt(v);
                } catch (RuntimeException ex) {
                    Messages.sendMessage(sender, "<red>Měsíc zadej jako YYYYMM, např. 202611.");
                    return 0;
                }
            }
            FDailyRewards plugin = FDailyRewards.get();
            YearMonth removed = plugin.rewards().pass().remove(t.uuid(), month);
            plugin.rewards().invalidate(t.uuid());
            if (removed == null) Messages.sendMessage(sender, "Hráč " + who(t) + " nemá zaplacený " + (month == null ? "žádný měsíc" : "měsíc " + month) + ".");
            else Messages.sendMessage(sender, "Odebrán měsíc " + removed + " hráči " + who(t) + ".");
            return removed == null ? 0 : 1;
        });
    }

    /** Zpětně kompatibilní {@code removevip <hráč> <1-12>} (měsíc v aktuálním roce odměn). */
    public static int RemoveVipExecutor(CommandContext<CommandSourceStack> ctx) {
        return run(ctx, "fdailyrewards.admin.setvip", null, sender -> {
            Target t = target(sender, arg(ctx, "player"));
            if (t == null) return 0;
            int m = ctx.getArgument("month", Integer.class);
            if (m < 1 || m > 12) {
                Messages.sendMessage(sender, "<red>Měsíc musí být 1 až 12 (nebo použij removepass s YYYYMM).");
                return 0;
            }
            FDailyRewards plugin = FDailyRewards.get();
            YearMonth ym = YearMonth.of(plugin.time().today().getYear(), m);
            YearMonth removed = plugin.rewards().pass().remove(t.uuid(), ym);
            plugin.rewards().invalidate(t.uuid());
            Messages.sendMessage(sender, removed == null ? "Hráč " + who(t) + " nemá Pass pro " + ym + "." : "Odebrán měsíc " + ym + " hráči " + who(t) + ".");
            return removed == null ? 0 : 1;
        });
    }

    public static int PassInfoExecutor(CommandContext<CommandSourceStack> ctx) {
        return run(ctx, "fdailyrewards.admin.passinfo", null, sender -> {
            Target t = target(sender, arg(ctx, "player"));
            if (t == null) return 0;
            FDailyRewards plugin = FDailyRewards.get();
            var info = plugin.rewards().pass().info(t.uuid());
            if (!info.paid()) {
                Messages.sendMessage(sender, who(t) + ": Login Pass v databázi nemá" + (info.legacyPermission() ? ", ale má starou permission (přechodová podpora)." : "."));
                return 1;
            }
            String later = info.laterMonths().isEmpty() ? ""
                    : " Po mezeře ještě zaplaceno: " + info.laterMonths().stream().map(YearMonth::toString).collect(Collectors.joining(", ")) + ".";
            Messages.sendMessage(sender, who(t) + ": Login Pass na " + info.activeMonths().size() + " měs. (" + monthRange(List.copyOf(info.activeMonths()))
                    + "), platí do " + CzFormat.date(info.lastDay()) + (info.legacyPermission() ? ", navíc stará permission." : ".") + later);
            return 1;
        });
    }

    private static String monthRange(List<YearMonth> months) {
        if (months.isEmpty()) return "-";
        return months.size() == 1 ? months.get(0).toString() : months.get(0) + " až " + months.get(months.size() - 1);
    }
}
