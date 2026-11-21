package dev.revere.alley.feature.command.impl.main.impl;

import dev.revere.alley.library.command.BaseCommand;
import dev.revere.alley.library.command.CommandArgs;
import dev.revere.alley.library.command.annotation.CommandData;
import dev.revere.alley.feature.arena.ArenaService;
import dev.revere.alley.feature.combat.CombatService;
import dev.revere.alley.feature.cooldown.CooldownService;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.kit.setting.KitSettingService;
import dev.revere.alley.feature.knockback.KnockbackBranch;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.knockback.KnockbackProfile;
import dev.revere.alley.feature.knockback.data.PlayerKnockbackData;
import dev.revere.alley.feature.knockback.minemen.MinemenClubSettings;
import dev.revere.alley.feature.queue.QueueService;
import dev.revere.alley.feature.emoji.EmojiService;
import dev.revere.alley.feature.duel.DuelRequestService;
import dev.revere.alley.feature.match.MatchService;
import dev.revere.alley.feature.match.snapshot.SnapshotService;
import dev.revere.alley.feature.party.PartyService;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.common.version.ClientVersionService;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Locale;

/**
 * @author Emmy
 * @project Alley
 * @date 30/05/2024 - 12:15
 */
public class AlleyDebugCommand extends BaseCommand {

    @CommandData(
            name = "alley.debug",
            isAdminOnly = true,
            usage = "alley debug <memory/instance/profile/profileData/kb> [player]",
            description = "Displays debug information for development purposes."
            // 显示用于开发目的的调试信息。
    )

    @Override
    public void onCommand(CommandArgs command) {
        Player player = command.getPlayer();
        String[] args = command.getArgs();
        ProfileService profileService = this.plugin.getService(ProfileService.class);
        Profile profile = profileService.getProfile(player.getUniqueId());

        if (args.length < 1) {
            command.sendUsage();
            return;
        }

        switch (args[0]) {
            case "kb":
                Player target = args.length > 1 ? this.plugin.getServer().getPlayerExact(args[1]) : player;
                if (target == null) {
                    player.sendMessage(CC.translate("&cThat player is not online."));
                    return;
                }
                this.sendKnockbackInfo(target, player);
                break;
            case "memory":
                this.sendMemoryInfo(player);
                break;
            case "instance":
                this.sendInstanceInfo(player);
                break;
            case "profile":
                this.sendProfileInfo(profile, player);
                break;
            case "profiledata":
                this.sendProfileData(profile, player);
                break;
            default:
                command.sendUsage();
                break;
        }
    }

    private void sendKnockbackInfo(Player target, Player viewer) {
        KnockbackManager manager = this.plugin.getService(KnockbackManager.class);
        PlayerKnockbackData data = manager.getPlayerData(target);
        KnockbackProfile applied = manager.getAppliedProfile(target);
        viewer.sendMessage(CC.translate("&6[KB] &f" + target.getName()
                + " &7branch=&e" + data.getBranch()
                + " &7assigned=&e" + data.getProfileName()
                + " &7resolved=&e" + (applied == null ? "none" : applied.getName())));
        viewer.sendMessage(CC.translate("&7Hurt window: &f" + target.getMaximumNoDamageTicks()
                + " &7remaining: &f" + target.getNoDamageTicks()));
        if (applied == null) {
            viewer.sendMessage(CC.translate("&7No knockback profile is applied to this player."));
            return;
        }
        if (applied.getBranch() != KnockbackBranch.MINEMENCLUB) return;

        MinemenClubSettings settings = applied.getMinemenClub();
        viewer.sendMessage(CC.translate("&7Loaded horizontal=&f" + settings.horizontal()
                + " &7vertical=&f" + settings.vertical()
                + " &7sprint-multiplier=&f" + settings.sprintMultiplier()));
        viewer.sendMessage(CC.translate("&7friction.horizontal=&f" + settings.horizontalFriction()
                + " &7gravity=&f" + settings.gravity()));
        AttributeInstance gravity = target.getAttribute(Attribute.GRAVITY);
        viewer.sendMessage(CC.translate("&7Server gravity attribute=&f"
                + (gravity == null ? "unavailable" : gravity.getValue())));
        if (!data.isServerControlled()) {
            int protocol = this.plugin.getService(ClientVersionService.class).getProtocol(target);
            if (protocol <= 0) {
                viewer.sendMessage(CC.translate(
                        "&eGravity: paused (unknown client protocol=" + protocol + ")"));
            } else if (protocol < 766) {
                viewer.sendMessage(CC.translate(
                        "&7Gravity mode=&evertical additive descent &7client protocol=&f" + protocol));
            } else {
                viewer.sendMessage(CC.translate("&7Gravity mode=&anative client attribute"));
            }
        } else {
            viewer.sendMessage(CC.translate("&7Gravity mode=&anative Bot physics"));
        }
        viewer.sendMessage(CC.translate("&7Knockback gravity: &f"
                + manager.getGravityCompensationStatus(target)));
        viewer.sendMessage(CC.translate("&7range-factor=&f" + settings.rangeFactor()
                + " &7start-range-reduction=&f" + settings.startRangeReduction()
                + " &7max-range-reduction=&f" + settings.maxRangeReduction()
                + " &7min-range=&f" + settings.minRange()));
        viewer.sendMessage(CC.translate("&7vertical-limit=&f" + settings.verticalLimit()
                + " &7vertical-limit-value=&f" + settings.verticalLimitValue()
                + " &7hit_delay=&f" + applied.getHitDelay()));
    }

    /**
     * Sends memory information to the player.
     * 向玩家发送内存信息。
     *
     * @param player The player to send the information to.
     *               接收信息的玩家。
     */
    private void sendMemoryInfo(Player player) {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long allocatedMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = allocatedMemory - freeMemory;

        Arrays.asList(
                "",
                "     &6&lAlley &7│ &fMemory Information",
                "      &6&l│ &fMax Memory: &6" + this.formatNumber((int) (maxMemory / 1024 / 1024)) + "MB",
                "      &6&l│ &fAllocated Memory: &6" + this.formatNumber((int) (allocatedMemory / 1024 / 1024)) + "MB",
                "      &6&l│ &fFree Memory: &6" + this.formatNumber((int) (freeMemory / 1024 / 1024)) + "MB",
                "      &6&l│ &fUsed Memory: &6" + this.formatNumber((int) (usedMemory / 1024 / 1024)) + "MB",
                ""
        ).forEach(line -> player.sendMessage(CC.translate(line)));
    }

    /**
     * Sends instance information to the player.
     * 向玩家发送实例信息。
     *
     * @param player The player to send the information to.
     *               接收信息的玩家。
     */
    private void sendInstanceInfo(Player player) {
        Arrays.asList(
                "",
                "     &6&lAlley &7│ &fInstance Information",
                "      &6&l│ &fProfiles: &6" + this.formatNumber(this.plugin.getService(ProfileService.class).getProfiles().size()),
                "      &6&l│ &fMatches: &6" + this.formatNumber(this.plugin.getService(MatchService.class).getMatches().size()),
                "      &6&l│ &fQueues: &6" + this.formatNumber(this.plugin.getService(QueueService.class).getQueues().size()),
                "      &6&l│ &fQueue profiles: &6" + this.formatNumber(Arrays.stream(this.plugin.getService(QueueService.class).getQueues().stream().mapToInt(queue -> queue.getProfiles().size()).toArray()).sum()),
                "      &6&l│ &fCooldowns: &6" + this.formatNumber(this.plugin.getService(CooldownService.class).getCooldowns().size()),
                "      &6&l│ &fActive Cooldowns: &6" + this.formatNumber((int) this.plugin.getService(CooldownService.class).getCooldowns().stream().filter(cooldown -> cooldown.getC().isActive()).count()),
                "      &6&l│ &fCombats: &6" + this.formatNumber(this.plugin.getService(CombatService.class).getCombatMap().size()),
                "      &6&l│ &fKits: &6" + this.formatNumber(this.plugin.getService(KitService.class).getKits().size()),
                "      &6&l│ &fKit Settings: &6" + this.formatNumber(this.plugin.getService(KitSettingService.class).getSettings().size()),
                "      &6&l│ &fParties: &6" + this.formatNumber(this.plugin.getService(PartyService.class).getParties().size()),
                "      &6&l│ &fArenas: &6" + this.formatNumber(this.plugin.getService(ArenaService.class).getArenas().size()),
                "      &6&l│ &fSnapshots: &6" + this.formatNumber(this.plugin.getService(SnapshotService.class).getSnapshots().size()),
                "      &6&l│ &fDuel Requests: &6" + this.formatNumber(this.plugin.getService(DuelRequestService.class).getDuelRequests().size()),
                "      &6&l│ &fEmojis: &6" + this.formatNumber(this.plugin.getService(EmojiService.class).getEmojis().size()),
                ""
        ).forEach(line -> player.sendMessage(CC.translate(line)));
    }

    /**
     * Sends profile information to the player.
     * 向玩家发送个人资料信息。
     *
     * @param profile The profile to send the information for.
     *                要发送信息的个人资料。
     * @param player  The player to send the information to.
     *                接收信息的玩家。
     */
    private void sendProfileInfo(Profile profile, Player player) {
        String banned = profile.getProfileData().isRankedBanned() ? "&c&lBANNED" : "&a&lNOT BANNED";
        Arrays.asList(
                "",
                "     &6&lProfile &7│ &f" + profile.getName(),
                "      &6&l│ &fUUID: &6" + profile.getUuid(),
                "      &6&l│ &fElo: &6" + this.formatNumber(profile.getProfileData().getElo()),
                "      &6&l│ &fCoins: &6" + this.formatNumber(profile.getProfileData().getCoins()),
                "      &6&l│ &fState: &6" + profile.getState() + " &7(" + profile.getState().getDescription() + ")",
                "      &6&l│ &fQueue Profile: &6" + (profile.getQueueProfile() != null ? profile.getQueueProfile().getQueue().getKit().getName() : "&c&lNULL"),
                "      &6&l│ &fRanked: &6" + banned,
                ""
        ).forEach(line -> player.sendMessage(CC.translate(line).replace("The player", profile.getName())));
    }

    /**
     * Sends profile data to the player.
     * 向玩家发送个人资料数据。
     *
     * @param profile The profile to send the data for.
     *                要发送数据的个人资料。
     * @param player  The player to send the data to.
     *                接收数据的玩家。
     */
    private void sendProfileData(Profile profile, Player player) {
        Arrays.asList(
                "",
                "     &6&lProfile Data &7│ &f" + profile.getName(),
                "      &6&l│ &fUnranked Wins: &6" + this.formatNumber(profile.getProfileData().getUnrankedWins()),
                "      &6&l│ &fUnranked Losses: &6" + this.formatNumber(profile.getProfileData().getUnrankedLosses()),
                "      &6&l│ &fRanked Wins: &6" + this.formatNumber(profile.getProfileData().getRankedWins()),
                "      &6&l│ &fRanked Losses: &6" + this.formatNumber(profile.getProfileData().getRankedLosses()),
                "      &6&l│ &fTotal FFA Kills: &6" + this.formatNumber(profile.getProfileData().getTotalFFAKills()),
                "      &6&l│ &fTotal FFA Deaths: &6" + this.formatNumber(profile.getProfileData().getTotalFFADeaths()),
                ""
        ).forEach(line -> player.sendMessage(CC.translate(line)));
    }

    /**
     * Formats a number with commas.
     * 用逗号格式化数字。
     *
     * @param number The number to format
     *               要格式化的数字
     * @return The formatted number
     *         格式化后的数字
     */
    private String formatNumber(int number) {
        return NumberFormat.getInstance(Locale.US).format(number);
    }
}
