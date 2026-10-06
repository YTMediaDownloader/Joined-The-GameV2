package net.joinedthegame.ai.goal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Junk;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Containers;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.Homes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Putting stuff away at home. Extras go in the chest by the bed (the bot keeps a working
 * amount of each thing: a stack of cobble, some logs and coal, all its iron). Treasure goes
 * in the secret chest under the floor: dig up the floorboard, stash it, put the floor back.
 */
public final class DepositGoal extends Goal {
    private enum Mode { STORE, STASH, HOMEWARD, NEW_CHEST }

    private Mode mode = Mode.STORE;
    private @Nullable BlockPos chest;
    private int ticks;
    private int openTicks;
    private boolean uncovered;
    /** Getting home, or making and putting down another chest. */
    private net.joinedthegame.ai.task.@Nullable Task task;

    public DepositGoal(BotBrain brain) {
        super(brain);
    }

    private @Nullable GlobalPos homeHere(BotPlayer bot) {
        GlobalPos home = this.brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return null;
        return home.pos().distSqr(bot.blockPosition()) <= 48 * 48 ? home : null;
    }

    @Override
    public int priority(BotPlayer bot) {
        GlobalPos home = homeHere(bot);
        if (home == null) {
            // Away from home with the bag full (a long mining trip): go home and unload, rather
            // than throwing things away. Not for a short hop's worth of extras.
            GlobalPos far = this.brain.record().home();
            if (far == null || far.dimension() != bot.level().dimension()) return 0;
            if (far.pos().distSqr(bot.blockPosition()) > 400 * 400) return 0;
            if (Inv.usedSlots(bot) < Inv.MAIN_SLOTS - 4) return 0;
            if (extraItems(bot, this.brain.buildingHouse(), this.brain.focus() == net.joinedthegame.ai.BotBrain.Focus.NETHER, false)
                    + extraItems(bot, this.brain.buildingHouse(), false, true) < 48) return 0;
            this.mode = Mode.HOMEWARD;
            return 50;
        }
        // Treasure to hide?
        GlobalPos stash = this.brain.record().stash();
        boolean nether = this.brain.focus() == net.joinedthegame.ai.BotBrain.Focus.NETHER;
        // Only if there's treasure over what we keep on us (it used to go and dig up the stash
        // for diamonds it was keeping, or golden apples, and put nothing in).
        if (stash != null && stash.dimension() == bot.level().dimension() && extraItems(bot, this.brain.buildingHouse(), nether, true) > 0
                && bot.level().getBlockState(stash.pos()).is(Blocks.CHEST)) {
            this.mode = Mode.STASH;
            this.chest = stash.pos();
            return 40;
        }
        // Extras to put away? (Mid-build, the house materials it's carrying don't count.)
        int extra = extraItems(bot, this.brain.buildingHouse(), nether, false);
        // Home: put away anything that's not for the job at hand (hoarders don't even wait for that much).
        int threshold = (int) (8 - this.brain.personality().hoarder() * 7);
        // Mining under the house: not every few ores. When the bag's getting full.
        if (this.brain.mining()) threshold = Integer.MAX_VALUE;
        if (extra == 0 || extra < threshold && Inv.usedSlots(bot) < 27) return 0;
        BotManager manager = BotManager.get(bot.level().getServer());
        List<BlockPos> storage = Homes.storageNear(bot.level(), home.pos());
        storage.removeIf(p -> this.brain.isBlacklisted(p) || manager.isStash(bot.level(), p));
        // A chest with room in it; none (or no chests at all): make another and put it down.
        BlockPos room = null;
        for (BlockPos p : storage) {
            if (bot.level().getBlockEntity(p) instanceof Container c && hasRoom(c)) {
                room = p;
                break;
            }
        }
        int urgency = Inv.usedSlots(bot) >= 27 ? 55 : 32;
        if (room == null) {
            // (Not while moving house: the new place has its own chests coming.)
            if (this.brain.now() < this.noNewChestUntil || this.brain.settling()) return 0;
            this.mode = Mode.NEW_CHEST;
            return urgency;
        }
        this.mode = Mode.STORE;
        this.chest = room;
        return urgency;
    }

    /** How many items are over the bot's keep amounts. */
    private long noNewChestUntil;

    private static boolean hasRoom(Container c) {
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).isEmpty()) return true;
        return false;
    }

    /** How many items over the keep amounts would go in the stash ({@code stash}) or the normal chests. */
    private static int extraItems(BotPlayer bot, boolean building, boolean netherTrip, boolean stash) {
        Map<Object, Integer> kept = new HashMap<>();
        int extra = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            int quota = Junk.keepQuota(bot, stack, building, netherTrip);
            Object key = Junk.quotaKey(stack, building);
            int have = kept.getOrDefault(key, 0);
            int keep = Math.max(0, Math.min(stack.getCount(), quota - have));
            kept.put(key, have + keep);
            if (!Junk.isKeeper(stack) && !Junk.isValuable(stack)) continue; // rubbish gets thrown out, not stored
            if (goesInStash(bot, stack) == stash) extra += stack.getCount() - keep;
        }
        return extra;
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
        this.openTicks = 0;
        this.uncovered = false;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (this.mode == Mode.HOMEWARD || this.mode == Mode.NEW_CHEST) return errand(bot);
        BlockPos chest = this.chest;
        if (chest == null || ++this.ticks > 20 * 90) return false;
        if (!BlockBreaker.canUse(bot, chest)) {
            Navigator nav = this.brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToUse(bot, chest);
                if (nav.status() == Navigator.Status.FAILED) {
                    this.brain.blacklist(chest);
                    return false;
                }
            }
            return true;
        }
        this.brain.navigator().stop(bot);

        // The secret chest is buried: dig up the floor above it first.
        if (this.mode == Mode.STASH && !bot.level().getBlockState(chest.above()).isAir()
                && !bot.level().getBlockState(chest.above()).canBeReplaced() && this.openTicks == 0) {
            this.brain.breaker().start(chest.above());
            this.brain.breaker().tick(bot, this.brain.controls());
            this.uncovered = true;
            return true;
        }

        this.brain.controls().lookAt(bot, Vec3.atCenterOf(chest));
        this.openTicks++;
        if (this.openTicks == 5 && Containers.open(bot, chest) == null) {
            this.brain.blacklist(chest);
            return false;
        }
        if (this.openTicks == 20 && bot.containerMenu instanceof ChestMenu menu) {
            Container container = menu.getContainer();
            int moved = depositExtras(bot, container, this.mode == Mode.STASH, this.brain.buildingHouse(), this.brain.focus() == net.joinedthegame.ai.BotBrain.Focus.NETHER);
            if (moved > 0) this.brain.log((this.mode == Mode.STASH ? "hid " : "stored ") + moved + " items");
            if (this.mode == Mode.STORE) net.joinedthegame.ai.Storage.refresh(bot, this.brain);
            else if (this.mode == Mode.STORE) this.brain.blacklist(chest); // full, use another
        }
        if (this.openTicks == 35) Containers.close(bot);
        if (this.openTicks >= 35) {
            // Floor back over the secret chest.
            if (this.mode == Mode.STASH && bot.level().getBlockState(chest.above()).canBeReplaced()) {
                this.brain.controls().lookAt(bot, Vec3.atCenterOf(chest.above()));
                if (!Placer.place(bot, chest.above(), Inv::isPlaceableBlock) && this.openTicks < 60) return true;
            }
            cooldown(this.brain.now(), 20 * 30);
            return false;
        }
        return true;
    }

    /** Store everything over the keep amounts: treasure in the stash, the rest in normal chests. */
    private static int depositExtras(BotPlayer bot, Container container, boolean stash, boolean building, boolean netherTrip) {
        Map<Object, Integer> kept = new HashMap<>();
        int moved = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            int quota = Junk.keepQuota(bot, stack, building, netherTrip);
            Object key = Junk.quotaKey(stack, building);
            int have = kept.getOrDefault(key, 0);
            int keep = Math.max(0, Math.min(stack.getCount(), quota - have));
            kept.put(key, have + keep);
            int extra = stack.getCount() - keep;
            if (extra <= 0 || goesInStash(bot, stack) != stash || !Junk.isKeeper(stack) && !Junk.isValuable(stack)) continue;
            if (keepsDiamonds(bot, stack)) continue;
            ItemStack part = stack.split(extra);
            int before = part.getCount();
            insertInto(container, part);
            moved += before - part.getCount();
            if (!part.isEmpty()) stack.grow(part.getCount()); // chest full: keep the rest
        }
        bot.getInventory().setChanged();
        container.setChanged();
        return moved;
    }

    /** Diamonds are for gear until it's wearing full diamond; only after that are they treasure. */
    private static boolean keepsDiamonds(BotPlayer bot, ItemStack stack) {
        return stack.is(net.minecraft.world.item.Items.DIAMOND) && !Inv.fullDiamond(bot);
    }

    /**
     * Treasure goes in the secret stash if there is one; with no stash (yet), the normal chests
     * are better than carrying it about.
     */
    private static boolean goesInStash(BotPlayer bot, ItemStack stack) {
        return stashable(bot, stack) && hasStash(bot);
    }

    private static boolean hasStash(BotPlayer bot) {
        var brain = bot.brain();
        var stash = brain == null ? null : brain.record().stash();
        return stash != null && stash.dimension() == bot.level().dimension()
                && (!bot.level().isLoaded(stash.pos()) || bot.level().getBlockState(stash.pos()).is(Blocks.CHEST));
    }

    private static boolean stashable(BotPlayer bot, ItemStack stack) {
        return Junk.isValuable(stack) && !keepsDiamonds(bot, stack);
    }

    private static void insertInto(Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) {
                int move = Math.min(slot.getMaxStackSize() - slot.getCount(), stack.getCount());
                if (move > 0) {
                    slot.grow(move);
                    stack.shrink(move);
                }
            }
        }
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            if (container.getItem(i).isEmpty()) container.setItem(i, stack.split(stack.getCount()));
        }
    }

    /** The longer jobs: the walk home with a full bag, or a new chest for the storage. */
    private boolean errand(BotPlayer bot) {
        if (this.task == null) {
            GlobalPos home = this.brain.record().home();
            if (home == null || home.dimension() != bot.level().dimension()) return false;
            if (this.mode == Mode.HOMEWARD) {
                this.brain.log("bag's full: heading home to put things away");
                this.task = new net.joinedthegame.ai.task.TravelTask(home.pos(), 8, "Heading home to put my stuff away");
            } else {
                this.task = newChest(bot, home.pos());
                if (this.task == null) {
                    this.noNewChestUntil = this.brain.now() + 20 * 60 * 5;
                    return false;
                }
            }
        }
        var status = this.task.tick(bot, this.brain);
        if (status == net.joinedthegame.ai.task.Task.Status.RUNNING) return true;
        this.task.stop(bot, this.brain);
        this.task = null;
        if (status == net.joinedthegame.ai.task.Task.Status.FAILED) {
            if (this.mode == Mode.NEW_CHEST) this.noNewChestUntil = this.brain.now() + 20 * 60 * 5;
            cooldown(this.brain.now(), 20 * 60 * 3);
        }
        return false; // (a chest just made goes down on the next go)
    }

    /**
     * The next step toward another storage chest: make one, then put it down by the others
     * (right next to one, so they join up into a double chest) or by the bed if there are none.
     */
    private net.joinedthegame.ai.task.@Nullable Task newChest(BotPlayer bot, BlockPos home) {
        if (Inv.count(bot, net.minecraft.world.item.Items.CHEST) == 0) {
            boolean[] failed = {false};
            var t = net.joinedthegame.ai.task.Tasks.toward(bot, net.joinedthegame.ai.task.Tasks.Need.of("a chest",
                    s -> s.is(net.minecraft.world.item.Items.CHEST), 1, b -> net.minecraft.world.item.Items.CHEST), failed);
            return failed[0] ? null : t;
        }
        net.joinedthegame.ai.nav.WorldView w = new net.joinedthegame.ai.nav.WorldView(bot.level());
        BotManager manager = BotManager.get(bot.level().getServer());
        List<BlockPos> next = new java.util.ArrayList<>();
        java.util.Map<BlockPos, net.minecraft.core.Direction> facing = new java.util.HashMap<>();
        for (BlockPos c : Homes.storageNear(bot.level(), home)) {
            if (manager.isStash(bot.level(), c)) continue;
            var state = bot.level().getBlockState(c);
            if (!state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE)) continue;
            // Only beside a single chest (three in a row don't join), to its left or right, facing
            // the same way: then they join up into a double chest.
            if (state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                    != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) continue;
            var face = state.getValue(net.minecraft.world.level.block.ChestBlock.FACING);
            for (var d : new net.minecraft.core.Direction[]{face.getClockWise(), face.getCounterClockWise()}) {
                next.add(c.relative(d));
                facing.put(c.relative(d), face);
            }
        }
        // No single chest to pair up with: anywhere free next to the bed.
        if (next.isEmpty()) for (BlockPos p : BlockPos.betweenClosed(home.offset(-3, -1, -3), home.offset(3, 1, 3))) next.add(p.immutable());
        for (BlockPos p : next) {
            if (!w.state(p).isAir() || !w.state(p.above()).isAir() || !w.solidFloor(p.below())) continue;
            if (p.closerThan(home, 1.5)) continue;
            boolean door = false;
            for (var d : net.minecraft.core.Direction.values()) {
                if (w.state(p.relative(d)).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) door = true;
            }
            if (door) continue;
            this.brain.log("storage's full: another chest at " + p.toShortString());
            var face = facing.get(p);
            // (We face the other way to place it facing this way.)
            return new net.joinedthegame.ai.task.BuildTask(List.of(net.joinedthegame.ai.task.BuildTask.Step.placeEssential(p,
                    s -> s.is(net.minecraft.world.item.Items.CHEST), face == null ? null : face.getOpposite())), "Putting down another chest");
        }
        return null;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        Containers.close(bot);
        if (this.task != null) this.task.stop(bot, this.brain);
        this.task = null;
    }

    @Override
    public String activity() {
        if (this.task != null) return this.task.activity();
        return switch (this.mode) {
            case STASH -> "Hiding valuables";
            case HOMEWARD -> "Heading home to put my stuff away";
            case NEW_CHEST -> "Making another chest";
            default -> "Storing items";
        };
    }
}
