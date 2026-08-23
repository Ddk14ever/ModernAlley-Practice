package dev.revere.alley.feature.kit.command.impl.data.inventory;

import dev.revere.alley.common.InventoryUtil;
import dev.revere.alley.common.text.CC;
import dev.revere.alley.core.locale.internal.impl.message.GlobalMessagesLocaleImpl;
import dev.revere.alley.feature.kit.Kit;
import dev.revere.alley.feature.kit.KitService;
import dev.revere.alley.feature.item.ItemService;
import dev.revere.alley.library.command.BaseCommand;
import dev.revere.alley.library.command.CommandArgs;
import dev.revere.alley.library.command.annotation.CommandData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

/**
 * Admin command to set optional extra items for a kit.
 * The admin's current inventory becomes the kit's editorItems.  The same
 * serialized ItemStack data is used by kit inventories, so PDC-backed Alley
 * items (weapons, golden heads, etc.) retain their identifiers here.
 */
public class KitExtraItemsCommand extends BaseCommand {
    @CommandData(
            name = "kit.extraitems",
            isAdminOnly = true,
            usage = "kit extraitems <kitName> [add <ak47|awp|grapplinghook|specialapple> [amount]]",
            description = "Set or add optional extra items for a kit."
    )
    @Override
    public void onCommand(CommandArgs command) {
        Player player = command.getPlayer();
        String[] args = command.getArgs();

        if (args.length < 1) {
            command.sendUsage();
            return;
        }

        KitService kitService = this.plugin.getService(KitService.class);
        Kit kit = kitService.getKit(args[0]);
        if (kit == null) {
            player.sendMessage(CC.translate(this.getString(GlobalMessagesLocaleImpl.KIT_NOT_FOUND)));
            return;
        }

        if (args.length >= 3 && args[1].equalsIgnoreCase("add")) {
            ItemStack item = getExtraItem(args[2]);
            if (item == null) {
                player.sendMessage(CC.translate("&cUnknown extra item. Use: ak47, awp, grapplinghook, specialapple, goldenhead."));
                return;
            }

            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Integer.parseInt(args[3]));
                } catch (NumberFormatException ignored) {
                    player.sendMessage(CC.translate("&cAmount must be a positive number."));
                    return;
                }
            }

            addExtraItems(kit, item, amount);
            kitService.saveKit(kit);
            player.sendMessage(CC.translate("&aAdded &6" + amount + "x &aextra item(s) to kit &6"
                    + kit.getDisplayName() + "&a."));
            return;
        }

        ItemStack[] contents = InventoryUtil.cloneItemStackArray(player.getInventory().getContents());
        // Only save non-null, non-air items
        int count = 0;
        for (ItemStack item : contents) {
            if (item != null && item.getType() != org.bukkit.Material.AIR) count++;
        }
        kit.setEditorItems(contents);
        kitService.saveKit(kit);

        player.sendMessage(CC.translate("&aSaved " + count + " extra items for kit &6" + kit.getDisplayName() + "&a."));
    }

    private ItemStack getExtraItem(String id) {
        ItemService itemService = this.plugin.getService(ItemService.class);
        return switch (id.toLowerCase()) {
            case "ak47", "ak-47" -> itemService.getAk47();
            case "awp" -> itemService.getAwp();
            case "grapplinghook", "grappling-hook", "grapple", "hook" -> itemService.getGrapplingHook();
            case "specialapple", "special-golden-apple" -> itemService.getSpecialGoldenApple();
            case "goldenhead", "golden-head" -> itemService.getGoldenHead();
            default -> null;
        };
    }

    private static void addExtraItems(Kit kit, ItemStack item, int amount) {
        ItemStack[] current = kit.getEditorItems();
        int baseSize = Math.max(36, current == null ? 0 : current.length);
        ItemStack[] updated = current == null ? new ItemStack[baseSize] : Arrays.copyOf(current, baseSize);

        for (int count = 0; count < amount; count++) {
            int empty = findEmptySlot(updated);
            if (empty == -1) {
                empty = updated.length;
                updated = Arrays.copyOf(updated, updated.length + 1);
            }
            updated[empty] = item.clone();
        }
        kit.setEditorItems(updated);
    }

    private static int findEmptySlot(ItemStack[] items) {
        for (int index = 0; index < items.length; index++) {
            if (items[index] == null || items[index].getType() == Material.AIR) return index;
        }
        return -1;
    }
}
