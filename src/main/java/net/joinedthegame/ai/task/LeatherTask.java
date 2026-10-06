package net.joinedthegame.ai.task;

import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import org.jspecify.annotations.Nullable;

/**
 * Get leather (for books): hunt cows, picking up what they drop. Leaves calves alone, and
 * the last couple of cows in sight too, so there are still cows round here next week.
 */
public final class LeatherTask implements Task {
    private final int wanted;
    private @Nullable AbstractCow target;
    private @Nullable CollectItems collect;
    private int ticks;
    private int repathIn;
    private int searching;

    public LeatherTask(BotPlayer bot, int count) {
        this.wanted = Inv.count(bot, Items.LEATHER) + count;
    }

    @Override
    public String activity() {
        return "Hunting cows for leather";
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (Inv.count(bot, Items.LEATHER) >= this.wanted) return Status.DONE;
        if (++this.ticks > 20 * 180) return Status.FAILED;

        if (this.collect != null) {
            if (this.collect.tick(bot, brain) == Status.RUNNING) return Status.RUNNING;
            this.collect = null;
        }

        AbstractCow cow = this.target;
        if (cow == null || !cow.isAlive()) {
            if (cow != null) {
                this.collect = new CollectItems(cow.blockPosition(), 5, 60);
                this.target = null;
                return Status.RUNNING;
            }
            List<AbstractCow> nearby = Senses.entitiesNear(bot, AbstractCow.class, 32, c -> c.isAlive() && !c.isBaby());
            this.target = nearby.size() > 2 ? nearby.getFirst() : null;
            if (this.target == null) return lookForCows(bot, brain);
            return Status.RUNNING;
        }

        Navigator nav = brain.navigator();
        double dist = Math.sqrt(cow.distanceToSqr(bot));
        if (Inv.bestWeapon(bot) >= 0) Inv.hold(bot, Inv.bestWeapon(bot));
        brain.controls().lookAt(bot, cow.getBoundingBox().getCenter());
        if (dist > 2.6) {
            if (--this.repathIn <= 0) {
                nav.moveNear(bot, cow.position(), 1.5);
                this.repathIn = 15;
                if (nav.status() == Navigator.Status.FAILED) {
                    this.target = null;
                    nav.stop(bot);
                }
            }
        } else {
            nav.stop(bot);
            if (bot.getAttackStrengthScale(0.5f) >= 0.95f) {
                bot.attack(cow);
                bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            }
        }
        return Status.RUNNING;
    }

    /** No cows (or too few to take from) in sight: roam about and look further afield. */
    private Status lookForCows(BotPlayer bot, BotBrain brain) {
        if (brain.navigator().isMoving()) return Status.RUNNING;
        if (++this.searching > 10) return Status.FAILED;
        net.joinedthegame.ai.Roam.tick(bot, brain.navigator());
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        brain.navigator().stop(bot);
    }
}
