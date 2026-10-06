package net.joinedthegame.ai.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.Homes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The lazy player's base: a hole. Next to a hillside the bot tunnels two blocks straight
 * in; on flat ground it digs down two blocks and carves a side pocket. Either way it ends
 * up with a 1x2 nook just big enough for a bed, puts its bed in, and calls it home.
 */
public final class BurrowTask implements Task {
    private enum Stage { PICK, DESCEND, CARVE, PLACE }

    private Stage stage = Stage.PICK;
    private Direction facing = Direction.NORTH;
    /** Where the bot stands while carving and placing. */
    private BlockPos standAt = BlockPos.ZERO;
    private int descendLeft;
    private final List<BlockPos> toCarve = new ArrayList<>();
    private int ticks;
    private int attempts;
    private String label = "Digging a home";
    private int edgeTicks;

    @Override
    public String activity() {
        return this.label;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (++this.ticks > 20 * 120) return Status.FAILED;
        if (Inv.count(bot, s -> s.is(ItemTags.BEDS)) == 0) return Status.FAILED;
        WorldView world = new WorldView(bot.level());
        return switch (this.stage) {
            case PICK -> pick(bot, brain, world);
            case DESCEND -> descend(bot, brain, world);
            case CARVE -> carve(bot, brain, world);
            case PLACE -> place(bot, brain, world);
        };
    }

    private Status pick(BotPlayer bot, BotBrain brain, WorldView world) {
        if (brain.navigator().isMoving()) return Status.RUNNING;
        if (++this.attempts > 6) return Status.FAILED;
        if (!bot.onGround() && !bot.isInWater()) return Status.RUNNING;
        BlockPos feet = Navigator.feet(bot);
        List<Direction> dirs = new ArrayList<>(Direction.Plane.HORIZONTAL.stream().toList());
        Collections.shuffle(dirs, new java.util.Random(bot.getRandom().nextLong()));

        // A hillside right next to us: tunnel straight in.
        for (Direction d : dirs) {
            BlockPos a = feet.relative(d);
            BlockPos b = feet.relative(d, 2);
            if (world.passable(a) || world.passable(a.above())) continue;
            if (!roomFits(world, a, b)) continue;
            start(bot, feet, d, 0, a, b);
            this.label = "Digging into the hill";
            return Status.RUNNING;
        }

        // Flat ground (or already underground): dig down, then sideways.
        boolean underground = !bot.level().canSeeSky(feet);
        int depth = underground ? 0 : 2;
        for (int i = 1; i <= depth; i++) {
            if (!world.breakable(feet.below(i)) || world.isWater(feet.below(i))) {
                depth = -1;
                break;
            }
        }
        if (depth >= 0 && world.solidFloor(feet.below(depth + 1))) {
            BlockPos bottom = feet.below(depth);
            for (Direction d : dirs) {
                BlockPos a = bottom.relative(d);
                BlockPos b = bottom.relative(d, 2);
                if (!roomFits(world, a, b)) continue;
                start(bot, feet, d, depth, a, b);
                this.label = underground ? "Making a home down here" : "Digging a home";
                return Status.RUNNING;
            }
        }

        // Nowhere good here: wander a few blocks and try again.
        double angle = bot.getRandom().nextDouble() * Math.PI * 2;
        brain.navigator().moveNear(bot, bot.position().add(Math.cos(angle) * 8, 0, Math.sin(angle) * 8), 2);
        return Status.RUNNING;
    }

    /** Can a bed go in cells a (foot) and b (head)? Floor under both, nothing wet or precious in the way. */
    private static boolean roomFits(WorldView world, BlockPos a, BlockPos b) {
        for (BlockPos cell : List.of(a, a.above(), b, b.above())) {
            if (!world.clearOrBreakable(cell) || world.isWater(cell) || world.isLava(cell)) return false;
        }
        // Somewhere to sleep, so no lava anywhere near it (behind the wall, under the floor).
        if (world.lavaNear(a.below(), b.above(), 3)) return false;
        return world.solidFloor(a.below()) && world.solidFloor(b.below());
    }

    private void start(BotPlayer bot, BlockPos feet, Direction d, int depth, BlockPos foot, BlockPos head) {
        this.facing = d;
        this.standAt = feet.below(depth);
        this.descendLeft = depth;
        this.toCarve.clear();
        this.toCarve.addAll(List.of(foot.above(), foot, head.above(), head));
        this.stage = depth > 0 ? Stage.DESCEND : Stage.CARVE;
    }

    private Status descend(BotPlayer bot, BotBrain brain, WorldView world) {
        BlockPos feet = Navigator.feet(bot);
        if (feet.getY() <= this.standAt.getY() && bot.onGround()) {
            this.stage = Stage.CARVE;
            return Status.RUNNING;
        }
        BlockPos below = feet.below();
        // Stand in the middle of the block first. Dug out from under the centre while standing
        // near its edge, the next block along still holds us up: we'd hang there "falling"
        // forever (and give up on the home two minutes later).
        Vec3 centre = Vec3.atBottomCenterOf(feet);
        double off = Math.hypot(bot.getX() - centre.x, bot.getZ() - centre.z);
        if (off > 0.2 && (bot.onGround() || world.passable(below))) {
            brain.controls().faceTowards(bot, centre);
            brain.controls().forward = world.passable(below) ? 0.6f : 0.3f;
            this.edgeTicks++;
            if (this.edgeTicks < 60) return Status.RUNNING;
        }
        this.edgeTicks = 0;
        if (world.passable(below)) return Status.RUNNING; // falling
        brain.breaker().start(below);
        if (brain.breaker().tick(bot, brain.controls()) == BlockBreaker.Result.FAILED) this.stage = Stage.PICK;
        return Status.RUNNING;
    }

    private Status carve(BotPlayer bot, BotBrain brain, WorldView world) {
        for (BlockPos cell : this.toCarve) {
            if (world.passable(cell)) continue;
            if (!world.breakable(cell)) {
                this.stage = Stage.PICK;
                return Status.RUNNING;
            }
            brain.breaker().start(cell);
            if (brain.breaker().tick(bot, brain.controls()) == BlockBreaker.Result.FAILED) this.stage = Stage.PICK;
            return Status.RUNNING;
        }
        this.stage = Stage.PLACE;
        return Status.RUNNING;
    }

    private Status place(BotPlayer bot, BotBrain brain, WorldView world) {
        BlockPos foot = this.standAt.relative(this.facing);
        // Beds point the way you're facing when you place them.
        bot.setYRot(this.facing.toYRot());
        bot.setYHeadRot(this.facing.toYRot());
        brain.controls().face(this.facing.toYRot(), 45f);
        if (!Placer.place(bot, foot, s -> s.is(ItemTags.BEDS))) {
            this.stage = Stage.PICK;
            return Status.RUNNING;
        }
        if (!bot.level().getBlockState(foot).is(BlockTags.BEDS)) return Status.FAILED;
        BlockPos head = Homes.headOf(bot.level(), foot);
        BotManager.get(bot.level().getServer()).setHome(brain.record(), GlobalPos.of(bot.level().dimension(), head));
        brain.log("built a home at " + head.toShortString());
        // Light it up so nothing spawns on the pillow.
        if (Inv.count(bot, s -> s.is(net.minecraft.world.item.Items.TORCH)) > 0) {
            Placer.place(bot, this.standAt.above(), s -> s.is(net.minecraft.world.item.Items.TORCH));
        }
        brain.controls().lookAt(bot, Vec3.atCenterOf(head));
        return Status.DONE;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        brain.breaker().cancel(bot);
        brain.navigator().stop(bot);
    }
}
