package net.joinedthegame.ai.goal;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Personality;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.FishTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Taking it easy (the brain's LEISURE focus): real people don't work every waking minute. A few
 * minutes of something that isn't progress: fishing, sitting by the campfire at home, climbing
 * to a high spot to take in the view, or a stroll round its own place (the garden, the farm, the
 * pen). Which, depends on the bot and what it's got.
 */
public final class LeisureGoal extends Goal {
    private enum Pastime { FISH, CAMPFIRE, VIEW, STROLL }

    private @Nullable Pastime pastime;
    private @Nullable Task task;
    private @Nullable BlockPos spot;
    private final List<BlockPos> stops = new ArrayList<>();
    private int ticks;
    private int pause;
    private int craftSteps;

    public LeisureGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (this.brain.focus() != BotBrain.Focus.LEISURE) return 0;
        // Night out in the open isn't the time (unless it's a campfire at home, by the house).
        if (bot.level().isDarkOutside() && this.pastime != Pastime.CAMPFIRE) return 0;
        // Hurt, just been shot at, something hostile about, or its stuff lying where it died: not now.
        if (!this.brain.safeToRelax(bot)) return 0;
        return 44;
    }

    @Override
    public void onOwnerDeath(BotPlayer bot) {
        // Whatever it was off to enjoy, that's over: no walking back to the same hilltop afterwards.
        this.pastime = null;
        this.task = null;
        this.spot = null;
        this.stops.clear();
        this.pause = 0;
    }

    @Override
    public void start(BotPlayer bot) {
        if (this.pastime == null) {
            // Lured away by a lake it saw: that's what it's here for.
            boolean lake = this.brain.takeFishingSpot() != null
                    && (Inv.count(bot, Items.FISHING_ROD) > 0 || Inv.count(bot, Items.STRING) >= 2);
            this.pastime = lake ? Pastime.FISH : choose(bot);
            this.ticks = 0;
            this.stops.clear();
            this.spot = null;
            this.task = null;
            this.craftSteps = 0;
            this.brain.log("taking it easy: " + this.pastime.name().toLowerCase(java.util.Locale.ROOT));
            // Partners do these things together: ask them along, if they're about.
            java.util.UUID partner = this.brain.partner();
            var server = bot.level().getServer();
            if (partner != null && server != null && server.getPlayerList().getPlayer(partner) instanceof BotPlayer p
                    && p.level() == bot.level() && p.distanceToSqr(bot) < 64 * 64) {
                var theirs = net.joinedthegame.bot.BotManager.get(server).brain(partner);
                if (theirs != null) theirs.invitedBy(p, bot.getUUID());
            } else if (partner != null) {
                this.brain.log("my partner's not about to come along");
            }
        }
    }

    private Pastime choose(BotPlayer bot) {
        Personality p = this.brain.personality();
        boolean home = this.brain.record().home() != null;
        Map<Pastime, Double> w = new EnumMap<>(Pastime.class);
        boolean canFish = Inv.count(bot, Items.FISHING_ROD) > 0 || Inv.count(bot, Items.STRING) >= 2;
        if (canFish) w.put(Pastime.FISH, 0.7 + p.homebody() * 0.3 + (1 - p.reckless()) * 0.2);
        if (home) {
            double fire = 0.4 + p.social() * 0.3 + p.homebody() * 0.4;
            // Evening's the time for it.
            long t = bot.level().getOverworldClockTime() % 24000;
            if (t > 11000 && t < 13500) fire *= 2.5;
            w.put(Pastime.CAMPFIRE, fire);
            w.put(Pastime.STROLL, 0.4 + p.homebody() * 0.5 + p.animalLover() * 0.2);
        }
        w.put(Pastime.VIEW, 0.3 + p.explorer() * 0.7);
        double total = w.values().stream().mapToDouble(Double::doubleValue).sum();
        double roll = bot.getRandom().nextDouble() * total;
        for (var e : w.entrySet()) if ((roll -= e.getValue()) < 0) return e.getKey();
        return Pastime.VIEW;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        Pastime what = this.pastime;
        if (what == null) return false;
        ++this.ticks;
        if (this.task != null) {
            Task.Status s = this.task.tick(bot, this.brain);
            if (s == Task.Status.RUNNING) return true;
            this.task.stop(bot, this.brain);
            this.task = null;
            if (s == Task.Status.FAILED && what == Pastime.FISH && Inv.count(bot, Items.FISHING_ROD) == 0 && this.craftSteps >= 6) return done(bot, false);
            if (what == Pastime.FISH && Inv.count(bot, Items.FISHING_ROD) > 0 && s != Task.Status.FAILED) return done(bot, true);
            if (what == Pastime.FISH && s == Task.Status.FAILED && Inv.count(bot, Items.FISHING_ROD) > 0) return done(bot, false);
        }
        return switch (what) {
            case FISH -> fish(bot);
            case CAMPFIRE -> campfire(bot);
            case VIEW -> view(bot);
            case STROLL -> stroll(bot);
        };
    }

    private boolean fish(BotPlayer bot) {
        if (Inv.count(bot, Items.FISHING_ROD) == 0) {
            // A rod first: three sticks and two string.
            if (this.craftSteps++ >= 6) return done(bot, false);
            boolean[] failed = {false};
            this.task = Tasks.toward(bot, Tasks.Need.of("a fishing rod", s -> s.is(Items.FISHING_ROD), 1, b -> Items.FISHING_ROD), failed);
            if (failed[0] || this.task == null) return done(bot, false);
            return true;
        }
        this.task = new FishTask(3 + bot.getRandom().nextInt(4));
        return true;
    }

    /** A campfire by the house (made the first time), then a while sat by it. */
    private boolean campfire(BotPlayer bot) {
        GlobalPos home = this.brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return done(bot, false);
        if (this.spot == null) {
            GlobalPos known = this.brain.memory().nearest("campfire", home, 16);
            if (known != null && bot.level().getBlockState(known.pos()).is(Blocks.CAMPFIRE)) {
                this.spot = known.pos();
            } else {
                if (Inv.count(bot, Items.CAMPFIRE) == 0) {
                    if (this.craftSteps++ >= 8) return done(bot, false);
                    boolean[] failed = {false};
                    this.task = Tasks.toward(bot, Tasks.Need.of("a campfire", s -> s.is(Items.CAMPFIRE), 1, b -> Items.CAMPFIRE), failed);
                    if (failed[0] || this.task == null) return done(bot, false);
                    return true;
                }
                BlockPos at = fireSpot(bot, home.pos());
                if (at == null) return done(bot, false);
                this.spot = at;
                this.task = new BuildTask(List.of(BuildTask.Step.place(at, s -> s.is(Items.CAMPFIRE))), "Making a campfire");
                this.brain.rememberPlace(bot, "campfire", at);
                return true;
            }
        }
        return sitBy(bot, this.spot, 20 * 60 * 2);
    }

    /** Open ground 3-5 blocks from home, not inside anything. */
    private static @Nullable BlockPos fireSpot(BotPlayer bot, BlockPos home) {
        WorldView w = new WorldView(bot.level());
        for (int r = 3; r <= 6; r++) {
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6;
                int x = home.getX() + (int) Math.round(Math.cos(a) * r);
                int z = home.getZ() + (int) Math.round(Math.sin(a) * r);
                int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos p = new BlockPos(x, y, z);
                if (w.state(p).isAir() && w.solidFloor(p.below()) && bot.level().canSeeSky(p) && w.state(p.above()).isAir()) return p;
            }
        }
        return null;
    }

    /** Somewhere up high close by: walk up there and look round for a while. */
    private boolean view(BotPlayer bot) {
        if (this.spot == null) {
            BlockPos best = null;
            int bestY = bot.getBlockY() + 4;
            for (int i = 0; i < 40; i++) {
                int x = bot.getBlockX() + bot.getRandom().nextInt(65) - 32;
                int z = bot.getBlockZ() + bot.getRandom().nextInt(65) - 32;
                if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (y > bestY && bot.level().getBlockState(new BlockPos(x, y - 1, z)).getFluidState().isEmpty()
                        && !risky(new BlockPos(x, y, z))) {
                    best = new BlockPos(x, y, z);
                    bestY = y;
                }
            }
            if (best == null) return done(bot, false);
            this.spot = best;
        }
        return sitBy(bot, this.spot, 20 * 50);
    }

    /** Not a hilltop by where it died, or a place it's died before. */
    private boolean risky(BlockPos p) {
        GlobalPos death = this.brain.deathSpot();
        if (death != null && death.pos().distSqr(p) < 24 * 24) return true;
        return this.brain.memory().nearest("death_hotspot", GlobalPos.of(this.brain.dimension(), p), 24) != null;
    }

    /** A slow walk round its own place: the garden, the farm, the pen, the trees. */
    private boolean stroll(BotPlayer bot) {
        GlobalPos home = this.brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return done(bot, false);
        if (this.stops.isEmpty() && this.ticks < 5) {
            for (String kind : new String[]{"garden", "farm", "pen", "tree_farm", "cane_farm", "campfire"}) {
                GlobalPos g = this.brain.memory().nearest(kind, home, 64);
                if (g != null) this.stops.add(g.pos());
            }
            this.stops.add(home.pos());
            java.util.Collections.shuffle(this.stops, new java.util.Random(bot.getRandom().nextLong()));
        }
        if (this.stops.isEmpty()) return done(bot, true);
        Navigator nav = this.brain.navigator();
        BlockPos next = this.stops.getFirst();
        if (bot.position().distanceToSqr(Vec3.atCenterOf(next)) < 4 * 4) {
            nav.stop(bot);
            if (++this.pause > 20 * 6) { // a little look round, then on to the next
                this.pause = 0;
                this.stops.removeFirst();
            }
            return true;
        }
        nav.sprint = false;
        if (!nav.isMoving()) {
            nav.moveNear(bot, Vec3.atCenterOf(next), 3);
            if (nav.status() == Navigator.Status.FAILED) this.stops.removeFirst();
        }
        return this.ticks < 20 * 60 * 4 || done(bot, true);
    }

    /** Get there, then just be there a while, looking about. */
    private boolean sitBy(BotPlayer bot, BlockPos where, int forTicks) {
        Navigator nav = this.brain.navigator();
        if (bot.position().distanceToSqr(Vec3.atCenterOf(where)) > 3.5 * 3.5) {
            nav.sprint = false;
            if (!nav.isMoving()) {
                nav.moveNear(bot, Vec3.atCenterOf(where), 2.5);
                if (nav.status() == Navigator.Status.FAILED) return done(bot, false);
            }
            return this.ticks < 20 * 60 * 3 || done(bot, false);
        }
        nav.stop(bot);
        if (++this.pause > forTicks) return done(bot, true);
        // Look about slowly, now and then at the fire (or the view).
        if (this.pause % 80 == 0) {
            float yaw = bot.getYRot() + (bot.getRandom().nextFloat() - 0.5f) * 120f;
            this.brain.controls().face(yaw, (bot.getRandom().nextFloat() - 0.6f) * 30f);
        }
        return true;
    }

    private boolean done(BotPlayer bot, boolean enjoyed) {
        Pastime what = this.pastime;
        if (what != null && enjoyed) {
            this.brain.remember(BotMemory.EventType.RELAXED, what.name().toLowerCase(java.util.Locale.ROOT));
        }
        this.pastime = null;
        this.pause = 0;
        this.brain.focusDone(BotBrain.Focus.LEISURE);
        if (!enjoyed) this.brain.focusFailed(BotBrain.Focus.LEISURE);
        return false;
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        // Sat by the fire, taking in the view, waiting for a bite: on purpose.
        return this.pause > 0 || this.task instanceof FishTask fish && fish.waitingForABite();
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        if (this.task != null) this.task.stop(bot, this.brain);
        this.task = null;
    }

    @Override
    public String activity() {
        Pastime what = this.pastime;
        if (this.task != null) return this.task.activity();
        if (what == null) return "Taking it easy";
        return switch (what) {
            case FISH -> "Fishing";
            case CAMPFIRE -> "Sitting by the campfire";
            case VIEW -> "Taking in the view";
            case STROLL -> "Having a wander round my place";
        };
    }
}
