package net.joinedthegame.ai.goal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.joinedthegame.JtgConfig;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.Duelist;
import net.joinedthegame.ai.social.Feelings;
import net.joinedthegame.ai.social.Feuds;
import net.joinedthegame.ai.social.Peace;
import net.joinedthegame.ai.social.Relation;
import net.joinedthegame.ai.social.Talk;
import net.joinedthegame.ai.task.CollectItems;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fights with people (bots, or you): the relationships part of fighting. Mobs are CombatGoal's;
 * this is about who someone is to us.
 * <ul>
 *   <li><b>Self-defence:</b> someone hits us for real (not a friendly tap): we hit back, unless
 *       they're a close friend (an accident, surely) or we'd lose (we get away).</li>
 *   <li><b>Rivals:</b> sworn enemies who run into each other nearly always fight (the deeper the
 *       hatred, the likelier), unless there's a truce.</li>
 *   <li><b>Helping a friend:</b> a close friend's in a fight nearby with someone we don't like:
 *       we jump in on their side.</li>
 *   <li><b>Vengeance</b> (John Wick: see pets) comes later in step 3.</li>
 * </ul>
 * In a fight: the Duelist does the fighting. Losing badly, or outnumbered, a bot may call peace
 * (the only time bots talk) or get away: never by digging in (against someone who just follows you
 * down), but by breaking line of sight and running home (shutting the door) or to its friends,
 * shield up, an ender pearl if it has one. Chasers give up after a while. Winning: the loser's
 * stuff is looted only if the hatred runs deep.
 * <p>
 * Survival comes first throughout: emergencies (air, fire, eating when nearly dead, mobs) all
 * outrank a fight with a person, and a fight isn't started while drowning, hurt, or dug in for
 * the night.
 */
public final class FeudGoal extends Goal {
    private enum Mode { FIGHT, RETREAT, LOOT, HUNT }

    private @Nullable UUID opp;
    private String oppName = "";
    private Feuds.Why why = Feuds.Why.RIVAL;
    private @Nullable Mode mode;
    private int ticks;
    private long lastSawAt;
    private final Duelist duel;
    private @Nullable Task loot;
    private boolean calledPeace;
    private long decideAnswerAt = -1;
    private @Nullable BlockPos retreatTo;
    private int pearlCooldown;
    /** John Wick: hunting in spells (a few minutes on, a while getting on with life). */
    private long huntUntil;
    private long nextHuntAt;

    /** Who we rolled for when we ran into them (and whether it was a fight), and when. */
    private final Map<UUID, Long> rolledAt = new HashMap<>();
    /** When we last decided about someone who hit us and ran, turning up again. */
    private final Map<UUID, Long> backRolledAt = new HashMap<>();
    /** Ones who hit us and have since gone off (out of sight or well away): turning up again is "back". */
    private final java.util.Set<UUID> wentAway = new java.util.HashSet<>();
    private final Map<UUID, Boolean> rolledFight = new HashMap<>();

    public FeudGoal(BotBrain brain) {
        super(brain);
        this.duel = new Duelist(brain);
    }

    // --- starting a fight ------------------------------------------------------------

    @Override
    public int priority(BotPlayer bot) {
        if (this.opp != null && this.mode != null) {
            if (this.mode == Mode.RETREAT) return 90; // getting away is staying alive
            if (this.mode == Mode.HUNT) return 58;      // a spell of hunting: above chores, below everything urgent
            return this.why == Feuds.Why.VENGEANCE ? 84 : 80;
        }
        if (!JtgConfig.RIVALRIES.get()) return 0;
        return trigger(bot) ? (this.why == Feuds.Why.VENGEANCE ? 84 : 80) : 0;
    }

    private boolean trigger(BotPlayer bot) {
        var server = bot.level().getServer();
        if (server == null) return false;
        long now = this.brain.now();

        // Hit for real by someone just now: defend ourselves (or get away).
        UUID hitBy = this.brain.personAttacker(20 * 3);
        if (hitBy != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(hitBy);
            Relation r = this.brain.knownRelation(hitBy);
            if (p != null && p.isAlive() && p.level() == bot.level() && p.distanceToSqr(bot) < 16 * 16 && allowedAgainst(p)
                    && !(r != null && r.liking >= 60)) {
                begin(bot, p, Feuds.Why.SELF_DEFENCE, outmatched(bot, p) ? Mode.RETREAT : Mode.FIGHT);
                return true;
            }
        }
        // Back again: they hit us, ran off, and here they are (hit-and-run). Unfinished business.
        // Decided once each time they turn up: likely, and likelier every round, for the brave and
        // the unforgiving; an easygoing one may just keep an eye on them. A close friend, never.
        for (var other : bot.level().players()) {
            if (other == bot || !(other instanceof ServerPlayer p)) continue;
            int rounds = this.brain.unsettledRounds(p.getUUID());
            if (rounds == 0 || !p.isAlive() || p.isSpectator() || !allowedAgainst(p)) continue;
            // Only once they've actually gone (out of sight, or well off) and come back.
            if (p.distanceToSqr(bot) > 16 * 16 || !bot.hasLineOfSight(p)) {
                this.wentAway.add(p.getUUID());
                continue;
            }
            if (p.distanceToSqr(bot) > 14 * 14 || !this.wentAway.remove(p.getUUID())) continue;
            Relation r = this.brain.knownRelation(p.getUUID());
            if (r != null && r.liking >= 60) continue;
            Long at = this.backRolledAt.get(p.getUUID());
            if (at != null && now - at < 20 * 30) continue;
            this.backRolledAt.put(p.getUUID(), now);
            double chance = Math.clamp(0.45 + rounds * 0.2 + (1 - Feelings.forgiving(this.brain.personality())) * 0.3
                    + this.brain.personality().brave() * 0.1, 0.0, 0.97);
            boolean go = bot.getRandom().nextDouble() < chance;
            this.brain.log(p.getGameProfile().name() + "'s back (hit me and ran" + (rounds > 1 ? ", " + rounds + " times" : "") + "): "
                    + (go ? "not letting that go" : "keeping an eye on them"));
            if (!go) continue;
            begin(bot, p, Feuds.Why.SELF_DEFENCE, outmatched(bot, p) ? Mode.RETREAT : Mode.FIGHT);
            return true;
        }
        // John Wick: the one who killed our pet, in sight. Drops everything (staying alive aside).
        for (Relation r : this.brain.relations()) {
            if (!r.wick) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(r.id);
            if (p == null || !p.isAlive() || p.isSpectator() || p.level() != bot.level() || !allowedAgainst(p)) continue;
            if (p.distanceToSqr(bot) > 48 * 48 || !bot.hasLineOfSight(p)) continue;
            if (bot.getHealth() < bot.getMaxHealth() * 0.4f || bot.isInWater()) continue; // heal first, then
            this.brain.log("there's " + r.name + ". dropping everything");
            begin(bot, p, Feuds.Why.VENGEANCE, Mode.FIGHT);
            return true;
        }
        // ...and now and then, a spell of going out to find them.
        if (now >= this.nextHuntAt && Inv.bestWeapon(bot) >= 0 && fitToStart(bot)) {
            for (Relation r : this.brain.relations()) {
                if (!r.wick) continue;
                ServerPlayer p = server.getPlayerList().getPlayer(r.id);
                if (p == null || !p.isAlive() || p.level() != bot.level() || !allowedAgainst(p)) continue;
                this.nextHuntAt = now + 20 * 60 * (6 + bot.getRandom().nextInt(5));
                this.huntUntil = now + 20 * 60 * 4;
                begin(bot, p, Feuds.Why.VENGEANCE, Mode.HUNT);
                this.brain.log("going hunting for " + r.name);
                return true;
            }
        }
        if (!fitToStart(bot)) return false;

        for (Relation r : this.brain.relations()) {
            if (r.rivalSince == 0) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(r.id);
            if (p == null || !p.isAlive() || p.isSpectator() || p.level() != bot.level() || !allowedAgainst(p)) continue;
            if (p.distanceToSqr(bot) > 20 * 20 || !bot.hasLineOfSight(p)) continue;
            // Ran into a rival: once per meeting, a fight or not (the deeper the hatred, the likelier).
            Long at = this.rolledAt.get(r.id);
            if (at == null || now - at > 20 * 60 * 2) {
                this.rolledAt.put(r.id, now);
                boolean truce = r.truceUntil > now;
                double chance = truce ? 0.08 * (1 - Feelings.forgiving(this.brain.personality()))
                        : Math.clamp(0.3 + (-r.liking - 70) / 30.0 * 0.6, 0.3, 0.92);
                boolean fight = bot.getRandom().nextDouble() < chance;
                this.rolledFight.put(r.id, fight);
                if (truce && fight) breakTruce(bot, p, r);
                this.brain.log("ran into " + r.name + " (" + (truce ? "truce" : "rival") + "): " + (fight ? "fight" : "leaving it, this time"));
            }
            if (Boolean.TRUE.equals(this.rolledFight.get(r.id))) {
                this.rolledFight.put(r.id, false); // one fight per meeting
                begin(bot, p, Feuds.Why.RIVAL, Mode.FIGHT);
                return true;
            }
        }

        // A close friend in a fight nearby, with someone we don't like (or don't know): jump in.
        if ((this.brain.personality().brave() >= 0.3 || this.brain.partner() != null) && bot.getHealth() >= bot.getMaxHealth() * 0.7f) {
            for (var other : bot.level().players()) {
                if (!(other instanceof BotPlayer friend) || friend == bot || friend.distanceToSqr(bot) > 20 * 20) continue;
                Relation f = this.brain.knownRelation(friend.getUUID());
                boolean partner = f != null && f.partnerSince > 0;
                if (f == null || f.liking < 60 && !partner) continue;
                Feuds.Fight fight = Feuds.of(friend.getUUID(), now);
                if (fight == null || fight.opponent().equals(bot.getUUID())) continue;
                Relation them = this.brain.knownRelation(fight.opponent());
                if (them != null && them.liking >= (partner ? 30 : 10)) continue; // we like them too: stay out of it
                ServerPlayer p = server.getPlayerList().getPlayer(fight.opponent());
                if (p == null || !p.isAlive() || p.level() != bot.level() || !allowedAgainst(p)) continue;
                this.brain.log(friend.getScoreboardName() + "'s in a fight with " + p.getGameProfile().name() + ": jumping in");
                begin(bot, p, Feuds.Why.HELPING_FRIEND, Mode.FIGHT);
                tell(p, bot, Feelings.Act.JUMPED_ME); // they'll remember who joined in
                return true;
            }
        }
        return false;
    }

    /** Not in a state to start anything: hurt, in the water, dug in for the night, mobs about. */
    private boolean fitToStart(BotPlayer bot) {
        if (bot.getHealth() < bot.getMaxHealth() * 0.6f || bot.isInWater()) return false;
        if (bot.level().isDarkOutside() && !ShelterGoal.nightReady(bot)) return false;
        return bot.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, bot.getBoundingBox().inflate(10),
                m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy).isEmpty();
    }

    /** Fights with real players can be switched off on their own. */
    private static boolean allowedAgainst(ServerPlayer p) {
        return p instanceof BotPlayer || JtgConfig.PLAYER_RIVALRIES.get();
    }

    /** Clearly the weaker: much less health, or no weapon against someone with one. */
    private static boolean outmatched(BotPlayer bot, ServerPlayer p) {
        double mine = bot.getHealth() + bot.getArmorValue() * 0.6 + (Inv.bestWeapon(bot) >= 0 ? 4 : 0);
        double theirs = p.getHealth() + p.getArmorValue() * 0.6 + (p.getMainHandItem().isEmpty() ? 0 : 4);
        return mine < theirs * 0.55;
    }

    private void begin(BotPlayer bot, ServerPlayer p, Feuds.Why why, Mode mode) {
        this.opp = p.getUUID();
        this.oppName = p.getGameProfile().name();
        this.why = why;
        this.mode = mode;
        this.ticks = 0;
        this.lastSawAt = this.brain.now();
        this.calledPeace = false;
        this.decideAnswerAt = -1;
        this.retreatTo = null;
        this.brain.log("fight with " + this.oppName + " (" + why.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")"
                + (mode == Mode.RETREAT ? ": not one I'd win, getting away" : ""));
        // A rival fight makes it mutual: they'll count us a rival too, if there's no love lost.
        if (why == Feuds.Why.RIVAL && p instanceof BotPlayer other) {
            BotBrain theirs = brainOf(other);
            if (theirs != null) {
                Relation back = theirs.relation(bot.getUUID(), bot.getScoreboardName(), false);
                if (back.rivalSince == 0 && back.liking <= -20) theirs.becomeRivals(back);
            }
        }
    }

    // --- the fight -----------------------------------------------------------------

    @Override
    public void start(BotPlayer bot) {}

    @Override
    public boolean tick(BotPlayer bot) {
        UUID id = this.opp;
        Mode m = this.mode;
        if (id == null || m == null) return end(bot, null);
        if (m == Mode.LOOT) return lootTick(bot);
        if (m == Mode.HUNT) return huntTick(bot);
        if (id.equals(this.endRequested)) {
            this.endRequested = null;
            return end(bot, "made peace");
        }
        var server = bot.level().getServer();
        ServerPlayer them = server == null ? null : server.getPlayerList().getPlayer(id);
        long now = this.brain.now();
        ++this.ticks;

        // We won: they're dead (and it was us).
        if (this.brain.killed(id, 20 * 5)) return won(bot);
        if (them == null || !them.isAlive() || them.level() != bot.level()) return end(bot, "they're gone");
        double dist = Math.sqrt(them.distanceToSqr(bot));
        if (dist < 40 && bot.hasLineOfSight(them)) this.lastSawAt = now;
        Relation r = this.brain.relation(id, this.oppName, !(them instanceof BotPlayer));
        double hatred = Math.clamp(-r.liking / 100.0, 0, 1);

        // Fights not to the death (config): enough's enough once one of us is badly hurt.
        if (!JtgConfig.FIGHTS_TO_DEATH.get() && m == Mode.FIGHT && (them.getHealth() < 6 || bot.getHealth() < 6)) {
            this.brain.log("enough with " + this.oppName + " (not to the death)");
            return end(bot, "that's enough");
        }

        // Answers to our peace call.
        Peace.Answer answer = Peace.answerFor(bot.getUUID());
        if (answer != null && answer.from().equals(id)) {
            if (answer.yes()) return makePeace(bot, them, r);
            this.brain.log(this.oppName + " said no to peace");
            this.brain.refusedPeaceBy(id);
        }
        // A peace call to us: think about it a moment, then answer.
        Peace.Offer offer = Peace.offerTo(bot.getUUID(), now);
        if (offer != null && offer.from().equals(id)) {
            if (this.decideAnswerAt < 0) this.decideAnswerAt = now + 20 + bot.getRandom().nextInt(30);
            // Thinking about it: hold off (shield up, keep our distance), don't keep swinging.
            if (now < this.decideAnswerAt) {
                this.brain.navigator().stop(bot);
                this.brain.controls().lookAt(bot, them.getEyePosition());
                if (bot.distanceTo(them) < 3) this.brain.controls().forward = -0.8f;
                this.duel.raiseShield(bot);
                return true;
            }
            if (now >= this.decideAnswerAt) {
                this.decideAnswerAt = -1;
                boolean yes = acceptPeace(bot, them, r, hatred);
                if (yes) {
                    Talk.accept(bot, this.brain.personality());
                    Peace.answer(offer, true, now);
                    return makePeace(bot, them, r);
                }
                Talk.refuse(bot, this.brain.personality());
                Peace.answer(offer, false, now);
            }
        }

        // Lost them: chasers give up (sooner the less they care).
        long chaseFor = this.why == Feuds.Why.VENGEANCE ? 20 * 60 : (long) (20 * (8 + hatred * 25));
        if (now - this.lastSawAt > chaseFor || dist > 48) {
            return end(bot, m == Mode.RETREAT ? "got away from them" : "lost them");
        }
        // Self-defence is just that: once they've stopped hitting us (six seconds), so do we. Carrying
        // it on is what rivals do, not what anyone does over one hit.
        // (Unless they've done this before: hit and run twice, and backing off is just the run
        // part. Then it's on until we lose them, like a rival.)
        if (this.why == Feuds.Why.SELF_DEFENCE && m == Mode.FIGHT && this.ticks > 20 * 6 && !id.equals(this.brain.personAttacker(20 * 6))
                && this.brain.unsettledRounds(id) < 2) {
            return end(bot, "they've backed off");
        }
        Feuds.fighting(bot.getUUID(), id, this.why, now);

        // Every couple of seconds: how's it going?
        if (m == Mode.FIGHT && this.ticks % 40 == 0) assess(bot, them, hatred);
        if (this.mode == Mode.RETREAT) return retreatTick(bot, them, dist);
        // Called peace and waiting on the answer: back off, shield up (still defending, not attacking).
        if (Peace.waitingOn(bot.getUUID(), now)) {
            this.brain.navigator().stop(bot);
            this.brain.controls().lookAt(bot, them.getEyePosition());
            if (dist < 4) this.brain.controls().forward = -1f;
            this.duel.raiseShield(bot);
            return true;
        }
        if (!this.duel.fight(bot, them)) return end(bot, "can't get at them");
        return true;
    }

    /** Losing badly, or outnumbered: call peace, or get away, or (vengeful enough) fight on. */
    private void assess(BotPlayer bot, ServerPlayer them, double hatred) {
        if (this.why == Feuds.Why.VENGEANCE) return; // never
        float mine = bot.getHealth() / bot.getMaxHealth();
        float theirs = them.getHealth() / them.getMaxHealth();
        int against = Feuds.against(bot.getUUID(), this.brain.now()).size();
        if (!(them instanceof BotPlayer) || Feuds.of(them.getUUID(), this.brain.now()) == null) against = Math.max(against, 1);
        boolean losing = mine < 0.4f && mine < theirs;
        boolean outnumbered = against >= 2;
        if (!losing && !outnumbered) return;
        double grit = hatred * 0.5 + (1 - Feelings.forgiving(this.brain.personality())) * 0.3 + this.brain.personality().brave() * 0.2;
        double odds = losing ? 0.7 : 0.35 + (against - 1) * 0.2;
        if (grit > odds + 0.2) return; // too angry to stop
        if (!this.calledPeace && !Talk.proud(this.brain.personality()) || !this.calledPeace && bot.getRandom().nextDouble() < 0.3) {
            this.calledPeace = true;
            Talk.callPeace(bot, this.brain.personality());
            Peace.offer(bot.getUUID(), bot.getScoreboardName(), them.getUUID(), this.brain.now());
            this.brain.calledPeaceOn(them.getUUID());
            this.brain.log("called peace with " + this.oppName + (outnumbered ? " (outnumbered)" : " (losing)"));
            return;
        }
        if (!Peace.waitingOn(bot.getUUID(), this.brain.now())) {
            this.mode = Mode.RETREAT;
            this.retreatTo = null;
            this.brain.log("not worth it: getting away from " + this.oppName + (outnumbered ? " (outnumbered)" : ""));
        }
    }

    /**
     * Would we take their peace? The angrier and the more vengeful, the less likely; winning makes
     * it less likely too (why stop now?); a truce they broke before makes it much less likely.
     */
    private boolean acceptPeace(BotPlayer bot, ServerPlayer them, Relation r, double hatred) {
        if (this.why == Feuds.Why.VENGEANCE || r.wick) return false;
        double forgiving = Feelings.forgiving(this.brain.personality());
        double winning = Math.clamp(bot.getHealth() / bot.getMaxHealth() - them.getHealth() / them.getMaxHealth(), 0, 1);
        // The grudge decides, not the heat of the moment: everyone's furious mid-fight. A long
        // history of bad blood (the lasting impression) says no; a fight that's all heat can end.
        double grudge = Math.clamp(-(r.lasting * 0.75 + r.liking * 0.25) / 100.0, 0, 1);
        double chance = 0.45 + forgiving * 0.4 - grudge * 0.6 - winning * 0.25 - (r.trust < 10 ? 0.15 : 0);
        double roll = bot.getRandom().nextDouble();
        this.brain.log(String.format(java.util.Locale.ROOT, "peace from %s? (grudge %.2f, forgiving %.2f, winning %.2f: %.0f%% yes, rolled %.0f)",
                this.oppName, grudge, forgiving, winning, Math.clamp(chance, 0.03, 0.9) * 100, roll * 100));
        return roll < Math.clamp(chance, 0.03, 0.9);
    }

    private boolean makePeace(BotPlayer bot, ServerPlayer them, Relation r) {
        long until = this.brain.now() + 24000L;
        r.truceUntil = until;
        this.brain.feel(them, Feelings.Act.MADE_PEACE, 1);
        this.brain.remember(BotMemory.EventType.TRUCE, this.oppName);
        if (them instanceof BotPlayer other) {
            BotBrain theirs = brainOf(other);
            if (theirs != null) {
                Relation back = theirs.relation(bot.getUUID(), bot.getScoreboardName(), false);
                back.truceUntil = until;
                theirs.feel(bot, Feelings.Act.MADE_PEACE, 1);
                theirs.remember(BotMemory.EventType.TRUCE, bot.getScoreboardName());
                theirs.endFeud(bot.getUUID());
            }
        }
        return end(bot, "made peace");
    }

    /** Broke a truce we'd made: they won't forget it. */
    private void breakTruce(BotPlayer bot, ServerPlayer them, Relation r) {
        r.truceUntil = 0;
        this.brain.log("breaking the truce with " + r.name);
        tell(them, bot, Feelings.Act.BROKE_TRUCE);
    }

    // --- hunting (John Wick) -----------------------------------------------------------

    /**
     * A spell of hunting: off towards where they are (it knows roughly, the way a player hunting
     * someone on a server tracks them down), and the moment they're in sight, the fight's on. The
     * spell ends after a few minutes: it has a life too (until the next one).
     */
    private boolean huntTick(BotPlayer bot) {
        var server = bot.level().getServer();
        UUID id = this.opp;
        ServerPlayer them = server == null || id == null ? null : server.getPlayerList().getPlayer(id);
        if (them == null || !them.isAlive() || them.level() != bot.level()) return end(bot, "they're not around");
        if (this.brain.now() > this.huntUntil) return end(bot, "enough hunting for now");
        // Night's fallen and we're not geared for it: home first, the hunt can wait for morning.
        if (bot.level().isDarkOutside() && !ShelterGoal.nightReady(bot)) {
            this.nextHuntAt = Math.max(this.nextHuntAt, this.brain.now() + 20 * 60 * 2);
            return end(bot, "it's dark: another time");
        }
        ++this.ticks;
        double dist = Math.sqrt(them.distanceToSqr(bot));
        if (dist < 40 && bot.hasLineOfSight(them)) {
            this.brain.log("found " + this.oppName);
            this.mode = Mode.FIGHT;
            this.lastSawAt = this.brain.now();
            return true;
        }
        Navigator nav = this.brain.navigator();
        nav.sprint = dist > 30;
        if (!nav.isMoving() || this.ticks % 60 == 0) {
            // Roughly where they are, not to the block.
            Vec3 near = them.position().add(bot.getRandom().nextInt(17) - 8, 0, bot.getRandom().nextInt(17) - 8);
            nav.moveNear(bot, near, 6);
        }
        return true;
    }

    // --- getting away -----------------------------------------------------------------

    private boolean retreatTick(BotPlayer bot, ServerPlayer them, double dist) {
        Navigator nav = this.brain.navigator();
        if (this.retreatTo == null) this.retreatTo = safePlace(bot, them);
        BlockPos to = this.retreatTo;
        nav.sprint = true;
        if (!nav.isMoving() || this.ticks % 30 == 0) nav.moveNear(bot, Vec3.atBottomCenterOf(to), 2);
        // Right on our heels: shield up as we go.
        if (dist < 3.5) this.duel.raiseShield(bot);
        else this.duel.lowerShield(bot);
        // An ender pearl and they're close: throw it the way we're going.
        if (--this.pearlCooldown <= 0 && dist < 7 && Inv.find(bot, s -> s.is(Items.ENDER_PEARL)) >= 0) {
            this.pearlCooldown = 20 * 5;
            Inv.hold(bot, Inv.find(bot, s -> s.is(Items.ENDER_PEARL)));
            Vec3 away = Vec3.atCenterOf(to).subtract(bot.position()).normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(-away.x, away.z));
            bot.setYRot(yaw);
            bot.setXRot(-25f);
            bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND);
            this.brain.log("threw an ender pearl to get away from " + this.oppName);
        }
        // Home: in, and the door shut behind us.
        if (bot.position().distanceToSqr(Vec3.atCenterOf(to)) < 3 * 3) {
            shutDoors(bot);
            if (dist > 12) return end(bot, "got home, away from them");
        }
        if (dist > 32 && !bot.hasLineOfSight(them)) return end(bot, "got away from them");
        return true;
    }

    /** Home if it's not miles off, or a friend nearby, or just away from them. */
    private BlockPos safePlace(BotPlayer bot, ServerPlayer them) {
        var home = this.brain.record().home();
        if (home != null && home.dimension() == bot.level().dimension() && home.pos().distSqr(bot.blockPosition()) < 150 * 150) return home.pos();
        for (var p : bot.level().players()) {
            if (p == bot || p == them || p.distanceToSqr(bot) > 100 * 100) continue;
            Relation r = this.brain.knownRelation(p.getUUID());
            if (r != null && r.liking >= 30) return p.blockPosition();
        }
        Vec3 away = bot.position().subtract(them.position()).normalize().scale(40);
        BlockPos far = BlockPos.containing(bot.position().add(away));
        int y = bot.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, far.getX(), far.getZ());
        return new BlockPos(far.getX(), y, far.getZ());
    }

    private static void shutDoors(BotPlayer bot) {
        var level = bot.level();
        for (BlockPos p : BlockPos.betweenClosed(bot.blockPosition().offset(-3, -1, -3), bot.blockPosition().offset(3, 2, 3))) {
            var s = level.getBlockState(p);
            if (s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door && s.is(net.minecraft.tags.BlockTags.WOODEN_DOORS)
                    && s.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) {
                door.setOpen(bot, level, s, p.immutable(), false);
            }
        }
    }

    // --- winning ------------------------------------------------------------------------

    private boolean won(BotPlayer bot) {
        UUID id = this.opp;
        Relation r = id == null ? null : this.brain.knownRelation(id);
        BlockPos where = this.brain.killedAt(id);
        this.brain.log("beat " + this.oppName);
        // Their stuff: only taken when the hatred runs deep (rubbing it in).
        if (r != null && where != null && (r.liking <= -85 || r.wick)) {
            this.brain.log("taking " + this.oppName + "'s stuff");
            this.mode = Mode.LOOT;
            this.loot = new CollectItems(where, 6, 20 * 20);
            return true;
        }
        if (where != null) this.brain.log("leaving " + this.oppName + "'s stuff where it fell");
        return end(bot, null);
    }

    private boolean lootTick(BotPlayer bot) {
        Task t = this.loot;
        if (t != null && t.tick(bot, this.brain) == Task.Status.RUNNING) return true;
        var server = bot.level().getServer();
        UUID id = this.opp;
        ServerPlayer them = server == null || id == null ? null : server.getPlayerList().getPlayer(id);
        if (them != null) tell(them, bot, Feelings.Act.LOOTED_ME); // they'll find out when they go back for it
        return end(bot, null);
    }

    // --- endings and helpers ----------------------------------------------------------

    private boolean end(BotPlayer bot, @Nullable String why) {
        if (why != null && this.opp != null) this.brain.log("fight with " + this.oppName + " over: " + why);
        Feuds.stopped(bot.getUUID());
        this.duel.stop(bot);
        this.opp = null;
        this.mode = null;
        this.loot = null;
        this.brain.navigator().stop(bot);
        return false;
    }

    /** Someone else ended this for us (made peace from their side): ends on our next tick. */
    private @Nullable UUID endRequested;

    public void endFrom(UUID with) {
        if (with.equals(this.opp)) this.endRequested = with;
    }

    /** Tell a bot something we did to it (a player makes up their own mind). */
    private void tell(ServerPlayer them, BotPlayer us, Feelings.Act act) {
        if (them instanceof BotPlayer other) {
            BotBrain theirs = brainOf(other);
            if (theirs != null) theirs.feel(us, act, 1);
        }
    }

    private static @Nullable BotBrain brainOf(BotPlayer other) {
        var server = other.level().getServer();
        return server == null ? null : BotManager.get(server).brain(other.getUUID());
    }

    /** Testing: forget who we've run into lately (so the next meeting rolls again). */
    public void forgetEncounters() {
        this.rolledAt.clear();
        this.rolledFight.clear();
    }

    public @Nullable UUID opponent() {
        return this.opp;
    }

    @Override
    public void onOwnerDeath(BotPlayer bot) {
        end(bot, "I died");
    }

    @Override
    public void stop(BotPlayer bot) {
        // Interrupted (eating, a mob): the fight stays on (we come back to it), the moves stop.
        this.duel.stop(bot);
        this.brain.navigator().stop(bot);
    }

    @Override
    public String activity() {
        if (this.mode == Mode.RETREAT) return "Getting away from " + this.oppName;
        if (this.mode == Mode.LOOT) return "Taking " + this.oppName + "'s stuff";
        if (this.mode == Mode.HUNT) return "Hunting " + this.oppName;
        return "Fighting " + this.oppName;
    }
}
