package net.joinedthegame.ai;

import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Wooden things come in a dozen woods, and a recipe wants planks of one kind. So a bot builds
 * with whatever wood it has the most of: a birch forest bot ends up with a birch fence.
 */
public final class Wood {
    private Wood() {}

    /** The wood the bot has most of (counting logs as 4 planks), or oak if it has none. */
    public static String type(BotPlayer bot) {
        java.util.Map<String, Integer> amounts = new java.util.HashMap<>();
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.is(ItemTags.PLANKS) && !stack.is(ItemTags.LOGS)) continue;
            String wood = woodOf(stack.getItem());
            if (wood == null || item(wood, "planks") == Items.AIR) continue;
            amounts.merge(wood, stack.getCount() * (stack.is(ItemTags.LOGS) ? 4 : 1), Integer::sum);
        }
        String best = "oak";
        int most = 0;
        for (var e : amounts.entrySet()) {
            if (e.getValue() > most) {
                most = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** "birch" from birch planks, birch logs, stripped birch wood... */
    public static @org.jspecify.annotations.Nullable String woodOf(Item item) {
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        String wood = path.replace("stripped_", "");
        for (String suffix : new String[]{"_planks", "_log", "_wood", "_stem", "_hyphae", "_block"}) {
            if (wood.endsWith(suffix)) return wood.substring(0, wood.length() - suffix.length());
        }
        return null;
    }

    /** {@code item("birch", "fence")} is the birch fence. AIR if there's no such thing. */
    public static Item item(String wood, String suffix) {
        return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(wood + "_" + suffix));
    }

    /** The bot's preferred wood's version of something, falling back to oak. */
    public static Item preferred(BotPlayer bot, String suffix) {
        Item item = item(type(bot), suffix);
        return item == Items.AIR ? item("oak", suffix) : item;
    }
}
