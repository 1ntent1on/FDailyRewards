package com.fretka46.fDailyRewards.Commands;

import com.fretka46.fDailyRewards.UI.Menu;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class MainCommand {

    public static int executor(CommandContext<CommandSourceStack> ctx) {
        var sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return 0;
        }
        Menu.openFor(player);
        return 1;
    }
}
