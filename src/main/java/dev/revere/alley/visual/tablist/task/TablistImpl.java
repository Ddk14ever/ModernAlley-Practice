package dev.revere.alley.visual.tablist.task;

import dev.revere.alley.AlleyPlugin;
import dev.revere.alley.common.PlaceholderUtil;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.common.version.ClientVersionService;
import dev.revere.alley.core.locale.LocaleService;
import dev.revere.alley.core.locale.internal.impl.VisualsLocaleImpl;
import dev.revere.alley.core.profile.ProfileService;
import dev.revere.alley.visual.tablist.TablistAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * TablistAdapter接口的实现类，负责构建和更新玩家的制表符列表页眉和页脚。
 * @author Emmy
 * @project Alley
 * @date 07/09/2024 - 15:16
 */
public class  TablistImpl implements TablistAdapter {
    protected final AlleyPlugin plugin;
    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    /**
     * Constructor for the TablistVisualizer class.
     * TablistVisualizer类的构造函数。
     *
     * @param plugin The Alley bootstrap instance.
     *               Alley插件启动实例。
     */
    public TablistImpl(AlleyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> getHeader(Player player) {
        List<String> message = this.plugin.getService(LocaleService.class).getStringList(VisualsLocaleImpl.TAB_LIST_HEADER);
        message = message.stream()
                .map(line -> line.replace("{player}", player.getName()))
                .map(line -> line.replace("{online-players}", String.valueOf(this.plugin.getServer().getOnlinePlayers().size())))
                .map(line -> line.replace("{max-players}", String.valueOf(this.plugin.getServer().getMaxPlayers())))
                .map(line -> line.replace("{description}", this.plugin.getDescription().getDescription()))
                .collect(Collectors.toList());

        return PlaceholderUtil.setPapiSafe(player, message);
    }

    @Override
    public List<String> getFooter(Player player) {
        List<String> message = this.plugin.getService(LocaleService.class).getStringList(VisualsLocaleImpl.TAB_LIST_FOOTER);
        message = message.stream()
                .map(line -> line.replace("{player}", player.getName()))
                .map(line -> line.replace("{online-players}", String.valueOf(this.plugin.getServer().getOnlinePlayers().size())))
                .map(line -> line.replace("{max-players}", String.valueOf(this.plugin.getServer().getMaxPlayers())))
                .map(line -> line.replace("{description}", this.plugin.getDescription().getDescription()))
                .collect(Collectors.toList());

        return PlaceholderUtil.setPapiSafe(player, message);
    }

    @Override
    public void update(Player player) {
        // LegacyTablist owns 1.7 name colors independently of custom Tab settings.
        // Never turn header/footer lines (including blanks) into player entries.
        ClientVersionService versions = this.plugin.getService(ClientVersionService.class);
        if (versions.isLegacy17(player)) return;

        dev.revere.alley.core.profile.Profile profile = this.plugin.getService(ProfileService.class)
                .getProfiles().get(player.getUniqueId());
        if (profile == null || profile.getProfileData() == null
                || profile.getProfileData().getSettingData() == null) return;
        boolean enabled = profile.getProfileData().getSettingData().isTablistEnabled();
        if (!enabled) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            return;
        }

        List<String> headerLines = getHeader(player).stream()
                .map(CC::translate)
                .collect(Collectors.toList());

        List<String> footerLines = getFooter(player).stream()
                .map(CC::translate)
                .collect(Collectors.toList());

        Component headerComponent = SERIALIZER.deserialize(String.join("\n", headerLines));
        Component footerComponent = SERIALIZER.deserialize(String.join("\n", footerLines));

        player.sendPlayerListHeaderAndFooter(headerComponent, footerComponent);
    }

}
