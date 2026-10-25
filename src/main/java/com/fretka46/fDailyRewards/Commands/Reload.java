package com.fretka46.fDailyRewards.Commands;

import com.fretka46.fDailyRewards.FDailyRewards;
import com.fretka46.fDailyRewards.Utils.Messages;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class Reload {

    public static int executor(CommandContext<CommandSourceStack> ctx) {
        var sender = ctx.getSource().getSender();
        if (!sender.hasPermission("fdailyrewards.reload")) {
            sender.sendMessage("You do not have permission to use this command.");
            return 0;
        }
        FDailyRewards.get().reloadAll();
        Messages.sendMessage(sender, "Configuration reloaded successfully!");
        return 1;
    }
}
