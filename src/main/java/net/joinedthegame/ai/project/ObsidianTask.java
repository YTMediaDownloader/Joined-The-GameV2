package net.joinedthegame.ai.project;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.task.FetchTask;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Get some obsidian, the way a player would: whatever's stored at home first, then any
 * natural obsidian in sight, otherwise make it (water on a lava pool, see
 * {@link NetherTrip.CastObsidian}), going down to lava depth to find a pool if there isn't one
 * nearby. Needs a diamond pickaxe. Used by the nether trip (a portal) and enchanting (a table).
 */
public final class ObsidianTask implements Task {
    private final int wanted;
    private @Nullable Task sub;
    private int fails;
    private int naturalFails;
    private boolean lastWasNatural;
    private boolean lookForLava;
    private boolean triedChests;

    public ObsidianTask(int wanted) {
        this.wanted = wanted;
    }

    @Override
    public String activity() {
        return this.sub != null ? this.sub.activity() : "Getting obsidian";
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        int have = Inv.count(bot, Items.OBSIDIAN);
        if (have >= this.wanted) return Status.DONE;
        if (Inv.pickaxeTier(bot) < 5) return Status.FAILED;
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            Task finished = this.sub;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (s == Status.FAILED) {
                if (finished instanceof NetherTrip.CastObsidian c && c.noLava) {
                    // Nothing to cast from around here: go down to lava depth and look for some.
                    this.lookForLava = true;
                    return Status.RUNNING;
                }
                if (this.lastWasNatural) this.naturalFails++;
                if (++this.fails > 8) {
                    brain.log("obsidian: can't get any, giving up for now");
                    return Status.FAILED;
                }
            }
            return Status.RUNNING;
        }
        int missing = this.wanted - have;
        // Some put away at home from before? Go and get that first.
        if (!this.triedChests) {
            this.triedChests = true;
            if (FetchTask.stored(bot, brain, s -> s.is(Items.OBSIDIAN)) > 0) {
                this.sub = FetchTask.from(bot, brain, s -> s.is(Items.OBSIDIAN), missing, "Getting my obsidian from my chests");
                if (this.sub != null) return Status.RUNNING;
            }
        }
        // Natural obsidian in sight? Just mine it.
        // (Unless that keeps failing: it's under lava, behind bedrock... then cast our own.)
        this.lastWasNatural = false;
        if (this.naturalFails < 2 && Senses.findBlock(bot, s -> s.is(Blocks.OBSIDIAN), 16, 8, true, brain::isBlacklisted) != null) {
            this.lastWasNatural = true;
            this.sub = new MineTask(bot, Sources.OBSIDIAN, missing, "Mining obsidian");
            return Status.RUNNING;
        }
        // A water bucket to cast it with.
        if (Inv.count(bot, Items.WATER_BUCKET) == 0) {
            if (Inv.count(bot, Items.BUCKET) == 0) {
                boolean[] failed = {false};
                this.sub = Tasks.toward(bot, Tasks.Need.of("a bucket", st -> st.is(Items.BUCKET), 1, b -> Items.BUCKET), failed);
                if (failed[0] || this.sub == null) return Status.FAILED;
                return Status.RUNNING;
            }
            this.sub = new FarmProject.FillBucket();
            return Status.RUNNING;
        }
        if (this.lookForLava) {
            this.lookForLava = false;
            this.sub = new NetherTrip.LookForLava(bot, missing);
            return Status.RUNNING;
        }
        this.sub = new NetherTrip.CastObsidian();
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
    }
}
