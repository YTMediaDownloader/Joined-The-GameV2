package net.joinedthegame.ai.project;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Wood;
import net.joinedthegame.ai.nav.PathNode;
import net.joinedthegame.ai.nav.Pathfinder;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.BuildTask.Step;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: paths. Dirt paths dug with a shovel from the front door out to the places it
 * goes every day (the farm, the garden, the pen, the trees, the campfire), with a lamp post (a
 * fence with a torch on top) every so often, so it's lit at night. The grounds of a real home.
 */
public final class PathsProject implements Task {
    private static final String[] PLACES = {"farm", "garden", "pen", "tree_farm", "cane_farm", "campfire"};

    private enum Stage { GATHER, BUILD, DONE }

    private Stage stage = Stage.GATHER;
    private @Nullable Task sub;
    private final Gatherer gatherer = new Gatherer();

    public static boolean available(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension() || brain.record().house() == null) return false;
        if (brain.memory().ever(BotMemory.EventType.BUILT, "paths")) return false;
        for (String kind : PLACES) if (brain.memory().nearest(kind, home, 64) != null) return true;
        return false;
    }

    @Override
    public String activity() {
        if (this.sub != null) return this.sub.activity();
        return "Laying paths";
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
                GlobalPos house = brain.record().house();
                brain.remember(BotMemory.EventType.BUILT, "paths", house, 0.5f);
                this.stage = Stage.DONE;
            }
            return Status.RUNNING;
        }
        GlobalPos house = brain.record().house();
        GlobalPos home = brain.record().home();
        if (house == null || home == null || house.dimension() != bot.level().dimension()) return Status.FAILED;
        return switch (this.stage) {
            case GATHER -> {
                boolean[] failed = {false};
                Task step = this.gatherer.next(bot, brain, List.of(
                        Tasks.Need.of("a shovel", s -> s.is(ItemTags.SHOVELS), 1, b -> Items.STONE_SHOVEL),
                        Tasks.Need.nice("fences", s -> s.is(ItemTags.WOODEN_FENCES), 6, b -> Wood.preferred(b, "fence")),
                        Tasks.Need.nice("torches", s -> s.is(Items.TORCH), 6, b -> Items.TORCH)), failed);
                if (failed[0]) yield Status.FAILED;
                if (step != null) {
                    this.sub = step;
                    yield Status.RUNNING;
                }
                List<Step> steps = plan(bot, brain, house.pos(), home);
                if (steps.isEmpty()) yield Status.FAILED;
                this.stage = Stage.BUILD;
                this.sub = new BuildTask(steps, "Laying paths");
                yield Status.RUNNING;
            }
            case BUILD -> {
                this.stage = Stage.DONE;
                yield Status.RUNNING;
            }
            case DONE -> Status.DONE;
        };
    }

    /** A walkable route from the door to each place, dug into a path, lamp posts along it. */
    private static List<Step> plan(BotPlayer bot, BotBrain brain, BlockPos door, GlobalPos home) {
        WorldView w = new WorldView(bot.level());
        List<Step> steps = new ArrayList<>();
        java.util.Set<BlockPos> done = new java.util.HashSet<>();
        int lamps = Math.min(Inv.count(bot, s -> s.is(ItemTags.WOODEN_FENCES)), Inv.count(bot, Items.TORCH));
        BlockPos start = door;
        // The door's the room cell inside: step out of the front.
        if (!w.standable(start)) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (w.standable(start.relative(d, 2))) {
                    start = start.relative(d, 2);
                    break;
                }
            }
        }
        Pathfinder finder = new Pathfinder(bot, new Pathfinder.Options(false, false, 2, 2500, Integer.MAX_VALUE, false));
        for (String kind : PLACES) {
            GlobalPos place = brain.memory().nearest(kind, home, 64);
            if (place == null) continue;
            BlockPos target = place.pos();
            var path = finder.find(start, Vec3.atBottomCenterOf(target), p -> p.closerThan(target, 3));
            if (path == null) continue;
            int sinceLamp = 0;
            for (PathNode n : path.nodes()) {
                BlockPos ground = n.pos().below();
                if (!done.add(ground)) continue;
                var g = w.state(ground);
                if (g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.DIRT) || g.is(Blocks.COARSE_DIRT) || g.is(Blocks.PODZOL)) {
                    if (w.state(n.pos()).isAir() || w.state(n.pos()).canBeReplaced()) {
                        if (!w.state(n.pos()).isAir()) steps.add(Step.clearOut(n.pos()));
                        steps.add(Step.useOnTop(ground, s -> s.is(ItemTags.SHOVELS), (ww, p) -> ww.state(p).is(Blocks.DIRT_PATH)));
                    }
                }
                // A lamp post beside the path every eight blocks.
                if (++sinceLamp >= 8 && lamps > 0) {
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos post = n.pos().relative(d);
                        if (w.state(post).isAir() && w.state(post.above()).isAir() && w.solidFloor(post.below()) && !done.contains(post.below())) {
                            steps.add(Step.place(post, s -> s.is(ItemTags.WOODEN_FENCES)));
                            steps.add(Step.place(post.above(), s -> s.is(Items.TORCH)));
                            lamps--;
                            sinceLamp = 0;
                            break;
                        }
                    }
                }
            }
        }
        return steps;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }
}
