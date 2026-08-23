package dev.revere.alley.feature.item.command;

import dev.revere.alley.common.text.CC;
import dev.revere.alley.feature.item.ItemService;
import dev.revere.alley.library.command.BaseCommand;
import dev.revere.alley.library.command.CommandArgs;
import dev.revere.alley.library.command.annotation.CommandData;
import dev.revere.alley.library.command.annotation.CompleterData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Emmy
 * 作者 Emmy
 * @project alley-practice
 * 项目 alley-practice
 * @since 18/07/2025
 * 自 18/07/2025
 */
public class CustomItemsCommand extends BaseCommand {
    private static final List<String> ITEM_NAMES = List.of(
            "goldenhead", "specialapple", "ak47", "awp", "grapplinghook"
    );

    @CompleterData(name = "customitems", aliases = {"alleyitems", "specialitems"})
    public List<String> customItemsCompleter(CommandArgs command) {
        List<String> completion = new ArrayList<>();
        if (!command.getSender().hasPermission(this.getAdminPermission())) {
            return completion;
        }
        String[] args = command.getArgs();
        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            ITEM_NAMES.forEach(name -> {
                if (name.startsWith(partial)) {
                    completion.add(name);
                }
            });
        } else if (args.length == 2) {
            completion.addAll(List.of("1", "5", "16", "32", "64"));
        }
        return completion;
    }

    @CommandData(
            name = "customitems",
            aliases = {"alleyitems", "specialitems"},
            usage = "customitems",
            description = "List of commands for special items",
            isAdminOnly = true
    )
    @Override
    public void onCommand(CommandArgs command) {
        String[] args = command.getArgs();
        if (args.length == 0) {
            sendHelp(command.getPlayer());
            return;
        }

        String type = args[0].toLowerCase();
        int amount = 1;
        if (args.length > 1) {
            try {
                amount = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {
                command.getPlayer().sendMessage(CC.translate("&cInvalid amount."));
                return;
            }
        }

        ItemService service = this.plugin.getService(ItemService.class);
        ItemStack item = switch (type) {
            case "ak47", "ak-47" -> service.getAk47();
            case "awp" -> service.getAwp();
            case "grapplinghook", "grappling-hook", "grapple", "hook" -> service.getGrapplingHook();
            case "specialapple", "special-golden-apple" -> service.getSpecialGoldenApple();
            case "goldenhead", "golden-head" -> service.getGoldenHead();
            default -> null;
        };

        if (item == null) {
            command.getPlayer().sendMessage(CC.translate("&cUnknown item '&f" + args[0] + "&c'."));
            command.getPlayer().sendMessage(CC.translate("&7Use &6/customitems &7to list every available item."));
            return;
        }

        item.setAmount(amount);
        command.getPlayer().getInventory().addItem(item);
        command.getPlayer().sendMessage(CC.translate("&aGiven &6" + type + " &ax" + amount + "."));
    }

    private void sendHelp(Player player) {
        List.of(
                "",
                "&6&lCustom Items Commands Help:",
                " &6│ &6/customitems goldenhead &8(&7amount&8) &7| Gives you a golden head",
                " &6│ &6/customitems specialapple &8(&7amount&8) &7| Gives you a special golden apple",
                " &6│ &6/customitems ak47 &8(&7amount&8) &7| Gives you an AK-47",
                " &6│ &6/customitems awp &8(&7amount&8) &7| Gives you an AWP",
                " &6│ &6/customitems grapplinghook &8(&7amount&8) &7| Gives you a grappling hook",
                "&8Aliases: &7golden-head, special-golden-apple, ak-47, grappling-hook, grapple, hook",
                "&8Tip: &7Use /kit extraitems <kit> add <item> to add these items to a kit layout.",
                ""
        ).forEach(line -> player.sendMessage(CC.translate(line)));
    }
}
