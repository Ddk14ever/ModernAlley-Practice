package dev.revere.alley.feature.queue.listener;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.profile.Profile;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.core.profile.enums.ProfileState;
import dev.revere.alley.feature.bot.BotService;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.match.MatchState;
import dev.revere.alley.feature.match.utility.MatchResultFlight;
import dev.revere.alley.feature.queue.Queue;
import dev.revere.alley.feature.queue.QueueService;
import dev.revere.alley.feature.spawn.SpawnService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;
import java.util.Map;

public class PlayAgainListener implements Listener {
    public static final String KIT_KEY = "play_again_kit";
    /** Present only on papers handed out by a Bot match: id of the Bot difficulty. */
    public static final String BOT_KEY = "play_again_bot";

    /**
     * Players who requested Play Again while their match was still ending. The actual
     * queue add runs two ticks after the lobby release, while the return-to-lobby
     * top-up paper logic may run in between — this marker prevents that logic from
     * misreading the player as "not queued" and handing out a slot-3 paper again.
     * 正在从结束对局中请求"再来一局"的玩家。实际入队操作在下一tick执行（大厅释放之后），
     * 而返回大厅的补纸逻辑可能恰好在这之间运行——该标记防止补纸逻辑误判"未入队"而补发第4格纸。
     */
    private static final Set<java.util.UUID> PENDING_PLAY_AGAIN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<java.util.UUID, Object> PENDING_BOT_REMATCHES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether the player is currently awaiting the delayed Play Again queue add.
     * 玩家是否正在等待下一tick的"再来一局"入队。
     */
    public static boolean isPlayAgainPending(Player player) {
        return player != null && (PENDING_PLAY_AGAIN.contains(player.getUniqueId())
                || PENDING_BOT_REMATCHES.containsKey(player.getUniqueId()));
    }

    /** A voluntary Bot leave also cancels a rematch accepted two ticks earlier. */
    public static boolean cancelPendingBotRematch(Player player) {
        return player != null && PENDING_BOT_REMATCHES.remove(player.getUniqueId()) != null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (handlePlayAgain(event.getPlayer(), event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (handlePlayAgain(player, event.getCurrentItem())) event.setCancelled(true);
    }

    private boolean handlePlayAgain(Player player, ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return false;
        String displayName = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if (!"Play Again".equals(displayName)) return false;

        NamespacedKey key = new NamespacedKey(AlleyPlugin.getInstance(), KIT_KEY);
        String kitName = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (kitName == null || kitName.isBlank()) return false;

        Kit kit = AlleyPlugin.getInstance().getService(KitService.class).getKit(kitName);
        if (kit == null) {
            player.sendMessage(CC.translate("&cThat kit no longer exists."));
            return true;
        }

        String botDifficulty = item.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(AlleyPlugin.getInstance(), BOT_KEY), PersistentDataType.STRING);
        boolean handled = botDifficulty == null || botDifficulty.isBlank()
                ? queueForPlayAgain(player, kit)
                : queueForBotRematch(player, kit, botDifficulty);
        if (handled) {
            item.setAmount(0);
        }
        return true;
    }

    /**
     * Starts a new Bot duel with the same kit and difficulty, delayed two ticks so
     * the lobby release settles first (mirrors the queue path).
     */
    public static boolean queueForBotRematch(Player player, Kit kit, String difficultyId) {
        if (player == null || kit == null || difficultyId == null || difficultyId.isBlank()) {
            return false;
        }

        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile playerProfile = profileService.getProfile(player.getUniqueId());
        if (playerProfile == null) {
            player.sendMessage(CC.translate("&cYou cannot use Play Again right now."));
            return false;
        }
        Object request = new Object();
        if (PENDING_PLAY_AGAIN.contains(player.getUniqueId())
                || PENDING_BOT_REMATCHES.putIfAbsent(player.getUniqueId(), request) != null) return false;

        // Released by releasePlayerForPlayAgain; otherwise this paper was clicked
        // after the match already returned the player to the lobby.
        MatchState matchState = playerProfile.getMatch() == null ? null : playerProfile.getMatch().getState();
        if (playerProfile.getState() == ProfileState.PLAYING
                && (matchState == MatchState.ENDING_MATCH || matchState == MatchState.ENDING_ROUND)) {
            if (!playerProfile.getMatch().releasePlayerForPlayAgain(player)) {
                PENDING_BOT_REMATCHES.remove(player.getUniqueId(), request);
                player.sendMessage(CC.translate("&cYou cannot use Play Again right now."));
                return false;
            }
        } else if (playerProfile.getState() != ProfileState.LOBBY || playerProfile.getMatch() != null) {
            PENDING_BOT_REMATCHES.remove(player.getUniqueId(), request);
            player.sendMessage(CC.translate("&cYou cannot use Play Again right now."));
            return false;
        }

        Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
            try {
                if (PENDING_BOT_REMATCHES.get(player.getUniqueId()) != request) return;
                Profile currentProfile = profileService.getProfile(player.getUniqueId());
                if (!player.isOnline() || currentProfile == null
                        || currentProfile.getState() != ProfileState.LOBBY
                        || currentProfile.getMatch() != null
                        || currentProfile.getQueueProfile() != null) {
                    return;
                }
                if (player.isDead()) player.spigot().respawn();
                MatchResultFlight.clear(player);
                player.setAllowFlight(false);
                player.setFlying(false);
                AlleyPlugin.getInstance().getService(SpawnService.class).teleportToSpawn(player);
                BotService botService = AlleyPlugin.getInstance().getService(BotService.class);
                // Close the old session now instead of waiting for its result timer.
                botService.restartMatch(player, kit, difficultyId);
            } finally {
                PENDING_BOT_REMATCHES.remove(player.getUniqueId(), request);
            }
        }, 2L);
        return true;
    }

    /**
     * Requeues a player two server ticks after the lobby release so the teleport
     * and state reset settle before matchmaking begins.
     */
    public static boolean queueForPlayAgain(Player player, Kit kit) {
        if (player == null || kit == null) return false;

        Queue queue = AlleyPlugin.getInstance().getService(QueueService.class).getQueues().stream()
                .filter(candidate -> candidate.getKit().equals(kit) && !candidate.isRanked() && !candidate.isDuos())
                .findFirst()
                .orElse(null);
        if (queue == null) {
            player.sendMessage(CC.translate("&cThat queue is no longer available."));
            return false;
        }

        ProfileService profileService = AlleyPlugin.getInstance().getService(ProfileService.class);
        Profile playerProfile = profileService.getProfile(player.getUniqueId());
        if (playerProfile == null) {
            player.sendMessage(CC.translate("&cYou cannot use Play Again right now."));
            return false;
        }

        // The result is broadcast while the match is still ENDING_ROUND (then flips to
        // ENDING_MATCH), so accept both stages for an instant release + teleport to lobby.
        // 结果广播时比赛仍处于ENDING_ROUND（随后翻转为ENDING_MATCH），
        // 两个阶段都允许立即释放并传送回大厅。
        MatchState matchState = playerProfile.getMatch() == null ? null : playerProfile.getMatch().getState();
        if (playerProfile.getState() == ProfileState.PLAYING
                && (matchState == MatchState.ENDING_MATCH || matchState == MatchState.ENDING_ROUND)) {
            PENDING_PLAY_AGAIN.add(player.getUniqueId());
            if (!playerProfile.getMatch().releasePlayerForPlayAgain(player)) {
                PENDING_PLAY_AGAIN.remove(player.getUniqueId());
                player.sendMessage(CC.translate("&cYou cannot use Play Again right now."));
                return false;
            }
        } else if (playerProfile.getState() != ProfileState.LOBBY || playerProfile.getMatch() != null) {
            player.sendMessage(CC.translate("&cYou cannot use Play Again right now."));
            return false;
        }

        Bukkit.getScheduler().runTaskLater(AlleyPlugin.getInstance(), () -> {
            // Clear the pending marker regardless of the outcome, so the lobby top-up
            // paper logic can act correctly on a later return.
            // 无论入队成败都清除pending标记，以便后续返回大厅的补纸逻辑正确判断。
            PENDING_PLAY_AGAIN.remove(player.getUniqueId());
            Profile currentProfile = profileService.getProfile(player.getUniqueId());
            if (!player.isOnline() || currentProfile == null
                    || currentProfile.getState() != ProfileState.LOBBY
                    || currentProfile.getMatch() != null
                    || currentProfile.getQueueProfile() != null) {
                return;
            }
            // Force the player physically back to the lobby before matchmaking. The
            // match finalizer flips the profile to LOBBY while its lobby teleport
            // still runs ~50 ticks later — clicking Play Again inside that window
            // used to queue straight from the arena, skipping the lobby entirely.
            // 入队前强制玩家物理回到大厅。对局终结器把状态翻成LOBBY后，约50tick才执行
            // 回大厅传送；在这个窗口内点击"再来一局"会直接从竞技场入队，完全跳过大厅。
            if (player.isDead()) player.spigot().respawn();
            MatchResultFlight.clear(player);
            player.setAllowFlight(false);
            player.setFlying(false);
            AlleyPlugin.getInstance().getService(SpawnService.class).teleportToSpawn(player);
            queue.addPlayer(player, 0);
        }, 2L);
        return true;
    }

    public static boolean queueForPlayAgain(Player player, String kitName) {
        if (kitName == null || kitName.isBlank()) {
            if (player != null) player.sendMessage(CC.translate("&cUsage: /playagain <kit>"));
            return false;
        }
        Kit kit = AlleyPlugin.getInstance().getService(KitService.class).getKit(kitName);
        if (kit == null) {
            if (player != null) player.sendMessage(CC.translate("&cThat kit no longer exists."));
            return false;
        }
        return queueForPlayAgain(player, kit);
    }
}
