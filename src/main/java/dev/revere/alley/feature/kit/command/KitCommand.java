package dev.revere.alley.feature.kit.command;

import dev.revere.alley.common.text.CC;
import dev.revere.alley.common.text.ClickableUtil;
import dev.revere.alley.core.locale.internal.impl.message.GlobalMessagesLocaleImpl;
import dev.revere.alley.feature.kit.KitCategory;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.kit.setting.KitSettingService;
import dev.revere.alley.feature.knockback.KnockbackManager;
import dev.revere.alley.feature.match.model.BaseRaiderRole;
import dev.revere.alley.library.command.BaseCommand;
import dev.revere.alley.library.command.CommandArgs;
import dev.revere.alley.library.command.annotation.CommandData;
import dev.revere.alley.library.command.annotation.CompleterData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author Emmy
 * @project Alley
 * @date 28/04/2024 - 21:58
 */
public class KitCommand extends BaseCommand {
    private static final List<String> KIT_SUB_COMMANDS = List.of(
            "list", "create", "delete", "toggle", "view", "settings", "viewsettings",
            "setsetting", "setcategory", "description", "setdisclaimer", "displayname",
            "seteditable", "seticon", "setinventory", "getinventory", "extraitems",
            "addpotion", "clearpotions", "removepotion",
            "saveall", "save", "setraidingrolekit", "removeraidingrolekit",
            "setmenutitle", "setprofile", "resetlayouts", "setbotmode"
    );

    private static final List<String> KIT_VALUE_SUB_COMMANDS = List.of(
            "delete", "toggle", "view", "viewsettings", "setsetting", "setcategory",
            "description", "setdisclaimer", "displayname", "seteditable", "seticon",
            "setinventory", "getinventory", "extraitems", "addpotion", "removepotion",
            "clearpotions", "setmenutitle", "setprofile", "resetlayouts", "save",
            "setraidingrolekit", "removeraidingrolekit", "setbotmode"
    );

    @CompleterData(name = "kit")
    public List<String> kitCompleter(CommandArgs command) {
        List<String> completion = new ArrayList<>();
        String[] args = command.getArgs();

        if (!command.getSender().hasPermission(this.getAdminPermission())) {
            return completion;
        }

        if (args.length == 1) {
            completion.addAll(KIT_SUB_COMMANDS);
            return completion;
        }

        if (args.length == 2) {
            if (KIT_VALUE_SUB_COMMANDS.contains(args[0].toLowerCase())) {
                this.plugin.getService(KitService.class).getKits().forEach(kit -> completion.add(kit.getName()));
            }
            return completion;
        }

        if (args.length == 3) {
            switch (args[0].toLowerCase()) {
                case "extraitems" -> completion.addAll(List.of("add"));
                case "setsetting" -> this.plugin.getService(KitSettingService.class).getSettings()
                        .forEach(setting -> completion.add(setting.getName()));
                case "seteditable" -> completion.addAll(List.of("true", "false"));
                case "setcategory" -> Arrays.stream(KitCategory.values())
                        .forEach(category -> completion.add(category.name().toLowerCase()));
                case "setprofile" -> this.plugin.getService(KnockbackManager.class).getProfiles()
                        .forEach(profile -> completion.add(profile.getName()));
                case "setbotmode" -> completion.addAll(List.of("melee", "potpvp", "builduhc", "gomoku"));
                case "setraidingrolekit", "removeraidingrolekit" -> Arrays.stream(BaseRaiderRole.values())
                        .forEach(role -> completion.add(role.name().toLowerCase()));
                default -> { }
            }
            return completion;
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("extraitems")
                && args[2].equalsIgnoreCase("add")) {
            completion.addAll(List.of("ak47", "awp", "grapplinghook", "specialapple", "goldenhead"));
            return completion;
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("setsetting")) {
            completion.addAll(List.of("true", "false"));
        }

        return completion;
    }

    @CommandData(
            name = "kit",
            aliases = "kit.help",
            isAdminOnly = true,
            inGameOnly = false,
            usage = "kit help <page>",
            description = "View all kit commands."
    )
    @Override
    public void onCommand(CommandArgs command) {
        CommandSender sender = command.getSender();
        String[] args = command.getArgs();
        int page = 1;

        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                sender.sendMessage(this.getString(GlobalMessagesLocaleImpl.ERROR_INVALID_PAGE_NUMBER).replace("{input}", args[0]));
            }
        }

        if (page > this.pages.length || page < 1) {
            sender.sendMessage(this.getString(GlobalMessagesLocaleImpl.ERROR_NO_MORE_PAGES_AVAILABLE)
                    .replace("{input}", String.valueOf(page))
                    .replace("{max-pages}", String.valueOf(pages.length))
            );
            return;
        }

        sender.sendMessage("");
        sender.sendMessage(CC.translate("&6&lKit Commands &8(&7Page &f" + page + "&7/&f" + this.pages.length + "&8)"));
        for (String string : this.pages[page - 1]) {
            sender.sendMessage(CC.translate(string));
        }

        sender.sendMessage("");

        if (sender instanceof Player) {
            Player player = (Player) sender;
            ClickableUtil.sendPageNavigation(player, page, this.pages.length, "/kit", false, true);
        }
    }

    private final String[][] pages = {
            {
                    " &6│ &6/kit list &7| View all kits",
                    " &6│ &6/kit create &8(&7kitName&8) &7| Create a kit",
                    " &6│ &6/kit delete &8(&7kitName&8) &7| Delete a kit",
                    " &6│ &6/kit toggle &8(&7kitName&8) &7| Toggle a kit",
                    " &6│ &6/kit view &8(&7kitName&8) &7| View a kit",
                    "",
                    "&fUse &6/kithelper &ffor other useful commands."
            },
            {
                    " &6│ &6/kit settings &7| View all existing kit settings",
                    " &6│ &6/kit viewsettings &8(&7kitName&8) &7| View settings of a kit",
                    " &6│ &6/kit setsetting &8(&7kitName&8) &8(&7setting&8) &8(&7enabled&8) &7| Set kit setting",
                    " &6│ &6/kit setbotmode &8(&7kitName&8) &8(&7melee/potpvp/builduhc/gomoku&8) &7| Set AI mode used in bot matches"
            },
            {
                    " &6│ &6/kit setcategory &8(&7kitName&8) &8(&7category&8) &7| Set category of a kit",
                    " &6│ &6/kit description &8(&7kitName&8) &8(&7description&8) &7| Set description of a kit",
                    " &6│ &6/kit setdisclaimer &8(&7kitName&8) &8(&7disclaimer&8) &7| Set disclaimer",
                    " &6│ &6/kit displayname &8(&7kitName&8) &8(&7displayname&8) &7| Set display-name of a kit",
                    " &6│ &6/kit setmenutitle &8(&7kitName&8) &8(&7title&8) &7| Set menu title of a kit",
                    " &6│ &6/kit seteditable &8(&7kitName&8) &8(&7true/false&8) &7| Set if a kit is editable",
                    " &6│ &6/kit setprofile &8(&7kitName&8) &8(&7profileName&8) &7| Set kb profile of a kit",
                    " &6│ &6/kit seticon &8(&7kitName&8) &7| Set icon of a kit"
            },
            {
                    " &6│ &6/kit setinventory &8(&7kitName&8) &7| Set inventory of a kit",
                    " &6│ &6/kit getinventory &8(&7kitName&8) &7| Get inventory of a kit",
                    " &6│ &6/kit extraitems &8(&7kitName&8) &7| Edit the extra items of a kit",
                    " &6│ &6/kit extraitems &8(&7kitName&8) add &8(&7ak47/awp/grapplinghook/specialapple&8) &7| Add an Alley item"
            },
            {
                    " &6│ &6/kit addpotion &8(&7kitName&8) &7| Set potion effects of a kit",
                    " &6│ &6/kit removepotion &8(&7kitName&8) &7| Remove potion effects of a kit",
                    " &6│ &6/kit clearpotions &8(&7kitName&8) &7| Clear potion effects of a kit"
            },
            {
                    " &6│ &6/kit setraidingrolekit &8(&7kitName&8) &8(&7role&8) &8(&7roleKitName&8) &7| Set raiding role kit",
                    " &6│ &6/kit removeraidingrolekit &8(&7kitName&8) &8(&7role&8) &8(&7roleKitName&8) &7| Remove raiding role kit"
            },
            {
                    " &6│ &6/kit resetlayouts &8(&7kitName&8) &7| Reset all profile layouts",
                    " &6│ &6/kit saveall &7| Save all kits",
                    " &6│ &6/kit save &8(&7kitName&8) &7| Save a kit"
            }
    };
}
