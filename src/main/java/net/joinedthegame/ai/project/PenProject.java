package net.joinedthegame.ai.project;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Wood;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.BuildTask.Step;
import net.joinedthegame.ai.task.FetchTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: an animal pen. A fenced square by the house with a gate; then two animals led
 * in the way a player does it, by holding their favourite food (they follow anyone holding it),
 * and fed so they breed. Cows or sheep want wheat, pigs carrots (or potatoes, beetroot), chickens
 * seeds. It goes for whichever there's food for and animals about.
 */
public final class PenProject implements Task {
    /** The animals that can be penned, and what they follow. */
    private enum Kind {
        COW(net.minecraft.world.entity.animal.cow.Cow.class, s -> s.is(Items.WHEAT), "cows"),
        SHEEP(net.minecraft.world.entity.animal.sheep.Sheep.class, s -> s.is(Items.WHEAT), "sheep"),
        PIG(net.minecraft.world.entity.animal.pig.Pig.class, s -> s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT), "pigs"),
        CHICKEN(net.minecraft.world.entity.animal.chicken.Chicken.class, s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS), "chickens");

        final Class<? extends Animal> type;
        final Predicate<ItemStack> food;
        final String label;

        Kind(Class<? extends Animal> type, Predicate<ItemStack> food, String label) {
            this.type = type;
            this.food = food;
            this.label = label;
        }
    }

    private enum Stage { CHOOSE, SITE, GATHER, PREPARE, FENCE, LURE, BREED, DONE }

    private Stage stage = Stage.CHOOSE;
    private @Nullable Task sub;
    private @Nullable Kind kind;
    private @Nullable BlockPos centre;
    private Direction gateSide = Direction.NORTH;
    private Gatherer gatherer = new Gatherer();
    /** Rounds of going back for what went missing (lost in a death, say). */
    private int rounds;
    private int foodTries;
    private @Nullable Animal leading;
    private int lureTicks;
    private int waitedIn;
    /** Stood inside the gate with the food out, letting it come in: on purpose. */
    private boolean waiting;

    public boolean waiting() {
        return this.waiting && this.stage == Stage.LURE;
    }
    private int breedTicks;

    public static boolean available(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return false;
        return !brain.memory().ever(BotMemory.EventType.BUILT, "an animal pen");
    }

    @Override
    public String activity() {
        if (this.sub != null) return this.sub.activity();
        return switch (this.stage) {
            case LURE -> "Leading " + (this.kind == null ? "animals" : this.kind.label) + " home";
            case BREED -> "Breeding my " + (this.kind == null ? "animals" : this.kind.label);
            default -> "Building an animal pen";
        };
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (s == Status.FAILED && this.stage == Stage.GATHER) this.gatherer.stepFailed();
            if (this.stage == Stage.PREPARE) this.stage = Stage.FENCE;
            else if (this.stage == Stage.FENCE) {
                // Check what's really there, not what we meant to put there: anything missing (we
                // died and dropped the fences half way, say), go and get exactly that, and finish.
                int[] missing = Farms.ringMissing(new WorldView(bot.level()), this.centre, this.gateSide);
                if (missing[0] + missing[1] > 0) {
                    if (++this.rounds > 3) {
                        brain.log("pen: still " + missing[0] + " fences short after three goes: leaving it for now");
                        return Status.FAILED;
                    }
                    brain.log("pen: " + missing[0] + " fences" + (missing[1] > 0 ? " and the gate" : "") + " still to go in: getting them");
                    this.gatherer = new Gatherer();
                    this.stage = Stage.GATHER;
                    return Status.RUNNING;
                }
                this.stage = Stage.LURE;
            }
            return Status.RUNNING;
        }
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return Status.FAILED;
        return switch (this.stage) {
            case CHOOSE -> choose(bot, brain, home.pos());
            case SITE -> {
                this.centre = Farms.findSite(bot, home.pos(), 40);
                if (this.centre == null) {
                    brain.log("pen: nowhere flat enough near home");
                    yield Status.FAILED;
                }
                BlockPos d = home.pos().subtract(this.centre);
                this.gateSide = Math.abs(d.getX()) > Math.abs(d.getZ())
                        ? (d.getX() > 0 ? Direction.EAST : Direction.WEST)
                        : (d.getZ() > 0 ? Direction.SOUTH : Direction.NORTH);
                this.stage = Stage.GATHER;
                yield Status.RUNNING;
            }
            case GATHER -> {
                Kind k = this.kind;
                boolean[] failed = {false};
                // What the ring's still missing (all of it the first time round).
                int[] missing = Farms.ringMissing(new WorldView(bot.level()), this.centre, this.gateSide);
                Task step = this.gatherer.next(bot, brain, List.of(
                        Tasks.Need.of("fences", s -> s.is(ItemTags.WOODEN_FENCES), missing[0], b -> Wood.preferred(b, "fence")),
                        Tasks.Need.of("a gate", s -> s.is(ItemTags.FENCE_GATES), missing[1], b -> Wood.preferred(b, "fence_gate")),
                        Tasks.Need.nice("torches", s -> s.is(Items.TORCH), 4, b -> Items.TORCH)), failed);
                if (failed[0]) {
                    brain.log("pen: can't get " + this.gatherer.failedOn);
                    yield Status.FAILED;
                }
                if (step != null) {
                    this.sub = step;
                    yield Status.RUNNING;
                }
                if (k == null || Inv.count(bot, k.food) < 2) {
                    // The food went somewhere (eaten, stored): get some back out, or give up.
                    Task fetch = k == null ? null : FetchTask.from(bot, brain, k.food, 4, "Getting animal food from my chests");
                    if (fetch == null) yield Status.FAILED;
                    this.sub = fetch;
                    yield Status.RUNNING;
                }
                this.stage = Stage.PREPARE;
                this.sub = new BuildTask(Farms.prepare(new WorldView(bot.level()), this.centre), "Levelling the ground for a pen");
                yield Status.RUNNING;
            }
            case PREPARE -> {
                this.stage = Stage.FENCE;
                yield Status.RUNNING;
            }
            case FENCE -> {
                List<Step> steps = new ArrayList<>();
                List<BlockPos> ring = Farms.fence(this.centre, this.gateSide);
                steps.add(Step.placeFacing(ring.getFirst(), s -> s.is(ItemTags.FENCE_GATES), this.gateSide.getOpposite()));
                for (BlockPos p : ring.subList(1, ring.size())) steps.add(Step.place(p, s -> s.is(ItemTags.WOODEN_FENCES)));
                for (BlockPos p : Farms.corners(this.centre)) steps.add(Step.place(p.above(), s -> s.is(Items.TORCH)));
                this.sub = new BuildTask(steps, "Fencing in a pen");
                yield Status.RUNNING;
            }
            case LURE -> lure(bot, brain);
            case BREED -> breed(bot, brain);
            case DONE -> Status.DONE;
        };
    }

    private boolean wentHome;

    /** Which animal: one there's food for (in the bag or at home) and a couple of about. */
    private Status choose(BotPlayer bot, BotBrain brain, BlockPos home) {
        // The pen goes by the house, so it's the animals round home that count: go and look there.
        if (!this.wentHome && bot.blockPosition().distSqr(home) > 32 * 32) {
            this.wentHome = true;
            this.sub = new net.joinedthegame.ai.task.TravelTask(home, 8, "Heading home to build a pen");
            return Status.RUNNING;
        }
        for (Kind k : Kind.values()) {
            int food = Inv.count(bot, k.food) + FetchTask.stored(bot, brain, k.food);
            if (food < 2) continue; // one to lead with, one more to breed (the second feed can be found later)
            if (Senses.entitiesNear(bot, k.type, 48, a -> a.isAlive() && !a.isBaby()).size() < 2) continue;
            this.kind = k;
            brain.log("pen: going to keep " + k.label);
            this.stage = Stage.SITE;
            if (Inv.count(bot, k.food) < 4) {
                Task fetch = FetchTask.from(bot, brain, k.food, 4, "Getting animal food from my chests");
                if (fetch != null) this.sub = fetch;
            }
            return Status.RUNNING;
        }
        brain.log("pen: no animals about that I've the food for");
        return Status.FAILED;
    }

    private boolean inPen(Animal a) {
        BlockPos c = this.centre;
        if (c == null) return false;
        return Math.abs(a.getBlockX() - c.getX()) <= Farms.HALF && Math.abs(a.getBlockZ() - c.getZ()) <= Farms.HALF
                && Math.abs(a.getBlockY() - c.getY() - 1) <= 2;
    }

    private List<? extends Animal> penned(BotPlayer bot) {
        Kind k = this.kind;
        BlockPos c = this.centre;
        if (k == null || c == null) return List.of();
        return bot.level().getEntitiesOfClass(k.type, new net.minecraft.world.phys.AABB(c).inflate(Farms.HALF + 1, 3, Farms.HALF + 1),
                a -> a.isAlive() && !a.isBaby() && inPen(a));
    }

    /**
     * Fetch one animal at a time: walk up to it holding its food, then walk back into the pen
     * slowly (it follows at a walk, and loses interest more than ten blocks back). Two in, done.
     */
    private Status lure(BotPlayer bot, BotBrain brain) {
        Kind k = this.kind;
        BlockPos c = this.centre;
        if (k == null || c == null) return Status.FAILED;
        if (penned(bot).size() >= 2) {
            this.stage = Stage.BREED;
            this.leading = null;
            brain.navigator().stop(bot);
            brain.navigator().leaveDoorsOpen = false;
            closeGate(bot);
            return Status.RUNNING;
        }
        // Nothing to lead with (eaten, dropped when we died): get more first, from the chests, or
        // for chickens pull up some grass for seeds.
        if (Inv.count(bot, k.food) == 0 && this.foodTries < 3) {
            this.foodTries++;
            Task fetch = FetchTask.from(bot, brain, k.food, 4, "Getting animal food from my chests");
            if (fetch == null && k == Kind.CHICKEN) fetch = new net.joinedthegame.ai.task.MineTask(bot, FarmProject.GRASS, 4, "Pulling up grass for seeds");
            if (fetch != null) {
                this.sub = fetch;
                return Status.RUNNING;
            }
        }
        if (++this.lureTicks > 20 * 60 * 5 || Inv.count(bot, k.food) == 0) {
            brain.log("pen: couldn't get the " + k.label + " in");
            // The pen's built: that's still something.
            brain.remember(BotMemory.EventType.BUILT, "an animal pen", GlobalPos.of(bot.level().dimension(), c), 0.4f);
            brain.rememberPlace(bot, "pen", c);
            return Status.DONE;
        }
        Inv.hold(bot, k.food);
        Navigator nav = brain.navigator();
        nav.sprint = false;
        Animal a = this.leading;
        if (a == null || !a.isAlive() || inPen(a) || a.distanceToSqr(bot) > 24 * 24) {
            a = null;
            for (Animal cand : Senses.entitiesNear(bot, k.type, 48, x -> x.isAlive() && !x.isBaby() && !inPen(x))) {
                a = cand;
                break;
            }
            this.leading = a;
            if (a == null) {
                // None in sight from here: go and look round home.
                if (!nav.isMoving()) nav.moveNear(bot, Vec3.atCenterOf(c), 3);
                return Status.RUNNING;
            }
        }
        this.waiting = false;
        // The gate stays open behind us while it follows us in (the navigator shuts gates), and
        // if it's shut (we built the fence from the inside), open it: animals can't.
        nav.leaveDoorsOpen = true;
        BlockPos gate = Farms.fence(c, this.gateSide).getFirst();
        var gs = bot.level().getBlockState(gate);
        if (gs.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                && !gs.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN)
                && net.joinedthegame.ai.skill.BlockBreaker.inReach(bot, gate) && this.lureTicks % 10 == 0) {
            Placer.use(bot, gate);
        }
        // Wait just inside the gate, where it'll come in.
        Vec3 inside = Vec3.atBottomCenterOf(gate.relative(this.gateSide.getOpposite()));
        // Animals only follow food within about ten blocks, and they're slow: keep it close.
        boolean following = a.distanceToSqr(bot) < 6.5 * 6.5;
        boolean weAreIn = bot.position().distanceToSqr(inside) < 1.5 * 1.5;
        if (!following) {
            // Go to it (it comes the last bit itself once it smells the food).
            if (!nav.isMoving() || this.lureTicks % 40 == 0) nav.moveNear(bot, a.position(), 2.5);
            this.waitedIn = 0;
        } else if (!weAreIn) {
            // Back into the pen with it in tow. Keep walking: a tempted animal comes to you by
            // itself (stopping to wait for it is how it ends up stuck the wrong side of a fence).
            if (!nav.isMoving() || this.lureTicks % 60 == 0) nav.moveNear(bot, inside, 1.0);
        } else {
            // In: stand here with the food out and let it come. Not coming? Try another one.
            nav.stop(bot);
            brain.controls().lookAt(bot, a);
            this.waiting = true;
            if (++this.waitedIn > 20 * 12) {
                this.waitedIn = 0;
                this.leading = null;
            }
        }
        return Status.RUNNING;
    }

    /** Feed the two in the pen: they breed, and there's a baby. */
    private Status breed(BotPlayer bot, BotBrain brain) {
        Kind k = this.kind;
        BlockPos c = this.centre;
        if (k == null || c == null) return Status.FAILED;
        if (++this.breedTicks > 20 * 60) return finish(bot, brain, c);
        var animals = penned(bot);
        Animal hungry = null;
        for (Animal a : animals) {
            if (a.canFallInLove() && a.getAge() == 0) {
                hungry = a;
                break;
            }
        }
        if (hungry == null || Inv.count(bot, k.food) == 0) return finish(bot, brain, c);
        if (hungry.distanceToSqr(bot) > 3 * 3) {
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) nav.moveNear(bot, hungry.position(), 2);
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);
        Inv.hold(bot, k.food);
        if (this.breedTicks % 10 == 0) Placer.interact(bot, hungry);
        return Status.RUNNING;
    }

    /** Shut the pen's gate if it's open and in reach (the animals are in). */
    private void closeGate(BotPlayer bot) {
        BlockPos c = this.centre;
        if (c == null) return;
        BlockPos gate = Farms.fence(c, this.gateSide).getFirst();
        var s = bot.level().getBlockState(gate);
        if (s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                && s.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN)
                && net.joinedthegame.ai.skill.BlockBreaker.inReach(bot, gate)) {
            Placer.use(bot, gate);
        }
    }

    private Status finish(BotPlayer bot, BotBrain brain, BlockPos c) {
        brain.navigator().leaveDoorsOpen = false;
        closeGate(bot);
        GlobalPos where = GlobalPos.of(bot.level().dimension(), c);
        brain.remember(BotMemory.EventType.BUILT, "an animal pen", where, 0.7f);
        brain.rememberPlace(bot, "pen", c);
        brain.log("pen: done, with " + penned(bot).size() + " " + (this.kind == null ? "animals" : this.kind.label));
        this.stage = Stage.DONE;
        return Status.DONE;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().leaveDoorsOpen = false;
        brain.navigator().stop(bot);
    }
}
