package net.joinedthegame.ai.project;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.Pathfinder;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Enchanter;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.LeatherTask;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: enchanting. XP finally has a use.
 * <ol>
 *   <li>An enchanting table: 2 diamonds, 4 obsidian and a book (paper from sugar cane,
 *       leather from cows), set up by home somewhere with room round it for shelves.</li>
 *   <li>Bookshelves round it, a few each time (each is 3 books), up to the full 15.</li>
 *   <li>Enchanting: the most important unenchanted gear first, taking the best option, once
 *       there are the levels for it. Lapis gets mined when needed.</li>
 * </ol>
 * Done over several goes: it's a lot of leather.
 */
public final class EnchantProject implements Task {
    static final int SHELVES_WANTED = 15;
    /** Shelves to add in one go (each is 3 books: 3 leather, 9 sugar cane). */
    static final int SHELVES_PER_GO = 3;

    private @Nullable Task sub;
    private int steps;
    private int shelvesThisGo;
    private boolean noLeather;
    private boolean noCane;
    /** No more room (or nothing to make them with): enchant with what we've got. */
    private boolean shelvesStuck;
    private int lastEnchant = -100;
    /** A roll we didn't like, waiting for the grindstone (what it is, and what's on it). */
    private net.minecraft.world.item.@Nullable Item grindItem;
    private String grindEnchants = "";
    private int grinds;
    private int ticks;
    private String note = "Getting into enchanting";

    @Override
    public String activity() {
        return this.sub != null ? this.sub.activity() : this.note;
    }

    /** The table we use: remembered, and still there. */
    public static @Nullable BlockPos table(BotPlayer bot, BotBrain brain) {
        GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        GlobalPos known = brain.memory().nearest("enchanting_table", here, 256);
        if (known == null) return null;
        if (bot.level().isLoaded(known.pos()) && !bot.level().getBlockState(known.pos()).is(Blocks.ENCHANTING_TABLE)) {
            brain.memory().forget("enchanting_table", known);
            return null;
        }
        return known.pos();
    }

    /** Worth starting (or carrying on): no table yet, room for shelves, or gear to enchant. */
    public static boolean worthIt(BotPlayer bot, BotBrain brain) {
        if (Inv.pickaxeTier(bot) < 5) return false; // obsidian needs diamond
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return false;
        BlockPos table = table(bot, brain);
        if (table == null) return true;
        if (bot.level().isLoaded(table) && Enchanter.shelves(bot.level(), table) < SHELVES_WANTED) return true;
        return readyToEnchant(bot, brain, table, false);
    }

    /**
     * Worth spending levels: something to enchant, 30 levels, and the full 15 shelves (or as
     * many as we're going to manage). Cheap enchants with no shelves are a waste of XP.
     */
    static boolean readyToEnchant(BotPlayer bot, BotBrain brain, BlockPos table, boolean shelvesDone) {
        if (Enchanter.pick(bot) == null || bot.experienceLevel < 30) return false;
        int shelves = bot.level().isLoaded(table) ? Enchanter.shelves(bot.level(), table) : 0;
        return shelves >= SHELVES_WANTED || shelvesDone;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            Task done = this.sub;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (s == Status.FAILED) {
                if (done instanceof LeatherTask) this.noLeather = true;
                else if (done instanceof MineTask m && m.activity().contains("sugar cane")) this.noCane = true;
                else if (table(bot, brain) == null) return Status.FAILED; // can't get the table made
            }
        }
        this.ticks++;
        if (++this.steps > 80) return Status.DONE;

        BlockPos table = table(bot, brain);
        if (table == null) return makeTable(bot, brain);
        if (this.grindItem != null) return regrind(bot, brain, table);

        // Shelves first, a few at a time: enchanting without them wastes the levels.
        if (!this.noLeather && !this.noCane && !this.shelvesStuck
                && bot.level().isLoaded(table) && Enchanter.shelves(bot.level(), table) < SHELVES_WANTED) {
            Task t = shelf(bot, brain, table);
            if (t != null) {
                this.sub = t;
                return Status.RUNNING;
            }
            // Made our share of shelves for this go: carry on another time.
            if (!this.shelvesStuck && this.shelvesThisGo >= SHELVES_PER_GO && Inv.count(bot, Items.BOOKSHELF) == 0) return Status.DONE;
        }

        // Then enchant, if it's worth the levels.
        boolean shelvesDone = this.shelvesStuck || this.noLeather || this.noCane;
        if (readyToEnchant(bot, brain, table, shelvesDone)) {
            if (Inv.count(bot, Items.LAPIS_LAZULI) < 3) {
                this.note = "Getting lapis";
                this.sub = new MineTask(bot, Sources.LAPIS, 9, "Mining lapis");
                return Status.RUNNING;
            }
            return enchant(bot, brain, table);
        }
        return Status.DONE;
    }

    // --- the table ------------------------------------------------------------------------

    private Status makeTable(BotPlayer bot, BotBrain brain) {
        if (Inv.count(bot, Items.ENCHANTING_TABLE) > 0) return placeTable(bot, brain);
        this.note = "Making an enchanting table";
        boolean[] failed = {false};
        // Diamonds (2), obsidian (4), a book.
        if (Inv.count(bot, Items.DIAMOND) < 2) {
            this.sub = Tasks.toward(bot, Tasks.Need.of("diamonds", s -> s.is(Items.DIAMOND), 2, b -> Items.DIAMOND), failed);
            return failed[0] ? Status.FAILED : Status.RUNNING;
        }
        if (Inv.count(bot, Items.OBSIDIAN) < 4) {
            this.sub = new ObsidianTask(4);
            return Status.RUNNING;
        }
        if (Inv.count(bot, Items.BOOK) < 1) {
            Task t = books(bot, 1);
            if (t == null) return Status.FAILED;
            this.sub = t;
            return Status.RUNNING;
        }
        this.sub = Tasks.toward(bot, Tasks.Need.of("an enchanting table", s -> s.is(Items.ENCHANTING_TABLE), 1, b -> Items.ENCHANTING_TABLE), failed);
        return failed[0] ? Status.FAILED : Status.RUNNING;
    }

    /** Next step toward {@code count} books: leather from cows, paper from sugar cane. */
    private @Nullable Task books(BotPlayer bot, int count) {
        int need = count - Inv.count(bot, Items.BOOK);
        if (need <= 0) return null;
        if (Inv.count(bot, Items.LEATHER) < need) {
            if (this.noLeather) return null;
            return new LeatherTask(bot, need - Inv.count(bot, Items.LEATHER));
        }
        int paper = need * 3;
        if (Inv.count(bot, Items.PAPER) < paper && Inv.count(bot, Items.SUGAR_CANE) < paper - Inv.count(bot, Items.PAPER)) {
            if (this.noCane) return null;
            int cane = paper - Inv.count(bot, Items.PAPER) - Inv.count(bot, Items.SUGAR_CANE);
            return new MineTask(bot, Sources.SUGAR_CANE, cane, "Cutting sugar cane");
        }
        boolean[] failed = {false};
        Task t = Tasks.toward(bot, Tasks.Need.of("books", s -> s.is(Items.BOOK), count, b -> Items.BOOK), failed);
        return failed[0] ? null : t;
    }

    /**
     * Put the table down near home, somewhere the whole setup fits: the table, the ring of
     * air round it, and all 15 shelf spots, with a way in kept clear. If there's no open space
     * that big (a home dug into a hill), dig a room out for it first.
     */
    private Status placeTable(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return Status.FAILED;
        if (home.pos().distSqr(bot.blockPosition()) > 24 * 24) {
            this.sub = new net.joinedthegame.ai.task.TravelTask(home.pos(), 6, "Heading home with the enchanting table");
            return Status.RUNNING;
        }
        Setup setup = plan(bot.level(), home.pos());
        if (setup == null) {
            brain.log("enchanting: no room by home (" + home.pos().toShortString() + ") for an enchanting setup");
            return Status.FAILED;
        }
        brain.log("enchanting: table going at " + setup.table().toShortString() + " (way in from the " + setup.entrance()
                + (setup.dig().isEmpty() ? ")" : ", digging out " + setup.dig().size() + " blocks for the room)"));
        this.note = "Setting up the enchanting table";
        List<BuildTask.Step> steps = new ArrayList<>();
        for (BlockPos p : setup.dig()) steps.add(BuildTask.Step.digEssential(p));
        steps.add(BuildTask.Step.placeEssential(setup.table(), s -> s.is(Items.ENCHANTING_TABLE), null));
        this.sub = Tasks.sequence(new BuildTask(steps, setup.dig().isEmpty() ? "Setting up the enchanting table" : "Digging out an enchanting room"),
                new Remember(setup.table()));
        return Status.RUNNING;
    }

    /** Marks the table placed (in memory and the diary). */
    private record Remember(BlockPos spot) implements Task {
        @Override
        public String activity() {
            return "Setting up the enchanting table";
        }

        @Override
        public Status tick(BotPlayer bot, BotBrain brain) {
            if (!bot.level().getBlockState(this.spot).is(Blocks.ENCHANTING_TABLE)) return Status.FAILED;
            brain.rememberPlace(bot, "enchanting_table", this.spot);
            brain.remember(BotMemory.EventType.BUILT, "an enchanting table", GlobalPos.of(bot.level().dimension(), this.spot), 0.8f);
            return Status.DONE;
        }

        @Override
        public void stop(BotPlayer bot, BotBrain brain) {}
    }

    /** Where the table goes, which side the way in is, and anything to dig out first. */
    record Setup(BlockPos table, Direction entrance, List<BlockPos> dig) {}

    /** The way in: the side facing home. */
    static Direction entranceFor(BlockPos table, BlockPos home) {
        int dx = home.getX() - table.getX();
        int dz = home.getZ() - table.getZ();
        if (dx == 0 && dz == 0) return Direction.NORTH;
        return Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /** The three shelf columns in the middle of the entrance side stay empty, so we can get in. */
    static boolean reserved(BlockPos offset, Direction entrance) {
        int along = entrance.getAxis() == Direction.Axis.X ? offset.getX() : offset.getZ();
        int across = entrance.getAxis() == Direction.Axis.X ? offset.getZ() : offset.getX();
        int edge = entrance.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 2 : -2;
        return along == edge && Math.abs(across) <= 1;
    }

    /**
     * Best place for the whole setup within 10 of home. The 5x5, 2-high room round the table
     * must already be clear, or diggable (natural stone and dirt, never anyone's walls, chests
     * or beds), with floor under all of it, and a way in from the side facing home. Least
     * digging wins, then closest to home.
     */
    static @Nullable Setup plan(ServerLevel level, BlockPos home) {
        WorldView w = new WorldView(level);
        Setup best = null;
        int bestScore = Integer.MAX_VALUE;
        for (BlockPos c : BlockPos.betweenClosed(home.offset(-10, -2, -10), home.offset(10, 2, 10))) {
            // Never with the bed inside the room.
            if (Math.abs(c.getX() - home.getX()) <= 3 && Math.abs(c.getZ() - home.getZ()) <= 3 && Math.abs(c.getY() - home.getY()) <= 1) continue;
            List<BlockPos> dig = new ArrayList<>();
            boolean ok = true;
            for (int dx = -2; dx <= 2 && ok; dx++) {
                for (int dz = -2; dz <= 2 && ok; dz++) {
                    if (!w.solidFloor(c.offset(dx, -1, dz))) {
                        ok = false;
                        break;
                    }
                    for (int dy = 0; dy <= 1; dy++) {
                        BlockPos p = c.offset(dx, dy, dz);
                        var s = w.state(p);
                        if (s.isAir() || s.is(Blocks.BOOKSHELF) && (Math.abs(dx) == 2 || Math.abs(dz) == 2)) continue;
                        if (!w.breakable(p) || Pathfinder.builtBlock(s) || s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                                || !keepsCeiling(level, w, p)) {
                            ok = false;
                            break;
                        }
                        dig.add(p.immutable());
                    }
                }
            }
            if (!ok || w.lavaNear(c.offset(-2, -1, -2), c.offset(2, 2, 2), 2)) continue;
            Direction entrance = entranceFor(c, home);
            // The way in has to lead somewhere: clear (or diggable) just outside it.
            BlockPos outside = c.relative(entrance, 3);
            boolean low = w.state(outside).isAir() || w.breakable(outside) && !Pathfinder.builtBlock(w.state(outside));
            boolean high = w.state(outside.above()).isAir() || w.breakable(outside.above()) && !Pathfinder.builtBlock(w.state(outside.above()));
            if (!low || !high || !keepsCeiling(level, w, outside) || !keepsCeiling(level, w, outside.above())) continue;
            if (!w.state(outside).isAir()) dig.add(outside.immutable());
            if (!w.state(outside.above()).isAir()) dig.add(outside.above().immutable());
            int score = dig.size() * 10 + (int) Math.sqrt(c.distSqr(home));
            if (score < bestScore) {
                bestScore = score;
                // Dig from the way in inwards (top before bottom), so each block is in reach of the last hole.
                BlockPos from = outside;
                dig.sort(java.util.Comparator.comparingDouble((BlockPos p) -> p.distSqr(from)).thenComparing(p -> -p.getY()));
                best = new Setup(c.immutable(), entrance, dig);
            }
        }
        return best;
    }

    /**
     * Digging this out won't break into somewhere else: not the floor of a room above (air
     * that can't see the sky), and nothing standing on it (a bed, a chest, a furnace...).
     */
    private static boolean keepsCeiling(ServerLevel level, WorldView w, BlockPos p) {
        if (w.state(p).isAir()) return true;
        BlockPos up = p.above();
        var above = w.state(up);
        if (above.isAir() && !level.canSeeSky(up)) return false;
        return !(above.hasBlockEntity() || above.is(net.minecraft.tags.BlockTags.BEDS) || above.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                || above.is(Blocks.CRAFTING_TABLE) || above.is(Blocks.TORCH));
    }

    // --- shelves ------------------------------------------------------------------------

    /** Next step toward one more bookshelf round the table, or null if we can't. */
    private @Nullable Task shelf(BotPlayer bot, BotBrain brain, BlockPos table) {
        GlobalPos home = brain.record().home();
        BlockPos spot = shelfSpot(bot.level(), table, home == null ? table : home.pos());
        if (spot == null) {
            this.shelvesStuck = true;
            return null;
        }
        if (Inv.count(bot, Items.BOOKSHELF) == 0) {
            if (Inv.count(bot, Items.BOOK) < 3) {
                this.note = "Making books for bookshelves";
                return books(bot, 3);
            }
            if (this.shelvesThisGo >= SHELVES_PER_GO) return null; // that's enough leather for one go
            this.shelvesThisGo++;
            boolean[] failed = {false};
            Task t = Tasks.toward(bot, Tasks.Need.of("a bookshelf", s -> s.is(Items.BOOKSHELF), 1, b -> Items.BOOKSHELF), failed);
            return failed[0] ? null : t;
        }
        this.note = "Putting up a bookshelf";
        return new BuildTask(List.of(BuildTask.Step.placeEssential(spot, s -> s.is(Items.BOOKSHELF), null)), "Putting up a bookshelf");
    }

    /** A free shelf spot round the table: bottom row first, nothing in between, the way in left clear. */
    static @Nullable BlockPos shelfSpot(ServerLevel level, BlockPos table, BlockPos home) {
        WorldView w = new WorldView(level);
        Direction entrance = entranceFor(table, home);
        List<BlockPos> offsets = new ArrayList<>(EnchantingTableBlock.BOOKSHELF_OFFSETS);
        offsets.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        for (BlockPos offset : offsets) {
            if (reserved(offset, entrance)) continue;
            BlockPos shelf = table.offset(offset);
            BlockPos between = table.offset(offset.getX() / 2, offset.getY(), offset.getZ() / 2);
            if (!w.state(between).isAir() || !w.state(shelf).canBeReplaced()) continue;
            if (!w.solidFloor(shelf.below()) && !w.state(shelf.below()).is(Blocks.BOOKSHELF)) continue;
            // Never wall up a doorway.
            boolean door = false;
            for (Direction d : Direction.values()) {
                if (w.state(shelf.relative(d)).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) door = true;
            }
            if (!door) return shelf.immutable();
        }
        return null;
    }

    // --- enchanting -----------------------------------------------------------------------

    static final int MAX_GRINDS = 3;

    /**
     * A roll we didn't like: take it to the grindstone (making one and putting it by the
     * enchanting room if we've none), grind it clean, get some of the levels back.
     */
    private Status regrind(BotPlayer bot, BotBrain brain, BlockPos table) {
        Enchanter.Gear gear = Enchanter.find(bot, this.grindItem, this.grindEnchants);
        if (gear == null) {
            this.grindItem = null; // gone (dropped it, swapped it): never mind
            return Status.RUNNING;
        }
        this.note = "Grinding off an enchantment I didn't want";
        BlockPos stone = net.joinedthegame.ai.Senses.findBlock(bot, s -> s.is(Blocks.GRINDSTONE), 16, 6, false, brain::isBlacklisted);
        if (stone == null) {
            if (Inv.count(bot, Items.GRINDSTONE) == 0) {
                boolean[] failed = {false};
                Task t = Tasks.toward(bot, Tasks.Need.of("a grindstone", s -> s.is(Items.GRINDSTONE), 1, b -> Items.GRINDSTONE), failed);
                if (failed[0] || t == null) {
                    brain.log("enchanting: can't make a grindstone, I'll live with it");
                    this.grindItem = null;
                    return Status.RUNNING;
                }
                this.sub = t;
                return Status.RUNNING;
            }
            GlobalPos home = brain.record().home();
            BlockPos spot = grindstoneSpot(bot.level(), table, home == null ? table : home.pos());
            if (spot == null) {
                this.grindItem = null;
                return Status.RUNNING;
            }
            this.sub = new BuildTask(List.of(BuildTask.Step.placeEssential(spot, s -> s.is(Items.GRINDSTONE), null)), "Putting down a grindstone");
            return Status.RUNNING;
        }
        if (!BlockBreaker.canUse(bot, stone)) {
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToUse(bot, stone);
                if (nav.status() == Navigator.Status.FAILED) {
                    brain.blacklist(stone);
                    this.grindItem = null;
                }
            }
            this.steps--;
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);
        brain.controls().lookAt(bot, Vec3.atCenterOf(stone));
        String what = net.joinedthegame.ai.Planner.name(this.grindItem);
        if (Enchanter.grind(bot, stone, gear)) {
            this.grinds++;
            brain.log("ground the " + this.grindEnchants + " off my " + what);
        }
        this.grindItem = null;
        return Status.RUNNING;
    }

    /** A spot for the grindstone beside the enchanting room: not in it, not in the way in. */
    static @Nullable BlockPos grindstoneSpot(ServerLevel level, BlockPos table, BlockPos home) {
        WorldView w = new WorldView(level);
        Direction entrance = entranceFor(table, home);
        BlockPos passage = table.relative(entrance, 3);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(table.offset(-5, -1, -5), table.offset(5, 1, 5))) {
            if (Math.abs(p.getX() - table.getX()) <= 2 && Math.abs(p.getZ() - table.getZ()) <= 2) continue; // the room itself
            if (p.closerThan(passage, 1.9)) continue; // the way in
            if (!w.state(p).isAir() || !w.state(p.above()).isAir() || !w.solidFloor(p.below())) continue;
            boolean ns = w.state(p.north()).isAir() && w.state(p.south()).isAir();
            boolean ew = w.state(p.east()).isAir() && w.state(p.west()).isAir();
            int open = 0;
            boolean door = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (w.state(p.relative(d)).isAir()) open++;
                if (w.state(p.relative(d)).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) door = true;
            }
            if (door || open == 0 || open == 2 && (ns || ew)) continue;
            double d = p.distSqr(passage);
            if (d < bestDist) {
                bestDist = d;
                best = p.immutable();
            }
        }
        return best;
    }

    private Status enchant(BotPlayer bot, BotBrain brain, BlockPos table) {
        this.note = "Enchanting";
        if (!BlockBreaker.canUse(bot, table)) {
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) {
                if (table.distSqr(bot.blockPosition()) > 40 * 40) {
                    this.sub = new net.joinedthegame.ai.task.TravelTask(table, 3, "Heading to my enchanting table");
                    return Status.RUNNING;
                }
                nav.moveToUse(bot, table);
                if (nav.status() == Navigator.Status.FAILED) {
                    brain.log("enchanting: can't get to my table");
                    return Status.FAILED;
                }
            }
            this.steps--; // walking there isn't a step
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);
        brain.controls().lookAt(bot, Vec3.atCenterOf(table));
        this.steps--;
        if (this.ticks - this.lastEnchant < 40) return Status.RUNNING; // a moment between each
        this.lastEnchant = this.ticks;
        this.steps++;
        Enchanter.Gear gear = Enchanter.pick(bot);
        if (gear == null) return Status.RUNNING;
        String what = net.joinedthegame.ai.Planner.name(gear.get(bot).getItem());
        Enchanter.Result r = Enchanter.enchant(bot, table, gear, false);
        switch (r) {
            case ENCHANTED -> {
                net.minecraft.world.item.ItemStack done = gear.get(bot);
                String names = net.joinedthegame.ai.Enchants.describe(done);
                if (!net.joinedthegame.ai.Enchants.shouldGrind(bot, done, brain.personality(), this.grinds, MAX_GRINDS)) {
                    brain.log("enchanted my " + what + ": " + names);
                    brain.remember(BotMemory.EventType.MADE, "enchanted " + what, null, 0.6f);
                } else {
                    // Not what we were after: off it comes, and try again when the levels are back.
                    brain.log("enchanted my " + what + ": " + names + ". Not what I wanted, grindstone time");
                    this.grindItem = done.getItem();
                    this.grindEnchants = names;
                    return Status.RUNNING;
                }
                Inv.equipBestArmor(bot);
            }
            case TOO_EXPENSIVE -> {
                brain.log("enchanting: the good option for my " + what + " is more levels than I have");
                return Status.DONE;
            }
            case FAILED -> {
                brain.log("enchanting: couldn't enchant my " + what);
                return Status.DONE;
            }
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
    }
}
