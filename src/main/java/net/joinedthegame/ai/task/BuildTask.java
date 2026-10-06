package net.joinedthegame.ai.task;

import java.util.List;
import java.util.function.Predicate;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Works through a list of "dig this out" / "put that there" steps, the way a player builds:
 * walk close enough, dig or place, next. Used for huts, cave rooms, furnishing and the
 * secret chest. Bots aren't architects: a block that won't go in after a few tries is
 * skipped (poorly built is fine), unless the step is marked essential, like the bed.
 */
public final class BuildTask implements Task {
    public enum Kind { DIG, PLACE, USE, REPLACE }

    /** When a USE step counts as done (the grass is farmland now, the seeds are in). */
    public interface Check {
        boolean test(WorldView world, BlockPos pos);
    }

    /**
     * @param facing    for beds, doors and gates: which way it points (the bot faces this way
     *                  to place it). For USE steps, the face of the block to click.
     * @param essential the whole build fails without this step
     * @param done      USE steps only: has it worked yet?
     */
    public record Step(Kind kind, BlockPos pos, @Nullable Predicate<ItemStack> item, @Nullable Direction facing, boolean essential,
                       @Nullable Check done) {
        public static Step dig(BlockPos pos) {
            return new Step(Kind.DIG, pos, null, null, false, null);
        }

        /** Dig out something you can walk through (a crop, a flower): done only when it's air. */
        public static Step clearOut(BlockPos pos) {
            return new Step(Kind.DIG, pos, null, null, false, (w, p) -> w.state(p).isAir());
        }

        public static Step digEssential(BlockPos pos) {
            return new Step(Kind.DIG, pos, null, null, true, null);
        }

        public static Step place(BlockPos pos, Predicate<ItemStack> item) {
            return new Step(Kind.PLACE, pos, item, null, false, null);
        }

        /** A block that points somewhere (door, gate, stairs), placed while facing {@code facing}. */
        public static Step placeFacing(BlockPos pos, Predicate<ItemStack> item, Direction facing) {
            return new Step(Kind.PLACE, pos, item, facing, false, null);
        }

        public static Step placeEssential(BlockPos pos, Predicate<ItemStack> item, @Nullable Direction facing) {
            return new Step(Kind.PLACE, pos, item, facing, true, null);
        }

        /** Swap whatever is there (dirt, grass) for this (planks): dig it out, then place. */
        public static Step replace(BlockPos pos, Predicate<ItemStack> item) {
            return new Step(Kind.REPLACE, pos, item, null, false, null);
        }

        /** Use an item on the top of a block: hoe the grass, plant the seeds. */
        public static Step useOnTop(BlockPos pos, Predicate<ItemStack> item, Check done) {
            return new Step(Kind.USE, pos, item, Direction.UP, false, done);
        }

        /** Beds are two blocks long and the other half goes in front. */
        boolean twoLong() {
            return this.kind == Kind.PLACE && this.facing != null && this.item != null
                    && this.item.test(new ItemStack(net.minecraft.world.item.Items.BED.red()));
        }
    }

    private final List<Step> steps;
    private final String label;
    private int index;
    private int stepTicks;
    private int tries;
    private int ticks;

    public BuildTask(List<Step> steps, String label) {
        this.steps = steps;
        this.label = label;
        // Big builds get more time: about four seconds a step, at least six minutes.
        this.timeLimit = Math.max(20 * 60 * 6, steps.size() * 20 * 4);
    }

    private final int timeLimit;

    @Override
    public String activity() {
        return this.label;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (++this.ticks > this.timeLimit) return Status.FAILED;
        WorldView world = new WorldView(bot.level());
        // Skip steps that are already done.
        while (this.index < this.steps.size() && isDone(world, this.steps.get(this.index))) next();
        if (this.index >= this.steps.size()) return Status.DONE;

        Step step = this.steps.get(this.index);
        if (++this.stepTicks > 20 * 15) return giveUpOnStep(brain, step, "took too long");

        // Get within arm's reach (and out of the way, if we're standing where the block goes).
        // Standing where it goes (or, for a bed, where the other half goes)? Move first.
        boolean inTheWay = (step.kind() == Kind.PLACE || step.kind() == Kind.REPLACE) && (bot.getBoundingBox().intersects(new AABB(step.pos()))
                || step.facing() != null && bot.getBoundingBox().intersects(new AABB(step.pos().above()))
                || step.twoLong() && bot.getBoundingBox().intersects(new AABB(step.pos().relative(step.facing()))));
        if (!BlockBreaker.inReach(bot, step.pos()) || inTheWay) {
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) {
                BlockPos target = step.pos();
                double r = BlockBreaker.REACH - 0.8;
                BlockPos otherHalf = step.twoLong() ? target.relative(step.facing()) : target;
                nav.moveTo(bot, Vec3.atCenterOf(target), p -> {
                    if (p.equals(target) || p.above().equals(target) || p.equals(otherHalf)) return false;
                    Vec3 eye = Vec3.atBottomCenterOf(p).add(0, 1.62, 0);
                    return eye.distanceToSqr(Vec3.atCenterOf(target)) <= r * r;
                });
                if (nav.status() == Navigator.Status.FAILED) {
                    nav.stop(bot);
                    return giveUpOnStep(brain, step, "can't get there");
                }
            }
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);

        if (step.kind() == Kind.DIG) {
            boolean ok = step.essential() ? world.breakableOnPurpose(step.pos()) : world.breakable(step.pos());
            if (!ok) return giveUpOnStep(brain, step, "can't dig it");
            brain.breaker().start(step.pos());
            if (brain.breaker().tick(bot, brain.controls()) == BlockBreaker.Result.FAILED) return giveUpOnStep(brain, step, "dig failed");
            return Status.RUNNING;
        }

        if (step.kind() == Kind.REPLACE && !world.state(step.pos()).canBeReplaced()) {
            if (Inv.find(bot, step.item()) < 0) return giveUpOnStep(brain, step, "out of blocks");
            if (!world.breakable(step.pos())) return giveUpOnStep(brain, step, "can't dig it");
            brain.breaker().start(step.pos());
            if (brain.breaker().tick(bot, brain.controls()) == BlockBreaker.Result.FAILED) return giveUpOnStep(brain, step, "dig failed");
            return Status.RUNNING;
        }

        if (step.kind() == Kind.USE) {
            if (Inv.find(bot, step.item()) < 0) return giveUpOnStep(brain, step, "nothing to use");
            // Something growing on top (grass, a flower) has to go before you can hoe it.
            BlockPos above = step.pos().above();
            if (step.facing() == Direction.UP && !world.state(above).isAir() && world.state(above).canBeReplaced()) {
                brain.breaker().start(above);
                brain.breaker().tick(bot, brain.controls());
                return Status.RUNNING;
            }
            if (this.stepTicks % 5 != 0) return Status.RUNNING;
            Placer.useOn(bot, step.pos(), step.facing(), step.item());
            if (++this.tries > 6) return giveUpOnStep(brain, step, "didn't take");
            return Status.RUNNING;
        }

        // Place.
        if (Inv.find(bot, step.item()) < 0) {
            if (step.essential()) brain.log("build '" + this.label + "' needs an item it doesn't have, for " + step.pos().toShortString());
            return step.essential() ? Status.FAILED : giveUpOnStep(brain, step, "out of blocks");
        }
        if (step.facing() != null) {
            // Beds point the way you face when placing them.
            bot.setYRot(step.facing().toYRot());
            bot.setYHeadRot(step.facing().toYRot());
            brain.controls().face(step.facing().toYRot(), 50f);
        } else {
            brain.controls().lookAt(bot, Vec3.atCenterOf(step.pos()));
        }
        if (this.stepTicks % 4 != 0) return Status.RUNNING; // a moment to turn, like a player
        if (!Placer.place(bot, step.pos(), step.item()) && ++this.tries > 4) return giveUpOnStep(brain, step, "won't place");
        return Status.RUNNING;
    }

    public static boolean isDone(WorldView world, Step step) {
        if (step.kind() == Kind.DIG && step.done() != null) return step.done().test(world, step.pos());
        if (step.kind() == Kind.DIG) return world.passable(step.pos()) && !world.isWater(step.pos()) || world.state(step.pos()).isAir();
        if (step.kind() == Kind.USE) return step.done() != null && step.done().test(world, step.pos());
        if (step.kind() == Kind.REPLACE) {
            var state = world.state(step.pos());
            return !state.canBeReplaced() && step.item().test(new ItemStack(state.getBlock().asItem()));
        }
        return !world.state(step.pos()).canBeReplaced();
    }

    private Status giveUpOnStep(BotBrain brain, Step step, String why) {
        if (step.essential()) {
            brain.log("build '" + this.label + "' failed: " + why + " at " + step.pos().toShortString());
            return Status.FAILED;
        }
        next();
        return Status.RUNNING;
    }

    private void next() {
        this.index++;
        this.stepTicks = 0;
        this.tries = 0;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        brain.breaker().cancel(bot);
        brain.navigator().stop(bot);
    }
}
