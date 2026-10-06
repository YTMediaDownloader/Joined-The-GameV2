package net.joinedthegame.ai;

import net.joinedthegame.bot.BotPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The bot's "keyboard and mouse". Goals and the navigator write to this each tick, and
 * {@link #apply} turns it into the same inputs a player's client would produce: WASD,
 * jump, sneak, sprint and where the camera points.
 */
public final class Controls {
    public float forward;
    public float strafe;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;
    /** Shield up this tick (for readability; the slowdown comes from using any item). */
    public boolean blocking;

    private float targetYaw;
    private float targetPitch;
    private boolean lookSet;
    /** Degrees per tick. Players don't snap their camera around instantly. */
    private float turnSpeed = 40f;

    public void reset() {
        this.forward = 0;
        this.strafe = 0;
        this.jump = false;
        this.sneak = false;
        this.sprint = false;
        this.blocking = false;
        this.turnSpeed = 40f;
    }

    public void lookAt(BotPlayer bot, Vec3 target) {
        Vec3 eye = bot.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        this.targetYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        this.targetPitch = (float) -(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG);
        this.lookSet = true;
    }

    public void lookAt(BotPlayer bot, Entity entity) {
        lookAt(bot, entity.getEyePosition());
    }

    public void face(float yaw, float pitch) {
        this.targetYaw = yaw;
        this.targetPitch = pitch;
        this.lookSet = true;
    }

    /** Turn to walk toward a point, keeping the camera roughly level. */
    public void faceTowards(BotPlayer bot, Vec3 target) {
        double dx = target.x - bot.getX();
        double dz = target.z - bot.getZ();
        this.targetYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        this.targetPitch = 10f;
        this.lookSet = true;
    }

    public void turnSpeed(float degreesPerTick) {
        this.turnSpeed = degreesPerTick;
    }

    /** True once the camera has (nearly) finished turning to where it was told to look. */
    public boolean isLookingAtTarget(BotPlayer bot, float toleranceDegrees) {
        return Math.abs(Mth.wrapDegrees(bot.getYRot() - this.targetYaw)) <= toleranceDegrees
                && Math.abs(bot.getXRot() - this.targetPitch) <= toleranceDegrees;
    }

    public float targetYaw() {
        return this.targetYaw;
    }

    public void apply(BotPlayer bot) {
        if (this.lookSet) {
            float yaw = Mth.approachDegrees(bot.getYRot(), this.targetYaw, this.turnSpeed);
            float pitch = Mth.approachDegrees(bot.getXRot(), Mth.clamp(this.targetPitch, -90f, 90f), this.turnSpeed);
            bot.setYRot(yaw);
            bot.setXRot(pitch);
            bot.setYHeadRot(yaw);
        }
        // A real client slows you to a crawl while you hold up a shield or eat; the server
        // doesn't, so do it here or bots would sprint around behind their shields.
        boolean usingItem = bot.isUsingItem();
        bot.zza = usingItem ? this.forward * 0.2f : this.forward;
        bot.xxa = usingItem ? this.strafe * 0.2f : this.strafe;
        if (usingItem) this.sprint = false;
        bot.setJumping(this.jump);
        bot.setShiftKeyDown(this.sneak);
        boolean canSprint = this.sprint && this.forward > 0 && !this.sneak && bot.getFoodData().getFoodLevel() > 6;
        bot.setSprinting(canSprint);
    }
}
