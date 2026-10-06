package net.joinedthegame.ai.skill;

import java.util.function.Predicate;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Opening chests and barrels through their normal menu, so the lid opens, the sound plays
 * and double chests just work, then moving items in or out like shift-clicking.
 */
public final class Containers {
    private Containers() {}

    /** Open the container at {@code pos}. Returns its contents, or null if it wouldn't open. */
    public static @Nullable Container open(BotPlayer bot, BlockPos pos) {
        MenuProvider provider = bot.level().getBlockState(pos).getMenuProvider(bot.level(), pos);
        if (provider == null) return null;
        bot.openMenu(provider);
        return bot.containerMenu instanceof ChestMenu menu ? menu.getContainer() : null;
    }

    public static void close(BotPlayer bot) {
        if (bot.containerMenu != bot.inventoryMenu) bot.closeContainer();
    }

    /** Shift-click everything matching into the container. Returns how many items moved. */
    public static int deposit(BotPlayer bot, Container container, Predicate<ItemStack> filter) {
        int moved = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty() || !filter.test(stack)) continue;
            int before = stack.getCount();
            insert(container, stack);
            moved += before - stack.getCount();
        }
        bot.getInventory().setChanged();
        container.setChanged();
        return moved;
    }

    /** Take everything matching out of the container. Returns how many items moved. */
    public static int take(BotPlayer bot, Container container, Predicate<ItemStack> filter) {
        int moved = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || !filter.test(stack)) continue;
            int before = stack.getCount();
            bot.getInventory().add(stack);
            moved += before - stack.getCount();
            if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
        }
        container.setChanged();
        bot.getInventory().setChanged();
        return moved;
    }

    /** Merge {@code stack} into the container, shrinking it by however much fits. */
    private static void insert(Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
                int room = Math.min(slot.getMaxStackSize(), container.getMaxStackSize()) - slot.getCount();
                int move = Math.min(room, stack.getCount());
                if (move > 0) {
                    slot.grow(move);
                    stack.shrink(move);
                }
            }
        }
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            if (container.getItem(i).isEmpty() && container.canPlaceItem(i, stack)) {
                container.setItem(i, stack.split(stack.getCount()));
            }
        }
    }
}
