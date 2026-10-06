package net.joinedthegame.ai.goal;

import java.util.UUID;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Crouch at a bot while looking at it and it follows you around for a while. */
public final class FollowGoal extends Goal {
    private Vec3 lastGoal = Vec3.ZERO;

    public FollowGoal(BotBrain brain) {
        super(brain);
    }

    private @Nullable Player target(BotPlayer bot) {
        UUID id = this.brain.followTarget();
        if (id == null) return null;
        Player player = bot.level().getPlayerByUUID(id);
        // Gone into spectator mode: they're not really here any more, stop following.
        if (player != null && player.isSpectator()) {
            this.brain.stopFollowing();
            return null;
        }
        if (player == null || !player.isAlive() || player.distanceToSqr(bot) > 80 * 80) {
            if (player != null || bot.level().getServer().getPlayerList().getPlayer(id) == null) this.brain.stopFollowing();
            return null;
        }
        return player;
    }

    @Override
    public int priority(BotPlayer bot) {
        return target(bot) != null ? 70 : 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        Player player = target(bot);
        if (player == null) return false;
        return followStep(bot, this.brain, player, 3.0, this);
    }

    /** Shared with {@link TagAlongGoal}. */
    static boolean followStep(BotPlayer bot, BotBrain brain, Player player, double keepWithin, Object state) {
        Navigator nav = brain.navigator();
        double dist = Math.sqrt(player.distanceToSqr(bot));
        if (dist > keepWithin + 1.5) {
            // Open ground and we can see them: just run at them, no pathfinding, sprinting
            // to catch up. That's how players follow each other.
            int y = Navigator.feet(bot).getY();
            net.joinedthegame.ai.nav.WorldView world = new net.joinedthegame.ai.nav.WorldView(bot.level());
            if (dist < 24 && Math.abs(player.getY() - bot.getY()) < 1.1 && bot.onGround() && bot.hasLineOfSight(player)
                    && Navigator.straightWalkable(world, bot.position(), player.position(), y)) {
                nav.stop(bot);
                brain.controls().faceTowards(bot, player.position());
                brain.controls().forward = 1.0f;
                brain.controls().sprint = dist > keepWithin + 4 || player.isSprinting();
                if (bot.horizontalCollision) brain.controls().jump = true;
                return true;
            }
            nav.sprint = dist > keepWithin + 4 || player.isSprinting();
            Vec3 goal = player.position();
            FollowGoal self = state instanceof FollowGoal f ? f : null;
            Vec3 last = self != null ? self.lastGoal : Vec3.ZERO;
            if (!nav.isMoving() || goal.distanceToSqr(last) > 9) {
                nav.moveNear(bot, goal, keepWithin);
                if (self != null) self.lastGoal = goal;
            }
        } else {
            nav.stop(bot);
            brain.controls().lookAt(bot, player);
        }
        return true;
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return true; // following a player who's standing still
    }

    @Override
    public String activity() {
        return "Following you";
    }
}
