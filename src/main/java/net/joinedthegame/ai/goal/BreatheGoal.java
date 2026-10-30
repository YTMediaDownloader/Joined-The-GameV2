package net.joinedthegame.ai.goal;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Running out of air under water: everything else stops (a fight included) and the bot goes
 * for the nearest place its head will be out of the water, the way a player does. Just
 * holding jump only works in open water: under a ceiling (a flooded tunnel it just dug into,
 * an underwater cave, ice) it's how bots drowned with air two blocks away. If there's nowhere
 * to swim to, it digs up towards the air.
 */
public final class BreatheGoal extends Goal {
    private @Nullable BlockPos air;
    private int ticks;
    private boolean digging;

    public BreatheGoal(BotBrain brain) {
        super(brain);
    }

    /** Head under water and the air going (sooner if there's a ceiling over us). */
    private static boolean needsAir(BotPlayer bot) {
        if (!bot.isUnderWater()) return false;
        int air = bot.getAirSupply();
        int max = bot.getMaxAirSupply();
        if (air < max * 0.6) return true;
        return air < max - 30 && ceilingOverhead(bot);
    }

    /** Something solid between us and the surface straight up (so swimming up won't do it). */
    private static boolean ceilingOverhead(BotPlayer bot) {
        WorldView w = new WorldView(bot.level());
        BlockPos p = BlockPos.containing(bot.getEyePosition());
        for (int i = 0; i < 12; i++) {
            p = p.above();
            if (!w.isWater(p)) return !w.passable(p); // reached the top: air, or a lid
        }
        return true; // a long way up
    }

    @Override
    public int priority(BotPlayer bot) {
        // Above everything, fighting included: drowning doesn't wait for the zombie to die.
        return needsAir(bot) || (this.air != null && bot.isUnderWater()) ? 105 : 0;
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
        this.digging = false;
        this.air = findAir(bot);
        if (this.air != null) this.brain.log("out of air: heading for " + this.air.toShortString());
        else this.brain.log("out of air and nowhere to swim to: digging up");
    }

    @Override
    public boolean tick(BotPlayer bot) {
        ++this.ticks;
        if (!bot.isUnderWater()) {
            // Head's out: done (the bot takes a breath and gets on with things).
            this.air = null;
            return false;
        }
        this.brain.controls().jump = true; // swim up as we go
        BlockPos target = this.air;
        if (target != null && !this.digging) {
            Navigator nav = this.brain.navigator();
            if (!nav.isMoving()) {
                nav.moveTo(bot, Vec3.atBottomCenterOf(target), p -> breathable(new WorldView(bot.level()), p));
                if (nav.status() == Navigator.Status.FAILED) {
                    nav.stop(bot);
                    this.digging = true;
                }
            }
            if (this.ticks % 40 == 0) this.air = findAir(bot); // (the water moves, we move)
            return true;
        }
        // Nowhere to swim to: dig up through whatever's over our head.
        this.brain.navigator().stop(bot);
        BlockPos up = BlockPos.containing(bot.getEyePosition()).above();
        WorldView w = new WorldView(bot.level());
        if (!w.passable(up) && w.breakableOnPurpose(up) && BlockBreaker.inReach(bot, up)) {
            this.brain.controls().lookAt(bot, Vec3.atCenterOf(up));
            this.brain.breaker().start(up);
            this.brain.breaker().tick(bot, this.brain.controls());
        }
        return true;
    }

    /** A spot to stand (or float) with the head out in the air. */
    private static boolean breathable(WorldView w, BlockPos feet) {
        BlockPos head = feet.above();
        return w.passable(feet) && w.passable(head) && !w.isWater(head);
    }

    /**
     * The nearest breathable spot we can swim to: a search outward through the water (and any
     * open space), never through solid blocks, up to about 16 blocks away.
     */
    private static @Nullable BlockPos findAir(BotPlayer bot) {
        WorldView w = new WorldView(bot.level());
        BlockPos start = BlockPos.containing(bot.position());
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && seen.size() < 2000) {
            BlockPos p = queue.poll();
            if (breathable(w, p)) return p;
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (seen.contains(n) || n.distManhattan(start) > 24) continue;
                if (!w.passable(n) && !w.isWater(n)) continue;
                seen.add(n);
                queue.add(n);
            }
        }
        return null;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        this.brain.breaker().cancel(bot);
    }

    @Override
    public String activity() {
        return "Coming up for air";
    }
}
