package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.bot.BotPlayer;

/**
 * Something a bot might want to do: fight, eat, sleep, follow you, work on its gear.
 * Every so often the brain asks every goal how much it wants to run right now and switches
 * to the most urgent one.
 */
public abstract class Goal {
    protected final BotBrain brain;
    private long cooldownUntil;

    protected Goal(BotBrain brain) {
        this.brain = brain;
    }

    /** How urgent this is right now. 0 means "not now". Roughly 0..100. */
    public abstract int priority(BotPlayer bot);

    public void start(BotPlayer bot) {}

    /** Run one tick. Return false when finished (or given up). */
    public abstract boolean tick(BotPlayer bot);

    public void stop(BotPlayer bot) {
        this.brain.navigator().stop(bot);
    }

    /**
     * Standing still on purpose, waiting on the game (asleep, hiding out the night, food in the
     * furnace, a trial spawner's reward...): the freeze watchdog leaves it alone.
     */
    public boolean waitingOnPurpose(BotPlayer bot) {
        return false;
    }

    /** Text for the bot list, e.g. "Fighting a zombie". */
    public abstract String activity();

    public final boolean onCooldown(long now) {
        return now < this.cooldownUntil;
    }

    /** Don't pick this goal again for a while (it just failed, or just finished). */
    public final void cooldown(long now, int ticks) {
        // Only ever extend: a goal that asked for a long rest after failing keeps it.
        this.cooldownUntil = Math.max(this.cooldownUntil, now + ticks);
    }
}
