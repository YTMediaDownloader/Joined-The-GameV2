package net.joinedthegame.ai.goal;

import net.joinedthegame.JtgConfig;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Don't wander too far from the real players. Bots load chunks like any player, so one
 * that strolls 2000 blocks away costs performance, and you'd never see it anyway.
 */
public final class LeashGoal extends Goal {
    private @Nullable ServerPlayer anchor;

    public LeashGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        int max = JtgConfig.MAX_DISTANCE.get();
        if (max <= 0) return 0;
        ServerPlayer nearest = BotManager.nearestRealPlayer(bot);
        this.anchor = nearest;
        if (nearest == null) return 0;
        return nearest.distanceToSqr(bot) > (double) max * max ? 60 : 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        ServerPlayer anchor = this.anchor;
        if (anchor == null || anchor.level() != bot.level()) return false;
        int max = JtgConfig.MAX_DISTANCE.get();
        if (anchor.distanceToSqr(bot) < (max * 0.5) * (max * 0.5)) return false;
        Navigator nav = this.brain.navigator();
        nav.sprint = true;
        if (!nav.isMoving()) {
            nav.moveNear(bot, anchor.position(), max * 0.4);
            if (nav.status() == Navigator.Status.FAILED) {
                nav.stop(bot);
                cooldown(this.brain.now(), 100);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return true;
    }

    @Override
    public String activity() {
        return "Heading back";
    }
}
