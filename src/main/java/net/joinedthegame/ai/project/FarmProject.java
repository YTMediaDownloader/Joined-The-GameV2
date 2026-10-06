package net.joinedthegame.ai.project;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.Wood;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.BuildTask.Step;
import net.joinedthegame.ai.task.FetchTask;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: a wheat farm by the house (see {@link Farms} for the layout). Find a flat
 * spot, get a hoe, seeds and a bucket of water, dig the water hole, till, plant, fence it in.
 * Afterwards {@link net.joinedthegame.ai.goal.FarmGoal} keeps it harvested and replanted.
 */
public final class FarmProject implements Task {
    /** Grass to break for seeds (one in eight drops some). */
    static final Sources GRASS = new Sources("grass",
            s -> s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN),
            s -> s.is(Items.WHEAT_SEEDS), 0, Sources.SURFACE);
    static final int SEEDS_WANTED = 10;

    private enum Stage { SITE, GATHER, PREPARE, WATER, PLANT, FENCE, DONE }

    private Stage stage = Stage.SITE;
    private @Nullable BlockPos center;
    private Direction gateSide = Direction.NORTH;
    private @Nullable Task sub;
    private final java.util.Set<String> skipped = new java.util.HashSet<>();
    private final java.util.Set<String> fetched = new java.util.HashSet<>();
    private Tasks.@Nullable Need need;
    private int rounds;
    private int waterTries;
    private int plantTries;

    @Override
    public String activity() {
        if (this.sub != null) return this.sub.activity();
        return switch (this.stage) {
            case SITE -> "Looking for a spot for a farm";
            case GATHER -> "Getting ready to farm";
            case PREPARE -> "Levelling the ground for a farm";
            case WATER -> "Watering the farm";
            case PLANT -> "Planting a farm";
            case FENCE -> "Fencing in the farm";
            case DONE -> "Farming";
        };
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (s == Status.FAILED && this.stage == Stage.GATHER) {
                Tasks.Need n = this.need;
                if (n != null && n.optional()) this.skipped.add(n.label());
                else if (n == null && Inv.count(bot, Farms::isSeed) >= 4) this.skipped.add("seeds"); // some is enough
            }
            if (s == Status.FAILED && this.stage == Stage.WATER && ++this.waterTries > 3) return Status.FAILED;
            if (s == Status.DONE && this.stage == Stage.PLANT && !planted(bot)) {
                // Didn't take (trampled, wrong ground...). Try again, then give up.
                if (++this.plantTries > 2) {
                    brain.log("farm: couldn't get anything to grow");
                    return Status.FAILED;
                }
                return Status.RUNNING;
            }
            if (s == Status.DONE) advanceAfter(bot, brain);
            return Status.RUNNING;
        }
        if (++this.rounds > 60) return Status.FAILED;
        return switch (this.stage) {
            case SITE -> site(bot, brain);
            case GATHER -> gather(bot, brain);
            case PREPARE -> {
                this.sub = new BuildTask(Farms.prepare(new WorldView(bot.level()), this.center), "Levelling the ground for a farm");
                yield Status.RUNNING;
            }
            case WATER -> water(bot, brain);
            case PLANT -> {
                // A block over the water with a torch on it, the classic farm centrepiece: the
                // water can't freeze over, and the crops keep growing at night.
                List<Step> steps = new ArrayList<>();
                steps.add(Step.place(this.center.above(), Inv::isPlaceableBlock));
                steps.add(Step.place(this.center.above(2), st -> st.is(Items.TORCH)));
                steps.addAll(Farms.tillAndPlant(new WorldView(bot.level()), this.center));
                this.sub = new BuildTask(steps, "Planting a farm");
                yield Status.RUNNING;
            }
            case FENCE -> {
                this.sub = new BuildTask(fenceSteps(), "Fencing in the farm");
                yield Status.RUNNING;
            }
            case DONE -> Status.DONE;
        };
    }

    /** Farmland down and at least a few seeds in. */
    private boolean planted(BotPlayer bot) {
        WorldView w = new WorldView(bot.level());
        int crops = 0;
        for (BlockPos c : Farms.field(this.center)) if (Farms.PLANTED.test(w, c)) crops++;
        return Farms.intact(w, this.center) && crops >= 4;
    }

    private void advanceAfter(BotPlayer bot, BotBrain brain) {
        if (this.stage == Stage.PREPARE) this.stage = Stage.WATER;
        else if (this.stage == Stage.PLANT) this.stage = Stage.FENCE;
        else if (this.stage == Stage.FENCE) {
            brain.rememberPlace(bot, "farm", this.center);
            brain.remember(BotMemory.EventType.PLANTED, "a wheat farm", GlobalPos.of(bot.level().dimension(), this.center), 0.9f);
            this.stage = Stage.DONE;
        }
    }

    private Status site(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return Status.FAILED;
        this.center = Farms.findSite(bot, home.pos(), 40);
        if (this.center == null) {
            brain.log("farm: nowhere flat enough near home " + Farms.lastRejections);
            return Status.FAILED;
        }
        // Gate on the side facing home.
        BlockPos d = home.pos().subtract(this.center);
        this.gateSide = Math.abs(d.getX()) > Math.abs(d.getZ())
                ? (d.getX() > 0 ? Direction.EAST : Direction.WEST)
                : (d.getZ() > 0 ? Direction.SOUTH : Direction.NORTH);
        brain.log("farm: going to plant at " + this.center.toShortString());
        this.stage = Stage.GATHER;
        return Status.RUNNING;
    }

    private List<Tasks.Need> needs(BotPlayer bot) {
        int dirt = Farms.dirtNeeded(new WorldView(bot.level()), this.center);
        return List.of(
                Tasks.Need.of("dirt", s -> s.is(Items.DIRT), dirt, b -> Items.DIRT),
                Tasks.Need.of("a hoe", s -> s.is(ItemTags.HOES), 1, b -> Items.STONE_HOE),
                Tasks.Need.of("a bucket", s -> s.is(Items.BUCKET) || s.is(Items.WATER_BUCKET), 1, b -> Items.BUCKET),
                Tasks.Need.nice("fences", s -> s.is(ItemTags.WOODEN_FENCES), 23, b -> Wood.preferred(b, "fence")),
                Tasks.Need.nice("a gate", s -> s.is(ItemTags.FENCE_GATES), 1, b -> Wood.preferred(b, "fence_gate")),
                Tasks.Need.nice("torches", s -> s.is(Items.TORCH), 4, b -> Items.TORCH));
    }

    private Status gather(BotPlayer bot, BotBrain brain) {
        this.need = null;
        for (Tasks.Need n : needs(bot)) {
            if (this.skipped.contains(n.label()) || n.missing(bot) <= 0) continue;
            this.need = n;
            if (this.fetched.add(n.label())) {
                Task fetch = FetchTask.from(bot, brain, n.have(), n.missing(bot), "Getting " + n.label() + " from my chests");
                if (fetch != null) {
                    this.sub = fetch;
                    return Status.RUNNING;
                }
            }
            boolean[] failed = {false};
            Task step = Tasks.toward(bot, n, failed);
            if (step != null) {
                this.sub = step;
                return Status.RUNNING;
            }
            if (failed[0]) {
                if (n.optional()) {
                    this.skipped.add(n.label());
                    continue;
                }
                brain.log("farm: can't get " + n.label());
                return Status.FAILED;
            }
        }
        this.need = null;
        // Seeds: from the chests, or by pulling up grass.
        if (!this.skipped.contains("seeds") && Inv.count(bot, Farms::isSeed) < SEEDS_WANTED) {
            if (this.fetched.add("seeds")) {
                Task fetch = FetchTask.from(bot, brain, Farms::isSeed, SEEDS_WANTED, "Getting seeds from my chests");
                if (fetch != null) {
                    this.sub = fetch;
                    return Status.RUNNING;
                }
            }
            this.sub = new MineTask(bot, GRASS, SEEDS_WANTED - Inv.count(bot, Farms::isSeed), "Pulling up grass for seeds");
            return Status.RUNNING;
        }
        if (Inv.count(bot, Farms::isSeed) == 0) return Status.FAILED;
        this.stage = Stage.PREPARE;
        return Status.RUNNING;
    }

    /** Dig the hole in the middle and pour a bucket of water in. */
    private Status water(BotPlayer bot, BotBrain brain) {
        WorldView w = new WorldView(bot.level());
        BlockPos hole = this.center;
        if (w.state(hole).getFluidState().is(Fluids.WATER)) {
            this.stage = Stage.PLANT;
            return Status.RUNNING;
        }
        if (!w.state(hole).canBeReplaced()) {
            this.sub = new BuildTask(List.of(Step.digEssential(hole)), "Digging a water hole");
            return Status.RUNNING;
        }
        if (Inv.count(bot, Items.WATER_BUCKET) == 0) {
            this.sub = new FillBucket();
            return Status.RUNNING;
        }
        this.sub = new PourWater(hole);
        return Status.RUNNING;
    }

    private List<Step> fenceSteps() {
        List<Step> steps = new ArrayList<>();
        List<BlockPos> ring = Farms.fence(this.center, this.gateSide);
        // The gate faces along the fence line, so you walk through it towards the field.
        steps.add(Step.placeFacing(ring.getFirst(), s -> s.is(ItemTags.FENCE_GATES), this.gateSide.getOpposite()));
        for (BlockPos p : ring.subList(1, ring.size())) steps.add(Step.place(p, s -> s.is(ItemTags.WOODEN_FENCES)));
        for (BlockPos p : Farms.corners(this.center)) steps.add(Step.place(p.above(), s -> s.is(Items.TORCH)));
        return steps;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }

    // --- water ---------------------------------------------------------------------------

    /** Find some water and scoop up a bucketful. */
    static final class FillBucket implements Task {
        private @Nullable BlockPos water;
        private int ticks;
        private int missed;

        /** Nothing solid between {@code eye} and the water's surface. */
        private static boolean clearTo(BotPlayer bot, Vec3 eye, Vec3 surface, BlockPos water) {
            var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(eye, surface,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
            return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(water);
        }

        @Override
        public String activity() {
            return "Fetching water";
        }

        @Override
        public Status tick(BotPlayer bot, BotBrain brain) {
            if (Inv.count(bot, Items.WATER_BUCKET) > 0) return Status.DONE;
            if (Inv.count(bot, Items.BUCKET) == 0 || ++this.ticks > 20 * 120) {
                brain.log("farm: couldn't fill the bucket (bucket " + Inv.count(bot, Items.BUCKET) + ", water at " + this.water + ")");
                return Status.FAILED;
            }
            if (this.water == null) {
                this.water = Senses.findBlock(bot, s -> s.getFluidState().isSourceOfType(Fluids.WATER), 48, 10, false,
                        p -> brain.isBlacklisted(p) || !bot.level().getBlockState(p.above()).isAir());
                // Snowy places: frozen over, so break the ice to get at it.
                if (this.water == null) {
                    this.water = Senses.findBlock(bot, s -> s.is(Blocks.ICE), 48, 10, false,
                            // (ice over thin air just vanishes when broken)
                            p -> brain.isBlacklisted(p) || bot.level().getBlockState(p.below()).isAir());
                }
                if (this.water == null) {
                    GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
                    GlobalPos known = brain.memory().nearest("water", here, 128);
                    if (known == null) {
                        brain.log("farm: no water anywhere near");
                        return Status.FAILED;
                    }
                    this.water = known.pos();
                }
            }
            BlockPos water = this.water;
            var state = bot.level().getBlockState(water);
            boolean isWater = state.getFluidState().isSourceOfType(Fluids.WATER);
            if (!isWater && !state.is(Blocks.ICE) && bot.level().isLoaded(water)) {
                // Not water any more (filled in, dried up): forget it and look again.
                brain.memory().forget("water", GlobalPos.of(bot.level().dimension(), water));
                brain.blacklist(water);
                this.water = null;
                return Status.RUNNING;
            }
            // A bucket's reach is short, and it needs a clear line to the surface: from "in reach"
            // but round the bank it just misses, and we'd stand at the water's edge for good.
            // Get close with a clear view; missing anyway, get closer; still missing, other water.
            Vec3 surface = Vec3.atCenterOf(water).add(0, 0.4, 0);
            double close = this.missed >= 3 ? 2.6 : 3.6;
            boolean ready = bot.getEyePosition().distanceTo(surface) <= close && clearTo(bot, bot.getEyePosition(), surface, water);
            if (!ready) {
                Navigator nav = brain.navigator();
                if (!nav.isMoving()) {
                    BlockPos target = water;
                    double r = close - 0.3;
                    nav.moveTo(bot, surface, p -> {
                        if (p.equals(target) || p.above().equals(target)) return false;
                        Vec3 eye = Vec3.atBottomCenterOf(p).add(0, 1.62, 0);
                        return eye.distanceTo(surface) <= r && clearTo(bot, eye, surface, target);
                    });
                    if (nav.status() == Navigator.Status.FAILED) {
                        brain.blacklist(water);
                        this.water = null;
                        this.missed = 0;
                        nav.stop(bot);
                    }
                }
                return Status.RUNNING;
            }
            brain.navigator().stop(bot);
            if (state.is(Blocks.ICE)) {
                brain.breaker().start(water);
                brain.breaker().tick(bot, brain.controls());
                return Status.RUNNING;
            }
            brain.rememberPlace(bot, "water", water);
            if (this.ticks % 10 == 0 && !Placer.useAimedAt(bot, surface, s -> s.is(Items.BUCKET)) && ++this.missed >= 8) {
                brain.blacklist(water); // can't land it from anywhere we can stand: other water
                this.water = null;
                this.missed = 0;
            }
            return Status.RUNNING;
        }

        @Override
        public void stop(BotPlayer bot, BotBrain brain) {
            brain.navigator().stop(bot);
        }
    }

    /**
     * Pour the bucket into the hole. Stand right at its edge and aim at its floor: from further
     * back the aim clips the grass beside it and the water ends up on top of the field.
     */
    static final class PourWater implements Task {
        private final BlockPos hole;
        private int ticks;

        PourWater(BlockPos hole) {
            this.hole = hole;
        }

        @Override
        public String activity() {
            return "Watering the farm";
        }

        @Override
        public Status tick(BotPlayer bot, BotBrain brain) {
            var level = bot.level();
            if (level.getFluidState(this.hole).isSourceOfType(Fluids.WATER)) return Status.DONE;
            if (++this.ticks > 20 * 40) {
                brain.log("farm: couldn't pour the water into " + this.hole.toShortString() + " (state " + level.getBlockState(this.hole) + ")");
                return Status.FAILED;
            }
            BlockPos feet = Navigator.feet(bot);
            boolean atEdge = feet.getY() == this.hole.getY() + 1
                    && Math.abs(feet.getX() - this.hole.getX()) + Math.abs(feet.getZ() - this.hole.getZ()) == 1;
            if (!atEdge) {
                Navigator nav = brain.navigator();
                if (!nav.isMoving()) {
                    BlockPos hole = this.hole;
                    nav.moveTo(bot, Vec3.atCenterOf(hole.above()), p -> p.getY() == hole.getY() + 1
                            && Math.abs(p.getX() - hole.getX()) + Math.abs(p.getZ() - hole.getZ()) == 1);
                    if (nav.status() == Navigator.Status.FAILED) {
                        brain.log("farm: can't get next to the water hole");
                        return Status.FAILED;
                    }
                }
                return Status.RUNNING;
            }
            brain.navigator().stop(bot);
            if (this.ticks % 10 != 0) return Status.RUNNING;
            // Spilt some on the field last time? Scoop it back up first.
            for (BlockPos p : BlockPos.betweenClosed(this.hole.offset(-2, 1, -2), this.hole.offset(2, 1, 2))) {
                if (Inv.count(bot, Items.BUCKET) > 0 && level.getFluidState(p).isSourceOfType(Fluids.WATER)) {
                    Placer.useAimedAt(bot, Vec3.atCenterOf(p).add(0, 0.3, 0), st -> st.is(Items.BUCKET));
                    return Status.RUNNING;
                }
            }
            if (Inv.count(bot, Items.WATER_BUCKET) == 0) {
                brain.log("farm: lost the water somewhere");
                return Status.FAILED;
            }
            Placer.useAimedAt(bot, Vec3.atBottomCenterOf(this.hole).add(0, 0.05, 0), st -> st.is(Items.WATER_BUCKET));
            return Status.RUNNING;
        }

        @Override
        public void stop(BotPlayer bot, BotBrain brain) {
            brain.navigator().stop(bot);
        }
    }
}
