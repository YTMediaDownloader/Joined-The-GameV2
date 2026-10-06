package net.joinedthegame.ai.goal;

import java.util.UUID;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.entity.player.Player;

/** After a friendly punch: stop, turn and look at whoever did it for a few seconds. */
public final class InterruptedGoal extends Goal {
    public InterruptedGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        return this.brain.isInterrupted() ? 100 : 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        UUID by = this.brain.interruptedBy();
        Player player = by == null ? null : bot.level().getPlayerByUUID(by);
        if (player != null) this.brain.controls().lookAt(bot, player);
        return this.brain.isInterrupted();
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return true;
    }

    @Override
    public String activity() {
        return "Standing still";
    }
}
