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
        // A netherite project runs its own nether trip: don't start a second one alongside it.
        // (Even one already going: the project's trip takes over from here.)
        if (this.brain.focus() == BotBrain.Focus.PROJECT
                && this.brain.projects().current() == net.joinedthegame.ai.goal.ProjectGoal.Kind.NETHERITE) {
            if (this.trip != null) {
                this.trip.stop(bot, this.brain);
                this.trip = null;
            }
            return 0;
        }
        if (NetherTrip.inNether(bot)) return 40;
        if (this.brain.focus() != BotBrain.Focus.NETHER) return 0;
        // Setting off: in daylight, like anyone sensible.
        if (this.trip == null && bot.level().isDarkOutside()) return 0;
        return 45;
    }

    /** A nether trip is under way (it owns the bot's focus till it's done). */
    public boolean inProgress() {
        return this.trip != null;
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
            this.brain.focusFailed(BotBrain.Focus.NETHER);
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
