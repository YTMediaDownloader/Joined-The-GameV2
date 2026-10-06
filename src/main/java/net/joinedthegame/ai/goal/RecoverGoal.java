package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.task.CollectItems;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Died? Run back and grab your stuff before it despawns. Gives up after 5 minutes (that's
 * when dropped items vanish anyway). Dying again on the way just updates where to go; after
 * three deaths in a row the bot cuts its losses and starts over like a fresh spawn.
 */
public final class RecoverGoal extends Goal {
    /** Dropped items despawn after 5 minutes, so there's no point trying for longer. */
    public static final int GIVE_UP_TICKS = 20 * 60 * 5;
    public static final int MAX_DEATHS = 3;

    private @Nullable CollectItems collect;
    private int navFailures;

    public RecoverGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        GlobalPos death = this.brain.deathSpot();
        if (death == null || bot.isDeadOrDying()) return 0;
        if (death.dimension() != bot.level().dimension()) return 0;
        // Not in the dark without armour: that's how it died in the first place. Morning.
        if (bot.level().isDarkOutside() && !ShelterGoal.nightReady(bot)) return 0;
        if (this.brain.now() - this.brain.deathTime() > GIVE_UP_TICKS) {
            this.brain.remember(net.joinedthegame.ai.BotMemory.EventType.LOST_ITEMS, "", death, 0.6f);
            this.brain.forgetDeath(false);
            return 0;
        }
        return 72;
    }

    @Override
    public void start(BotPlayer bot) {
        this.collect = null;
        this.navFailures = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        GlobalPos death = this.brain.deathSpot();
        if (death == null) return false;
        Vec3 spot = Vec3.atCenterOf(death.pos());

        if (this.collect != null) {
            if (this.collect.tick(bot, this.brain) == CollectItems.Status.RUNNING) return true;
            this.brain.remember(net.joinedthegame.ai.BotMemory.EventType.RECOVERED_ITEMS, "", death, 0.5f);
            this.brain.forgetDeath(true);
            return false;
        }

        if (bot.position().distanceToSqr(spot) < 6 * 6) {
            this.brain.navigator().stop(bot);
            this.collect = new CollectItems(death.pos(), 10, 20 * 15);
            return true;
        }

        Navigator nav = this.brain.navigator();
        nav.sprint = true;
        if (!nav.isMoving()) {
            nav.moveNear(bot, spot, 4);
            if (nav.status() == Navigator.Status.FAILED && ++this.navFailures > 10) {
                this.brain.log("can't get back to where I died, giving up");
                this.brain.forgetDeath(false);
                return false;
            }
        }
        return true;
    }

    @Override
    public String activity() {
        return "Going back for my stuff";
    }
}
