package net.joinedthegame.ai;

import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;

/**
 * Bundles: when the bag's getting full, the odds and ends (seeds, flowers, feathers, string,
 * bones, dyes...) go in a bundle to free up slots. When there's room again they come back out,
 * so crafting and the planner can see them.
 */
public final class Bundles {
    private Bundles() {}

    /** Small stuff worth carrying but not worth a whole slot each. */
    public static boolean isTrinket(ItemStack s) {
        return s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(Items.MELON_SEEDS) || s.is(Items.PUMPKIN_SEEDS)
                || s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item()) || s.is(Items.FEATHER) || s.is(Items.STRING)
                || s.is(Items.BONE) || s.is(ItemTags.DYES) || s.is(ItemTags.EGGS) || s.is(ItemTags.SAPLINGS)
                || s.is(Items.FLINT) || s.is(Items.CLAY_BALL) || s.is(Items.LEATHER) || s.is(Items.GUNPOWDER);
    }

    private static int bundleSlot(BotPlayer bot) {
        return Inv.find(bot, s -> s.is(ItemTags.BUNDLES));
    }

    /** Bag nearly full: tuck the odds and ends into the bundle. */
    public static void pack(BotPlayer bot) {
        int slot = bundleSlot(bot);
        if (slot < 0) return;
        ItemStack bundle = bot.getInventory().getItem(slot);
        BundleContents contents = bundle.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        BundleContents.Mutable m = contents.asMutable();
        boolean changed = false;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            if (i == slot) continue;
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty() || !isTrinket(stack)) continue;
            if (m.tryInsert(stack) > 0) changed = true; // shrinks the stack by what went in
            if (stack.isEmpty()) bot.getInventory().setItem(i, ItemStack.EMPTY);
        }
        if (changed) {
            bundle.set(DataComponents.BUNDLE_CONTENTS, m.toImmutable());
            bot.getInventory().setChanged();
        }
    }

    /** Room in the bag again: take everything back out of the bundle. */
    public static void unpack(BotPlayer bot) {
        int slot = bundleSlot(bot);
        if (slot < 0) return;
        ItemStack bundle = bot.getInventory().getItem(slot);
        BundleContents contents = bundle.get(DataComponents.BUNDLE_CONTENTS);
        if (contents == null || contents.isEmpty()) return;
        BundleContents.Mutable m = contents.asMutable();
        ItemStack out;
        while (Inv.usedSlots(bot) < Inv.MAIN_SLOTS - 4 && (out = m.removeOne()) != null) {
            if (!bot.getInventory().add(out) && !out.isEmpty()) {
                m.tryInsert(out); // no room after all: back in it goes
                break;
            }
        }
        bundle.set(DataComponents.BUNDLE_CONTENTS, m.toImmutable());
        bot.getInventory().setChanged();
    }
}
