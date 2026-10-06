package net.joinedthegame.ai.skill;

import net.joinedthegame.ai.Controls;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Holding left-click on a block: pick the best tool, look at it, swing, show the crack
 * animation and break it once the progress fills up. Break speed comes from the game's own
 * formula, so tools, efficiency, haste and being underwater all count.
 */
public final class BlockBreaker {
    public enum Result { RUNNING, DONE, FAILED }

    public static final double REACH = 4.5;

    private @Nullable BlockPos target;
    private float progress;
    private int ticks;
    private int lastStage = -1;

    /** Progress on the last block we stopped hitting, so coming straight back resumes it. */
    private @Nullable BlockPos pausedPos;
    private float pausedProgress;
    private long pausedAt;
    private boolean checkResume;

    public @Nullable BlockPos target() {
        return this.target;
    }

    public void start(BlockPos pos) {
        if (pos.equals(this.target)) return;
        this.target = pos.immutable();
        this.progress = 0;
        this.ticks = 0;
        this.lastStage = -1;
        this.checkResume = true;
    }

    /** Mid-way through breaking something? */
    public boolean busy() {
        return this.target != null && this.progress > 0;
    }

    public void cancel(BotPlayer bot) {
        if (this.target != null) {
            this.pausedPos = this.target;
            this.pausedProgress = this.progress;
            this.pausedAt = bot.level().getGameTime();
            bot.level().destroyBlockProgress(bot.getId(), this.target, -1);
        }
        this.target = null;
    }

    /**
     * Close enough to click a container (chest, furnace, table) like a player: about 4 blocks to
     * the nearest face, and actually able to see it (no opening chests through walls).
     */
    public static boolean canUse(BotPlayer bot, BlockPos pos) {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(pos);
        Vec3 eye = bot.getEyePosition();
        double dx = Math.max(Math.max(box.minX - eye.x, 0), eye.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - eye.y, 0), eye.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - eye.z, 0), eye.z - box.maxZ);
        if (dx * dx + dy * dy + dz * dz > 4.0 * 4.0) return false;
        return canSee(bot, eye, pos);
    }

    /** Clear line from {@code eye} to the block (it's the first thing hit). */
    public static boolean canSee(BotPlayer bot, Vec3 eye, BlockPos pos) {
        var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(pos),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    /**
     * Would breaking this drop us more than 3 blocks? Only for the block(s) holding us up: if
     * nothing else is under our feet and there's no ground or water within 3 blocks of it.
     */
    public static boolean wouldDrop(BotPlayer bot, BlockPos pos) {
        var w = new net.joinedthegame.ai.nav.WorldView(bot.level());
        var box = bot.getBoundingBox();
        int floorY = net.minecraft.util.Mth.floor(box.minY - 0.05);
        if (pos.getY() != floorY) return false;
        boolean under = false;
        for (int x = net.minecraft.util.Mth.floor(box.minX); x <= net.minecraft.util.Mth.floor(box.maxX - 1e-4); x++) {
            for (int z = net.minecraft.util.Mth.floor(box.minZ); z <= net.minecraft.util.Mth.floor(box.maxZ - 1e-4); z++) {
                BlockPos p = new BlockPos(x, floorY, z);
                if (p.equals(pos)) under = true;
                else if (w.solidFloor(p)) return false; // still held up by the edge of another block
            }
        }
        if (!under) return false;
        for (int d = 1; d <= 3; d++) {
            BlockPos p = pos.below(d);
            if (w.isWater(p) || w.solidFloor(p)) return false;
            if (!w.passable(p)) return false; // something there to land on (or dig, later)
        }
        return true;
    }

    public static boolean inReach(BotPlayer bot, BlockPos pos) {
        return bot.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= REACH * REACH;
    }

    public Result tick(BotPlayer bot, Controls controls) {
        if (this.target == null) return Result.FAILED;
        ServerLevel level = bot.level();
        BlockState state = level.getBlockState(this.target);
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(level, this.target).isEmpty()) {
            finish(bot);
            return Result.DONE;
        }
        if (!inReach(bot, this.target) || state.getDestroySpeed(level, this.target) < 0) {
            cancel(bot);
            return Result.FAILED;
        }
        if (wouldDrop(bot, this.target)) {
            // The block we're standing on, with a long drop under it: never dig straight down
            // into a cave (whoever asked for this block).
            cancel(bot);
            return Result.FAILED;
        }

        if (this.checkResume) {
            this.checkResume = false;
            if (this.target.equals(this.pausedPos) && level.getGameTime() - this.pausedAt < 100) {
                this.progress = this.pausedProgress;
                this.ticks = 20;
            }
        }
        controls.lookAt(bot, Vec3.atCenterOf(this.target));
        if (this.ticks == 0 || this.ticks == 20) Inv.holdBestToolFor(bot, state);
        this.ticks++;
        if (this.ticks > 1200) {
            cancel(bot);
            return Result.FAILED;
        }
        if (!controls.isLookingAtTarget(bot, 25f) && this.ticks < 20) return Result.RUNNING;

        if (this.ticks % 5 == 0) bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        this.progress += state.getDestroyProgress(bot, level, this.target);
        int stage = (int) (this.progress * 10f);
        if (stage != this.lastStage) {
            level.destroyBlockProgress(bot.getId(), this.target, stage);
            this.lastStage = stage;
        }
        if (this.progress >= 1.0f) {
            BlockPos pos = this.target;
            finish(bot);
            bot.gameMode.destroyBlock(pos);
            bot.resetLastActionTime();
            return level.getBlockState(pos).isAir() || level.getBlockState(pos) != state ? Result.DONE : Result.FAILED;
        }
        return Result.RUNNING;
    }

    private void finish(BotPlayer bot) {
        if (this.target != null) bot.level().destroyBlockProgress(bot.getId(), this.target, -1);
        this.target = null;
        this.progress = 0;
        this.ticks = 0;
        this.lastStage = -1;
    }
}
