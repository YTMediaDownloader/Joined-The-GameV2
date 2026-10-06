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
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: doing the place up. Pick flowers and plant them round the house, and put
 * torches out in the yard so the place is lit at night (and nothing spawns on the doorstep).
 */
public final class DecorateProject implements Task {
    static final Sources FLOWERS = new Sources("flowers",
            s -> s.is(BlockTags.SMALL_FLOWERS) && !s.is(Blocks.WITHER_ROSE), s -> s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item()), 0, Sources.SURFACE);
    static final int FLOWERS_WANTED = 6;

    private enum Stage { GATHER, PLACE, DONE }

    private Stage stage = Stage.GATHER;
    private @Nullable Task sub;
    private boolean triedFlowers;
    private int torchSteps;

    @Override
    public String activity() {
        if (this.sub != null) return this.sub.activity();
        return "Doing up my place";
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (this.stage == Stage.PLACE) {
                GlobalPos home = brain.record().home();
                brain.remember(BotMemory.EventType.BUILT, "decorations", home, 0.6f);
                this.stage = Stage.DONE;
            }
            return Status.RUNNING;
        }
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return Status.FAILED;
        return switch (this.stage) {
            case GATHER -> {
                if (!this.triedFlowers && Inv.count(bot, s -> s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item())) < FLOWERS_WANTED) {
                    this.triedFlowers = true;
                    this.sub = new MineTask(bot, FLOWERS, FLOWERS_WANTED - Inv.count(bot, s -> s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item())), "Picking flowers");
                    yield Status.RUNNING;
                }
                // Torches take a few steps (sticks, coal, then the torches).
                if (this.torchSteps < 6 && Inv.count(bot, Items.TORCH) < 6) {
                    this.torchSteps++;
                    boolean[] failed = {false};
                    this.sub = Tasks.toward(bot, Tasks.Need.nice("torches", s -> s.is(Items.TORCH), 6, b -> Items.TORCH), failed);
                    if (failed[0] || this.sub == null) this.torchSteps = 6;
                    yield Status.RUNNING;
                }
                if (Inv.count(bot, s -> s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item())) == 0 && Inv.count(bot, Items.TORCH) == 0) yield Status.FAILED;
                this.stage = Stage.PLACE;
                yield Status.RUNNING;
            }
            case PLACE -> {
                List<Step> steps = steps(bot, centre(brain, home.pos()));
                if (steps.isEmpty()) yield Status.FAILED;
                this.sub = new BuildTask(steps, "Doing up my place");
                yield Status.RUNNING;
            }
            case DONE -> Status.DONE;
        };
    }

    /** Middle of the house if we know it, else the bed. */
    private static BlockPos centre(BotBrain brain, BlockPos bed) {
        GlobalPos house = brain.record().house();
        return house != null && house.pos().distSqr(bed) < 16 * 16 ? house.pos() : bed;
    }

    /** Flowers in a ring 3-5 blocks out, torches further out; only on open ground outdoors. */
    private static List<Step> steps(BotPlayer bot, BlockPos centre) {
        WorldView w = new WorldView(bot.level());
        List<Step> steps = new ArrayList<>();
        List<BlockPos> used = new ArrayList<>();
        int flowers = Inv.count(bot, s -> s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item()));
        int torches = Math.min(4, Inv.count(bot, Items.TORCH));
        for (int i = 0; i < 48 && (flowers > 0 || torches > 0); i++) {
            double angle = i * 2.399; // golden angle: spreads the spots out evenly
            boolean torch = torches > 0 && (flowers == 0 || i % 3 == 0);
            double r = torch ? 7 : 4 + (i % 2);
            int x = centre.getX() + (int) Math.round(Math.cos(angle) * r);
            int z = centre.getZ() + (int) Math.round(Math.sin(angle) * r);
            int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos spot = new BlockPos(x, y, z);
            if (!fits(w, spot, torch) || used.stream().anyMatch(p -> p.distSqr(spot) < 4)) continue;
            used.add(spot);
            if (torch) {
                steps.add(Step.place(spot, s -> s.is(Items.TORCH)));
                torches--;
            } else {
                steps.add(Step.place(spot, s -> s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item())));
                flowers--;
            }
        }
        return steps;
    }

    private static boolean fits(WorldView w, BlockPos spot, boolean torch) {
        BlockState here = w.state(spot);
        if (!here.isAir() || !w.level().canSeeSky(spot)) return false;
        BlockState ground = w.state(spot.below());
        if (torch) return w.solidFloor(spot.below()) && !ground.is(BlockTags.LEAVES);
        return ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.PODZOL);
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }
}
