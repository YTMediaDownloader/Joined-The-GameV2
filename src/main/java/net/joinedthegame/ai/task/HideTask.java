package net.joinedthegame.ai.task;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Controls;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.FallingBlock;
import org.jspecify.annotations.Nullable;

/**
 * The classic panic move: dig straight down three blocks and put a block over your head.
 * Zombies can't reach you and skeletons can't see you. Used to wait out the night, or to
 * escape a fight that's going badly. Falls back to pillaring up if the ground can't be dug.
 *
 * <p>Two things make this work like a player doing it:
 * <ul>
 *   <li>The bot stays centred over the hole. A player is 0.6 wide, so standing off-centre
 *       it keeps resting on the next block's edge and never drops into the hole it dug.</li>
 *   <li>Three deep, not two. The lid goes one block under the surface, where the hole's own
 *       walls give it something to be placed against (on flat ground the surface-level cell
 *       has nothing next to it at all).</li>
 * </ul>
 */
public final class HideTask implements Task {
    private enum Stage { DIG, SEAL, WAIT, PILLAR, BOX }

    /** How deep the bot's feet end up below where it started. */
    private static final int DEPTH = 3;

    private Stage stage = Stage.DIG;

    public HideTask() {}

    /** Skip the digging: straight up a 3-high pillar (something's already on top of us). */
    public static HideTask pillar() {
        HideTask t = new HideTask();
        t.stage = Stage.PILLAR;
        return t;
    }

    /**
     * Wall ourselves in where we stand: blocks all round at feet and head height and one
     * overhead. Quick, works against skeletons too, and nothing can reach in.
     */
    public static HideTask box() {
        HideTask t = new HideTask();
        t.stage = Stage.BOX;
        return t;
    }

    /** Still building a pillar or a box (exposed while we do it). */
    public boolean building() {
        return this.stage == Stage.PILLAR || this.stage == Stage.BOX;
    }

    public boolean pillaring() {
        return this.stage == Stage.PILLAR;
    }

    /** Already somewhere safe (on top of a pillar): just wait here. */
    public static HideTask stay() {
        HideTask t = new HideTask();
        t.stage = Stage.WAIT;
        t.pillared = true;
        return t;
    }

    /** Up a finished pillar (out of zombie reach). */
    public boolean onPillar(BotPlayer bot) {
        return this.pillared && this.top != null && bot.onGround() && bot.getY() >= this.top.getY() + 2.9;
    }
    /** The hole's column (x/z) and the ground level we started on (y). */
    private @Nullable BlockPos top;
    private int ticks;
    private int stuckTicks;
    private int lastFeetY = Integer.MIN_VALUE;
    private @Nullable BlockPos jumpedFrom;
    private @Nullable BlockPos waitSpot;
    private boolean pillared;
    private final java.util.Set<BlockPos> boxSkipped = new java.util.HashSet<>();
    private int boxTries;

    @Override
    public String activity() {
        return this.stage == Stage.WAIT ? "Hiding" : "Digging in";
    }

    /**
     * Can't dig down here: wall ourselves in if we've the blocks (safe from arrows too),
     * otherwise up a pillar.
     */
    private static Stage fallback(BotPlayer bot) {
        return Inv.scaffoldCount(bot) >= 9 ? Stage.BOX : Stage.PILLAR;
    }

    /** Already in a covered hole? */
    public static boolean isHidden(BotPlayer bot) {
        WorldView w = new WorldView(bot.level());
        BlockPos feet = Navigator.feet(bot);
        if (w.passable(feet.above(2))) return false;
        int walls = 0;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (!w.passable(feet.relative(d)) && !w.passable(feet.relative(d).above())) walls++;
        }
        return walls >= 4;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (++this.ticks > 20 * 60 * 10) return Status.DONE;
        WorldView w = new WorldView(bot.level());
        BlockPos feet = Navigator.feet(bot);
        brain.navigator().stop(bot);
        if (isHidden(bot) && this.stage != Stage.WAIT) {
            this.stage = Stage.WAIT;
            this.waitSpot = feet;
        }
        if (this.top == null) this.top = feet;
        BlockPos top = this.top;

        // Not making progress (no deeper, not higher) for a long while? Try something else.
        if (feet.getY() != this.lastFeetY) {
            this.lastFeetY = feet.getY();
            this.stuckTicks = 0;
        } else if (this.stage != Stage.WAIT && ++this.stuckTicks > 20 * 20) {
            brain.breaker().cancel(bot);
            if (this.stage == Stage.PILLAR) return Status.FAILED;
            this.stage = fallback(bot);
            this.top = feet;
            this.stuckTicks = 0;
            return Status.RUNNING;
        }

        switch (this.stage) {
            case DIG -> {
                int bottom = top.getY() - DEPTH;
                if (feet.getY() <= bottom) {
                    this.stage = Stage.SEAL;
                    return Status.RUNNING;
                }
                // Always dig straight down the same column, wherever the body has drifted.
                BlockPos below = new BlockPos(top.getX(), feet.getY() - 1, top.getZ());
                centre(bot, brain.controls(), top);
                if (w.passable(below)) return Status.RUNNING; // dug: centring drops us in
                if (!canDigDown(w, below)) {
                    brain.breaker().cancel(bot);
                    // Got partway down? A two-deep hole with a lid still beats nothing.
                    if (feet.getY() < top.getY() - 1) {
                        this.stage = Stage.SEAL;
                    } else {
                        this.stage = fallback(bot);
                        this.top = feet;
                    }
                    return Status.RUNNING;
                }
                brain.breaker().start(below);
                if (brain.breaker().tick(bot, brain.controls()) == BlockBreaker.Result.FAILED) {
                    this.stage = fallback(bot);
                    this.top = feet;
                }
            }
            case SEAL -> {
                // A lid over our head. The hole's walls hold it up.
                BlockPos cap = feet.above(2);
                if (!w.state(cap).canBeReplaced()) {
                    this.stage = Stage.WAIT;
                    return Status.RUNNING;
                }
                centre(bot, brain.controls(), top);
                if (!bot.onGround()) return Status.RUNNING;
                if (Inv.scaffoldCount(bot) == 0) return Status.FAILED;
                Placer.place(bot, cap, Inv.scaffold(bot));
            }
            case PILLAR -> {
                // Can't dig here: get up out of reach instead.
                if (feet.getY() >= top.getY() + 3 || Inv.scaffoldCount(bot) == 0) {
                    this.stage = Stage.WAIT;
                    this.pillared = true;
                    return Status.RUNNING;
                }
                centre(bot, brain.controls(), top);
                brain.controls().face(bot.getYRot(), 90f);
                if (bot.onGround()) {
                    this.jumpedFrom = feet;
                    brain.controls().jump = true;
                } else if (this.jumpedFrom != null && bot.getY() > this.jumpedFrom.getY() + 1.05
                        && w.state(this.jumpedFrom).canBeReplaced()) {
                    // Top of the jump: put a block where we were standing.
                    Placer.place(bot, this.jumpedFrom, Inv.scaffold(bot));
                }
            }
            case BOX -> {
                brain.navigator().stop(bot);
                if (Inv.scaffoldCount(bot) == 0) return Status.FAILED;
                // Next empty spot of the box: sides at feet and head height, then the lid.
                BlockPos next = null;
                for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                    for (int y = 0; y <= 1; y++) {
                        BlockPos c = feet.relative(d).above(y);
                        if (w.state(c).canBeReplaced() && !this.boxSkipped.contains(c)) {
                            next = c;
                            break;
                        }
                    }
                    if (next != null) break;
                }
                if (next == null && w.state(feet.above(2)).canBeReplaced() && !this.boxSkipped.contains(feet.above(2))) next = feet.above(2);
                if (next == null) {
                    this.stage = Stage.WAIT;
                    this.waitSpot = feet;
                    return Status.RUNNING;
                }
                // A mob standing in the gap stops the block going in; skip it after a couple of tries.
                if (!Placer.place(bot, next, Inv.scaffold(bot)) && ++this.boxTries > 3) {
                    this.boxSkipped.add(next);
                    this.boxTries = 0;
                }
            }
            case WAIT -> {
                // Sit tight. The goal that started this decides when it's safe. But if we've
                // been shoved out of the hole, or the lid's gone, sort it out again.
                if (this.waitSpot == null) this.waitSpot = feet;
                if (feet.distSqr(this.waitSpot) > 2) {
                    this.stage = Stage.DIG;
                    this.top = null;
                    this.waitSpot = null;
                    this.pillared = false;
                } else if (!this.pillared && w.passable(feet.above(2)) && Inv.scaffoldCount(bot) > 0) {
                    this.stage = Stage.SEAL;
                }
                return Status.RUNNING;
            }
        }
        return Status.RUNNING;
    }

    /**
     * Shuffle towards the middle of the column. Never sneaks: sneaking is exactly what stops
     * you dropping into a hole.
     */
    private static void centre(BotPlayer bot, Controls controls, BlockPos column) {
        double dx = column.getX() + 0.5 - bot.getX();
        double dz = column.getZ() + 0.5 - bot.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.08) return;
        // World offset -> the bot's own forward/strafe, whichever way it happens to face.
        float yaw = bot.getYRot() * Mth.DEG_TO_RAD;
        double sin = Mth.sin(yaw);
        double cos = Mth.cos(yaw);
        double forward = -dx * sin + dz * cos;
        double strafe = dx * cos + dz * sin;
        double speed = Math.min(1.0, dist * 2.5) / dist;
        controls.forward = (float) (forward * speed);
        controls.strafe = (float) (strafe * speed);
        controls.sneak = false;
        controls.sprint = false;
    }

    private static boolean canDigDown(WorldView w, BlockPos below) {
        if (!w.breakable(below)) return false;
        if (w.isWater(below.below()) || w.isLava(below.below())) return false;
        // Never dig into a drop we can't climb back out of.
        return w.solidFloor(below.below()) || w.breakable(below.below());
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        brain.breaker().cancel(bot);
    }

    /** True if the block above this one would fall in when it's dug out. */
    public static boolean fallsIn(WorldView w, BlockPos pos) {
        return w.state(pos.above()).getBlock() instanceof FallingBlock;
    }
}
