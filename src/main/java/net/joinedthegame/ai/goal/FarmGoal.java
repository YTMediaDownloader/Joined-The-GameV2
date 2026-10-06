package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.project.Farms;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.CollectItems;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.GlobalPos;
import org.jspecify.annotations.Nullable;

/**
 * Looking after the farm: when enough wheat is ripe, go harvest it and replant straight away,
 * and sow any bare patches while there are seeds to spare. The wheat ends up as bread (see
 * {@link HuntGoal}), so a bot with a farm stops having to raid villages.
 */
public final class FarmGoal extends Goal {
    private @Nullable Task task;
    private @Nullable GlobalPos farm;

    public FarmGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (bot.level().isDarkOutside()) return 0;
        if (this.task != null) return 46;
        GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        GlobalPos farm = this.brain.memory().nearest("farm", here, 96);
        if (farm == null || !bot.level().isLoaded(farm.pos())) return 0;
        WorldView w = new WorldView(bot.level());
        if (!Farms.intact(w, farm.pos())) {
            this.brain.log("my farm's gone");
            this.brain.memory().forget("farm", farm);
            return 0;
        }
        this.farm = farm;
        int ripe = Farms.ripe(w, farm.pos());
        boolean hungry = Inv.count(bot, s -> Inv.isFood(s) && !Inv.isBadFood(s)) < 4;
        // Above everyday gearing up (which tops out at 40), so the wheat doesn't rot while it mines.
        if (ripe >= 10 || ripe >= 3 && hungry) return hungry ? 50 : 46;
        if (Farms.bare(w, farm.pos()) >= 4 && Inv.count(bot, Farms::isSeed) >= 4) return 45;
        return 0;
    }

    @Override
    public void start(BotPlayer bot) {
        GlobalPos farm = this.farm;
        if (this.task != null || farm == null) return;
        WorldView w = new WorldView(bot.level());
        this.task = Tasks.sequence(new BuildTask(Farms.harvest(w, farm.pos()), "Harvesting the farm"),
                new CollectItems(farm.pos().above(), 5, 100));
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (this.task == null) return false;
        Task.Status status = this.task.tick(bot, this.brain);
        if (status == Task.Status.RUNNING) return true;
        this.task.stop(bot, this.brain);
        this.task = null;
        cooldown(this.brain.now(), 20 * 60 * 2);
        return false;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        if (this.task != null) this.task.stop(bot, this.brain);
        this.task = null;
    }

    @Override
    public String activity() {
        return this.task == null ? "Farming" : this.task.activity();
    }
}
