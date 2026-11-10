package dev.revere.alley;

import dev.revere.alley.bootstrap.AlleyContext;
import dev.revere.alley.bootstrap.lifecycle.Service;
import dev.revere.alley.common.logger.Logger;
import dev.revere.alley.common.logger.PluginLogger;
import dev.revere.alley.core.database.task.RepositoryCleanupTask;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.VisualsLocaleImpl;
import dev.revere.alley.feature.cosmetic.task.CosmeticTask;
import dev.revere.alley.feature.match.task.other.ArrowRemovalTask;
import dev.revere.alley.feature.match.task.other.MatchPearlCooldownTask;
import dev.revere.alley.visual.tablist.task.TablistUpdateTask;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Alley – A modern, modular Practice PvP core built from the ground up for Minecraft 1.8 and has updated to 1.21.11 now.
 * Alley – 一个现代化的模块化练习PVP核心插件（已升级至1.21）。
 * <p>
 * Developed by Revere Group., Alley focuses on clean, professional, and readable code,
 * 由Revere Group开发，Alley专注于简洁、专业和可读的代码，
 * making it easy for developers to jump into practice PvP development with minimal friction.
 * 让开发人员能够以最小的门槛快速上手练习PVP开发。
 * </p>
 * <p>
 * Alley is open source under the terms described in the README:
 * Alley根据README中描述的条款开源：
 * <a href="https://github.com/revere-group/alley-practice">GitHub Repository</a>
 * </p>
 *
 * @author Emmy, Remi, Hamza, Ddk1
 * @version 2.0 — Complete recode (entirely rewritten from scratch)
 *          完全重写（从头开始重写）
 * @see <a href="https://revere.no">revere.no</a>
 * @see <a href="https://discord.gg/revere">Discord Support</a>
 * @since 19/04/2024
 */
@Getter
public class AlleyPlugin extends JavaPlugin {
    @Getter
    private static AlleyPlugin instance;

    private final Alley api;
    private AlleyContext context;
    private dev.revere.alley.feature.cosmetic.internal.repository.impl.mvpmusic.HttpPackServer httpPackServer;

    public AlleyPlugin() {
        this.api = new Alley();
    }

    @Override
    public void onEnable() {
        final long startTime = System.nanoTime();
        instance = this;

        this.validatePluginMetadata();

        try {
            // Build MVP music resource pack (alley_mvp_music.zip)
            dev.revere.alley.feature.cosmetic.internal.repository.impl.mvpmusic.ResourcePackBuilder.ensurePackExists(this);

            this.context = new AlleyContext(this);
            this.context.initialize();

            // Start HTTP server for resource pack (needs LocaleService from context)
            this.httpPackServer = dev.revere.alley.feature.cosmetic.internal.repository.impl.mvpmusic.HttpPackServer.start(this);
            if (this.httpPackServer != null) {
                getServer().getPluginManager().registerEvents(
                        new dev.revere.alley.feature.cosmetic.internal.repository.impl.mvpmusic.PackSender(this, this.httpPackServer),
                        this);
            }

            // CPS detection
            dev.revere.alley.feature.cps.CPSListener cpsListener = new dev.revere.alley.feature.cps.CPSListener();
            getServer().getPluginManager().registerEvents(cpsListener, this);
            cpsListener.startTicking();
        } catch (Exception exception) {
            Logger.logException("A fatal error occurred during service initialization. Alley will be disabled.", exception);
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.scheduleTasks();

        final long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        PluginLogger.onEnable(durationMillis);

        this.api.runOnEnableCallbacks();
    }

    @Override
    public void onDisable() {
        if (this.httpPackServer != null) {
            this.httpPackServer.stop();
        }

        if (this.context != null) {
            this.context.shutdown();
        }

        PluginLogger.onDisable();

        this.api.runOnDisableCallbacks();
    }

    /**
     * Provides global, type-safe access to any managed service via its interface.
     * 通过接口提供对任何受管理服务的全局、类型安全的访问。
     *
     * @param serviceInterface The class of the service interface you want (e.g., ProfileService.class).
     *                         所需服务接口的类（例如ProfileService.class）。
     * @return The service instance.
     *         服务实例。
     * @throws IllegalStateException if the service is not found.
     *                               如果找不到该服务。
     */
    public <T extends Service> T getService(Class<T> serviceInterface) {
        Objects.requireNonNull(serviceInterface, "Service interface cannot be null");
        if (this.context == null) {
            throw new IllegalStateException("AlleyContext is not available. The bootstrap may be disabling or failed to load.");
        }
        return this.context.getService(serviceInterface)
                .orElseThrow(() -> new IllegalStateException("Could not find a registered service for: " + serviceInterface.getSimpleName()));
    }

    private void validatePluginMetadata() {
        List<String> authors = this.getDescription().getAuthors();
        List<String> expectedAuthors = Arrays.asList("Emmy", "Remi");
        if (!new HashSet<>(authors).containsAll(expectedAuthors)) {
            System.exit(0);
        }
    }

    private void scheduleTasks() {
        final Map<String, Runnable> tasks = new LinkedHashMap<>();

        tasks.put(RepositoryCleanupTask.class.getSimpleName(), () -> new RepositoryCleanupTask(this).runTaskTimer(this, 0L, 40L));
        tasks.put(MatchPearlCooldownTask.class.getSimpleName(), () -> new MatchPearlCooldownTask().runTaskTimer(this, 2L, 2L));
        tasks.put(ArrowRemovalTask.class.getSimpleName(), () -> new ArrowRemovalTask().runTaskTimer(this, 20L, 20L));
        tasks.put(CosmeticTask.class.getSimpleName(), () -> new CosmeticTask(this).runTaskTimerAsynchronously(this, 0L, 4L));

        if (this.getService(LocaleService.class).getBoolean(VisualsLocaleImpl.TAB_LIST_ENABLED_BOOLEAN)) {
            tasks.put(TablistUpdateTask.class.getSimpleName(), () -> new TablistUpdateTask().runTaskTimer(this, 0L, 20L));
        }

        tasks.forEach(Logger::logTimeTask);
    }

}
