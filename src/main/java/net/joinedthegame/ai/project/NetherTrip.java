package net.joinedthegame.ai.project;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.Structures;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.BuildTask.Step;
import net.joinedthegame.ai.task.CollectItems;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.ai.task.TravelTask;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A trip to the nether, start to finish: get ready, get obsidian (the way players do: water on
 * a lava pool), build and light a portal, go through, mine quartz, find a fortress and fight
 * blazes for rods, then come home through the same portal. Both portals are remembered (sticky
 * places), so the way home is never forgotten.
 */
public final class NetherTrip implements Task {
    public static final int RODS_WANTED = 6;
    static final int OBSIDIAN_NEEDED = 10;

    private enum Stage { PREP, OBSIDIAN, PORTAL, ENTER, NETHER, RETURN, DONE }

    private Stage stage = Stage.PREP;
    private @Nullable Task sub;
    private final java.util.Set<String> skipped = new java.util.HashSet<>();
    private Tasks.@Nullable Need need;
    private int rounds;
    private int ticks;
    private long enteredNetherAt;
    private int netherStep;
    private @Nullable BlockPos portalFront;
    private @Nullable BlockPos portalCell;
    private int enterTicks;
    private String note = "";
    private boolean triedHome;

    @Override
    public String activity() {
        if (this.sub != null) return this.sub.activity();
        return switch (this.stage) {
            case PREP -> "Getting ready for the nether";
            case OBSIDIAN -> "Getting obsidian";
            case PORTAL -> "Building a nether portal";
            case ENTER -> "Going through the portal";
            case NETHER -> "In the nether" + this.note;
            case RETURN -> "Heading home from the nether";
            case DONE -> "Back from the nether";
        };
    }

    public static boolean inNether(BotPlayer bot) {
        return bot.level().dimension() == Level.NETHER;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        ++this.ticks;
        // Already over there (a restart, or we just came through): pick up from the right place.
        if (inNether(bot) && this.stage.ordinal() < Stage.NETHER.ordinal()) {
            this.stage = Stage.NETHER;
            this.sub = null;
            arrived(bot, brain);
        }
        if (!inNether(bot) && (this.stage == Stage.NETHER || this.stage == Stage.RETURN)) {
            brain.remember(BotMemory.EventType.VISITED, "the nether", null, 0.8f);
            this.stage = Stage.DONE;
        }
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            Task finished = this.sub;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (s == Status.FAILED && this.stage == Stage.PREP) {
                Tasks.Need n = this.need;
                if (n != null && n.optional()) this.skipped.add(n.label());
                else if (++this.rounds > 30) return Status.FAILED;
            }
            if (s == Status.FAILED && this.stage == Stage.PORTAL) return Status.FAILED;
            if (s == Status.FAILED && this.stage == Stage.OBSIDIAN && finished instanceof ObsidianTask) return Status.FAILED;
            return Status.RUNNING;
        }
        return switch (this.stage) {
            case PREP -> prep(bot, brain);
            case OBSIDIAN -> obsidian(bot, brain);
            case PORTAL -> portal(bot, brain);
            case ENTER -> enter(bot, brain, Level.NETHER);
            case NETHER -> nether(bot, brain);
            case RETURN -> goHome(bot, brain);
            case DONE -> Status.DONE;
        };
    }

    // --- getting ready ------------------------------------------------------------------

    private List<Tasks.Need> needs(BotPlayer bot, BotBrain brain) {
        List<Tasks.Need> needs = new ArrayList<>();
        needs.add(Tasks.Need.of("flint and steel", s -> s.is(Items.FLINT_AND_STEEL), 1, b -> Items.FLINT_AND_STEEL));
        needs.add(Tasks.Need.of("building blocks", Inv::isPlaceableBlock, 48, b -> Items.COBBLESTONE));
        needs.add(Tasks.Need.nice("a gold helmet", s -> s.is(Items.GOLDEN_HELMET), 1, b -> Items.GOLDEN_HELMET));
        if (knownPortal(bot, brain) == null && Inv.count(bot, Items.OBSIDIAN) < OBSIDIAN_NEEDED) {
            needs.add(Tasks.Need.of("a water bucket", s -> s.is(Items.WATER_BUCKET), 1, b -> Items.BUCKET));
        }
        return needs;
    }

    private Status prep(BotPlayer bot, BotBrain brain) {
        if (++this.rounds > 40) return Status.FAILED;
        this.need = null;
        for (Tasks.Need n : needs(bot, brain)) {
            if (this.skipped.contains(n.label()) || n.missing(bot) <= 0) continue;
            // An empty bucket for the lava: fill it.
            if (n.label().equals("a water bucket") && Inv.count(bot, Items.BUCKET) > 0) {
                this.need = n;
                this.sub = new FarmProject.FillBucket();
                return Status.RUNNING;
            }
            this.need = n;
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
                brain.log("nether: can't get " + n.label());
                return Status.FAILED;
            }
        }
        this.need = null;
        GlobalPos portal = knownPortal(bot, brain);
        if (portal != null) {
            this.portalCell = portal.pos();
            this.stage = Stage.ENTER;
        } else {
            this.stage = Inv.count(bot, Items.OBSIDIAN) >= OBSIDIAN_NEEDED ? Stage.PORTAL : Stage.OBSIDIAN;
        }
        return Status.RUNNING;
    }

    /** A lit portal we know of on this side, not too far off. */
    private static @Nullable GlobalPos knownPortal(BotPlayer bot, BotBrain brain) {
        GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        GlobalPos portal = brain.memory().nearest("portal", here, 400);
        if (portal == null) return null;
        if (bot.level().isLoaded(portal.pos()) && !bot.level().getBlockState(portal.pos()).is(Blocks.NETHER_PORTAL)) {
            brain.memory().forget("portal", portal); // broken or gone out
            return null;
        }
        return portal;
    }

    // --- obsidian -----------------------------------------------------------------------

    private Status obsidian(BotPlayer bot, BotBrain brain) {
        if (Inv.count(bot, Items.OBSIDIAN) >= OBSIDIAN_NEEDED) {
            this.stage = Stage.PORTAL;
            return Status.RUNNING;
        }
        this.sub = new ObsidianTask(OBSIDIAN_NEEDED);
        return Status.RUNNING;
    }

    /**
     * No lava nearby: head down to where lava pools are (and natural obsidian, which gets mined
     * on the way if we come across it) and tunnel about until a pool turns up.
     */
    static final class LookForLava implements Task {
        private MineTask mine;
        private final int obsidian;
        private int ticks;

        LookForLava(BotPlayer bot, int obsidian) {
            this.obsidian = obsidian;
            this.mine = new MineTask(bot, Sources.OBSIDIAN, obsidian, "Looking for lava");
        }

        @Override
        public String activity() {
            return "Looking for lava";
        }

        @Override
        public Status tick(BotPlayer bot, BotBrain brain) {
            if (++this.ticks % 40 == 0 && !brain.breaker().busy()) {
                WorldView w = new WorldView(bot.level());
                if (Senses.findBlock(bot, s -> s.getFluidState().isSourceOfType(Fluids.LAVA), 24, 10, false,
                        p -> brain.isBlacklisted(p) || CastObsidian.openSideOf(w, p) == null) != null) {
                    brain.log("nether: found lava to make obsidian from");
                    return Status.DONE;
                }
            }
            Status s = this.mine.tick(bot, brain);
            // The mining search gives up after a while of finding nothing; lava takes longer
            // to come across than ore, so keep tunnelling (a fresh direction) for a good while.
            if (s == Status.FAILED && this.ticks < 20 * 60 * 6) {
                this.mine.stop(bot, brain);
                this.mine = new MineTask(bot, Sources.OBSIDIAN, this.obsidian, "Looking for lava");
                return Status.RUNNING;
            }
            return s;
        }

        @Override
        public void stop(BotPlayer bot, BotBrain brain) {
            this.mine.stop(bot, brain);
        }
    }

    /**
     * Turn one lava source into obsidian and mine it: pour water next to it (it flows in and
     * the lava sets), scoop the water back up, dig out the obsidian.
     */
    static final class CastObsidian implements Task {
        private @Nullable BlockPos lava;
        private @Nullable BlockPos spot;
        private int ticks;
        private int phase;
        private int missed;
        /** Failed because there's no lava around at all (so go and look, rather than retry). */
        boolean noLava;
        private @Nullable Task dig;

        @Override
        public String activity() {
            return "Making obsidian";
        }

        @Override
        public Status tick(BotPlayer bot, BotBrain brain) {
            if (++this.ticks > 20 * 90) {
                if (this.lava != null) brain.blacklist(this.lava); // don't come straight back to the same pool
                return Status.FAILED;
            }
            var level = bot.level();
            if (this.dig != null) {
                Status s = this.dig.tick(bot, brain);
                if (s == Status.RUNNING) return Status.RUNNING;
                this.dig = null;
                return Status.DONE;
            }
            if (this.lava == null && !find(bot, brain)) {
                brain.log("nether: no lava pool nearby to make obsidian from");
                this.noLava = true;
                return Status.FAILED;
            }
            BlockPos lava = this.lava;
            BlockPos spot = this.spot;
            // Set: scoop the water back and dig the obsidian out.
            if (level.getBlockState(lava).is(Blocks.OBSIDIAN)) {
                if (level.getFluidState(spot).isSourceOfType(Fluids.WATER) && Inv.count(bot, Items.BUCKET) > 0) {
                    if (this.ticks % 10 == 0) Placer.useAimedAt(bot, Vec3.atCenterOf(spot).add(0, 0.3, 0), s -> s.is(Items.BUCKET));
                    return Status.RUNNING;
                }
                this.dig = Tasks.sequence(new BuildTask(List.of(Step.digEssential(lava)), "Mining obsidian"),
                        new CollectItems(lava, 4, 80));
                return Status.RUNNING;
            }
            if (!level.getFluidState(lava).isSourceOfType(Fluids.LAVA)) {
                brain.blacklist(lava);
                this.lava = null;
                return Status.RUNNING;
            }
            // Get properly close to the pouring spot (a bucket's reach is short) with a clear view
            // of its floor, without standing in it. Pours that keep missing: get closer still.
            Vec3 pour = Vec3.atBottomCenterOf(spot).add(0, 0.05, 0);
            double close = this.missed >= 3 ? 2.5 : 3.5;
            boolean ready = bot.getEyePosition().distanceTo(pour) <= close && !bot.blockPosition().equals(spot)
                    && BlockBreaker.canSee(bot, bot.getEyePosition(), spot.below());
            if (!ready) {
                Navigator nav = brain.navigator();
                if (!nav.isMoving()) {
                    BlockPos target = spot;
                    nav.moveTo(bot, Vec3.atCenterOf(target), p -> {
                        if (p.equals(target) || p.equals(lava)) return false;
                        Vec3 eye = Vec3.atBottomCenterOf(p).add(0, 1.62, 0);
                        return eye.distanceTo(pour) <= close - 0.3 && BlockBreaker.canSee(bot, eye, target.below());
                    });
                    if (nav.status() == Navigator.Status.FAILED) {
                        brain.blacklist(lava);
                        this.lava = null;
                        nav.stop(bot);
                    }
                }
                return Status.RUNNING;
            }
            brain.navigator().stop(bot);
            if (this.ticks % 10 == 0) {
                if (Inv.count(bot, Items.WATER_BUCKET) > 0) {
                    // Pour onto the floor of the spot next to the lava; it runs straight in.
                    if (!Placer.useAimedAt(bot, pour, s -> s.is(Items.WATER_BUCKET))) this.missed++;
                } else if (level.getFluidState(spot).isSourceOfType(Fluids.WATER) && Inv.count(bot, Items.BUCKET) > 0) {
                    // Poured, but the lava didn't set: get the water back and try somewhere else.
                    Placer.useAimedAt(bot, Vec3.atCenterOf(spot).add(0, 0.3, 0), s -> s.is(Items.BUCKET));
                    brain.blacklist(lava);
                    this.lava = null;
                } else {
                    return Status.FAILED; // water's gone somewhere: go and fill the bucket again
                }
                if (this.missed >= 8) { // can't land it from anywhere we can stand
                    brain.blacklist(lava);
                    this.lava = null;
                    this.missed = 0;
                }
            }
            return Status.RUNNING;
        }

        /** A lava source with an open, floored spot beside it to pour water on. */
        private boolean find(BotPlayer bot, BotBrain brain) {
            WorldView w = new WorldView(bot.level());
            BlockPos found = Senses.findBlock(bot, s -> s.getFluidState().isSourceOfType(Fluids.LAVA), 32, 12, false,
                    p -> brain.isBlacklisted(p) || openSideOf(w, p) == null);
            if (found == null) return false;
            this.lava = found;
            this.spot = openSideOf(w, found);
            return true;
        }

        static @Nullable BlockPos openSideOf(WorldView w, BlockPos lava) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = lava.relative(d);
                if (w.state(side).isAir() && w.solidFloor(side.below()) && w.state(side.above()).isAir()) return side;
            }
            return null;
        }

        @Override
        public void stop(BotPlayer bot, BotBrain brain) {
            if (this.dig != null) this.dig.stop(bot, brain);
            brain.navigator().stop(bot);
        }
    }

    // --- the portal -------------------------------------------------------------------------

    private Status portal(BotPlayer bot, BotBrain brain) {
        if (this.portalCell != null && bot.level().getBlockState(this.portalCell).is(Blocks.NETHER_PORTAL)) {
            brain.rememberPlace(bot, "portal", this.portalCell);
            this.stage = Stage.ENTER;
            return Status.RUNNING;
        }
        if (this.portalCell != null && Inv.count(bot, Items.FLINT_AND_STEEL) > 0) {
            // Frame's up: light it from inside, on the bottom obsidian.
            BlockPos floor = this.portalCell.below();
            if (!BlockBreaker.inReach(bot, floor)) {
                Navigator nav = brain.navigator();
                if (!nav.isMoving()) nav.moveToReach(bot, floor);
                return Status.RUNNING;
            }
            brain.navigator().stop(bot);
            if (this.ticks % 10 == 0) Placer.useOn(bot, floor, Direction.UP, s -> s.is(Items.FLINT_AND_STEEL));
            if (this.ticks % 200 == 0) return Status.FAILED; // won't light: frame must be wrong
            return Status.RUNNING;
        }
        // A portal belongs near home (that's where you'll come back to), unless there's no room.
        GlobalPos home = brain.record().home();
        if (!this.triedHome && home != null && home.dimension() == bot.level().dimension()
                && home.pos().distSqr(bot.blockPosition()) > 20 * 20) {
            this.triedHome = true;
            this.sub = new TravelTask(home.pos(), 8, "Heading home to build a portal");
            return Status.RUNNING;
        }
        // Somewhere flat to put it up.
        PortalSite site = findSite(bot);
        if (site == null) {
            brain.log("nether: nowhere to build a portal");
            return Status.FAILED;
        }
        this.portalCell = site.at(1, 1);
        this.portalFront = site.front();
        this.sub = new BuildTask(site.steps(), "Building a nether portal");
        return Status.RUNNING;
    }

    /** A 4-wide, 5-tall portal frame. {@code origin} is the bottom-left corner, along {@code right}. */
    record PortalSite(BlockPos origin, Direction right, Direction facing) {
        BlockPos at(int x, int y) {
            return this.origin.relative(this.right, x).above(y);
        }

        BlockPos front() {
            return at(1, 0).relative(this.facing.getOpposite());
        }

        List<Step> steps() {
            List<Step> steps = new ArrayList<>();
            // Clear the inside first.
            for (int x = 1; x <= 2; x++) for (int y = 1; y <= 3; y++) steps.add(Step.dig(at(x, y)));
            // Corners can be anything; obsidian everywhere else. Bottom up so it all has support.
            steps.add(Step.place(at(0, 0), Inv::isPlaceableBlock));
            steps.add(Step.place(at(3, 0), Inv::isPlaceableBlock));
            steps.add(Step.placeEssential(at(1, 0), s -> s.is(Items.OBSIDIAN), null));
            steps.add(Step.placeEssential(at(2, 0), s -> s.is(Items.OBSIDIAN), null));
            for (int y = 1; y <= 3; y++) {
                steps.add(Step.placeEssential(at(0, y), s -> s.is(Items.OBSIDIAN), null));
                steps.add(Step.placeEssential(at(3, y), s -> s.is(Items.OBSIDIAN), null));
            }
            steps.add(Step.place(at(0, 4), Inv::isPlaceableBlock));
            steps.add(Step.place(at(3, 4), Inv::isPlaceableBlock));
            steps.add(Step.placeEssential(at(1, 4), s -> s.is(Items.OBSIDIAN), null));
            steps.add(Step.placeEssential(at(2, 4), s -> s.is(Items.OBSIDIAN), null));
            return steps;
        }
    }

    private static @Nullable PortalSite findSite(BotPlayer bot) {
        WorldView w = new WorldView(bot.level());
        BlockPos origin = bot.blockPosition();
        for (int r = 2; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                    BlockPos base = new BlockPos(x, bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    for (Direction right : new Direction[]{Direction.EAST, Direction.SOUTH}) {
                        PortalSite site = new PortalSite(base, right, right.getClockWise());
                        if (fits(w, site)) return site;
                    }
                }
            }
        }
        return null;
    }

    private static boolean fits(WorldView w, PortalSite site) {
        for (int x = 0; x <= 3; x++) {
            if (!w.solidFloor(site.at(x, 0).below())) return false;
            for (int y = 0; y <= 4; y++) {
                var s = w.state(site.at(x, y));
                if (!s.canBeReplaced() || !s.getFluidState().isEmpty()) return false;
            }
        }
        return w.standable(site.front());
    }

    // --- going through ---------------------------------------------------------------------

    /** Walk up to the portal and step in; stand there till the world changes. */
    private Status enter(BotPlayer bot, BotBrain brain, net.minecraft.resources.ResourceKey<Level> to) {
        if (bot.level().dimension() == to) return Status.RUNNING; // handled at the top of tick()
        BlockPos cell = this.portalCell;
        // Still a walk away: go there first (in hops); the clock only starts once we're close.
        if (cell != null && cell.distSqr(bot.blockPosition()) > 8 * 8) {
            this.enterTicks = 0;
            this.sub = new TravelTask(cell, 4, "Going to the portal");
            return Status.RUNNING;
        }
        if (++this.enterTicks > 20 * 60) return Status.FAILED;
        if (cell == null || !bot.level().getBlockState(cell).is(Blocks.NETHER_PORTAL)) {
            // Find the portal block near the remembered spot (the frame might face either way).
            BlockPos near = Senses.findBlock(bot, s -> s.is(Blocks.NETHER_PORTAL), 6, 4, false, p -> false);
            if (near == null) {
                if (cell != null && cell.distSqr(bot.blockPosition()) > 6 * 6) {
                    if (this.sub == null) this.sub = new TravelTask(cell, 3, "Going to the portal");
                    return Status.RUNNING;
                }
                return Status.FAILED;
            }
            this.portalCell = cell = near;
        }
        brain.rememberPlace(bot, "portal", cell);
        // Plan a path that ends inside the portal (pathfinding normally steers clear of them),
        // so steps, pillars and frames at odd heights are handled like any other path.
        boolean inPortal = bot.level().getBlockState(bot.blockPosition()).is(Blocks.NETHER_PORTAL);
        if (inPortal) {
            brain.navigator().stop(bot);
            return Status.RUNNING; // stand still and let it take us
        }
        Navigator nav = brain.navigator();
        if (!nav.isMoving()) {
            BlockPos target = cell;
            WorldView.portalsOk = true;
            try {
                nav.moveTo(bot, Vec3.atBottomCenterOf(target), p -> bot.level().getBlockState(p).is(Blocks.NETHER_PORTAL));
            } finally {
                WorldView.portalsOk = false;
            }
            if (nav.status() != Navigator.Status.FAILED) return Status.RUNNING;
            nav.stop(bot);
        } else {
            return Status.RUNNING;
        }
        brain.controls().faceTowards(bot, Vec3.atBottomCenterOf(cell));
        if (!bot.blockPosition().equals(cell)) {
            brain.controls().forward = 0.6f;
            // The frame's bottom edge is a step up: hop in like anyone would.
            if (bot.onGround() && (cell.getY() > Mth.floor(bot.getY() + 0.01) || bot.horizontalCollision)) brain.controls().jump = true;
        }
        return Status.RUNNING;
    }

    // --- in the nether -------------------------------------------------------------------------

    private void arrived(BotPlayer bot, BotBrain brain) {
        this.enteredNetherAt = brain.now();
        this.netherStep = 0;
        BlockPos portal = Senses.findBlock(bot, s -> s.is(Blocks.NETHER_PORTAL), 4, 4, false, p -> false);
        if (portal != null) {
            this.portalCell = portal;
            brain.rememberPlace(bot, "portal", portal);
        }
        brain.log("made it to the nether");
    }

    private Status nether(BotPlayer bot, BotBrain brain) {
        long time = brain.now() - this.enteredNetherAt;
        boolean timeUp = time > 20 * 60 * 12;
        boolean bagFull = Inv.usedSlots(bot) >= Inv.MAIN_SLOTS - 2;
        // Beaten up: go home and heal rather than push our luck in a fortress.
        boolean hurtAndHungry = bot.getHealth() < 10 || bot.getHealth() < 14 && Inv.bestFood(bot, false) < 0;
        if (timeUp || bagFull || hurtAndHungry || Inv.count(bot, Items.BLAZE_ROD) >= RODS_WANTED && this.netherStep >= 5) {
            this.stage = Stage.RETURN;
            return Status.RUNNING;
        }
        // Out of food over here and getting hungry: hoglins are pork, if you're careful.
        if (Inv.bestFood(bot, false) < 0 && bot.getFoodData().getFoodLevel() <= 12) {
            var hoglin = Senses.entitiesNear(bot, net.minecraft.world.entity.monster.hoglin.Hoglin.class, 32,
                    h -> h.isAlive() && !h.isBaby());
            if (!hoglin.isEmpty()) {
                this.note = ", hunting a hoglin for food";
                this.sub = new HuntHoglin(hoglin.getFirst());
                return Status.RUNNING;
            }
        }
        switch (this.netherStep) {
            case 0 -> {
                // Quartz first: it's everywhere and it's XP.
                this.note = ", mining quartz";
                this.netherStep = 1;
                this.sub = new MineTask(bot, Sources.QUARTZ, 12, "Mining nether quartz");
            }
            case 1, 2, 3 -> {
                // Look about a bit first: new places, new structures (they're noticed on the way).
                this.netherStep++;
                GlobalPos seen = brain.memory().nearest(Structures.Kind.FORTRESS.placeKey(),
                        GlobalPos.of(bot.level().dimension(), bot.blockPosition()), 600);
                if (seen == null || this.netherStep <= 2) {
                    this.note = ", exploring";
                    this.sub = explore(bot);
                    return Status.RUNNING;
                }
            }
            case 4, 5 -> {
                // Blaze rods: a fortress we know of, or go and find one.
                this.netherStep = 5;
                GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
                GlobalPos fortress = brain.memory().nearest(Structures.Kind.FORTRESS.placeKey(), here, 600);
                if (fortress == null) {
                    this.note = ", looking for a fortress";
                    this.sub = explore(bot);
                } else if (fortress.pos().distSqr(bot.blockPosition()) > 40 * 40) {
                    this.note = ", heading to a fortress";
                    this.sub = new TravelTask(fortress.pos(), 24, "Heading to a nether fortress");
                } else {
                    this.note = ", hunting blazes";
                    this.sub = new HuntBlazes();
                }
            }
            default -> this.stage = Stage.RETURN;
        }
        return Status.RUNNING;
    }

    /** Head off in some direction through the nether, staying at portal height-ish. */
    private static Task explore(BotPlayer bot) {
        double angle = bot.getRandom().nextDouble() * Math.PI * 2;
        int x = Mth.floor(bot.getX() + Math.cos(angle) * 80);
        int z = Mth.floor(bot.getZ() + Math.sin(angle) * 80);
        return new TravelTask(new BlockPos(x, bot.getBlockY(), z), 10, "Exploring the nether");
    }

    /** Hang about near a blaze spawner and let the fight come to us (CombatGoal does the fighting). */
    static final class HuntBlazes implements Task {
        private @Nullable BlockPos spawner;
        private int ticks;

        @Override
        public String activity() {
            return "Hunting blazes";
        }

        @Override
        public Status tick(BotPlayer bot, BotBrain brain) {
            if (Inv.count(bot, Items.BLAZE_ROD) >= RODS_WANTED) return Status.DONE;
            if (++this.ticks > 20 * 60 * 5) return Status.DONE;
            if (this.spawner == null) {
                this.spawner = Senses.findBlock(bot, s -> s.is(Blocks.SPAWNER), 32, 16, false, p -> false);
                if (this.spawner == null) {
                    // No spawner in sight: wander the fortress a bit and look again.
                    if (!brain.navigator().isMoving()) {
                        double a = bot.getRandom().nextDouble() * Math.PI * 2;
                        brain.navigator().moveNear(bot, bot.position().add(Math.cos(a) * 12, 0, Math.sin(a) * 12), 3);
                    }
                    return Status.RUNNING;
                }
            }
            // Close enough to wake the spawner (16 blocks), far enough that they come a few at a
            // time and we can shoot them on the way in.
            if (bot.blockPosition().distSqr(this.spawner) > 14 * 14) {
                Navigator nav = brain.navigator();
                if (!nav.isMoving()) {
                    nav.moveNear(bot, Vec3.atCenterOf(this.spawner), 12);
                    if (nav.status() == Navigator.Status.FAILED) {
                        nav.stop(bot);
                        this.spawner = null;
                    }
                }
            }
            return Status.RUNNING;
        }

        @Override
        public void stop(BotPlayer bot, BotBrain brain) {
            brain.navigator().stop(bot);
        }
    }

    /** Last-resort food: a hoglin. Hit it, back off when it charges, pick up the pork. */
    static final class HuntHoglin implements Task {
        private final net.minecraft.world.entity.monster.hoglin.Hoglin hoglin;
        private int ticks;

        HuntHoglin(net.minecraft.world.entity.monster.hoglin.Hoglin hoglin) {
            this.hoglin = hoglin;
        }

        @Override
        public String activity() {
            return "Hunting a hoglin";
        }

        @Override
        public Status tick(BotPlayer bot, BotBrain brain) {
            if (++this.ticks > 20 * 60) return Status.FAILED;
            if (!this.hoglin.isAlive()) {
                return this.ticks > 20 * 60 - 1 ? Status.DONE : collect(bot, brain);
            }
            double dist = bot.distanceTo(this.hoglin);
            brain.controls().lookAt(bot, this.hoglin);
            if (dist > 3) {
                Navigator nav = brain.navigator();
                if (!nav.isMoving() || this.ticks % 15 == 0) nav.moveNear(bot, this.hoglin.position(), 2);
                return Status.RUNNING;
            }
            brain.navigator().stop(bot);
            if (Inv.bestWeapon(bot) >= 0) Inv.hold(bot, Inv.bestWeapon(bot));
            if (bot.getAttackStrengthScale(0.5f) > 0.9f) {
                bot.attack(this.hoglin);
                bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            } else {
                brain.controls().forward = -0.6f; // step back while it winds up
            }
            return Status.RUNNING;
        }

        private @Nullable CollectItems pickup;

        private Status collect(BotPlayer bot, BotBrain brain) {
            if (this.pickup == null) this.pickup = new CollectItems(this.hoglin.blockPosition(), 6, 80);
            return this.pickup.tick(bot, brain);
        }

        @Override
        public void stop(BotPlayer bot, BotBrain brain) {
            brain.navigator().stop(bot);
        }
    }

    // --- home -----------------------------------------------------------------------------

    private Status goHome(BotPlayer bot, BotBrain brain) {
        this.note = "";
        GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        GlobalPos portal = brain.memory().nearest("portal", here, 2000);
        if (portal == null) {
            brain.log("nether: lost the way home!");
            return Status.FAILED;
        }
        this.portalCell = portal.pos();
        if (portal.pos().distSqr(bot.blockPosition()) > 8 * 8) {
            this.sub = new TravelTask(portal.pos(), 4, "Heading back to the portal");
            return Status.RUNNING;
        }
        return enter(bot, brain, Level.OVERWORLD);
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }
}
