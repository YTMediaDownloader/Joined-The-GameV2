package net.joinedthegame.ai.project;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Wood;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.BuildTask.Step;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: a flower garden. A fenced square by the house, planted with every colour it
 * can find in a chequerboard, a gate facing home and a torch on each corner post.
 */
public final class GardenProject implements Task {
    static final int FLOWERS_WANTED = 13;

    private enum Stage { SITE, FLOWERS, GATHER, PREPARE, BUILD, DONE }

    private Stage stage = Stage.SITE;
    private @Nullable Task sub;
    private @Nullable BlockPos centre;
    private Direction gateSide = Direction.NORTH;
    private Gatherer gatherer = new Gatherer();
    private int rounds;
    private boolean picked;

    private static boolean flower(ItemStack s) {
        return s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item()) && !s.is(Items.WITHER_ROSE);
    }

    public static boolean available(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension() || brain.record().house() == null) return false;
        return !brain.memory().ever(BotMemory.EventType.BUILT, "a flower garden");
    }

    @Override
    public String activity() {
        if (this.sub != null) return this.sub.activity();
        return "Making a flower garden";
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (s == Status.FAILED && this.stage == Stage.GATHER) this.gatherer.stepFailed();
            if (this.stage == Stage.BUILD) {
                if (this.centre == null) return Status.FAILED;
                // What's really there: anything missing (dropped dying half way), get it and finish.
                int[] missing = Farms.ringMissing(new WorldView(bot.level()), this.centre, this.gateSide);
                if (missing[0] + missing[1] > 0 && ++this.rounds <= 3) {
                    brain.log("garden: " + missing[0] + " fences" + (missing[1] > 0 ? " and the gate" : "") + " still to go in: getting them");
                    this.gatherer = new Gatherer();
                    this.stage = Stage.GATHER;
                    return Status.RUNNING;
                }
                GlobalPos where = GlobalPos.of(bot.level().dimension(), this.centre);
                brain.remember(BotMemory.EventType.BUILT, "a flower garden", where, 0.6f);
                brain.rememberPlace(bot, "garden", this.centre);
                this.stage = Stage.DONE;
            } else if (this.stage == Stage.PREPARE) {
                this.stage = Stage.BUILD;
            }
            return Status.RUNNING;
        }
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return Status.FAILED;
        return switch (this.stage) {
            case SITE -> {
                this.centre = Farms.findSite(bot, home.pos(), 32);
                if (this.centre == null) {
                    brain.log("garden: nowhere flat enough near home");
                    yield Status.FAILED;
                }
                BlockPos d = home.pos().subtract(this.centre);
                this.gateSide = Math.abs(d.getX()) > Math.abs(d.getZ())
                        ? (d.getX() > 0 ? Direction.EAST : Direction.WEST)
                        : (d.getZ() > 0 ? Direction.SOUTH : Direction.NORTH);
                this.stage = Stage.FLOWERS;
                yield Status.RUNNING;
            }
            case FLOWERS -> {
                this.stage = Stage.GATHER;
                int have = Inv.count(bot, GardenProject::flower);
                if (!this.picked && have < FLOWERS_WANTED) {
                    this.picked = true;
                    this.sub = new MineTask(bot, DecorateProject.FLOWERS, FLOWERS_WANTED - have, "Picking flowers for a garden");
                }
                yield Status.RUNNING;
            }
            case GATHER -> {
                if (Inv.count(bot, GardenProject::flower) < 4 && this.rounds == 0) yield Status.FAILED;
                boolean[] failed = {false};
                int[] missing = Farms.ringMissing(new WorldView(bot.level()), this.centre, this.gateSide);
                Task step = this.gatherer.next(bot, brain, List.of(
                        Tasks.Need.of("fences", s -> s.is(ItemTags.WOODEN_FENCES), missing[0], b -> Wood.preferred(b, "fence")),
                        Tasks.Need.of("a gate", s -> s.is(ItemTags.FENCE_GATES), missing[1], b -> Wood.preferred(b, "fence_gate")),
                        Tasks.Need.nice("dirt", s -> s.is(Items.DIRT), Farms.dirtNeeded(new WorldView(bot.level()), this.centre), b -> Items.DIRT),
                        Tasks.Need.nice("torches", s -> s.is(Items.TORCH), 4, b -> Items.TORCH)), failed);
                if (failed[0]) {
                    brain.log("garden: can't get " + this.gatherer.failedOn);
                    yield Status.FAILED;
                }
                if (step != null) {
                    this.sub = step;
                    yield Status.RUNNING;
                }
                this.stage = Stage.PREPARE;
                this.sub = new BuildTask(Farms.prepare(new WorldView(bot.level()), this.centre), "Levelling the ground for a garden");
                yield Status.RUNNING;
            }
            case PREPARE -> {
                this.stage = Stage.BUILD;
                yield Status.RUNNING;
            }
            case BUILD -> {
                this.sub = new BuildTask(steps(bot), "Planting a flower garden");
                yield Status.RUNNING;
            }
            case DONE -> Status.DONE;
        };
    }

    /** Flowers on every other square (a different one each time it can), then the fence, gate, torches. */
    private List<Step> steps(BotPlayer bot) {
        List<Step> steps = new ArrayList<>();
        BlockPos c = this.centre;
        int flowers = Inv.count(bot, GardenProject::flower);
        for (int x = -Farms.HALF; x <= Farms.HALF && flowers > 0; x++) {
            for (int z = -Farms.HALF; z <= Farms.HALF && flowers > 0; z++) {
                if ((x + z) % 2 != 0) continue;
                steps.add(Step.place(c.offset(x, 1, z), GardenProject::flower));
                flowers--;
            }
        }
        List<BlockPos> ring = Farms.fence(c, this.gateSide);
        steps.add(Step.placeFacing(ring.getFirst(), s -> s.is(ItemTags.FENCE_GATES), this.gateSide.getOpposite()));
        for (BlockPos p : ring.subList(1, ring.size())) steps.add(Step.place(p, s -> s.is(ItemTags.WOODEN_FENCES)));
        for (BlockPos p : Farms.corners(c)) steps.add(Step.place(p.above(), s -> s.is(Items.TORCH)));
        return steps;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }
}
