package dev.revere.alley.feature.bot.command;

import dev.revere.alley.common.text.CC;
import dev.revere.alley.feature.bot.BotService;
import dev.revere.alley.library.command.BaseCommand;
import dev.revere.alley.library.command.CommandArgs;
import dev.revere.alley.library.command.annotation.CommandData;
import org.bukkit.entity.Player;

public class BotLeaveCommand extends BaseCommand {
    @Override
    @CommandData(
            name = "botleave",
            aliases = {"leavebot"},
            usage = "botleave",
            description = "Leave your current bot duel."
    )
    public void onCommand(CommandArgs command) {
        Player player = command.getPlayer();
        BotService botService = this.plugin.getService(BotService.class);
        if (!botService.leaveMatch(player)) {
            player.sendMessage(CC.translate("&cYou are not in a bot duel."));
        }
    }
}
