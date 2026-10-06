package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.project.NetherTrip;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.bot.BotPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Going to the nether (see {@link NetherTrip}). Runs when the bot's focus is
 * {@link BotBrain.Focus#NETHER}, and always if it finds itself in the nether with no trip under
 * way (after a restart, say): then it's just getting home.
 */
public final class NetherGoal extends Goal {
    private @Nullable NetherTrip trip;

    public NetherGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        // Over there: this is the plan (low enough that picking up blaze rods and raiding
        // fortress chests still get a look in).
        if (NetherTrip.inNether(bot)) return 40;
        if (this.brain.focus() != BotBrain.Focus.NETHER) return 0;
        // Setting off: in daylight, like anyone sensible.
        if (this.trip == null && bot.level().isDarkOutside()) return 0;
        return 45;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (this.trip == null) this.trip = new NetherTrip();
        Task.Status status = this.trip.tick(bot, this.brain);
        if (status == Task.Status.RUNNING) return true;
        this.trip.stop(bot, this.brain);
        this.brain.log("nether trip " + status);
        this.trip = null;
        if (status == Task.Status.FAILED) {
            this.brain.focusDone(BotBrain.Focus.NONE);
            cooldown(this.brain.now(), 20 * 60 * 10);
        } else {
            this.brain.focusDone(BotBrain.Focus.NETHER);
        }
        return false;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        if (this.trip != null) this.trip.stop(bot, this.brain);
    }

    @Override
    public String activity() {
        return this.trip == null ? "Thinking about the nether" : this.trip.activity();
    }
}
