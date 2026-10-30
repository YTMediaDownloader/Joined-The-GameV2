package net.joinedthegame.ai.goal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.social.Feelings;
import net.joinedthegame.ai.social.Relation;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Saying hi. Early on everyone fans out after wood and stone and nobody gets to know anybody, so
 * friendships hardly start. A sociable bot passing someone it barely knows sometimes goes over,
 * faces them and crouches hello (the way players do), a few seconds, then back to work. Both of
 * them know each other a bit better for it, and warm a little.
 * <p>
 * Kept small on purpose: daytime, safe, only someone close by (a few steps), never mid-fight or
 * mid-anything-urgent (it can break off chopping or mining: it's a few seconds); once per person every
 * ten minutes and one hello every three minutes at most; likelier the more sociable the bot (a
 * loner hardly ever bothers).
 */
public final class GreetGoal extends Goal {
    private @Nullable UUID with;
    private String withName = "";
    private int ticks;
    /** Time close enough to say hi (adds up, even if they're busy and moving about). */
    private int close;
    private long lastHelloAt = Long.MIN_VALUE / 2;
    /** Someone's coming over to say hi to us: stop a moment and say it back. */
    private boolean responding;

    /** Another bot's coming over to say hi: stop, turn to them, and crouch back (if we're not busy with something urgent). */
    public void greetedBy(UUID from, String name) {
        if (this.with != null) return;
        this.with = from;
        this.withName = name;
        this.ticks = 0;
        this.close = 0;
        this.responding = true;
    }
    private final Map<UUID, Long> greetedAt = new HashMap<>();

    public GreetGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (this.with != null) {
            // Still about? (A hello that got put off while they wandered off: forget it.)
            var server = bot.level().getServer();
            ServerPlayer them = server == null ? null : server.getPlayerList().getPlayer(this.with);
            if (them == null || them.level() != bot.level() || them.distanceToSqr(bot) > 32 * 32) {
                this.with = null;
                return 0;
            }
            return this.responding ? 60 : 55; // a few seconds: it can break off chopping or mining for it
        }
        long now = this.brain.now();
        if (now % 20 != 0 || now - this.lastHelloAt < 20 * 60 * 3) return 0;
        if (bot.level().isDarkOutside() || !this.brain.safeToRelax(bot) || bot.isInWater()) return 0;
        double social = this.brain.personality().social();
        for (var p : bot.level().players()) {
            if (p == bot || !(p instanceof ServerPlayer sp) || sp.isSpectator() || !sp.isAlive()) continue;
            if (p.distanceToSqr(bot) > 12 * 12 || p.distanceToSqr(bot) < 3 * 3 || !bot.hasLineOfSight(p)) continue;
            Relation r = this.brain.knownRelation(p.getUUID());
            if (r != null && (r.familiarity >= 25 || r.liking <= -10 || r.rivalSince > 0)) continue; // known already, or not keen
            Long at = this.greetedAt.get(p.getUUID());
            if (at != null && now - at < 20 * 60 * 10) continue;
            this.greetedAt.put(p.getUUID(), now);
            // Sociable ones nearly always do; loners now and then.
            boolean hello = bot.getRandom().nextDouble() < 0.15 + social * 0.7;
            if (!hello) continue;
            this.with = p.getUUID();
            this.withName = p.getGameProfile().name();
            this.ticks = 0;
            this.close = 0;
            this.responding = false;
            this.lastHelloAt = now;
            // They see us coming over: they'll stop a moment.
            if (p instanceof BotPlayer other) {
                BotBrain theirs = BotManager.get(bot.level().getServer()).brain(other.getUUID());
                if (theirs != null) theirs.greetedBy(bot.getUUID(), bot.getScoreboardName());
            }
            return 60; // just a few seconds, right here: worth stopping for
        }
        return 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        UUID id = this.with;
        var server = bot.level().getServer();
        ServerPlayer them = id == null || server == null ? null : server.getPlayerList().getPlayer(id);
        if (them == null || !them.isAlive() || them.level() != bot.level() || ++this.ticks > 20 * 20) return finish(bot, false);
        Navigator nav = this.brain.navigator();
        double dist = bot.distanceTo(them);
        if (this.responding) {
            // Wait for them where we are, facing them; crouch back once they're here.
            nav.stop(bot);
            this.brain.controls().lookAt(bot, them.getEyePosition());
            if (dist <= 4.5) {
                int t = this.close++ % 40;
                this.brain.controls().sneak = t >= 3 && t < 9 || t >= 15 && t < 21;
            }
            return this.close < 40 || finish(bot, false);
        }
        if (dist > 4.5) {
            if (!nav.isMoving() || this.ticks % 20 == 0) nav.moveNear(bot, them.position(), 2.5);
            return true;
        }
        nav.stop(bot);
        this.brain.controls().lookAt(bot, them.getEyePosition());
        // Two crouches: hello.
        int t = this.close++ % 40;
        this.brain.controls().sneak = t < 6 || t >= 12 && t < 18;
        if (this.close >= 40) return finish(bot, true);
        return true;
    }

    private boolean finish(BotPlayer bot, boolean said) {
        UUID id = this.with;
        this.with = null;
        this.responding = false;
        var server = bot.level().getServer();
        ServerPlayer them = id == null || server == null ? null : server.getPlayerList().getPlayer(id);
        if (said && them != null) {
            this.brain.log("said hi to " + this.withName);
            this.brain.feel(them, Feelings.Act.GREETED, 1);
            if (them instanceof BotPlayer other) {
                BotBrain theirs = BotManager.get(server).brain(other.getUUID());
                if (theirs != null) theirs.feel(bot, Feelings.Act.GREETED, 1);
            }
        }
        return false;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        this.brain.controls().sneak = false;
    }

    @Override
    public String activity() {
        return "Saying hi to " + this.withName;
    }
}
