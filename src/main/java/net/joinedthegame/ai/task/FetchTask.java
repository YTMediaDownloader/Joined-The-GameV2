package net.joinedthegame.ai.task;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Containers;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.Homes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Go to the chests at home and take out some of something: the logs it stored last week
 * are better than chopping a new tree. Ignores the secret stash.
 */
public final class FetchTask implements Task {
    private final Predicate<ItemStack> what;
    private int wanted;
    private final String label;
    private final List<BlockPos> chests;
    private int index;
    private int openTicks;
    private int ticks;

    private FetchTask(Predicate<ItemStack> what, int count, String label, List<BlockPos> chests) {
        this.what = what;
        this.wanted = count;
        this.label = label;
        this.chests = chests;
    }

    /** A trip to the home chests for this, or null if none of them has any. */
    public static @Nullable FetchTask from(BotPlayer bot, BotBrain brain, Predicate<ItemStack> what, int count, String label) {
        List<BlockPos> chests = new ArrayList<>();
        for (BlockPos pos : storage(bot, brain)) {
            if (bot.level().getBlockEntity(pos) instanceof Container c && countIn(c, what) > 0) chests.add(pos);
        }
        return chests.isEmpty() ? null : new FetchTask(what, count, label, chests);
    }

    /** Something from the secret stash (trial keys, an ominous bottle): dig it up, take it, cover it again. */
    public static @Nullable FetchTask fromStash(BotPlayer bot, BotBrain brain, Predicate<ItemStack> what, int count, String label) {
        GlobalPos stash = brain.record().stash();
        if (stash == null || stash.dimension() != bot.level().dimension() || !bot.level().isLoaded(stash.pos())) return null;
        if (!(bot.level().getBlockEntity(stash.pos()) instanceof Container c) || countIn(c, what) == 0) return null;
        return new FetchTask(what, count, label, new ArrayList<>(List.of(stash.pos())));
    }

    /** How many of this are in the stash (0 if it isn't loaded). */
    public static int inStash(BotPlayer bot, BotBrain brain, Predicate<ItemStack> what) {
        GlobalPos stash = brain.record().stash();
        if (stash == null || stash.dimension() != bot.level().dimension() || !bot.level().isLoaded(stash.pos())) return 0;
        return bot.level().getBlockEntity(stash.pos()) instanceof Container c ? countIn(c, what) : 0;
    }

    /** Clear out these chests entirely (or as much as fits in the bag). */
    public static FetchTask emptying(List<BlockPos> chests, String label) {
        return new FetchTask(s -> true, 36 * 64, label, new ArrayList<>(chests));
    }

    /** How much of this is stored at home (not counting the stash). */
    public static int stored(BotPlayer bot, BotBrain brain, Predicate<ItemStack> what) {
        int n = 0;
        for (BlockPos pos : storage(bot, brain)) {
            if (bot.level().getBlockEntity(pos) instanceof Container c) n += countIn(c, what);
        }
        return n;
    }

    private static List<BlockPos> storage(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension() || !bot.level().isLoaded(home.pos())) return List.of();
        if (home.pos().distSqr(bot.blockPosition()) > 96 * 96) return List.of();
        List<BlockPos> out = new ArrayList<>(Homes.storageNear(bot.level(), home.pos()));
        BotManager manager = BotManager.get(bot.level().getServer());
        out.removeIf(p -> manager.isStash(bot.level(), p) || brain.isBlacklisted(p));
        return out;
    }

    private static int countIn(Container c, Predicate<ItemStack> what) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    @Override
    public String activity() {
        return this.label;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (++this.ticks > 20 * 90) return Status.FAILED;
        if (this.wanted <= 0 || this.index >= this.chests.size()) {
            Containers.close(bot);
            return Status.DONE;
        }
        BlockPos chest = this.chests.get(this.index);
        if (!BlockBreaker.canUse(bot, chest)) {
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToUse(bot, chest);
                if (nav.status() == Navigator.Status.FAILED) {
                    nav.stop(bot);
                    this.index++;
                }
            }
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);
        // A buried chest (the secret stash) won't open with the floor on top: lift the floorboard.
        if (this.openTicks == 0 && net.minecraft.world.level.block.ChestBlock.isChestBlockedAt(bot.level(), chest)
                && !bot.level().getBlockState(chest.above()).isAir()) {
            brain.breaker().start(chest.above());
            brain.breaker().tick(bot, brain.controls());
            return Status.RUNNING;
        }
        brain.controls().lookAt(bot, Vec3.atCenterOf(chest));
        this.openTicks++;
        if (this.openTicks == 5 && Containers.open(bot, chest) == null) {
            this.index++;
            this.openTicks = 0;
        }
        if (this.openTicks == 15 && bot.containerMenu instanceof net.minecraft.world.inventory.ChestMenu menu) {
            // A full bag can't take anything out: it used to open the chest, find no room,
            // put it back, and go off to make more instead. Swap some of what we're meant to
            // be storing anyway into the chest first.
            makeSpace(bot, menu.getContainer());
            int took = takeUpTo(bot, menu.getContainer(), this.what, this.wanted);
            this.wanted -= took;
            if (took == 0 && countIn(menu.getContainer(), this.what) > 0) {
                brain.log("fetch: no room in my bag to take anything out");
                this.wanted = 0;
            }
        }
        if (this.openTicks >= 25) {
            Containers.close(bot);
            this.index++;
            this.openTicks = 0;
        }
        return Status.RUNNING;
    }

    /**
     * Bag full? Put things in this chest that are over what we keep on us (or spare building
     * blocks) until a slot's free. Never tools, armour, food or what we came for.
     */
    static void makeSpace(BotPlayer bot, Container container) {
        var inv = bot.getInventory();
        net.joinedthegame.ai.Junk.consolidate(bot);
        for (int pass = 0; pass < 2 && inv.getFreeSlot() < 0; pass++) {
            for (int i = 0; i < Inv.MAIN_SLOTS && inv.getFreeSlot() < 0; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                boolean storable = net.joinedthegame.ai.Junk.keepQuota(bot, stack, false, false) == 0 && net.joinedthegame.ai.Junk.isKeeper(stack);
                boolean spareBlocks = pass == 1 && Inv.isPlaceableBlock(stack) && Inv.scaffoldCount(bot) > 64;
                if (!storable && !spareBlocks) continue;
                int before = stack.getCount();
                for (int j = 0; j < container.getContainerSize() && !stack.isEmpty(); j++) {
                    ItemStack slot = container.getItem(j);
                    if (slot.isEmpty()) {
                        container.setItem(j, stack.split(stack.getCount()));
                    } else if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
                        int move = Math.min(slot.getMaxStackSize() - slot.getCount(), stack.getCount());
                        slot.grow(move);
                        stack.shrink(move);
                    }
                }
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                if (stack.getCount() == before) break; // chest's full too
            }
        }
        container.setChanged();
        inv.setChanged();
    }

    /** Take up to {@code max} matching items. Returns how many came out. */
    public static int takeUpTo(BotPlayer bot, Container container, Predicate<ItemStack> what, int max) {
        int moved = 0;
        for (int i = 0; i < container.getContainerSize() && moved < max; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || !what.test(stack)) continue;
            ItemStack part = stack.split(Math.min(stack.getCount(), max - moved));
            int before = part.getCount();
            if (bot.brain() != null) bot.brain().markFetched(part.getItem());
            bot.getInventory().add(part);
            moved += before - part.getCount();
            if (!part.isEmpty()) {
                stack.grow(part.getCount()); // bag full
                break;
            }
            if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
        }
        container.setChanged();
        bot.getInventory().setChanged();
        return moved;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        Containers.close(bot);
        brain.navigator().stop(bot);
    }
}
