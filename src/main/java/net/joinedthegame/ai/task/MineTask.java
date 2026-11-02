package net.joinedthegame.ai.task;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Get N of a raw material: find the nearest visible block, walk over, dig it, grab the
 * drops, repeat. If nothing is in sight, wander (for trees) or dig a staircase down and
 * tunnel (for stone and ores) until something turns up.
 */
public final class MineTask implements Task {
    private final Sources source;
    private final int target;
    private final String label;

    private @Nullable BlockPos block;
    private @Nullable Task sub;
    private int searchCooldown;
    /** The breaker's count when we picked this block: it went up, so we're the ones who broke it. */
    private int brokenBefore;
    /** Trees spotted in the distance (logged once). */
    private @Nullable BlockPos seenTrees;
    private int searches;
    private int ticks;
    private @Nullable Vec3 tunnelGoal;
    private Direction tunnelDir;
    private int blockTicks;
    private @Nullable BlockPos aim;
    /** The current target is a side-grab (copper/coal), not what we came for. */
    private boolean bonus;
    private boolean bonusDiamond;
    /** Already tried moving for a clear view of the current target. */
    private boolean triedReposition;
    /** Places not to take from (our own farm, say). */
    private java.util.function.Predicate<BlockPos> avoid = p -> false;

    private int lastHave = -1;
    private int lastGain;
    private final int startHave;

    public MineTask(BotPlayer bot, Sources source, int count, String label) {
        this.source = source;
        this.startHave = Inv.count(bot, source.yields());
        this.target = this.startHave + count;
        this.label = label;
        this.tunnelDir = Direction.Plane.HORIZONTAL.getRandomDirection(bot.getRandom());
    }

    public MineTask avoiding(java.util.function.Predicate<BlockPos> avoid) {
        this.avoid = avoid;
        return this;
    }

    @Override
    public String activity() {
        return this.label;
    }

    /** How many it set out to get. */
    public int wanted() {
        return this.target - this.startHave;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        int have = Inv.count(bot, this.source.yields());
        if (have >= this.target) return Status.DONE;
        // The pickaxe broke half way: stop, rather than carry on "mining iron" with bare hands.
        // (The plan gets a new pickaxe made first, from the cobble and the spare sticks.)
        if (Inv.pickaxeTier(bot) < this.source.pickTier()) {
            brain.log("no pickaxe good enough for " + this.source.label() + " any more: stopping");
            this.toolBroke = true;
            return have > this.startHave ? Status.DONE : Status.FAILED;
        }
        // Going for a proper haul takes a while: keep at it as long as it's turning stuff up.
        // Three minutes without a single one, or twelve all told, and that's enough (diamonds
        // are rarer: five minutes between finds is normal, and fifteen all told).
        ++this.ticks;
        if (have > this.lastHave) {
            this.lastHave = have;
            this.lastGain = this.ticks;
        }
        boolean rare = this.source == Sources.DIAMOND;
        if (this.ticks - this.lastGain > 20 * 60 * (rare ? 5 : 3) || this.ticks > 20 * 60 * (rare ? 15 : 12)) {
            // Something's better than nothing: done if it got any, failed if none at all.
            return have > this.startHave ? Status.DONE : Status.FAILED;
        }

        if (this.sub != null) {
            Status status = this.sub.tick(bot, brain);
            if (status == Status.RUNNING) return Status.RUNNING;
            this.sub = null;
        }

        // Drops lying about: ore we dug through while tunnelling, or that rolled off somewhere.
        // Pick them up before carrying on (nobody walks away from dropped iron).
        if (!brain.breaker().busy() && this.ticks - this.lastStrayCheck >= 20) {
            this.lastStrayCheck = this.ticks;
            Task collect = collectStrays(bot, brain);
            if (collect != null) {
                this.sub = collect;
                return Status.RUNNING;
            }
        }

        // Wood, and we're down a mine: back up the way we came first (to where we last saw the
        // sky), not a tunnel dug straight up through the rock toward the nearest tree. That dig
        // was most of the night's "Chopping wood" troubles.
        if (this.source == Sources.LOGS && this.block == null && !this.wentUp && net.joinedthegame.ai.goal.SleepGoal.deepUnderground(bot)) {
            this.wentUp = true;
            BlockPos sky = brain.lastSky();
            if (sky != null && sky.distSqr(bot.blockPosition()) < 160 * 160) {
                brain.log("need wood: back up the way I came first");
                this.sub = new net.joinedthegame.ai.task.TravelTask(sky, 3, "Heading back up for wood");
                return Status.RUNNING;
            }
        }
        if (this.block != null) return mineTarget(bot, brain);

        if (this.searchCooldown-- <= 0) {
            this.searchCooldown = 20;
            this.block = search(bot, brain);
            if (this.block != null) {
                this.brokenBefore = brain.breaker().broken();
                this.triedReposition = false;
                this.tunnelGoal = null;
                brain.navigator().stop(bot);
                this.blockTicks = 0;
                return Status.RUNNING;
            }
            if (this.waitingFor == null) this.searches++;
        }
        return keepExploring(bot, brain);
    }

    private @Nullable BlockPos search(BotPlayer bot, BotBrain brain) {
        boolean surface = !this.source.isUnderground() || this.source == Sources.STONE;
        int radius = surface ? 24 : 16;
        // Wood comes off trees, not houses (ours, another bot's, a village's): see Trees. And it
        // keeps its distance from anyone it dislikes: not working right next to them.
        var avoidPeople = brain.dislikedNearby(bot, 40);
        // Stone and ore: not right where somebody's stood (any search, the close-by fallbacks too).
        // And in a crowd (two or more digging round here: everyone spawned together and went for the
        // one patch of stone in sight), don't look here at all: head off our own way, away from them,
        // and dig there. Ten bots in two little holes kept getting stuck on each other.
        if (this.source != Sources.LOGS && this.source.isUnderground()) {
            Vec3 away = crowd(bot);
            if (away != null) {
                this.tunnelDir = net.minecraft.core.Direction.getApproximateNearest(away.x, 0, away.z);
                this.tunnelGoal = null;
                if (!this.toldCrowd) brain.log("too many people digging here: off my own way (" + this.tunnelDir.getName() + ")");
                this.toldCrowd = true;
                return null;
            }
        }
        java.util.function.Predicate<BlockPos> skip = p -> brain.isBlacklisted(p) || this.avoid.test(p)
                || this.source == Sources.LOGS && !net.joinedthegame.ai.Trees.choppable(bot, p)
                || nearAny(avoidPeople, p, 8)
                || this.source != Sources.LOGS && someoneBeside(bot, p)
                // Not under water (sand off a river bed, stone at the bottom of a lake): five times
                // slower to dig in water, twenty-five floating, and the drops float off. Cozmo got
                // stuck again and again mining sand and stone under a river.
                || !bot.isInWater() && bot.level().getFluidState(p.above()).is(net.minecraft.tags.FluidTags.WATER);
        // Diamonds in sight and a pickaxe that can take them: nobody walks past diamonds.
        BlockPos found = null;
        if (this.source.isUnderground() && this.source != Sources.DIAMOND && Inv.pickaxeTier(bot) >= 4) {
            found = Senses.findBlock(bot, Sources.DIAMOND.blocks(), 8, 5, true, skip);
            if (found != null) {
                this.bonus = true;
                this.bonusDiamond = true;
                brain.log("spotted diamonds at " + found.toShortString());
            }
        }
        if (found == null) {
            // Someone else already at it (that tree, that vein): find our own.
            // And not right where somebody's standing: stone's everywhere, and following someone
            // down their one-wide hole just gets everyone stuck in it.
            found = Senses.findBlock(bot, this.source.blocks(), radius, surface ? 12 : 10, true,
                    p -> skip.test(p) || net.joinedthegame.ai.Claims.someoneElses(bot, p) != null
                            || this.source != Sources.LOGS && someoneBeside(bot, p));
            if (found == null) {
                // The only one about is someone else's: take some anyway, wait our turn, or leave it.
                BlockPos taken = Senses.findBlock(bot, this.source.blocks(), radius, surface ? 12 : 10, true, skip);
                var claim = taken == null ? null : net.joinedthegame.ai.Claims.someoneElses(bot, taken);
                if (claim != null) {
                    Choice c = choose(bot, brain, claim);
                    if (c == Choice.TAKE) {
                        found = taken;
                        this.competingWith = claim;
                    } else if (c == Choice.WAIT && this.waitingFor == null) {
                        this.waitingFor = claim;
                        this.waitStart = this.ticks;
                    }
                }
            }
        }
        if (found == null && this.source.isUnderground()) {
            // Ore round a corner or showing a face in the tunnel wall: not in line of sight,
            // but anyone digging along would notice it.
            WorldView w = new WorldView(bot.level());
            found = Senses.findBlock(bot, s -> this.source.blocks().test(s), 6, 4, false,
                    p -> skip.test(p) || !Senses.isExposed(w, p));
        }
        if (found == null && this.source.isUnderground()) {
            found = Senses.findBlock(bot, this.source.blocks(), 4, 4, false, skip);
        }
        if (found == null && this.source.isUnderground()) {
            // Some we saw earlier and didn't take (busy, interrupted): go back for it.
            var here = net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition());
            var known = brain.memory().nearest("ore:" + this.source.label(), here, 48);
            if (known != null) {
                if (bot.level().isLoaded(known.pos()) && this.source.blocks().test(bot.level().getBlockState(known.pos()))
                        && !skip.test(known.pos())) {
                    found = known.pos();
                    brain.log("going back for the " + this.source.label() + " I saw at " + found.toShortString());
                } else {
                    brain.memory().forget("ore:" + this.source.label(), known); // gone now
                }
            }
        }
        if (found == null && (this.source == Sources.IRON || this.source == Sources.DIAMOND)) {
            // Hunting the good stuff, but grab coal sitting right next to us anyway.
            found = Senses.findBlock(bot, Sources.COAL.blocks(), 4, 3, true, brain::isBlacklisted);
            if (found != null) this.bonus = true;
        }
        // Coal in sight while digging for anything else underground: nobody walks past coal.
        if (found == null && this.source.isUnderground() && this.source != Sources.COAL
                && Inv.count(bot, net.minecraft.world.item.Items.COAL) < 32) {
            found = Senses.findBlock(bot, Sources.COAL.blocks(), 6, 4, true, brain::isBlacklisted);
            if (found != null) this.bonus = true;
        }
        return found;
    }

    private boolean toldCrowd;
    private boolean toolBroke;
    private boolean wentUp;

    /** Stopped because the pickaxe gave out (not because the job's done). */
    public boolean toolBroke() {
        return this.toolBroke;
    }

    /**
     * Two or more others within five blocks (about our level): which way is away from them
     * (null if it's not a crowd). Only while we're not already off down our own tunnel.
     */
    private static @Nullable Vec3 crowd(BotPlayer bot) {
        double sx = 0, sz = 0;
        int n = 0;
        for (var other : bot.level().players()) {
            if (other == bot || other.isSpectator() || !other.isAlive()) continue;
            double dx = other.getX() - bot.getX(), dz = other.getZ() - bot.getZ(), dy = other.getY() - bot.getY();
            if (dx * dx + dz * dz > 5 * 5 || Math.abs(dy) > 4) continue;
            sx += dx;
            sz += dz;
            n++;
        }
        if (n < 2) return null;
        // Away from where they are on average (or any way, if we're right in the middle).
        if (sx * sx + sz * sz < 0.01) return new Vec3(bot.getRandom().nextDouble() - 0.5, 0, bot.getRandom().nextDouble() - 0.5);
        return new Vec3(-sx, 0, -sz);
    }

    /** Another player stood within a couple of blocks of it (working there, or about to). */
    private static boolean someoneBeside(BotPlayer bot, BlockPos p) {
        for (var other : bot.level().players()) {
            if (other == bot || other.isSpectator() || !other.isAlive()) continue;
            double dx = other.getX() - (p.getX() + 0.5), dz = other.getZ() - (p.getZ() + 0.5), dy = other.getY() - p.getY();
            if (dx * dx + dz * dz <= 2.5 * 2.5 && dy > -2 && dy < 4) return true;
        }
        return false;
    }

    private enum Choice { TAKE, WAIT, LEAVE }

    /** Working someone else's tree (or vein) alongside them: told them once, if they noticed. */
    private net.joinedthegame.ai.Claims.@Nullable Claim competingWith;
    private boolean competitionNoticed;
    /** Waiting our turn for this one, since when. */
    private net.joinedthegame.ai.Claims.@Nullable Claim waitingFor;
    private int waitStart;

    /**
     * The only tree about and someone's already on it. Decided once per bot per job (not re-rolled
     * every look), by who we are: a selfish bot (reckless, a hoarder, not one for company) piles
     * in and takes some; a patient one waits its turn, if there's nowhere else in sight; the rest
     * leave it to them and look further afield. Bad blood with that bot (things it did to us that
     * we saw) makes taking some more likely, a little.
     */
    private Choice choose(BotPlayer bot, BotBrain brain, net.joinedthegame.ai.Claims.Claim claim) {
        String key = "tree:" + claim.owner();
        String earlier = brain.recentDecision(key);
        if (earlier != null) return Choice.valueOf(earlier);
        // A friend's tree: just muck in. Nobody's competing (and they won't mind: see reportCompetition).
        if (brain.friendly(claim.owner())) {
            brain.log(claim.name() + "'s on the only " + (this.source == Sources.LOGS ? "tree" : this.source.label())
                    + " about: they're a friend, mucking in");
            brain.decide(key, Choice.TAKE.name());
            return Choice.TAKE;
        }
        String what = (this.source == Sources.LOGS ? "tree" : this.source.label())
                + " (at " + claim.pos().toShortString() + ", " + claim.blocks().size() + " blocks)";
        double take = Math.clamp(0.03 + brain.selfish() * 0.75 + brain.grudgeAgainst(claim.owner()), 0.03, 0.9);
        Choice c;
        if (bot.getRandom().nextDouble() < take) {
            c = Choice.TAKE;
            brain.log("the only " + what + " about and " + claim.name() + "'s on it: going for it too"
                    + (brain.grudgeAgainst(claim.owner()) > 0 ? " (after last time)" : ""));
            brain.remember(net.joinedthegame.ai.BotMemory.EventType.COMPETED_FOR, claim.name());
        } else {
            BlockPos other = this.source == Sources.LOGS ? Senses.treesOnTheHorizon(bot, 96,
                    t -> flatDistSqr(bot, t) < 20 * 20 || brain.isDudTrees(t)
                            || net.joinedthegame.ai.Claims.someoneElsesNear(bot, t, 8) != null) : null;
            boolean elsewhere = other != null;
            if (!elsewhere && bot.getRandom().nextDouble() < brain.patient()) {
                c = Choice.WAIT;
                brain.log(claim.name() + "'s on the only " + what + " about: waiting my turn");
                // If they see us standing back for them, that counts for something.
                var server = bot.level().getServer();
                var them = server == null ? null : server.getPlayerList().getPlayer(claim.owner());
                var theirs = server == null ? null : net.joinedthegame.bot.BotManager.get(server).brain(claim.owner());
                if (them != null && theirs != null && BotBrain.sees(them, bot)) {
                    theirs.feel(bot, net.joinedthegame.ai.social.Feelings.Act.WAITED_FOR_ME, 1);
                }
            } else {
                c = Choice.LEAVE;
                brain.log(claim.name() + "'s on the only " + what + " about: leaving it to them, "
                        + (other != null ? "there's more over at " + other.toShortString() : "looking elsewhere"));
                brain.remember(net.joinedthegame.ai.BotMemory.EventType.LEFT_TO, claim.name());
            }
        }
        brain.decide(key, c.name());
        return c;
    }

    /** Waiting for them to finish: stand by (not in the way), watch them, and don't wait for ever. */
    private @Nullable Status waitTurn(BotPlayer bot, BotBrain brain) {
        var claim = this.waitingFor;
        if (claim == null) return null;
        var now = net.joinedthegame.ai.Claims.of(claim.owner(), bot.level().getGameTime());
        if (now == null || now.since() != claim.since()) {
            // They're done with it: our turn (search again straight away).
            brain.log(claim.name() + "'s done: my turn");
            brain.remember(net.joinedthegame.ai.BotMemory.EventType.WAITED_FOR, claim.name());
            this.waitingFor = null;
            brain.forgetDecision("tree:" + claim.owner());
            this.searchCooldown = 0;
            return Status.RUNNING;
        }
        if (this.ticks - this.waitStart > 20 * 90) {
            brain.log("fed up waiting for " + claim.name() + ", looking elsewhere");
            this.waitingFor = null;
            brain.decide("tree:" + claim.owner(), Choice.LEAVE.name());
            return null;
        }
        Navigator nav = brain.navigator();
        double flat = flatDistSqr(bot, claim.pos());
        if (flat < 4 * 4) {
            // Right under it: what they chop lands on us (and gets picked up). Step back.
            double dx = bot.getX() - (claim.pos().getX() + 0.5), dz = bot.getZ() - (claim.pos().getZ() + 0.5);
            double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            if (!nav.isMoving()) nav.moveNear(bot, new Vec3(claim.pos().getX() + 0.5 + dx / len * 5.5, bot.getY(),
                    claim.pos().getZ() + 0.5 + dz / len * 5.5), 1.2);
        } else if (flat > 7 * 7) {
            if (!nav.isMoving()) nav.moveNear(bot, Vec3.atCenterOf(claim.pos()), 5);
        } else {
            nav.stop(bot);
            var server = bot.level().getServer();
            var them = server == null ? null : server.getPlayerList().getPlayer(claim.owner());
            if (them != null) brain.controls().lookAt(bot, them);
        }
        return Status.RUNNING;
    }

    /** First block off someone else's tree: if they saw us, they'll remember it. */
    private void reportCompetition(BotPlayer bot, BotBrain brain) {
        var claim = this.competingWith;
        if (claim == null || this.competitionNoticed) return;
        this.competitionNoticed = true;
        var server = bot.level().getServer();
        if (server == null) return;
        var them = server.getPlayerList().getPlayer(claim.owner());
        var theirs = net.joinedthegame.bot.BotManager.get(server).brain(claim.owner());
        if (them == null || theirs == null) return;
        // Seen it, and not already put out by them over this in the last few minutes (one pile-in
        // is one thing to remember, however many armfuls it takes).
        String key = "crowded:" + bot.getScoreboardName();
        if (theirs.friendly(bot.getUUID())) {
            // A friend mucking in on our tree: that's fine.
            if (BotBrain.sees(them, bot) && theirs.recentDecision(key) == null) {
                theirs.decide(key, "friend");
                theirs.log(bot.getScoreboardName() + " is helping with my tree (they're a friend)");
            }
            return;
        }
        if (BotBrain.sees(them, bot) && theirs.recentDecision(key) == null) {
            theirs.decide(key, "noticed");
            theirs.log(bot.getScoreboardName() + " is chopping my tree too (at " + claim.pos().toShortString() + ")");
            theirs.remember(net.joinedthegame.ai.BotMemory.EventType.CROWDED_BY, bot.getScoreboardName());
            theirs.feel(bot, net.joinedthegame.ai.social.Feelings.Act.CROWDED_ME, 1);
        }
    }

    private Status mineTarget(BotPlayer bot, BotBrain brain) {
        BlockPos pos = this.block;
        {
            // Ours while we're at it, the whole tree (or vein), so others can see. Only a block
            // that's still there: a gone one starts no claim.
            var here = bot.level().getBlockState(pos);
            if (!here.isAir()) {
                java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> kind = this.source == Sources.LOGS
                        ? st -> st.is(net.minecraft.tags.BlockTags.LOGS) : st -> st.is(here.getBlock());
                net.joinedthegame.ai.Claims.claim(bot, pos, kind, this.source == Sources.LOGS ? 256 : 32);
            }
        }
        WorldView world = new WorldView(bot.level());
        boolean stillThere = this.bonus
                ? Sources.COAL.blocks().test(world.state(pos)) || Sources.DIAMOND.blocks().test(world.state(pos))
                : this.source.blocks().test(world.state(pos));
        if (!stillThere) {
            // What drops from it is ours for a bit (others can see we chopped it).
            if (brain.breaker().broken() > this.brokenBefore) net.joinedthegame.ai.Claims.noteBreak(bot, pos);
            if (this.competingWith != null && this.competingWith.blocks().contains(pos)) reportCompetition(bot, brain);
            // Worth remembering where the good stuff was.
            if (this.bonusDiamond) {
                brain.remember(net.joinedthegame.ai.BotMemory.EventType.FOUND, Sources.DIAMOND.label(),
                        net.minecraft.core.GlobalPos.of(bot.level().dimension(), pos), 1f);
            }
            this.bonusDiamond = false;
            if (!this.bonus && (this.source == Sources.DIAMOND || this.source == Sources.IRON)) {
                var where = net.minecraft.core.GlobalPos.of(bot.level().dimension(), pos);
                brain.remember(net.joinedthegame.ai.BotMemory.EventType.FOUND, this.source.label(), where, this.source == Sources.DIAMOND ? 1f : 0.4f);
                // Remember the vein only while there's more of it; a mined-out one is useless.
                String kind = "ore:" + this.source.label();
                BlockPos more = Senses.findBlock(bot, this.source.blocks(), 3, 3, false, p -> p.equals(pos) || brain.isBlacklisted(p));
                if (more != null && more.distSqr(pos) <= 9) brain.rememberPlace(bot, kind, more);
                else brain.memory().forgetAround(kind, where, 6);
            }
            this.bonus = false;
            this.block = null;
            this.aim = null;
            brain.breaker().cancel(bot);
            this.sub = new CollectItems(pos, 6, 120);
            return Status.RUNNING;
        }
        if (++this.blockTicks > 20 * 40) {
            brain.blacklist(pos);
            this.block = null;
            brain.breaker().cancel(bot);
            return Status.RUNNING;
        }
        Navigator nav = brain.navigator();
        if (nav.isMoving() && this.triedReposition && this.aim == null) return Status.RUNNING; // getting a better view
        if (BlockBreaker.inReach(bot, pos)) {
            nav.stop(bot);
            // Dig through whatever is between us and the block, like a player would.
            // Keep digging the same block until it's gone; re-aiming every tick (the view
            // shifts as the bot moves) would keep resetting the break progress.
            BlockPos aim = this.aim;
            if (aim == null || world.passable(aim)) {
                aim = firstObstruction(bot, pos);
                // The only way through is the block we're standing on? Don't dig our own floor
                // out (we'd drop, pillar back up and do it again): find a spot with a clear view.
                if (aim != null && isUnderUs(bot, aim) && !this.triedReposition) {
                    this.triedReposition = true;
                    BlockPos target = pos;
                    double r = BlockBreaker.REACH - 0.8;
                    nav.moveTo(bot, Vec3.atCenterOf(target), p -> {
                        Vec3 eye = Vec3.atBottomCenterOf(p).add(0, 1.62, 0);
                        if (eye.distanceToSqr(Vec3.atCenterOf(target)) > r * r) return false;
                        var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(target),
                                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
                        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(target);
                    });
                    if (nav.status() != Navigator.Status.FAILED) return Status.RUNNING;
                    nav.stop(bot); // nowhere better: dig through after all
                }
                if (aim == null) aim = pos;
                else if (!world.breakable(aim)) {
                    brain.blacklist(pos);
                    this.block = null;
                    this.aim = null;
                    return Status.RUNNING;
                }
                // The ore itself skips the breakable() check above, and that's how Duckatyt died:
                // he mined the iron he was standing on, and the lava under it had him a second later.
                if (lavaRisk(world, bot, aim)) {
                    brain.log("lava by the block at " + aim.toShortString() + ": leaving it");
                    brain.blacklist(pos);
                    this.block = null;
                    this.aim = null;
                    return Status.RUNNING;
                }
                this.aim = aim;
            }
            brain.breaker().start(aim);
            BlockBreaker.Result result = brain.breaker().tick(bot, brain.controls());
            if (result != BlockBreaker.Result.RUNNING) this.aim = null;
            if (result == BlockBreaker.Result.FAILED) {
                brain.blacklist(pos);
                this.block = null;
            }
            brain.debug = "target " + pos.toShortString() + " aim " + aim.toShortString();
            return Status.RUNNING;
        }
        if (!nav.isMoving()) {
            nav.moveToReach(bot, pos);
            if (nav.status() == Navigator.Status.FAILED) {
                brain.blacklist(pos);
                this.block = null;
                nav.stop(bot);
            }
        }
        return Status.RUNNING;
    }

    /** Items given a try already (unreachable, say): don't keep going back for them. */
    private final java.util.Set<java.util.UUID> triedItems = new java.util.HashSet<>();
    private int lastStrayCheck = -100;

    /** A trip to pick up useful drops close by, or null if there aren't any worth it. */
    private @Nullable Task collectStrays(BotPlayer bot, BotBrain brain) {
        if (Inv.usedSlots(bot) >= Inv.MAIN_SLOTS - 1) return null;
        var items = Senses.itemsNear(bot, bot.blockPosition(), 6);
        items.removeIf(e -> !e.isAlive() || e.getItem().isEmpty() || this.triedItems.contains(e.getUUID())
                || e.getOwner() == bot // tossed it ourselves
                || !(net.joinedthegame.ai.Junk.isKeeper(e.getItem()) || net.joinedthegame.ai.Junk.isValuable(e.getItem()))
                || e.getY() < bot.getY() - 6 || e.getY() > bot.getY() + 3);
        if (items.isEmpty()) return null;
        for (var e : items) this.triedItems.add(e.getUUID());
        return new CollectItems(items.getFirst().blockPosition(), 6, 120);
    }

    /**
     * Would breaking this let lava at us? Lava touching it flows into the hole; and if it's our
     * own floor, we drop into whatever's below, so lava anywhere down that drop (or at the
     * bottom, or beside where we'd land) is a death a player would see coming.
     */
    private static boolean lavaRisk(WorldView world, BotPlayer bot, BlockPos block) {
        for (Direction dir : Direction.values()) if (world.isLava(block.relative(dir))) return true;
        if (!isUnderUs(bot, block)) return false;
        BlockPos.MutableBlockPos p = block.mutable();
        for (int down = 0; down < 6; down++) {
            p.move(Direction.DOWN);
            if (world.isLava(p)) return true;
            for (Direction side : Direction.Plane.HORIZONTAL) if (world.isLava(p.relative(side))) return true;
            if (!world.passable(p)) return WorldView.isDangerous(world.state(p)); // the floor we'd land on
        }
        return false;
    }

    /** One of the blocks holding us up (straight under our feet, or under the edge we're on). */
    private static boolean isUnderUs(BotPlayer bot, BlockPos block) {
        if (block.getY() != Mth.floor(bot.getY() - 0.01) ) return false;
        net.minecraft.world.phys.AABB feet = bot.getBoundingBox().move(0, -0.5, 0).deflate(0.01, 0, 0.01);
        return feet.intersects(new net.minecraft.world.phys.AABB(block));
    }

    /** The first solid block between the eyes and {@code pos}, or null if the view is clear. */
    private static @Nullable BlockPos firstObstruction(BotPlayer bot, BlockPos pos) {
        var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(bot.getEyePosition(), Vec3.atCenterOf(pos),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(pos)) return null;
        return hit.getBlockPos();
    }

    private static boolean nearAny(java.util.List<Vec3> people, BlockPos p, double within) {
        for (Vec3 v : people) if (v.distanceToSqr(Vec3.atCenterOf(p)) < within * within) return true;
        return false;
    }

    private static double flatDistSqr(BotPlayer bot, BlockPos p) {
        double dx = p.getX() + 0.5 - bot.getX(), dz = p.getZ() + 0.5 - bot.getZ();
        return dx * dx + dz * dz;
    }

    private Status keepExploring(BotPlayer bot, BotBrain brain) {
        Status waiting = waitTurn(bot, brain);
        if (waiting != null) return waiting;
        Navigator nav = brain.navigator();
        if (nav.isMoving()) return Status.RUNNING;
        if (this.searches > 40) return Status.FAILED;

        if (!this.source.isUnderground()) {
            // Trees in view a long way off (an island, a wood across the plain): go that way.
            double angle;
            boolean toTrees = false;
            // Got to the trees we saw and still nothing we can chop (all out of reach, or just
            // leaves): a dud, so don't keep coming back to the same spot.
            BlockPos went = this.seenTrees;
            if (went != null && flatDistSqr(bot, went) < 16 * 16) {
                brain.markDudTrees(went);
                this.seenTrees = null;
            }
            BlockPos trees = this.source == Sources.LOGS ? Senses.treesOnTheHorizon(bot, 96, t -> flatDistSqr(bot, t) < 20 * 20
                    || brain.isDudTrees(t) || net.joinedthegame.ai.Claims.someoneElsesNear(bot, t, 8) != null) : null;
            // (Measured flat: a treetop thirty blocks straight up is right here, not in the distance.
            // If the search can't find a log we can get at, there isn't one: it's a dud.)
            if (trees != null && flatDistSqr(bot, trees) > 20 * 20) {
                angle = Math.atan2(trees.getZ() + 0.5 - bot.getZ(), trees.getX() + 0.5 - bot.getX());
                toTrees = true;
                if (!trees.equals(this.seenTrees)) {
                    this.seenTrees = trees;
                    brain.log("trees in the distance at " + trees.toShortString() + ", heading over");
                }
            } else {
                // Nothing in view: keep going the way we're going (see BotBrain.searchHeading),
                // with a little drift, instead of a new random way every few steps.
                angle = brain.searchHeading(bot) + (bot.getRandom().nextDouble() - 0.5) * 0.6;
            }
            int dist = toTrees ? (int) Math.min(40, Math.sqrt(flatDistSqr(bot, trees))) : 28 + bot.getRandom().nextInt(12);
            int x = Mth.floor(bot.getX() + Math.cos(angle) * dist);
            int z = Mth.floor(bot.getZ() + Math.sin(angle) * dist);
            if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) {
                if (!toTrees) brain.turnSearchHeading(bot);
                return Status.RUNNING;
            }
            int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            nav.moveNear(bot, new Vec3(x + 0.5, y, z + 0.5), 3);
            if (nav.status() == Navigator.Status.FAILED) {
                nav.stop(bot);
                if (!toTrees) brain.turnSearchHeading(bot);
            }
            return Status.RUNNING;
        }

        // Underground: staircase down to the right depth, then tunnel.
        int y = Mth.floor(bot.getY());
        int goalY = this.source == Sources.STONE ? Math.min(y - 3, this.source.mineY()) : this.source.mineY();
        goalY = Math.max(goalY, bot.level().getMinY() + 6);
        if (y > goalY + 2) {
            int dy = y - goalY;
            Vec3 goal = new Vec3(bot.getX() + this.tunnelDir.getStepX() * dy, goalY, bot.getZ() + this.tunnelDir.getStepZ() * dy);
            int finalGoalY = goalY;
            nav.moveTo(bot, goal, p -> p.getY() <= finalGoalY + 1);
        } else {
            if (this.tunnelGoal == null || bot.position().distanceToSqr(this.tunnelGoal) < 4) {
                if (this.tunnelGoal != null && bot.getRandom().nextInt(3) == 0) {
                    this.tunnelDir = bot.getRandom().nextBoolean() ? this.tunnelDir.getClockWise() : this.tunnelDir.getCounterClockWise();
                }
                this.tunnelGoal = bot.position().add(this.tunnelDir.getStepX() * 24, 0, this.tunnelDir.getStepZ() * 24);
            }
            nav.moveNear(bot, this.tunnelGoal, 1.5);
        }
        if (nav.status() == Navigator.Status.FAILED) {
            nav.stop(bot);
            this.tunnelDir = this.tunnelDir.getClockWise();
            this.tunnelGoal = null;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        brain.breaker().cancel(bot);
        brain.navigator().stop(bot);
    }
}
