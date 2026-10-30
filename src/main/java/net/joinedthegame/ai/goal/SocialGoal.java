package net.joinedthegame.ai.goal;

import java.util.UUID;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.social.Feelings;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Company (the brain's SOCIAL focus): go and spend a while with someone it likes, a bot or a real
 * player. Find them, then just be around them: a few blocks off, keeping up if they move, looking
 * over now and then. A few minutes of it and the Social need is met, and they know each other a bit
 * better (both of them, if it's a bot: they spent the time together).
 * <p>
 * Who it goes to see is up to how it feels (see {@link BotBrain#companion}): never someone it
 * dislikes, more likely someone it likes and knows well.
 */
public final class SocialGoal extends Goal {
    private @Nullable UUID with;
    private String withName = "";
    private int ticks;
    private int together;
    private int stay;

    public SocialGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (this.brain.focus() != BotBrain.Focus.SOCIAL) return 0;
        if (bot.level().isDarkOutside()) return 0;
        if (!this.brain.safeToRelax(bot)) return 0;
        return 44;
    }

    /** Going along with our partner: we said yes, so it gets us away from mining too. */
    private boolean invited;

    public boolean invited() {
        return this.invited && this.with != null;
    }

    /** Our partner asked us along: go to them (instead of picking someone). */
    public void joinOn(UUID partner) {
        this.invited = true;
        this.with = partner;
        this.withName = "";
        this.ticks = 0;
        this.together = 0;
        this.stay = 20 * 60 * 3;
    }

    @Override
    public void start(BotPlayer bot) {
        if (this.with != null) {
            if (this.withName.isEmpty()) {
                var server = bot.level().getServer();
                ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(this.with);
                this.withName = p == null ? "my partner" : p.getGameProfile().name();
            }
            return; // picking up where we left off (interrupted), or joining our partner
        }
        ServerPlayer who = this.brain.companion(bot);
        this.ticks = 0;
        this.together = 0;
        this.stay = 20 * 60 * (2 + bot.getRandom().nextInt(3));
        if (who == null) {
            this.with = null;
            return;
        }
        this.with = who.getUUID();
        this.withName = who.getGameProfile().name();
        this.brain.log("going to see " + this.withName);
    }

    @Override
    public boolean tick(BotPlayer bot) {
        UUID id = this.with;
        if (id == null) return done(bot, null, false, "nobody to go and see");
        var server = bot.level().getServer();
        ServerPlayer who = server == null ? null : server.getPlayerList().getPlayer(id);
        if (who == null || !who.isAlive() || who.level() != bot.level() || who.isSpectator()) return done(bot, null, false, "they've gone");
        ++this.ticks;
        Navigator nav = this.brain.navigator();
        double dist = bot.distanceTo(who);
        // A visit has an end: six minutes at most (if they were mostly off doing things and we
        // were trailing after them, a minute or more of it together still counts).
        if (this.ticks > 20 * 60 * 6) return done(bot, who, this.together >= 20 * 60, "they were too busy");
        if (dist > 12) {
            // Couldn't get to them in a good while: another time.
            if (this.ticks > 20 * 60 * 3 && this.together < 20 * 20) return done(bot, who, false, "couldn't catch up with them");
            nav.sprint = dist > 24;
            if (!nav.isMoving() || this.ticks % 40 == 0) {
                nav.moveNear(bot, who.position(), 3);
                if (nav.status() == Navigator.Status.FAILED && this.ticks > 20 * 30) return done(bot, who, false, "can't get to them");
            }
            return true;
        }
        // With them. If they're on the move, tag along (that's still company); if not, stand about.
        // (A visit under way gets to finish: the focus doesn't run out on it.)
        ++this.together;
        this.brain.keepFocusUntil(this.brain.now() + (this.stay - this.together) + 20 * 30);
        if (dist > 4.5) {
            nav.sprint = false;
            if (!nav.isMoving() || this.ticks % 20 == 0) nav.moveNear(bot, who.position(), 3);
            return this.together < this.stay || done(bot, who, true, null);
        }
        nav.stop(bot);
        // Look at them mostly, round about now and then.
        if (this.together % 60 < 40) this.brain.controls().lookAt(bot, who);
        else if (this.together % 60 == 40) {
            this.brain.controls().face(bot.getYRot() + (bot.getRandom().nextFloat() - 0.5f) * 120f, 0f);
        }
        return this.together < this.stay || done(bot, who, true, null);
    }

    private boolean done(BotPlayer bot, @Nullable ServerPlayer who, boolean spent, @Nullable String why) {
        if (!spent) this.brain.log("company: gave up on seeing " + this.withName + " (" + why + ")");
        if (spent && who != null) {
            this.brain.log("spent a while with " + this.withName);
            this.brain.remember(BotMemory.EventType.SOCIALISED, this.withName);
            this.brain.feel(who, Feelings.Act.SPENT_TIME, 1);
            // They spent it with us too, if it's a bot (a player makes up their own mind).
            if (who instanceof BotPlayer other) {
                // Partners: a flower now and then. Not with anyone, and really into them: maybe ask.
                if (other.getUUID().equals(this.brain.partner())) {
                    if (bot.getRandom().nextDouble() < 0.4) this.brain.giveFlower(bot, other);
                } else {
                    this.brain.considerAskingOut(bot, other);
                }
                BotBrain theirs = BotManager.get(bot.level().getServer()).brain(other.getUUID());
                if (theirs != null) {
                    theirs.remember(BotMemory.EventType.SOCIALISED, bot.getScoreboardName());
                    theirs.feel(bot, Feelings.Act.SPENT_TIME, 1);
                }
            }
        }
        this.with = null;
        this.invited = false;
        this.brain.focusDone(BotBrain.Focus.SOCIAL);
        if (!spent) this.brain.focusFailed(BotBrain.Focus.SOCIAL);
        return false;
    }

    @Override
    public void onOwnerDeath(BotPlayer bot) {
        this.with = null;
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return this.together > 0; // standing about with them is the point
    }

    @Override
    public String activity() {
        if (this.with == null) return "Looking for company";
        return this.together > 0 ? "Hanging out with " + this.withName : "Going to see " + this.withName;
    }
}
