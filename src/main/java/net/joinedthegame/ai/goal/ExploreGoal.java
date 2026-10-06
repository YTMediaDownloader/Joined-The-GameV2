package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Nothing better to do: wander somewhere, have a look around, maybe wander back. */
public final class ExploreGoal extends Goal {
    private int ticks;
    private int lookAround;
    private boolean arrived;

    public ExploreGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        // In a wandering mood: this beats grinding for gear.
        if (this.brain.focus() == BotBrain.Focus.WANDER) return 41;
        return 10 + (int) (this.brain.personality().explorer() * 10);
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
        this.arrived = false;
        this.lookAround = 40 + bot.getRandom().nextInt(80);
        Vec3 dest = pickDestination(bot);
        if (dest != null) this.brain.navigator().moveNear(bot, dest, 3);
    }

    private Vec3 pickDestination(BotPlayer bot) {
        // Drift toward the nearest player so bots stay part of the action.
        ServerPlayer anchor = BotManager.nearestRealPlayer(bot);
        double cx = bot.getX();
        double cz = bot.getZ();
        if (anchor != null && anchor.distanceToSqr(bot) > 48 * 48 && bot.getRandom().nextBoolean()) {
            cx = anchor.getX();
            cz = anchor.getZ();
        }
        double range = 16 + this.brain.personality().explorer() * 32;
        if (this.brain.focus() == BotBrain.Focus.WANDER) range = 48 + bot.getRandom().nextInt(48);
        double angle = bot.getRandom().nextDouble() * Math.PI * 2;
        int x = Mth.floor(cx + Math.cos(angle) * range);
        int z = Mth.floor(cz + Math.sin(angle) * range);
        if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) return null;
        int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        // Underground? Explore at the same depth instead of tunnelling to the surface.
        if (bot.level().canSeeSky(bot.blockPosition()) || y - bot.getY() < 8) return new Vec3(x + 0.5, y, z + 0.5);
        return new Vec3(x + 0.5, bot.getY(), z + 0.5);
    }

    @Override
    public boolean tick(BotPlayer bot) {
        this.ticks++;
        Navigator nav = this.brain.navigator();
        if (!this.arrived && nav.isMoving() && this.ticks < 20 * 40) return true;
        this.arrived = true;
        // Look around for a bit like a player taking in the view.
        if (this.ticks % 30 == 0) {
            float yaw = bot.getYRot() + (bot.getRandom().nextFloat() - 0.5f) * 140f;
            this.brain.controls().face(yaw, (bot.getRandom().nextFloat() - 0.5f) * 30f);
        } else {
            this.brain.controls().face(this.brain.controls().targetYaw(), 0);
        }
        return --this.lookAround > 0;
    }

    @Override
    public String activity() {
        return "Exploring";
    }
}
