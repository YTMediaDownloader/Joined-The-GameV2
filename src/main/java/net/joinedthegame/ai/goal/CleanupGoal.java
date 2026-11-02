package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Scaffold;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Tidying up after ourselves: mining back the blocks we put down only to get somewhere (pillars,
 * bridges, the box we hid in), once we're done with them, like a tidy player does. Only when it's
 * quiet, only what's close by, and never anything that would hurt:
 * <ul>
 *   <li>never the block we're standing on, or one under it (no dropping ourselves into a gap);</li>
 *   <li>never one someone's standing on, or with something built on top;</li>
 *   <li>never one by water or lava (no letting a river or a lava pool in);</li>
 *   <li>only if it's still the block we put there (anything else isn't ours to take).</li>
 * </ul>
 * The blocks come back to us as we mine them.
 */
public final class CleanupGoal extends Goal {
    /** Done with it: it's been there a while (we're not still on our way up it). */
    private static final int SETTLED = 20 * 20;
    /** Close enough to go back for (a fight that sent us running a little way still counts). */
    private static final double NEAR = 32;
    /** Old ones we never got back to: forget them (we've moved on). */
    private static final int FORGET = 20 * 60 * 15;

    private Scaffold.@Nullable Placed target;
    private int ticks;
    private int cleared;

    public CleanupGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (this.brain.scaffold.isEmpty()) return 0;
        long now = this.brain.now();
        this.brain.scaffold.removeIf(p -> now - p.when() > FORGET);
        if (onCooldown(now) || this.brain.inAFight()) return 0;
        // Quiet only: no monsters about, and not out under the night sky unready for it.
        if (bot.level().isDarkOutside() && bot.level().canSeeSky(bot.blockPosition()) && !ShelterGoal.nightReady(bot)) return 0;
        if (!Senses.entitiesNear(bot, Mob.class, 16, m -> m.isAlive() && m instanceof Enemy).isEmpty()) return 0;
        return next(bot) != null ? 42 : 0;
    }

    /** The nearest of ours we can safely take back now, or null. */
    private Scaffold.@Nullable Placed next(BotPlayer bot) {
        WorldView w = new WorldView(bot.level());
        long now = this.brain.now();
        // Gone, or someone's changed it: not ours any more. (Dropped here, before going through
        // the list: taking them out while going through it crashed the server.)
        this.brain.scaffold.removeIf(p -> p.at().dimension() == bot.level().dimension() && bot.level().isLoaded(p.at().pos())
                && !w.state(p.at().pos()).is(p.block()));
        Scaffold.Placed best = null;
        double bestScore = Double.MAX_VALUE;
        for (Scaffold.Placed p : this.brain.scaffold) {
            if (p.at().dimension() != bot.level().dimension() || now - p.when() < SETTLED) continue;
            BlockPos pos = p.at().pos();
            double dist = Math.sqrt(pos.distToCenterSqr(bot.position()));
            if (dist > NEAR || !bot.level().isLoaded(pos)) continue;
            if (!takeable(bot, w, p)) continue;
            // Nearest first; in a column, the top first (so nothing's left floating on what we took).
            double score = dist - pos.getY() * 0.01;
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /** Still ours, and safe to take: the rules in the class comment. */
    private boolean takeable(BotPlayer bot, WorldView w, Scaffold.Placed p) {
        BlockPos pos = p.at().pos();
        if (!w.state(pos).is(p.block())) return false; // gone or changed (dropped from the list in next())
        // Under us (standing on it, or further down the column we're on top of).
        AABB box = bot.getBoundingBox();
        boolean column = pos.getX() + 1 > box.minX && pos.getX() < box.maxX && pos.getZ() + 1 > box.minZ && pos.getZ() < box.maxZ;
        if (column && pos.getY() < bot.getY()) return false;
        // Someone on it, or something built on it (a torch, a block that isn't ours).
        if (!bot.level().getEntitiesOfClass(LivingEntity.class, new AABB(pos.above()).deflate(0.05)).isEmpty()) return false;
        var above = w.state(pos.above());
        if (!above.isAir() && !isOurs(pos.above())) return false;
        // By water or lava, or anything else the usual digging rules say no to.
        return w.breakable(pos);
    }

    private boolean isOurs(BlockPos pos) {
        for (Scaffold.Placed p : this.brain.scaffold) if (p.at().pos().equals(pos)) return true;
        return false;
    }

    @Override
    public void start(BotPlayer bot) {
        this.target = null;
        this.ticks = 0;
        this.cleared = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (++this.ticks > 20 * 40) return finish();
        WorldView w = new WorldView(bot.level());
        if (this.target == null || !this.brain.scaffold.contains(this.target) || !takeable(bot, w, this.target)) {
            this.target = next(bot);
            if (this.target == null) return finish();
        }
        BlockPos pos = this.target.at().pos();
        Navigator nav = this.brain.navigator();
        if (!BlockBreaker.inReach(bot, pos)) {
            if (!nav.isMoving()) {
                nav.noPlacing = true; // no new pillars to take the old ones down
                nav.moveToReach(bot, pos);
                nav.noPlacing = false;
                if (nav.status() == Navigator.Status.FAILED) {
                    nav.stop(bot);
                    this.brain.log("tidying up: can't get to the block at " + pos.toShortString() + ", leaving it");
                    this.brain.scaffold.remove(this.target); // can't get to it: leave it
                    this.target = null;
                }
            }
            return true;
        }
        nav.stop(bot);
        var breaker = this.brain.breaker();
        if (!pos.equals(breaker.target())) breaker.start(pos);
        BlockBreaker.Result r = breaker.tick(bot, this.brain.controls());
        if (r == BlockBreaker.Result.RUNNING) return true;
        this.brain.scaffold.remove(this.target);
        if (r == BlockBreaker.Result.DONE) this.cleared++;
        this.target = null;
        return true;
    }

    private boolean finish() {
        if (this.cleared > 0) this.brain.log("tidied up after myself: took back " + this.cleared + " block" + (this.cleared == 1 ? "" : "s") + " I'd put down");
        cooldown(this.brain.now(), 20 * 10);
        return false;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        this.brain.breaker().cancel(bot);
    }

    @Override
    public String activity() {
        return "Tidying up after myself";
    }
}
