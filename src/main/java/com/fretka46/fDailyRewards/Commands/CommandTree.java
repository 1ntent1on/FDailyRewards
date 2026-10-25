package com.fretka46.fDailyRewards.Commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.function.Predicate;

public final class CommandTree {

    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS = (ctx, builder) -> {
        String remaining = builder.getRemainingLowerCase();
        Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(remaining))
                .forEach(builder::suggest);
        return builder.buildFuture();
    };

    private static RequiredArgumentBuilder<CommandSourceStack, String> player() {
        return Commands.argument("player", StringArgumentType.word()).suggests(PLAYER_SUGGESTIONS);
    }

    private static final String[] ADMIN_PERMISSIONS = {
            "resetday", "setday", "giftday", "giftnext", "checkday", "unlockmissed", "setvip", "givepass", "removepass",
            "passinfo", "pending"
    };

    /** Podpříkaz vidí (i v tab-complete) jen ten, kdo na něj má právo; executor právo kontroluje znovu. */
    private static Predicate<CommandSourceStack> perm(String node) {
        return src -> src.getSender().hasPermission(node);
    }

    private static Predicate<CommandSourceStack> anyAdmin() {
        return src -> {
            for (String p : ADMIN_PERMISSIONS) if (src.getSender().hasPermission("fdailyrewards.admin." + p)) return true;
            return false;
        };
    }

    private static LiteralArgumentBuilder<CommandSourceStack> sub(String name) {
        return Commands.literal(name).requires(perm("fdailyrewards.admin." + name));
    }

    public static LiteralCommandNode<CommandSourceStack> buildRoot() {
        ArgumentBuilder<CommandSourceStack, ?> admin = Commands.literal("admin").requires(anyAdmin())
                .then(sub("resetday").then(player()
                        .then(Commands.argument("day", IntegerArgumentType.integer(1, 31)).executes(Admin::ResetDayExecutor))))
                .then(sub("setday").then(player()
                        .then(Commands.argument("day", IntegerArgumentType.integer(1, 31)).executes(Admin::SetDayExecutor))))
                .then(sub("giftday").then(player()
                        .then(Commands.argument("day", IntegerArgumentType.integer(1, 31)).executes(Admin::GiftDayExecutor))))
                .then(sub("giftnext").then(player().executes(Admin::GiftNextExecutor)))
                .then(sub("checkday").then(player().executes(Admin::CheckDayExecutor)))
                .then(sub("unlockmissed").then(player()
                        .executes(ctx -> Admin.UnlockMissedExecutor(ctx, true))
                        .then(Commands.literal("give").executes(ctx -> Admin.UnlockMissedExecutor(ctx, true)))
                        .then(Commands.literal("mark").executes(ctx -> Admin.UnlockMissedExecutor(ctx, false)))))
                .then(sub("pending").then(player()
                        .executes(ctx -> Admin.PendingExecutor(ctx, false))
                        .then(Commands.literal("clear").executes(ctx -> Admin.PendingExecutor(ctx, true)))))
                .then(sub("givepass").then(player()
                        .executes(ctx -> Admin.GivePassExecutor(ctx, false))
                        .then(Commands.argument("months", IntegerArgumentType.integer(1, 24))
                                .executes(ctx -> Admin.GivePassExecutor(ctx, true)))))
                .then(sub("removepass").then(player()
                        .executes(ctx -> Admin.RemovePassExecutor(ctx, false))
                        .then(Commands.argument("month", IntegerArgumentType.integer(200001, 209912))
                                .executes(ctx -> Admin.RemovePassExecutor(ctx, true)))))
                .then(sub("passinfo").then(player().executes(Admin::PassInfoExecutor)))
                // zpětně kompatibilní názvy (obě pod právem setvip)
                .then(sub("setvip").then(player().executes(Admin::SetVipExecutor)))
                .then(Commands.literal("removevip").requires(perm("fdailyrewards.admin.setvip")).then(player()
                        .then(Commands.argument("month", IntegerArgumentType.integer(1, 12)).executes(Admin::RemoveVipExecutor))));

        return Commands.literal("fdailyrewards")
                .executes(MainCommand::executor)
                .then(Commands.literal("reload").requires(perm("fdailyrewards.reload")).executes(Reload::executor))
                .then(admin)
                .build();
    }
}
