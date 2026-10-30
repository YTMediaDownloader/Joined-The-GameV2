package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.task.CollectItems;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Died? Run back and grab your stuff before it despawns. Gives up after 5 minutes (that's
 * when dropped items vanish anyway). Dying again on the way just updates where to go; after
 * three deaths in a row the bot cuts its losses and starts over like a fresh spawn.
 */
public final class RecoverGoal extends Goal {
    /** Dropped items despawn after 5 minutes, so there's no point trying for longer. */
    public static final int GIVE_UP_TICKS = 20 * 60 * 5;
    public static final int MAX_DEATHS = 3;

    private @Nullable CollectItems collect;
    private long toldGuarded = -1_000_000L;
    private int navFailures;

    public RecoverGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        GlobalPos death = this.brain.deathSpot();
        if (death == null || bot.isDeadOrDying()) return 0;
        if (death.dimension() != bot.level().dimension()) return 0;
        // Not in the dark without armour: that's how it died in the first place. Morning.
        long age = this.brain.now() - this.brain.deathTime();
        if (bot.level().isDarkOutside() && !ShelterGoal.nightReady(bot)) {
            if (age <= GIVE_UP_TICKS) this.brain.aside("leaving my stuff till morning");
            return 0;
        }
        if (age > GIVE_UP_TICKS) {
            this.brain.remember(net.joinedthegame.ai.BotMemory.EventType.LOST_ITEMS, "", death, 0.6f);
            this.brain.forgetDeath(false);
            return 0;
        }
        // Something that shoots still hanging about where we died (a skeleton in the shade of a
        // ravine, say): walking straight back gets us shot again, and again. Give it a chance to
        // wander off, unless the stuff's about to vanish anyway (the last minute: worth the risk).
        if (age < GIVE_UP_TICKS - 20 * 60) {
            String guard = guarded(bot, death);
            if (guard != null) {
                this.brain.aside("my stuff's guarded by " + guard);
                if (this.brain.now() - this.toldGuarded > 20 * 30) {
                    this.toldGuarded = this.brain.now();
                    this.brain.log("my stuff's guarded (" + guard + "), waiting for it to go before I go back ("
                            + (GIVE_UP_TICKS - age) / 20 + "s left)");
                }
                return 0;
            }
        }
        return 72;
    }

    /**
     * Whatever killed us still sitting on the pile: anything that shoots (16 blocks), or other
     * monsters by the spot when there's more than one of them, or we're not geared to take one on
     * (a bot in leather that died to a slime walks straight back into the same slime otherwise).
     */
    private @Nullable String guarded(BotPlayer bot, GlobalPos death) {
        if (!bot.level().isLoaded(death.pos())) return null;
        var mobs = bot.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(death.pos()).inflate(16),
                m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy);
        int close = 0;
        net.minecraft.world.entity.Mob near = null;
        for (var m : mobs) {
            if (m.isHolding(net.minecraft.world.item.Items.BOW) || m.isHolding(net.minecraft.world.item.Items.CROSSBOW)) return name(m);
            if (m.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(death.pos())) < 10 * 10) {
                close++;
                near = m;
            }
        }
        double gear = this.brain.needs().level(net.joinedthegame.ai.life.Needs.Need.GEAR);
        if (close >= 2) return close + " monsters";
        if (near != null && gear > 0.6) return name(near);
        return null;
    }

    private static String name(net.minecraft.world.entity.Mob m) {
        return "a " + m.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void start(BotPlayer bot) {
        this.collect = null;
        this.navFailures = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        GlobalPos death = this.brain.deathSpot();
        if (death == null) return false;
        Vec3 spot = Vec3.atCenterOf(death.pos());

        if (this.collect != null) {
            if (this.collect.tick(bot, this.brain) == CollectItems.Status.RUNNING) return true;
            this.brain.remember(net.joinedthegame.ai.BotMemory.EventType.RECOVERED_ITEMS, "", death, 0.5f);
            this.brain.forgetDeath(true);
            return false;
        }

        if (bot.position().distanceToSqr(spot) < 6 * 6) {
            this.brain.navigator().stop(bot);
            this.collect = new CollectItems(death.pos(), 10, 20 * 15);
            return true;
        }

        Navigator nav = this.brain.navigator();
        nav.sprint = true;
        if (!nav.isMoving()) {
            nav.moveNear(bot, spot, 4);
            if (nav.status() == Navigator.Status.FAILED && ++this.navFailures > 10) {
                this.brain.log("can't get back to where I died, giving up");
                this.brain.forgetDeath(false);
                return false;
            }
        }
        return true;
    }

    @Override
    public String activity() {
        return "Going back for my stuff";
    }
}
