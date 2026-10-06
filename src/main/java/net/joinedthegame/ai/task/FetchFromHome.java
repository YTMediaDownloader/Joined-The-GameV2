package net.joinedthegame.ai.task;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Planner;
import net.joinedthegame.ai.Storage;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import org.jspecify.annotations.Nullable;

/**
 * Go home and take something out of the chests there (it's remembered as being stored).
 * If it turns out not to be there after all, the remembered list gets corrected.
 */
public final class FetchFromHome implements Task {
    private final Item item;
    private final int count;
    private final String label;
    private @Nullable Task sub;
    private boolean fetching;

    public FetchFromHome(Planner.Fetch fetch) {
        this.item = fetch.item();
        this.count = fetch.count();
        this.label = fetch.label();
    }

    @Override
    public String activity() {
        return this.sub != null ? this.sub.activity() : this.label;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (this.fetching) {
                Storage.refresh(bot, brain);
                return s;
            }
            if (s == Status.FAILED) return Status.FAILED;
        }
        BlockPos home = Storage.home(bot, brain);
        if (home == null) return Status.FAILED;
        if (home.distSqr(bot.blockPosition()) > 40 * 40 || !bot.level().isLoaded(home)) {
            this.sub = new TravelTask(home, 6, "Heading home for " + Planner.name(this.item));
            return Status.RUNNING;
        }
        Storage.refresh(bot, brain);
        this.sub = FetchTask.from(bot, brain, s -> s.is(this.item), this.count, this.label);
        if (this.sub == null) {
            brain.log("thought I had " + Planner.name(this.item) + " at home, but it's not there");
            return Status.FAILED;
        }
        this.fetching = true;
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        this.fetching = false;
    }
}
