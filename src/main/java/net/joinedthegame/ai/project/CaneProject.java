package net.joinedthegame.ai.project;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.BuildTask.Step;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.TravelTask;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: sugar cane by the water near home. Some from a patch it's seen, planted along
 * the water's edge, where it grows by itself: paper for books (enchanting), sugar for later.
 */
public final class CaneProject implements Task {
    static final Sources CANE = new Sources("sugar cane", s -> s.is(Blocks.SUGAR_CANE), s -> s.is(Items.SUGAR_CANE), 0, Sources.SURFACE);
    static final int WANTED = 6;

    private enum Stage { TRAVEL, HARVEST, PLANT, DONE }

    private Stage stage = Stage.TRAVEL;
    private @Nullable Task sub;
    private @Nullable BlockPos patch;

    public static boolean available(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return false;
        if (brain.memory().ever(BotMemory.EventType.BUILT, "a sugar cane patch")) return false;
        return Inv.count(bot, Items.SUGAR_CANE) >= 2 || brain.memory().nearest("sugar_cane", home, 160) != null;
    }

    @Override
    public String activity() {
        if (this.sub != null) return this.sub.activity();
        return "Planting sugar cane";
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (this.stage == Stage.PLANT) {
                if (s == Status.FAILED || this.patch == null) return Status.FAILED;
                GlobalPos where = GlobalPos.of(bot.level().dimension(), this.patch);
                brain.remember(BotMemory.EventType.BUILT, "a sugar cane patch", where, 0.5f);
                brain.rememberPlace(bot, "cane_farm", this.patch);
                this.stage = Stage.DONE;
            }
            return Status.RUNNING;
        }
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return Status.FAILED;
        return switch (this.stage) {
            case TRAVEL -> {
                this.stage = Stage.HARVEST;
                if (Inv.count(bot, Items.SUGAR_CANE) >= WANTED) yield Status.RUNNING;
                GlobalPos seen = brain.memory().nearest("sugar_cane", home, 160);
                if (seen != null && seen.pos().distSqr(bot.blockPosition()) > 24 * 24) {
                    this.sub = new TravelTask(seen.pos(), 8, "Going to get some sugar cane");
                }
                yield Status.RUNNING;
            }
            case HARVEST -> {
                this.stage = Stage.PLANT;
                int have = Inv.count(bot, Items.SUGAR_CANE);
                if (have < WANTED) this.sub = new MineTask(bot, CANE, WANTED - have, "Cutting sugar cane");
                yield Status.RUNNING;
            }
            case PLANT -> {
                if (Inv.count(bot, Items.SUGAR_CANE) == 0) yield Status.FAILED;
                List<Step> steps = plan(bot, home.pos());
                if (steps.isEmpty()) {
                    brain.log("sugar cane: no water's edge near home to plant it on");
                    yield Status.FAILED;
                }
                this.sub = new BuildTask(steps, "Planting sugar cane");
                yield Status.RUNNING;
            }
            case DONE -> Status.DONE;
        };
    }

    /** Spots along the nearest water's edge to home (within 32): sand, dirt or grass beside water. */
    private List<Step> plan(BotPlayer bot, BlockPos home) {
        WorldView w = new WorldView(bot.level());
        int cane = Inv.count(bot, Items.SUGAR_CANE);
        List<BlockPos> spots = new ArrayList<>();
        BlockPos first = null;
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(home.offset(-32, -6, -32), home.offset(32, 6, 32))) near.add(p.immutable());
        near.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(home)));
        for (BlockPos p : near) {
            if (!plantable(w, p)) continue;
            if (first == null) first = p.immutable();
            if (p.distSqr(first) > 8 * 8) continue; // one patch, not dotted all over
            spots.add(p.immutable());
            if (spots.size() >= cane) break;
        }
        this.patch = first;
        List<Step> steps = new ArrayList<>();
        for (BlockPos p : spots) steps.add(Step.place(p, s -> s.is(Items.SUGAR_CANE)));
        return steps;
    }

    private static boolean plantable(WorldView w, BlockPos spot) {
        if (!w.state(spot).isAir() || !w.state(spot.above()).isAir()) return false;
        var ground = w.state(spot.below());
        if (!(ground.is(Blocks.SAND) || ground.is(Blocks.RED_SAND) || ground.is(Blocks.DIRT) || ground.is(Blocks.GRASS_BLOCK)
                || ground.is(Blocks.PODZOL) || ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.MUD))) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (w.state(spot.below().relative(d)).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return true;
        }
        return false;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }
}
