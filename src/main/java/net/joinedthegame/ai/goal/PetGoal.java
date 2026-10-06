package net.joinedthegame.ai.goal;

import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.project.WolfProject;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.wolf.Wolf;
import org.jspecify.annotations.Nullable;

/**
 * Looking after the dog: feed it some meat when it's hurt, and call it if it's sitting around
 * (a wolf sits as soon as it's tamed, or when it's been told to). Also notices wild wolves
 * while out and about, so the bot knows where to look when it wants one.
 */
public final class PetGoal extends Goal {
    private enum Job { FEED, CALL }

    private @Nullable Wolf pet;
    private Job job = Job.FEED;
    private int ticks;

    public PetGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (this.brain.now() % 200 == 0) noticeWildWolves(bot);
        // Any dog that's ours (tamed ourselves, or one a player handed over).
        if (this.brain.now() % 20 != 0 && this.pet == null) return 0;
        List<Wolf> pets = WolfProject.pets(bot, 20);
        for (Wolf wolf : pets) {
            if (wolf.getHealth() < wolf.getMaxHealth() * 0.7f && Inv.find(bot, wolf::isFood) >= 0) {
                this.pet = wolf;
                this.job = Job.FEED;
                return 47;
            }
            // Up and about near home (a fresh one, or it got up): sit it back down in the yard.
            if (!wolf.isOrderedToSit() && atHome(bot)) {
                this.pet = wolf;
                this.job = Job.CALL;
                return 44;
            }
        }
        return 0;
    }

    private boolean atHome(BotPlayer bot) {
        var home = this.brain.record().home();
        return home != null && home.dimension() == bot.level().dimension() && home.pos().distSqr(bot.blockPosition()) < 32 * 32;
    }

    private void noticeWildWolves(BotPlayer bot) {
        for (Wolf wolf : Senses.entitiesNear(bot, Wolf.class, 32, w -> w.isAlive() && !w.isTame())) {
            // One spot per pack: wolves wander, so don't log every step they take.
            var here = net.minecraft.core.GlobalPos.of(bot.level().dimension(), wolf.blockPosition());
            var known = this.brain.memory().nearest("wolves", here, 32);
            if (known != null) this.brain.memory().remember("wolves", known, this.brain.now()); // still here: keep it fresh
            else this.brain.rememberPlace(bot, "wolves", wolf.blockPosition());
            return;
        }
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        Wolf pet = this.pet;
        if (pet == null || !pet.isAlive() || ++this.ticks > 20 * 30) return false;
        if (bot.distanceToSqr(pet) > 2.5 * 2.5) {
            Navigator nav = this.brain.navigator();
            if (!nav.isMoving() || this.ticks % 20 == 0) {
                nav.moveNear(bot, pet.position(), 1.5);
                if (nav.status() == Navigator.Status.FAILED) return false;
            }
            return true;
        }
        this.brain.navigator().stop(bot);
        this.brain.controls().lookAt(bot, pet);
        if (this.ticks % 10 != 0) return true;
        if (this.job == Job.FEED) {
            if (!Inv.hold(bot, pet::isFood)) return false;
            Placer.interact(bot, pet);
            return pet.getHealth() < pet.getMaxHealth() * 0.9f;
        }
        // Sit, in the yard, out of the way.
        if (!net.joinedthegame.ai.Pets.sitAtHome(bot, this.brain, pet)) net.joinedthegame.ai.Pets.sit(pet);
        return false;
    }

    @Override
    public String activity() {
        return this.job == Job.FEED ? "Feeding my dog" : "Settling my dog in the yard";
    }
}
