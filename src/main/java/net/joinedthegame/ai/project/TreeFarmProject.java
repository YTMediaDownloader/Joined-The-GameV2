package net.joinedthegame.ai.project;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.BuildTask.Step;
import net.joinedthegame.ai.task.FetchTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: a tree farm. Saplings planted in a neat grid a little way from the house, a
 * few blocks apart so every one has room to grow, so there's always wood close to home instead of
 * a trek to the nearest forest. (The saplings come from chopping trees: the leaves drop them.)
 */
public final class TreeFarmProject implements Task {
    static final int WANTED = 6;

    private enum Stage { FETCH, PLANT, DONE }

    private Stage stage = Stage.FETCH;
    private @Nullable Task sub;
    private @Nullable BlockPos centre;

    public static boolean available(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return false;
        if (brain.memory().ever(BotMemory.EventType.BUILT, "a tree farm")) return false;
        return Inv.count(bot, s -> s.is(ItemTags.SAPLINGS)) + FetchTask.stored(bot, brain, s -> s.is(ItemTags.SAPLINGS)) >= 4;
    }

    @Override
    public String activity() {
        if (this.sub != null) return this.sub.activity();
        return "Planting a tree farm";
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (this.stage == Stage.PLANT) {
                if (s == Status.FAILED || this.centre == null) return Status.FAILED;
                GlobalPos where = GlobalPos.of(bot.level().dimension(), this.centre);
                brain.remember(BotMemory.EventType.BUILT, "a tree farm", where, 0.6f);
                brain.rememberPlace(bot, "tree_farm", this.centre);
                this.stage = Stage.DONE;
            }
            return Status.RUNNING;
        }
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return Status.FAILED;
        return switch (this.stage) {
            case FETCH -> {
                this.stage = Stage.PLANT;
                int have = Inv.count(bot, s -> s.is(ItemTags.SAPLINGS));
                if (have < WANTED) {
                    Task fetch = FetchTask.from(bot, brain, s -> s.is(ItemTags.SAPLINGS), WANTED - have, "Getting saplings from my chests");
                    if (fetch != null) this.sub = fetch;
                }
                yield Status.RUNNING;
            }
            case PLANT -> {
                List<Step> steps = plan(bot, home.pos());
                if (steps.isEmpty()) {
                    brain.log("tree farm: nowhere to plant near home");
                    yield Status.FAILED;
                }
                this.sub = new BuildTask(steps, "Planting a tree farm");
                yield Status.RUNNING;
            }
            case DONE -> Status.DONE;
        };
    }

    /**
     * Two rows of three, four blocks apart, 10-24 blocks from home: the first spot round home where
     * at least four of the six have open ground and room to grow.
     */
    private List<Step> plan(BotPlayer bot, BlockPos home) {
        WorldView w = new WorldView(bot.level());
        int saplings = Inv.count(bot, s -> s.is(ItemTags.SAPLINGS));
        for (int r = 10; r <= 24; r += 2) {
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8;
                int cx = home.getX() + (int) Math.round(Math.cos(a) * r);
                int cz = home.getZ() + (int) Math.round(Math.sin(a) * r);
                List<BlockPos> spots = new ArrayList<>();
                for (int gx = -1; gx <= 1; gx++) {
                    for (int gz = 0; gz <= 1; gz++) {
                        int x = cx + gx * 4, z = cz + gz * 4;
                        if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                        int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                        BlockPos spot = new BlockPos(x, y, z);
                        if (grows(w, spot)) spots.add(spot);
                    }
                }
                if (spots.size() < Math.min(4, saplings)) continue;
                this.centre = new BlockPos(cx, spots.getFirst().getY(), cz);
                List<Step> steps = new ArrayList<>();
                for (BlockPos p : spots.subList(0, Math.min(spots.size(), saplings))) steps.add(Step.place(p, s -> s.is(ItemTags.SAPLINGS)));
                return steps;
            }
        }
        return List.of();
    }

    /** Open dirt or grass, nothing in the way, and room above for a tree. */
    private static boolean grows(WorldView w, BlockPos spot) {
        var ground = w.state(spot.below());
        if (!(ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.PODZOL) || ground.is(Blocks.COARSE_DIRT))) return false;
        if (!w.level().canSeeSky(spot)) return false;
        for (int y = 0; y <= 6; y++) {
            var s = w.state(spot.above(y));
            if (!s.isAir() && !(y == 0 && s.canBeReplaced() && !s.is(BlockTags.SAPLINGS))) return false;
        }
        return true;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }
}
