package net.joinedthegame.ai;

import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import org.jspecify.annotations.Nullable;

/**
 * Bundles, used properly. A bag fills up with odds and ends (a few seeds, some feathers, a
 * couple of nuggets, an ink sac...), each taking a whole slot. Before anything gets thrown out to
 * make room, those go into bundles: every bundle in the bag, not just the first. Short of room
 * with string and leather to hand and no bundle with space left, a bot makes another. When there's
 * plenty of room again they come back out, so crafting and the planner can see them.
 * <p>
 * Only things bots carry but don't plan around: food, torches, fuel, arrows, building blocks,
 * keys and the like stay out (whatever's in a bundle, the planner can't see).
 */
public final class Bundles {
    private Bundles() {}

    /** Small stuff worth carrying but not worth a whole slot each. */
    public static boolean isTrinket(ItemStack s) {
        return s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS) || s.is(Items.MELON_SEEDS) || s.is(Items.PUMPKIN_SEEDS)
                || s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item()) || s.is(Items.FEATHER) || s.is(Items.STRING)
                || s.is(Items.BONE) || s.is(ItemTags.DYES) || s.is(ItemTags.EGGS) || s.is(ItemTags.SAPLINGS)
                || s.is(Items.FLINT) || s.is(Items.CLAY_BALL) || s.is(Items.LEATHER) || s.is(Items.GUNPOWDER)
                // More odds and ends: the leftovers of a day out.
                || s.is(Items.IRON_NUGGET) || s.is(Items.GOLD_NUGGET) || s.is(Items.INK_SAC) || s.is(Items.GLOW_INK_SAC)
                || s.is(Items.SLIME_BALL) || s.is(Items.RABBIT_HIDE) || s.is(Items.RABBIT_FOOT) || s.is(Items.GLOWSTONE_DUST)
                || s.is(Items.REDSTONE) || s.is(Items.COCOA_BEANS) || s.is(Items.HONEYCOMB) || s.is(Items.NAUTILUS_SHELL)
                || s.is(Items.PRISMARINE_SHARD) || s.is(Items.PRISMARINE_CRYSTALS) || s.is(Items.PHANTOM_MEMBRANE)
                || s.is(Items.AMETHYST_SHARD) || s.is(Items.ECHO_SHARD) || s.is(Items.ARMADILLO_SCUTE) || s.is(Items.TURTLE_SCUTE)
                || s.is(Items.SPIDER_EYE) || s.is(Items.SUGAR) || s.is(Items.PAPER) || s.is(Items.BLAZE_POWDER);
    }

    /** Pack, throw out, or make a bundle: all at this many slots used (the same point junk starts going). */
    public static final int FULLISH = Inv.MAIN_SLOTS - 8;

    /** Bag getting full: tuck the odds and ends into whatever bundles there are. Returns how many went in. */
    public static int pack(BotPlayer bot) {
        int moved = 0;
        for (int b = 0; b < Inv.MAIN_SLOTS; b++) {
            ItemStack bundle = bot.getInventory().getItem(b);
            if (!bundle.is(ItemTags.BUNDLES)) continue;
            BundleContents.Mutable m = bundle.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).asMutable();
            boolean changed = false;
            // A bundle holds what it holds (64 seeds, or 16 pearls, or one sword: the game's own
            // weights), so pack it to free the most slots: the smallest leftovers first, and only
            // whole stacks (half a stack in the bundle still leaves the slot taken: room spent on
            // nothing).
            java.util.List<Integer> slots = new java.util.ArrayList<>();
            for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
                ItemStack stack = bot.getInventory().getItem(i);
                if (i != b && !stack.isEmpty() && isTrinket(stack)) slots.add(i);
            }
            slots.sort(java.util.Comparator.comparingInt(i -> bot.getInventory().getItem(i).getCount()));
            for (int i : slots) {
                ItemStack stack = bot.getInventory().getItem(i);
                // Would all of it fit? (Tried on a copy, by the game's own rules.)
                BundleContents.Mutable trial = m.toImmutable().asMutable();
                if (trial.tryInsert(stack.copy()) < stack.getCount()) continue;
                int in = m.tryInsert(stack); // shrinks the stack by what went in
                if (in > 0) {
                    changed = true;
                    moved += in;
                }
                if (stack.isEmpty()) bot.getInventory().setItem(i, ItemStack.EMPTY);
            }
            if (changed) bundle.set(DataComponents.BUNDLE_CONTENTS, m.toImmutable());
        }
        if (moved > 0) bot.getInventory().setChanged();
        return moved;
    }

    /** Odds and ends lying loose that none of our bundles has room for. */
    private static boolean looseTrinketsWithNowhereToGo(BotPlayer bot) {
        int loose = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) if (isTrinket(bot.getInventory().getItem(i))) loose++;
        return loose >= 2;
    }

    /**
     * Short of room, odds and ends lying loose, and no bundle with space for them: make one, from
     * string and leather (two items, no table needed). Returns true if it made one.
     */
    public static boolean makeOneIfWorthIt(BotPlayer bot, @Nullable BotBrain brain) {
        if (Inv.usedSlots(bot) < FULLISH || !looseTrinketsWithNowhereToGo(bot)) return false;
        if (Inv.count(bot, Items.STRING) < 1 || Inv.count(bot, Items.LEATHER) < 1) return false;
        var step = new Planner(bot, false).next(Items.BUNDLE, Inv.count(bot, s -> s.is(ItemTags.BUNDLES)) + 1);
        if (!(step instanceof Planner.Craft craft) || !net.joinedthegame.ai.task.CraftTask.craftNow(bot, craft)) return false;
        if (brain != null) brain.log("made a bundle for all the odds and ends");
        return true;
    }

    /** Room in the bag again: take everything back out of every bundle (while there's space). */
    public static void unpack(BotPlayer bot) {
        for (int b = 0; b < Inv.MAIN_SLOTS; b++) {
            ItemStack bundle = bot.getInventory().getItem(b);
            if (!bundle.is(ItemTags.BUNDLES)) continue;
            BundleContents contents = bundle.get(DataComponents.BUNDLE_CONTENTS);
            if (contents == null || contents.isEmpty()) continue;
            BundleContents.Mutable m = contents.asMutable();
            ItemStack out;
            while (Inv.usedSlots(bot) < Inv.MAIN_SLOTS - 4 && (out = m.removeOne()) != null) {
                if (!bot.getInventory().add(out) && !out.isEmpty()) {
                    m.tryInsert(out); // no room after all: back in it goes
                    break;
                }
            }
            bundle.set(DataComponents.BUNDLE_CONTENTS, m.toImmutable());
        }
        bot.getInventory().setChanged();
    }
}
