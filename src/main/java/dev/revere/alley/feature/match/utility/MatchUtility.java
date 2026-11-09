package dev.revere.alley.feature.match.utility;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.text.ClickableUtil;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.message.GameMessagesLocaleImpl;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.feature.arena.Arena;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.setting.types.mode.*;
import dev.revere.alley.feature.match.Match;
import dev.revere.alley.feature.match.MatchState;
import dev.revere.alley.feature.match.model.GameParticipant;
import dev.revere.alley.feature.match.model.internal.MatchGamePlayer;
import dev.revere.alley.feature.queue.listener.PlayAgainListener;
import lombok.experimental.UtilityClass;
import net.md_5.bungee.api.chat.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @author Emmy
 * @project Alley
 * @date 24/09/2024 - 17:12
 * 比赛工具类 - 提供比赛相关的通用工具方法（边界检查、结果消息发送等）。
 */
@UtilityClass
public class MatchUtility {
    private final AlleyPlugin plugin = AlleyPlugin.getInstance();

    /**
     * Check if a location is beyond the bounds of an arena excluding specific conditions.
     * 检查某个位置是否超出竞技场边界，排除特定条件。
     *
     * @param location the location
     *        要检查的位置
     * @param profile  the profile
     *        玩家档案
     * @return if the location is beyond the bounds
     *         如果位置超出边界则返回 true
     */
    public boolean isBeyondBounds(Location location, Profile profile) {
        Arena arena = profile.getMatch().getArena();
        Location corner1 = arena.getMinimum();
        Location corner2 = arena.getMaximum();

        double minX = Math.min(corner1.getX(), corner2.getX());
        double maxX = Math.max(corner1.getX(), corner2.getX());
        double minY = Math.min(corner1.getY(), corner2.getY());
        double maxY = Math.max(corner1.getY(), corner2.getY());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());

        boolean withinBounds;

        /*
         * If the match is ending or has specific kit settings enabled, we only check X and Z bounds and exclude Y bounds,
         * because there is a death y level coordinate that eliminates players when they fall below it.
         * This is to prevent players from being stuck in the air because by default, moving out of bounds is cancelled.
         * 如果比赛即将结束或启用了特定的套件设置，则仅检查 X 和 Z 边界，排除 Y 边界，
         * 因为存在一个死亡 Y 坐标，玩家掉落低于该坐标时会被淘汰。
         * 这样可以防止玩家被卡在空中，因为默认情况下超出边界会被取消移动。
         */
        if (profile.getMatch().getState() == MatchState.ENDING_MATCH
                || profile.getMatch().getKit().isSettingEnabled(KitSettingBed.class)
                || profile.getMatch().getKit().isSettingEnabled(KitSettingLives.class)
                || profile.getMatch().getKit().isSettingEnabled(KitSettingRounds.class)
                || profile.getMatch().getKit().isSettingEnabled(KitSettingStickFight.class)
                || profile.getMatch().getKit().isSettingEnabled(KitSettingCheckpoint.class)) {
            withinBounds = location.getX() >= minX && location.getX() <= maxX && location.getZ() >= minZ && location.getZ() <= maxZ;
        } else {
            withinBounds = location.getX() >= minX && location.getX() <= maxX && location.getY() >= minY && location.getY() <= maxY && location.getZ() >= minZ && location.getZ() <= maxZ;
        }

        return !withinBounds;
    }

    /**
     * Sends a match result message to all participants and spectators.
     * 向所有参与者和观众发送比赛结果消息。
     *
     * @param match      The match.
     *        比赛实例。
     * @param winnerName The name of the winning team.
     *        获胜队伍的名称。
     * @param loserName  The name of the losing team.
     *        失败队伍的名称。
     * @param winnerUuid The UUID of the winning team.
     *        获胜队伍的 UUID。
     * @param loserUuid  The UUID of the losing team.
     *        失败队伍的 UUID。
     */
    public void sendMatchResult(Match match, String winnerName, String loserName, UUID winnerUuid, UUID loserUuid) {
        LocaleService localeService = AlleyPlugin.getInstance().getService(LocaleService.class);

        List<String> format = localeService.getStringList(GameMessagesLocaleImpl.MATCH_ENDED_MATCH_RESULT_REGULAR_FORMAT);
        String winnerCommand = localeService.getString(GameMessagesLocaleImpl.MATCH_ENDED_MATCH_RESULT_REGULAR_WINNER_COMMAND).replace("{winner}", String.valueOf(winnerUuid));
        String winnerHover = localeService.getString(GameMessagesLocaleImpl.MATCH_ENDED_MATCH_RESULT_REGULAR_WINNER_HOVER).replace("{winner}", winnerName);
        String loserCommand = localeService.getString(GameMessagesLocaleImpl.MATCH_ENDED_MATCH_RESULT_REGULAR_LOSER_COMMAND).replace("{loser}", String.valueOf(loserUuid));
        String loserHover = localeService.getString(GameMessagesLocaleImpl.MATCH_ENDED_MATCH_RESULT_REGULAR_LOSER_HOVER).replace("{loser}", loserName);

        for (String line : format) {
            if (line.contains("{winner}") && line.contains("{loser}")) {
                String[] parts = line.split("\\{winner}", 2);

                if (parts.length > 1) {
                    String[] loserParts = parts[1].split("\\{loser}", 2);

                    TextComponent winnerComponent = new TextComponent(CC.translate(winnerName));
                    winnerComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, winnerCommand));
                    winnerComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(CC.translate(winnerHover)).create()));

                    TextComponent loserComponent = new TextComponent(CC.translate(loserName));
                    loserComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, loserCommand));
                    loserComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(CC.translate(loserHover)).create()));

                    sendCombinedSpigotMessage(match,
                            new TextComponent(CC.translate(parts[0])),
                            winnerComponent,
                            new TextComponent(CC.translate(loserParts[0])),
                            loserComponent,
                            new TextComponent(loserParts.length > 1 ? CC.translate(loserParts[1]) : "")
                    );
                }
            } else if (line.contains("{winner}")) {
                String[] parts = line.split("\\{winner}", 2);

                TextComponent winnerComponent = new TextComponent(CC.translate(winnerName));
                winnerComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, winnerCommand));
                winnerComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(CC.translate(winnerHover)).create()));

                sendCombinedSpigotMessage(match,
                        new TextComponent(CC.translate(parts[0])),
                        winnerComponent,
                        new TextComponent(parts.length > 1 ? CC.translate(parts[1]) : "")
                );
            } else if (line.contains("{loser}")) {
                String[] parts = line.split("\\{loser}", 2);

                TextComponent loserComponent = new TextComponent(CC.translate(loserName));
                loserComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, loserCommand));
                loserComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(CC.translate(loserHover)).create()));

                sendCombinedSpigotMessage(match,
                        new TextComponent(CC.translate(parts[0])),
                        loserComponent,
                        new TextComponent(parts.length > 1 ? CC.translate(parts[1]) : "")
                );
            } else {
                match.sendMessage(CC.translate(line));
            }
        }
    }

    /**
     * Sends the conjoined match result message.
     * 发送联合比赛结果消息（团队模式下的获胜者和失败者队伍信息）。
     *
     * @param match             The match.
     *        比赛实例。
     * @param winnerParticipant The winner participant.
     *        获胜方参与者。
     * @param loserParticipant  The loser participant.
     *        失败方参与者。
     */
    public void sendConjoinedMatchResult(Match match, GameParticipant<MatchGamePlayer> winnerParticipant, GameParticipant<MatchGamePlayer> loserParticipant) {
        String winnerTeamName = winnerParticipant.getLeader().getUsername();
        String loserTeamName = loserParticipant.getLeader().getUsername();

        match.sendMessage("");
        match.sendMessage(CC.translate("&aWinner Team: &f" + winnerTeamName));

        for (MatchGamePlayer player : winnerParticipant.getAllPlayers()) {
            String playerName = player.getUsername();

            TextComponent playerComponent = new TextComponent(CC.translate("&7- &f" + playerName));
            playerComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/inventory " + playerName));
            playerComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(CC.translate("&eClick to view " + playerName + "'s inventory")).create()));

            sendCombinedSpigotMessage(match, playerComponent);
        }

        match.sendMessage("");
        match.sendMessage(CC.translate("&cLoser Team: &f" + loserTeamName));

        for (MatchGamePlayer player : loserParticipant.getAllPlayers()) {
            String playerName = player.getUsername();

            TextComponent playerComponent = new TextComponent(CC.translate("&7- &f" + playerName));
            playerComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/inventory " + playerName));
            playerComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(CC.translate("&eClick to view " + playerName + "'s inventory")).create()));

            sendCombinedSpigotMessage(match, playerComponent);
        }

        match.sendMessage(CC.translate(""));
    }

    /**
     * Builds the Play Again paper item for the given kit. Shared by the match result
     * hand-out (winner slot 1 / loser slot 0) and the return-to-lobby top-up (slot 3).
     * 为指定套件构建"再来一局"纸物品。对局结果发放（胜者1格/败者0格）与
     * 返回大厅补发（第4格）共用。
     */
    public ItemStack createPlayAgainItem(Kit kit) {
        return createPlayAgainItem(kit, null);
    }

    /**
     * Builds the Play Again paper. A non-null bot difficulty marks the paper as a
     * Bot rematch: right-clicking it starts another Bot duel with the same kit and
     * difficulty instead of queueing for an unranked match.
     */
    public ItemStack createPlayAgainItem(Kit kit, String botDifficultyId) {
        if (kit == null) return null;

        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        meta.setDisplayName(CC.translate("&6&lPlay Again"));
        List<String> lore = new ArrayList<>();
        boolean botRematch = botDifficultyId != null && !botDifficultyId.isBlank();
        lore.add(CC.translate(botRematch
                ? "&7Right-click to fight the same Bot again."
                : "&7Right-click to join the same queue again."));
        lore.add(CC.translate("&7Kit: &f" + kit.getDisplayName()));
        if (botRematch) {
            lore.add(CC.translate("&7Bot: &f" + botDifficultyId));
        }
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, PlayAgainListener.KIT_KEY),
                PersistentDataType.STRING,
                kit.getName());
        if (botRematch) {
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, PlayAgainListener.BOT_KEY),
                    PersistentDataType.STRING,
                    botDifficultyId);
        }
        paper.setItemMeta(meta);
        return paper;
    }

    /**
     * Sends the final clickable Play Again line after all result/progress output.
     * Only normal unranked solo queues can be rejoined through this action.
     */
    public void sendPlayAgain(Match match) {
        if (match == null || match.getKit() == null) return;

        String botDifficulty = match.getBotDifficultyId();
        String command;
        String hover;
        if (botDifficulty != null) {
            // Bot duel: the link restarts the same Bot instead of queueing.
            command = "/playagainbot " + match.getKit().getName() + " " + botDifficulty;
            hover = "&eClick to fight the same &6" + match.getKit().getDisplayName() + "&e Bot again.";
        } else {
            if (match.getQueue() == null || match.getQueue().isRanked() || match.getQueue().isDuos()) {
                return;
            }
            command = "/playagain " + match.getKit().getName();
            hover = "&eClick to queue for &6" + match.getKit().getDisplayName() + "&e again.";
        }

        TextComponent line = new TextComponent("");
        line.addExtra(ClickableUtil.createComponent(
                "&a&l(Play Again)",
                command,
                hover
        ));

        ProfileService profileService = plugin.getService(ProfileService.class);
        match.getParticipants().forEach(participant -> participant.getPlayers().forEach(gamePlayer -> {
            Player player = gamePlayer.getTeamPlayer();
            Profile profile = profileService.getProfile(gamePlayer.getUuid());
            if (player != null && (profile == null || profile.getGameEvent() == null)) {
                player.spigot().sendMessage(line);
            }
        }));

        match.getSpectators().forEach(uuid -> {
            Player player = plugin.getServer().getPlayer(uuid);
            Profile profile = profileService.getProfile(uuid);
            if (player != null && (profile == null || profile.getGameEvent() == null)) {
                player.spigot().sendMessage(line);
            }
        });
    }

    /**
     * Sends a combined spigot (clickable) message to all participants including spectators.
     * 向所有参与者（包括观众）发送组合的 Spigot（可点击）消息。
     *
     * @param message The message to send.
     *        要发送的消息。
     */
    public void sendCombinedSpigotMessage(Match match, BaseComponent... message) {
        match.getParticipants().forEach(gameParticipant -> {
            gameParticipant.getPlayers().forEach(uuid -> {
                Player player = uuid.getTeamPlayer();
                if (player != null) {
                    player.spigot().sendMessage(message);
                }
            });
        });

        match.getSpectators().forEach(uuid -> {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                player.spigot().sendMessage(message);
            }
        });
    }
}
