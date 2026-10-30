package net.joinedthegame.ai.project;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.task.FetchTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Getting the things a project needs, like a player would: what's in the chests at home first,
 * then made or gathered (see {@link Tasks#toward}). Nice-to-haves that can't be had are skipped;
 * a must-have that can't be had fails the project.
 */
final class Gatherer {
    private final Set<String> skipped = new HashSet<>();
    private final Set<String> fetched = new HashSet<>();
    private Tasks.@Nullable Need current;
    /** Why it failed, for the log. */
    String failedOn = "";

    /** The next step towards having all of {@code needs}; null once there's nothing left to get. */
    @Nullable Task next(BotPlayer bot, BotBrain brain, List<Tasks.Need> needs, boolean[] failed) {
        this.current = null;
        for (Tasks.Need n : needs) {
            if (this.skipped.contains(n.label()) || n.missing(bot) <= 0) continue;
            this.current = n;
            if (this.fetched.add(n.label())) {
                Task fetch = FetchTask.from(bot, brain, n.have(), n.missing(bot), "Getting " + n.label() + " from my chests");
                if (fetch != null) return fetch;
            }
            boolean[] cant = {false};
            Task step = Tasks.toward(bot, n, cant);
            if (step != null) return step;
            if (cant[0]) {
                if (n.optional()) {
                    this.skipped.add(n.label());
                    continue;
                }
                this.failedOn = n.label();
                failed[0] = true;
                return null;
            }
        }
        return null;
    }

    /** A step towards the current need just failed: if it was only nice to have, do without. */
    void stepFailed() {
        Tasks.Need n = this.current;
        if (n != null && n.optional()) this.skipped.add(n.label());
    }

    boolean skipped(String label) {
        return this.skipped.contains(label);
    }
}
