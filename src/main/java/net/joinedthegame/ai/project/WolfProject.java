package net.joinedthegame.ai.project;

import java.util.Comparator;
import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.ai.task.FetchTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.GlobalPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: get a dog. Bring bones (skeletons drop them; the bot keeps the ones it
 * gets), find a wild wolf, and feed it bones until it takes to you. Wolves live in forests and
 * taigas, so a bot with none nearby goes looking (and remembers where it saw some).
 */
public final class WolfProject implements Task {
    private @Nullable Task sub;
    private @Nullable Wolf target;
    private boolean fetched;
    private int ticks;
    private int searchTicks;
    private @Nullable Vec3 searchGoal;
    private boolean tamed;
    private boolean announced;

    /** Does this bot have a dog already (alive, as far as it knows)? */
    public static boolean hasPet(BotBrain brain) {
        return brain.memory().times(BotMemory.EventType.TAMED, "wolf") > brain.memory().times(BotMemory.EventType.PET_DIED, "wolf");
    }

    /** Its wolves within range. */
    public static List<Wolf> pets(BotPlayer bot, double range) {
        return Senses.entitiesNear(bot, Wolf.class, range, w -> w.isAlive() && w.isOwnedBy(bot));
    }

    @Override
    public String activity() {
        if (this.sub != null) return this.sub.activity();
        if (this.target != null) return "Taming a wolf";
        return "Looking for wolves";
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (++this.ticks > 20 * 60 * 8) return Status.FAILED;
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            this.sub = null;
        }
        if (this.tamed) return Status.DONE;

        if (Inv.count(bot, Items.BONE) == 0) {
            if (!this.fetched) {
                this.fetched = true;
                this.sub = FetchTask.from(bot, brain, s -> s.is(Items.BONE), 16, "Getting bones from my chests");
                if (this.sub != null) return Status.RUNNING;
            }
            brain.log("wolf: out of bones");
            return Status.FAILED;
        }

        Wolf wolf = this.target;
        if (wolf == null || !wolf.isAlive() || wolf.isTame() && !wolf.isOwnedBy(bot) || wolf.isAngry()
                || wolf.distanceToSqr(bot) > 64 * 64) {
            this.target = wolf = findWolf(bot);
            if (wolf == null) return search(bot, brain);
        }

        // Tamed! It sits down straight away; get it up to come home with us, then sit it in
        // the yard there (where it stays, out from under our feet).
        if (wolf.isTame() && wolf.isOwnedBy(bot)) {
            if (!this.announced) {
                this.announced = true;
                brain.remember(BotMemory.EventType.TAMED, "wolf", GlobalPos.of(bot.level().dimension(), wolf.blockPosition()), 1f);
                brain.log("tamed a wolf!");
            }
            GlobalPos home = brain.record().home();
            if (home == null || home.dimension() != bot.level().dimension()) {
                this.tamed = true; // nowhere to take it: it can sit here for now
                return Status.DONE;
            }
            if (home.pos().distSqr(bot.blockPosition()) > 16 * 16) {
                if (wolf.isOrderedToSit() && bot.distanceToSqr(wolf) < 3 * 3) {
                    Inv.hold(bot, s -> s.is(net.minecraft.tags.ItemTags.SWORDS) || s.isEmpty());
                    Placer.interact(bot, wolf); // up you get
                }
                if (!wolf.isOrderedToSit()) this.sub = new net.joinedthegame.ai.task.TravelTask(home.pos(), 8, "Taking my dog home");
                else if (bot.distanceToSqr(wolf) >= 3 * 3) brain.navigator().moveNear(bot, wolf.position(), 1.5);
                return Status.RUNNING;
            }
            if (net.joinedthegame.ai.Pets.sitAtHome(bot, brain, wolf)) brain.log("my dog's sitting in the yard now");
            this.tamed = true;
            return Status.DONE;
        }

        Navigator nav = brain.navigator();
        if (bot.distanceToSqr(wolf) > 2.5 * 2.5) {
            if (!nav.isMoving() || this.ticks % 20 == 0) {
                nav.moveNear(bot, wolf.position(), 1.5);
                if (nav.status() == Navigator.Status.FAILED) {
                    nav.stop(bot);
                    this.target = null;
                }
            }
            return Status.RUNNING;
        }
        nav.stop(bot);
        brain.controls().lookAt(bot, wolf);
        if (this.ticks % 15 == 0 && !wolf.isTame()) {
            Inv.hold(bot, s -> s.is(Items.BONE));
            Placer.interact(bot, wolf);
        }
        return Status.RUNNING;
    }

    private static @Nullable Wolf findWolf(BotPlayer bot) {
        return Senses.entitiesNear(bot, Wolf.class, 48, w -> w.isAlive() && !w.isTame() && !w.isBaby() && !w.isAngry())
                .stream().min(Comparator.comparingDouble(w -> w.distanceToSqr(bot))).orElse(null);
    }

    /** No wolves in sight: head for where we last saw some, or just go looking. */
    private Status search(BotPlayer bot, BotBrain brain) {
        if (++this.searchTicks > 20 * 60 * 5) {
            brain.log("wolf: couldn't find any wolves");
            return Status.FAILED;
        }
        Navigator nav = brain.navigator();
        if (nav.isMoving() && this.searchGoal != null && bot.position().distanceToSqr(this.searchGoal) > 6 * 6) return Status.RUNNING;
        GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        GlobalPos seen = brain.memory().nearest("wolves", here, 200);
        if (seen != null && seen.pos().distSqr(bot.blockPosition()) > 16 * 16) {
            this.searchGoal = Vec3.atBottomCenterOf(seen.pos());
        } else {
            double angle = bot.getRandom().nextDouble() * Math.PI * 2;
            int x = Mth.floor(bot.getX() + Math.cos(angle) * 40);
            int z = Mth.floor(bot.getZ() + Math.sin(angle) * 40);
            if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) return Status.RUNNING;
            this.searchGoal = new Vec3(x + 0.5, bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
        }
        nav.moveNear(bot, this.searchGoal, 4);
        if (nav.status() == Navigator.Status.FAILED) {
            nav.stop(bot);
            if (seen != null) brain.memory().forget("wolves", seen);
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }
}
