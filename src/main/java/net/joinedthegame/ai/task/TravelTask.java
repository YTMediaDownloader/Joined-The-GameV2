package net.joinedthegame.ai.task;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Walk somewhere far away. One path can only plan so far ahead, so long trips go in hops:
 * across the map first (48 blocks at a time, at ground level), then once we're over the
 * place, down (or up) to it a staircase at a time. Underground places (mineshafts, trial
 * chambers) are mostly straight down by the end, so the hops must never be aimed along the
 * straight line: that hop would land right where we stand and we'd "arrive" forever.
 */
public final class TravelTask implements Task {
    private final BlockPos target;
    private final double arriveWithin;
    private final String label;
    private int ticks;
    private int failures;
    /** Closest we've been, and when; no progress for a while means give up, not stand there. */
    private double best = Double.MAX_VALUE;
    private int bestAt;

    public TravelTask(BlockPos target, double arriveWithin, String label) {
        this.target = target;
        this.arriveWithin = arriveWithin;
        this.label = label;
    }

    @Override
    public String activity() {
        return this.label;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        Vec3 goal = Vec3.atBottomCenterOf(this.target);
        double dist = bot.position().distanceTo(goal);
        if (dist <= this.arriveWithin) return Status.DONE;
        this.ticks++;
        if (dist < this.best - 1.0) {
            this.best = dist;
            this.bestAt = this.ticks;
        }
        if (this.ticks > 20 * 60 * 4 || this.failures > 8 || this.ticks - this.bestAt > 20 * 30) {
            brain.log("travel: not getting any closer to " + this.target.toShortString() + ", giving up");
            return Status.FAILED;
        }
        Navigator nav = brain.navigator();
        if (nav.isMoving()) return Status.RUNNING;

        double dx = goal.x - bot.getX();
        double dz = goal.z - bot.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        double dy = goal.y - bot.getY();
        Vec3 hop;
        double within;
        if (flat > 40) {
            // Across the map: 48 blocks along the ground towards it.
            double s = Math.min(48, flat) / flat;
            int x = Mth.floor(bot.getX() + dx * s);
            int z = Mth.floor(bot.getZ() + dz * s);
            double y = bot.getY();
            if (bot.level().getChunkSource().hasChunk(x >> 4, z >> 4) && bot.level().dimensionType().hasSkyLight()
                    && bot.level().canSeeSky(bot.blockPosition())) {
                y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z); // on the surface: stay on it
            }
            hop = new Vec3(x + 0.5, y, z + 0.5);
            within = 6;
        } else if (Math.abs(dy) > 12) {
            // Over it (or under it): a staircase's worth at a time, edging closer as we go.
            double step = Math.min(12, Math.abs(dy));
            double s = flat < 1 ? 0 : Math.min(step, flat) / flat;
            hop = new Vec3(bot.getX() + dx * s, bot.getY() + Math.signum(dy) * step, bot.getZ() + dz * s);
            within = 4;
        } else {
            hop = goal;
            within = this.arriveWithin;
        }
        nav.moveNear(bot, hop, within);
        if (nav.status() == Navigator.Status.ARRIVED) {
            // That hop was no hop at all: go straight for the place instead.
            nav.moveNear(bot, goal, this.arriveWithin);
        }
        if (nav.status() == Navigator.Status.FAILED) {
            nav.stop(bot);
            this.failures++;
            // Stuck down a cave with the target up above? Get up to daylight first (digging up if
            // need be), then carry on from there.
            if (this.target.getY() > bot.getY() + 4 && bot.level().dimensionType().hasSkyLight() && !bot.level().canSeeSky(bot.blockPosition())) {
                int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bot.getBlockX(), bot.getBlockZ());
                nav.moveTo(bot, new Vec3(bot.getX(), y, bot.getZ()), p -> bot.level().canSeeSky(p));
                if (nav.status() == Navigator.Status.FAILED) nav.stop(bot);
            }
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        brain.navigator().stop(bot);
    }
}
