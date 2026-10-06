package net.joinedthegame.ai.project;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: make a bow. Three string (cobwebs drop it when you cut them with a sword;
 * spiders drop it too), three sticks, then some arrows to go with it if there are feathers.
 */
public final class BowProject implements Task {
    static final Sources COBWEB = new Sources("cobwebs", s -> s.is(Blocks.COBWEB), s -> s.is(Items.STRING), 0, 30);

    private @Nullable Task sub;
    private int steps;

    @Override
    public String activity() {
        return this.sub != null ? this.sub.activity() : "Making a bow";
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            this.sub = null;
        }
        if (++this.steps > 30) return Status.FAILED;
        boolean hasBow = Inv.count(bot, Items.BOW) > 0;
        if (!hasBow && Inv.count(bot, Items.STRING) < 3) {
            // Cobwebs in sight (mineshafts are full of them)? Cut some down.
            if (Senses.findBlock(bot, s -> s.is(Blocks.COBWEB), 24, 12, true, brain::isBlacklisted) != null) {
                this.sub = new MineTask(bot, COBWEB, 3 - Inv.count(bot, Items.STRING), "Cutting cobwebs for string");
                return Status.RUNNING;
            }
            brain.log("bow: no string to be had round here");
            return Status.FAILED;
        }
        boolean[] failed = {false};
        if (!hasBow) {
            this.sub = Tasks.toward(bot, Tasks.Need.of("a bow", s -> s.is(Items.BOW), 1, b -> Items.BOW), failed);
            return failed[0] ? Status.FAILED : Status.RUNNING;
        }
        // Bow made. Arrows too, if we've the feathers.
        if (Inv.count(bot, Items.ARROW) < 16 && Inv.count(bot, Items.FEATHER) > 0) {
            this.sub = Tasks.toward(bot, Tasks.Need.nice("arrows", s -> s.is(Items.ARROW), Inv.count(bot, Items.ARROW) + 4, b -> Items.ARROW), failed);
            if (this.sub != null && !failed[0]) return Status.RUNNING;
        }
        return Status.DONE;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
    }
}
