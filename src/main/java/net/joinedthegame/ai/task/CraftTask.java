package net.joinedthegame.ai.task;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Planner;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Craft something. Small recipes are made in the inventory grid; 3x3 ones need a crafting
 * table, so the bot walks to one nearby or puts its own down.
 */
public final class CraftTask implements Task {
    private final Planner.Craft step;
    private @Nullable BlockPos table;
    private int ticks;
    private int useTicks;
    /** We put this table down ourselves (and, away from home, take it back after). */
    private boolean placedOwn;
    private boolean crafted;
    private @Nullable CollectItems collect;

    public CraftTask(Planner.Craft step) {
        this.step = step;
    }

    @Override
    public String activity() {
        return this.step.label();
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (++this.ticks > 20 * 60) return this.crafted ? Status.DONE : Status.FAILED;
        if (this.crafted) return takeTableBack(bot, brain);
        if (!this.step.option().needsTable()) return craftAfterDelay(bot, brain, null);

        if (this.table == null || !bot.level().getBlockState(this.table).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
            this.table = Senses.findBlock(bot, s -> s.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE), 24, 8, false, brain::isBlacklisted);
            if (this.table == null) this.table = remembered(bot, brain, "crafting_table", net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
            // Carrying one, and that one's a walk away or round the rock: put ours down here. (Madison,
            // her pickaxe gone, tunnelled toward a table thirteen blocks off with one in her bag, got
            // stuck, and went wandering: a table goes down in a second and comes back up after.)
            if (this.table != null && Inv.count(bot, Items.CRAFTING_TABLE) > 0 && !BlockBreaker.canUse(bot, this.table)
                    && (this.table.distSqr(bot.blockPosition()) > 6 * 6 || !BlockBreaker.canSee(bot, bot.getEyePosition(), this.table))) {
                this.table = null;
            }
            if (this.table == null) {
                if (Inv.count(bot, Items.CRAFTING_TABLE) == 0) {
                    // No table we can reach: make one from planks, like anyone would.
                    Planner.Step make = new Planner(bot, false).next(Items.CRAFTING_TABLE, 1);
                    if (!(make instanceof Planner.Craft craft) || !craftNow(bot, craft)) return Status.FAILED;
                }
                if (brain.gettingOutOfWater(bot)) return Status.RUNNING; // not setting up in a pool
                BlockPos spot = findPlacementSpot(bot);
                if (spot == null) {
                    // Hemmed in: dig a nook in the wall and set it there.
                    if (this.nook == null) this.nook = findNook(bot);
                    if (this.nook != null && !bot.level().getBlockState(this.nook).isAir()) {
                        if (!this.nook.equals(brain.breaker().target())) brain.breaker().start(this.nook);
                        if (brain.breaker().tick(bot, brain.controls()) == BlockBreaker.Result.FAILED) this.nook = null;
                        return Status.RUNNING;
                    }
                    spot = this.nook;
                    this.nook = null;
                }
                if (spot == null || !Placer.place(bot, spot, s -> s.is(Items.CRAFTING_TABLE))) {
                    brain.log("craft: couldn't place table at " + spot);
                    return Status.FAILED;
                }
                this.table = spot;
                this.placedOwn = true;
            }
        }
        if (!BlockBreaker.canUse(bot, this.table)) {
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToUse(bot, this.table);
                if (nav.status() == Navigator.Status.FAILED) {
                    nav.stop(bot);
                    // Can't get there: forget that table, and put our own down if we have one.
                    brain.blacklist(this.table);
                    if (Inv.count(bot, Items.CRAFTING_TABLE) > 0) this.table = null;
                    else return Status.FAILED;
                }
            }
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);
        Status status = craftAfterDelay(bot, brain, this.table);
        // Our own table, put down out in the field: pick it back up, like anyone would. Left
        // behind, the next broken pickaxe down a mine finds the bot with no table and no wood.
        if (status == Status.DONE && this.placedOwn && !nearHome(bot, brain, this.table)) {
            // The scatterbrained ones sometimes just walk off without it.
            var p = brain.personality();
            double forgetful = (1 - p.diligent()) * 0.5 + (1 - p.hoarder()) * 0.2;
            if (bot.getRandom().nextDouble() < forgetful * 0.4) {
                brain.log("walked off and left my crafting table behind at " + this.table.toShortString());
                brain.rememberPlace(bot, "crafting_table", this.table);
                return Status.DONE;
            }
            this.crafted = true;
            return Status.RUNNING;
        }
        if (status == Status.DONE) brain.rememberPlace(bot, "crafting_table", this.table);
        return status;
    }

    private static boolean nearHome(BotPlayer bot, BotBrain brain, BlockPos pos) {
        var home = brain.record().home();
        return home != null && home.dimension() == bot.level().dimension() && home.pos().closerThan(pos, 24);
    }

    private Status takeTableBack(BotPlayer bot, BotBrain brain) {
        if (this.collect != null) {
            return this.collect.tick(bot, brain) == Status.RUNNING ? Status.RUNNING : Status.DONE;
        }
        BlockPos at = this.table;
        if (at == null) return Status.DONE;
        if (!bot.level().getBlockState(at).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) || !BlockBreaker.inReach(bot, at)) {
            this.collect = new CollectItems(at, 3, 40);
            return Status.RUNNING;
        }
        brain.breaker().start(at);
        BlockBreaker.Result result = brain.breaker().tick(bot, brain.controls());
        if (result == BlockBreaker.Result.FAILED) return Status.DONE; // leave it, then
        if (result == BlockBreaker.Result.DONE) this.collect = new CollectItems(at, 3, 40);
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.crafted && this.collect == null) brain.breaker().cancel(bot);
    }

    private Status craftAfterDelay(BotPlayer bot, BotBrain brain, @Nullable BlockPos at) {
        if (at != null) brain.controls().lookAt(bot, Vec3.atCenterOf(at));
        if (++this.useTicks == 1 && at != null) bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        if (this.useTicks < 12) return Status.RUNNING;
        for (int i = 0; i < this.step.times(); i++) {
            if (!craftOnce(bot)) {
                if (i == 0) {
                    // Usually not a bad recipe: the step was planned before something else came
                    // first (building, a chest, a death) and what it needed is gone. Re-plan.
                    boolean missing = false;
                    for (Item item : this.step.grid()) {
                        if (item != null && Inv.count(bot, item) < count(this.step.grid(), item)) missing = true;
                    }
                    brain.log(this.noRoom ? "craft: '" + this.step.label() + "': no room in my bag to make that"
                            : missing ? "craft: '" + this.step.label() + "': haven't got what it needs any more (used or put away since I planned it), rethinking"
                            : "craft: recipe didn't match the chosen items " + this.step.grid());
                }
                return i > 0 ? Status.DONE : Status.FAILED;
            }
        }
        return Status.DONE;
    }

    private boolean noRoom;

    /** Somewhere for the result: an empty slot, or a stack of the same it can go on top of. */
    private boolean roomFor(BotPlayer bot) {
        var inv = bot.getInventory();
        return inv.getFreeSlot() >= 0 || inv.getSlotWithRemainingSpace(this.step.option().result()) >= 0;
    }

    private boolean craftOnce(BotPlayer bot) {
        // Room for what we're making first: a full bag dropped Redlantern's new diamond boots on the
        // floor (and he went off to mine diamonds for another pair). Make room, or don't craft.
        if (!roomFor(bot) && !net.joinedthegame.ai.Junk.makeRoom(bot)) {
            this.noRoom = true;
            return false;
        }
        List<@Nullable Item> grid = this.step.grid();
        for (Item item : grid) {
            if (item != null && Inv.count(bot, item) < count(grid, item)) return false;
        }
        List<ItemStack> inputs = new ArrayList<>(grid.size());
        for (Item item : grid) inputs.add(item == null ? ItemStack.EMPTY : new ItemStack(item));
        int width = this.step.option().width();
        int height = this.step.option().height();
        while (inputs.size() < width * height) inputs.add(ItemStack.EMPTY);
        CraftingInput input = CraftingInput.of(width, height, inputs);
        CraftingRecipe recipe = this.step.option().holder().value();
        if (!recipe.matches(input, bot.level())) return false;
        ItemStack result = recipe.assemble(input);
        if (result.isEmpty()) return false;
        var remaining = recipe.getRemainingItems(input);

        for (Item item : grid) if (item != null) Inv.remove(bot, s -> s.is(item), 1);
        result.onCraftedBy(bot, result.getCount());
        give(bot, result);
        for (ItemStack rest : remaining) if (!rest.isEmpty()) give(bot, rest.copy());
        bot.getInventory().setChanged();
        return true;
    }

    /**
     * A table or furnace we've used before, not too far off, that's still there. Forgets ones
     * that have gone.
     */
    static @Nullable BlockPos remembered(BotPlayer bot, BotBrain brain, String kind, net.minecraft.world.level.block.Block block) {
        var here = net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        var pos = brain.memory().nearest(kind, here, 64);
        if (pos == null || brain.isBlacklisted(pos.pos()) || !bot.level().isLoaded(pos.pos())) return null;
        if (!bot.level().getBlockState(pos.pos()).is(block)) {
            brain.memory().forget(kind, pos);
            return null;
        }
        // Thirty blocks up a staircase isn't "nearby": a player down a mine puts their own table
        // or furnace down rather than climb all the way back for one. Only worth the walk if it's
        // close, or we've nothing to put down ourselves.
        BlockPos p = pos.pos();
        double flat = Math.sqrt(Math.pow(p.getX() - bot.getX(), 2) + Math.pow(p.getZ() - bot.getZ(), 2));
        boolean far = flat > 24 || Math.abs(p.getY() - bot.getBlockY()) > 8;
        if (far && canPutOwnDown(bot, block)) return null;
        return p;
    }

    /** Has (or can make on the spot) one of these to put down. */
    private static boolean canPutOwnDown(BotPlayer bot, net.minecraft.world.level.block.Block block) {
        if (Inv.count(bot, block.asItem()) > 0) return true;
        if (block == net.minecraft.world.level.block.Blocks.CRAFTING_TABLE) {
            return Inv.count(bot, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) >= 4 || Inv.count(bot, s -> s.is(net.minecraft.tags.ItemTags.LOGS)) > 0;
        }
        if (block == net.minecraft.world.level.block.Blocks.FURNACE) {
            return Inv.count(bot, s -> s.is(net.minecraft.tags.ItemTags.STONE_CRAFTING_MATERIALS)) >= 8;
        }
        return false;
    }

    /** Craft immediately (inventory-grid recipes only). Returns true if anything was made. */
    public static boolean craftNow(BotPlayer bot, Planner.Craft step) {
        if (step.option().needsTable()) return false;
        CraftTask task = new CraftTask(step);
        boolean any = false;
        for (int i = 0; i < step.times(); i++) {
            if (!task.craftOnce(bot)) break;
            any = true;
        }
        return any;
    }

    private static int count(List<@Nullable Item> grid, Item item) {
        int n = 0;
        for (Item i : grid) if (i == item) n++;
        return n;
    }

    public static void give(BotPlayer bot, ItemStack stack) {
        if (!bot.getInventory().add(stack) && !stack.isEmpty()) {
            // No room after all: down at our feet as an ordinary drop (not "thrown away": that one
            // can't be picked up for minutes and gets ignored), to grab as soon as there's room.
            var item = new net.minecraft.world.entity.item.ItemEntity(bot.level(), bot.getX(), bot.getY(), bot.getZ(), stack);
            item.setPickUpDelay(10);
            bot.level().addFreshEntity(item);
        }
    }

    /**
     * An open spot near the bot with solid ground, for putting down a table or furnace. Skips
     * any cell the bot's own body pokes into (players are 0.6 wide, so a bot standing off-centre
     * overlaps its neighbour), since the game refuses to place a block there.
     */
    private @Nullable BlockPos nook;

    public static @Nullable BlockPos findPlacementSpot(BotPlayer bot) {
        WorldView world = new WorldView(bot.level());
        BlockPos feet = Navigator.feet(bot);
        net.minecraft.world.phys.AABB body = bot.getBoundingBox().inflate(0.05);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos spot = feet.offset(dx, dy, dz);
                    if (!world.state(spot).canBeReplaced() || !world.state(spot).getFluidState().isEmpty()) continue;
                    if (!world.solidFloor(spot.below())) continue;
                    // Never on top of a chest (or anything else with an inside): a block on a
                    // chest's lid stops it opening.
                    if (world.state(spot.below()).hasBlockEntity()) continue;
                    if (body.intersects(new net.minecraft.world.phys.AABB(spot))) continue;
                    if (someoneIn(bot, spot)) continue; // the game won't put a block down on anybody
                    double dist = bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(spot));
                    if (dist > 4.0 * 4.0) continue;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = spot;
                    }
                }
            }
        }
        if (best != null) return best;
        // Cramped (a burrow, a 1-wide tunnel): stick it on any wall in reach instead. Blocks
        // don't need floor under them, just something next to them to click on.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos spot = feet.offset(dx, dy, dz);
                    if (!world.state(spot).canBeReplaced() || !world.state(spot).getFluidState().isEmpty()) continue;
                    if (body.intersects(new net.minecraft.world.phys.AABB(spot))) continue;
                    if (someoneIn(bot, spot)) continue; // the game won't put a block down on anybody
                    // Don't plug the way out at our own head height.
                    if (spot.equals(feet.above()) || spot.equals(feet.above(2))) continue;
                    if (Placer.findSupport(bot, spot) == null) continue;
                    double dist = bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(spot));
                    if (dist > 4.0 * 4.0) continue;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = spot;
                    }
                }
            }
        }
        return best;
    }

    private static boolean someoneIn(BotPlayer bot, BlockPos spot) {
        return !bot.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(spot),
                e -> e != bot && e.isAlive() && !e.isSpectator()).isEmpty();
    }

    /**
     * Nowhere open to put it (a one-wide staircase: stone all round but the way we came): a wall
     * block beside us, at our feet or chest, that's just rock or dirt, to dig out and set the
     * table in, like anyone would. Not the floor, not overhead, not anything worth keeping.
     */
    public static @Nullable BlockPos findNook(BotPlayer bot) {
        WorldView world = new WorldView(bot.level());
        BlockPos feet = Navigator.feet(bot);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos spot = feet.relative(d).above(dy);
                var state = world.state(spot);
                if (state.isAir() || state.hasBlockEntity() || !world.breakable(spot)) continue;
                if (!(state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || state.is(net.minecraft.tags.BlockTags.DIRT)
                        || state.is(net.minecraft.world.level.block.Blocks.COBBLESTONE) || state.is(net.minecraft.tags.BlockTags.SAND))) continue;
                double dist = bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(spot));
                if (dist < bestDist) {
                    bestDist = dist;
                    best = spot.immutable();
                }
            }
        }
        return best;
    }
}
