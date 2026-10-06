package net.joinedthegame.ai.task;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.bot.BotPlayer;

/** A multi-tick job: mine this, craft that, smelt those. Ticked by a goal. */
public interface Task {
    enum Status { RUNNING, DONE, FAILED }

    Status tick(BotPlayer bot, BotBrain brain);

    default void stop(BotPlayer bot, BotBrain brain) {}

    /** Shown in the bot list, e.g. "Mining iron". */
    String activity();
}
