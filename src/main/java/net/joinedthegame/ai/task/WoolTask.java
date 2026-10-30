package net.joinedthegame.ai.task;

import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import org.jspecify.annotations.Nullable;

/**
 * Get 3 wool of one colour for a bed, the early-game way: whack sheep. Prefers sheep that
 * match wool already in the inventory so the colours add up.
 */
public final class WoolTask implements Task {
    private @Nullable Sheep target;
    private @Nullable CollectItems collect;
    private int ticks;
    private int repathIn;
    private int searching;
    private boolean triedRemembered;
    private @Nullable Task travel;

    /** The wool item for each dye colour, in {@link DyeColor} order. */
    public static Item wool(DyeColor color) {
        return Items.WOOL.asList().get(color.ordinal());
    }

    public static Item bed(DyeColor color) {
        return Items.BED.asList().get(color.ordinal());
    }

    /** The colour we have the most wool of, or null if none. */
    public static @Nullable DyeColor bestColor(BotPlayer bot) {
        DyeColor best = null;
        int most = 0;
        for (DyeColor color : DyeColor.values()) {
            int n = Inv.count(bot, wool(color));
            if (n > most) {
                most = n;
                best = color;
            }
        }
        return best;
    }

    public static boolean hasEnough(BotPlayer bot) {
        DyeColor color = bestColor(bot);
        return color != null && Inv.count(bot, wool(color)) >= 3;
    }

    @Override
    public String activity() {
        return "Hunting sheep";
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (hasEnough(bot)) return Status.DONE;
        if (++this.ticks > 20 * 120) return Status.FAILED;
        // String from spiders: four make a wool, no sheep needed.
        if (Inv.count(bot, Items.STRING) >= 4 && this.ticks % 20 == 1) {
            var step = new net.joinedthegame.ai.Planner(bot, false).next(wool(DyeColor.WHITE), Inv.count(bot, wool(DyeColor.WHITE)) + 1);
            if (step instanceof net.joinedthegame.ai.Planner.Craft craft && CraftTask.craftNow(bot, craft)) {
                brain.log("made wool from string");
                return Status.RUNNING;
            }
        }

        if (this.collect != null) {
            if (this.collect.tick(bot, brain) == Status.RUNNING) return Status.RUNNING;
            this.collect = null;
        }

        Sheep sheep = this.target;
        if (sheep == null || !sheep.isAlive()) {
            if (sheep != null) {
                this.collect = new CollectItems(sheep.blockPosition(), 5, 60);
                this.target = null;
                return Status.RUNNING;
            }
            DyeColor want = bestColor(bot);
            List<Sheep> nearby = Senses.entitiesNear(bot, Sheep.class, 32, s -> s.isAlive() && !s.isBaby() && Senses.wouldNotice(bot, s));
            this.target = nearby.stream().filter(s -> want == null || s.getColor() == want).findFirst()
                    .orElse(nearby.isEmpty() ? null : nearby.getFirst());
            if (this.target == null) return lookForSheep(bot, brain);
            brain.rememberPlace(bot, "sheep", this.target.blockPosition());
            return Status.RUNNING;
        }

        Navigator nav = brain.navigator();
        double dist = Math.sqrt(sheep.distanceToSqr(bot));
        if (Inv.bestWeapon(bot) >= 0) Inv.hold(bot, Inv.bestWeapon(bot));
        brain.controls().lookAt(bot, sheep.getBoundingBox().getCenter());
        if (dist > 2.6) {
            if (--this.repathIn <= 0) {
                nav.moveNear(bot, sheep.position(), 1.5);
                this.repathIn = 15;
                if (nav.status() == Navigator.Status.FAILED) {
                    this.target = null;
                    nav.stop(bot);
                }
            }
        } else {
            nav.stop(bot);
            if (bot.getAttackStrengthScale(0.5f) >= 0.95f) {
                bot.attack(sheep);
                bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            }
        }
        return Status.RUNNING;
    }

    /** No sheep in sight: head up to the surface if we're underground, then roam around. */
    private Status lookForSheep(BotPlayer bot, BotBrain brain) {
        if (this.travel != null) {
            Status s = this.travel.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.travel = null;
        }
        if (brain.navigator().isMoving()) return Status.RUNNING;
        // Somewhere we saw sheep before: go there, rather than wander about at random.
        if (!this.triedRemembered) {
            this.triedRemembered = true;
            var here = net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition());
            var seen = brain.memory().nearest("sheep", here, 160);
            if (seen != null && seen.pos().distSqr(bot.blockPosition()) > 24 * 24) {
                brain.log("going to where I saw sheep, at " + seen.pos().toShortString());
                this.travel = new TravelTask(seen.pos(), 8, "Looking for sheep");
                return Status.RUNNING;
            }
        }
        if (++this.searching > 8) {
            // Not there any more either: forget that spot.
            var here = net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition());
            brain.memory().forgetAround("sheep", here, 32);
            return Status.FAILED;
        }
        net.joinedthegame.ai.Roam.tick(bot, brain.navigator());
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        brain.navigator().stop(bot);
    }
}
