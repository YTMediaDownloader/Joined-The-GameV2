package net.joinedthegame.ai.goal;

import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fighting like a decent player instead of a punching bag:
 * <ul>
 *   <li><b>Melee:</b> step in when the swing is charged, jump for a critical hit, then back off
 *       while it recharges so the zombie doesn't get its hit in.</li>
 *   <li><b>Skeletons:</b> close the gap fast, weaving side to side, shield up while the
 *       skeleton draws its bow; then fight it up close.</li>
 *   <li><b>Creepers:</b> hit and back away; if it's about to blow, run, or shield up when
 *       it's too late.</li>
 *   <li><b>Picking fights:</b> before and during every fight the bot sizes it up: its health,
 *       armour, weapon and shield against how many mobs there are and how nasty they are.
 *       Bad odds and it doesn't start the fight; odds turning bad and it leaves early, while
 *       it still has the health to get away.</li>
 *   <li><b>Getting away:</b> sprint off if it can (it can't when hungry), pillar up if
 *       something's right on it, dig in if archers are about. Eats once it has some room.</li>
 * </ul>
 * Shields only come up for melee when the bot is hurt, outnumbered or the nervous type:
 * normal bots don't turtle.
 */
public final class CombatGoal extends Goal {
    private @Nullable LivingEntity target;
    private int ticks;
    private int repathIn;
    /** Ticks left of backing off after a hit. */
    private int backOff;
    private int weave;
    private boolean jumpedForCrit;

    // Retreat state
    private boolean retreating;
    /** Cornered under fire: fight, don't go back to retreating, until then. */
    private long fightItOutUntil;
    private int stuckShielding;
    private @org.jspecify.annotations.Nullable Vec3 shieldFrom;
    private int retreatTicks;
    private int safeTicks;
    private @Nullable BlockPos pillarBase;
    private int pillarHeight;
    private net.joinedthegame.ai.task.@Nullable HideTask hide;
    /** Got hit trying to pillar or box in: for the rest of this retreat, just run. */
    private boolean noBuilding;
    private float buildStartHealth;
    private net.joinedthegame.ai.task.@Nullable HideTask lastHide;

    public CombatGoal(BotBrain brain) {
        super(brain);
    }

    // --- personality -----------------------------------------------------------

    /** Health at which we stop fighting and run. Brave/reckless bots push their luck. */
    private float fleeHealth() {
        double brave = this.brain.personality().brave();
        double reckless = this.brain.personality().reckless();
        return (float) (3 + (1 - brave) * 3 - reckless * 2); // ~1..6 hp: fight it out, like a player
    }

    /** The nervous ones keep their shield up a lot more. */
    private boolean cautious() {
        return this.brain.personality().brave() < 0.2;
    }

    // --- sizing up a fight ------------------------------------------------------------

    /**
     * How much fight the bot has in it: 1.0 is a healthy bot with no armour and a stone
     * sword; full iron with an iron sword and a shield is about 4.5.
     */
    public static double power(BotPlayer bot) {
        double health = bot.getHealth() / bot.getMaxHealth();
        double armor = 1 + bot.getArmorValue() / 10.0;
        int weaponTier = Math.max(Inv.swordTier(bot), Inv.axeTier(bot) - 1);
        double weapon = 0.6 + 0.2 * Math.max(1, weaponTier);
        double shield = hasShield(bot) ? 1.3 : 1.0;
        return health * armor * weapon * shield;
    }

    /** How dangerous one mob is, about 1.0 for a zombie. */
    private static double danger(BotPlayer bot, Mob mob) {
        double d;
        String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).getPath();
        d = switch (id) {
            case "warden" -> 1000.0; // nobody fights the warden: get away
            case "witch", "vindicator", "ravager", "evoker", "piglin_brute" -> 3.0;
            case "blaze", "ghast" -> 2.0;
            case "enderman", "wither_skeleton", "breeze" -> 2.5;
            case "creeper" -> 1.6;
            case "cave_spider", "spider" -> 1.2;
            case "vex", "phantom" -> 1.2;
            default -> 1.0;
        };
        if (isRanged(mob) && d < 1.5) d = hasShield(bot) ? 1.2 : 1.8; // arrows hurt a lot more without a shield
        if (isRanged(mob) && d >= 1.5 && !hasShield(bot)) d *= 1.5; // fireballs too
        if (mob.isBaby()) d *= 1.3; // fast and hard to hit
        if (id.equals("drowned") && mob.isHolding(Items.TRIDENT)) d = 2.0;
        // Further off counts for less (it might not even come). Not an archer: it doesn't have to.
        double dist = Math.sqrt(mob.distanceToSqr(bot));
        return dist <= 6 || isRanged(mob) ? d : d * 0.6;
    }

    private static double danger(BotPlayer bot, List<Mob> mobs) {
        double total = 0;
        int close = 0;
        for (Mob m : mobs) {
            total += danger(bot, m);
            if (!isRanged(m) && m.distanceToSqr(bot) < 5 * 5) close++;
        }
        // A crowd is worse than its mobs added up: they come from every side, one hits while you
        // swing at another, and the knockback pushes you into the next. Zombie crowds were a
        // quarter of all deaths in the overnight run.
        return total * (1 + 0.2 * Math.max(0, close - 1));
    }

    /**
     * Do we fancy our chances? Strength against danger, with brave and reckless bots needing
     * worse odds before they back down.
     */
    private boolean outmatched(BotPlayer bot, List<Mob> mobs) {
        if (mobs.isEmpty()) return false;
        // A healthy bot with a stone sword and no armour should take on two zombies and back
        // off from three; brave and reckless ones push it a bit further.
        double needed = 0.55 - this.brain.personality().brave() * 0.15 - this.brain.personality().reckless() * 0.15;
        // Defending a village in a raid: that's the whole point, hold your ground (a bit).
        if (bot.level().isRaided(bot.blockPosition()) && Inv.fullIron(bot)) needed -= 0.25;
        return power(bot) / danger(bot, mobs) < needed;
    }

    // --- threat picking ----------------------------------------------------------

    @Override
    public int priority(BotPlayer bot) {
        LivingEntity threat = findThreat(bot);
        if (threat == null && !this.retreating) return 0;
        // Badly hurt: don't go looking for a fight. Only deal with things actually coming
        // for us (and then mostly by getting away).
        if (threat != null && !this.retreating && bot.getHealth() < bot.getMaxHealth() * 0.5f) {
            boolean comingForUs = threat instanceof Mob m && m.getTarget() == bot || threat == this.brain.recentAttacker();
            if (!comingForUs && threat.distanceToSqr(bot) > 5 * 5) return 0;
        }
        // A fight we wouldn't win, and it hasn't noticed us: leave it alone.
        if (threat != null && !this.retreating) {
            boolean comingForUs = threat instanceof Mob m && m.getTarget() == bot || threat == this.brain.recentAttacker();
            if (!comingForUs && threat.distanceToSqr(bot) > 6 * 6 && outmatched(bot, threats(bot))) return 0;
        }
        if (threat != null) this.target = threat;
        return 88 + (int) (this.brain.personality().brave() * 6);
    }

    /** Any piece of gold armour on: piglins won't bother us. */
    public static boolean wearsGold(BotPlayer bot) {
        for (var slot : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bot.getItemBySlot(slot).getItem()).getPath();
            if (id.startsWith("golden_")) return true;
        }
        return false;
    }

    private static boolean isRanged(LivingEntity mob) {
        return mob.isHolding(Items.BOW) || mob.isHolding(Items.CROSSBOW) || mob.isHolding(Items.TRIDENT)
                || mob instanceof net.minecraft.world.entity.monster.Blaze || mob instanceof net.minecraft.world.entity.monster.Ghast
                || mob instanceof net.minecraft.world.entity.monster.breeze.Breeze;
    }

    /** Turns arrows back on the shooter (the breeze: the deflects_projectiles entity tag). */
    private static boolean deflectsArrows(LivingEntity mob) {
        return mob.is(net.minecraft.tags.EntityTypeTags.DEFLECTS_PROJECTILES);
    }

    /** Something shooting at us from the water: can't be fought, only got away from. */
    private static boolean shootingFromWater(Mob mob) {
        // Fully under the water (a drowned with a trident out in the river). Only touching it
        // doesn't count: trial chamber dispensers splash water about, so half the mobs in a
        // chamber are standing in some, and that's no reason to run from them.
        return isRanged(mob) && mob.isUnderWater();
    }

    private List<Mob> hostilesNear(BotPlayer bot, double range) {
        // Something deep underwater (a drowned down on the riverbed) can't be fought from up
        // here; leave it unless it's right on us.
        return Senses.entitiesNear(bot, Mob.class, range, m -> m.isAlive() && m instanceof Enemy
                && !(m instanceof Enderman && m.getTarget() != bot)
                // Piglins leave you alone in gold armour: return the favour.
                && !(m instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin && wearsGold(bot) && m.getTarget() != bot)
                // Neutral until provoked (zombified piglins come in groups): leave them be.
                && !(m instanceof net.minecraft.world.entity.NeutralMob && m.getTarget() != bot && !(m instanceof Enderman))
                && !(m.isUnderWater() && !isRanged(m) && m.distanceToSqr(bot) > 2.5 * 2.5));
    }

    /**
     * Everything that's actually a danger right now: anything close, plus anything that can
     * shoot us from range (a skeleton 15 blocks away is still a threat).
     */
    private List<Mob> threats(BotPlayer bot) {
        LivingEntity attacker = this.brain.recentAttacker();
        return hostilesNear(bot, 16).stream().filter(m -> m.distanceToSqr(bot) < 10 * 10 || m == attacker
                || isRanged(m) && m.getTarget() == bot && bot.hasLineOfSight(m)).toList();
    }

    private @Nullable LivingEntity findThreat(BotPlayer bot) {
        LivingEntity attacker = this.brain.recentAttacker();
        if (attacker != null && attacker.isAlive() && attacker.distanceToSqr(bot) < 16 * 16) return attacker;
        double range = 6 + this.brain.personality().brave() * 6;
        for (Mob mob : hostilesNear(bot, 16)) {
            LivingEntity mobTarget = mob.getTarget();
            // Going for us, for a real player (unless it can't stand them), or for a friend.
            boolean aggro = mobTarget == bot || mobTarget instanceof Player p && p != bot && p.distanceToSqr(bot) < 16 * 16
                    && (BotManager.isBot(p) ? this.brain.friendly(p.getUUID()) : !this.brain.dislikes(p.getUUID()));
            if (!aggro && mob.distanceToSqr(bot) > range * range) continue;
            // The old hand doesn't pick fights it doesn't need: only what's after it, or in its face.
            if (!aggro && this.brain.personality().nomad() > 0.5 && mob.distanceToSqr(bot) > 3 * 3) continue;
            if (!bot.hasLineOfSight(mob)) continue;
            return mob;
        }
        return null;
    }

    @Override
    public void start(BotPlayer bot) {
        this.spearMode = Inv.spearIsBest(bot);
        this.smashStage = 0;
        this.gap = null;
        this.ticks = 0;
        this.repathIn = 0;
        this.backOff = 0;
        this.retreating = false;
        this.safeTicks = 0;
        this.pillarBase = null;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        stopDrawing(bot);
        lowerShield(bot);
        this.retreating = false;
    }

    // --- tick -------------------------------------------------------------------------

    @Override
    public boolean tick(BotPlayer bot) {
        // (No time limit while hiding out: coming down off the pillar into the zombies is worse.)
        if (++this.ticks > 20 * 60 && !(this.retreating && this.hide != null)) return false;
        List<Mob> nearby = threats(bot);

        // Half way through a potion: give it the moment (back off a little, facing them). Swapping
        // to the sword now would waste it.
        if (net.joinedthegame.ai.Potions.drinking(bot)) {
            this.brain.navigator().stop(bot);
            Mob near = nearby.stream().min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(bot))).orElse(null);
            if (near != null) {
                this.brain.controls().lookAt(bot, near.getBoundingBox().getCenter());
                if (near.distanceToSqr(bot) < 4 * 4) this.brain.controls().forward = -0.7f;
            }
            return true;
        }
        if (!nearby.isEmpty() && net.joinedthegame.ai.Potions.fight(bot, this.brain, nearestDist(bot, nearby), danger(bot, nearby),
                this.retreating)) return true;

        // Cornered under fire earlier (couldn't back away, couldn't wall in): fighting it out, for
        // a while, rather than going straight back to standing there behind a shield.
        boolean cornered = this.brain.now() < this.fightItOutUntil;
        if (!cornered && (this.retreating || shouldRetreat(bot, nearby))) return retreat(bot, nearby);

        LivingEntity target = this.target;
        if (target == null || !target.isAlive() || target.isRemoved() || target.level() != bot.level()
                || target.distanceToSqr(bot) > 24 * 24) {
            LivingEntity next = findThreat(bot);
            if (next == null) {
                stopDrawing(bot); // it died mid-draw: put the bow down
                lowerShield(bot);
                return false;
            }
            this.target = target = next;
        }

        // The right one to hit: looked at again twice a second (straight away if a creeper's
        // close), sticking with the one we're on unless something's clearly worse.
        if (this.ticks % 10 == 0 || !(target instanceof Creeper)
                && nearby.stream().anyMatch(m -> m instanceof Creeper && m.distanceToSqr(bot) < 7 * 7)) {
            LivingEntity better = bestTarget(bot, nearby, target);
            if (better != null) this.target = target = better;
        }
        double dist = Math.sqrt(target.distanceToSqr(bot));
        // Someone coming down on us with a mace: shield up, or out from under them.
        if (net.joinedthegame.ai.skill.Duelist.dodgeSmash(bot, this.brain, target)) return true;
        // A mace smash under way (dropping off a ledge, or riding a wind charge up): see it through.
        if (this.smashStage != 0 && tryMace(bot, target, dist)) return true;
        if (this.spearMode && Inv.bestSpear(bot) >= 0) Inv.hold(bot, Inv.bestSpear(bot));
        else if (Inv.bestWeapon(bot) >= 0) Inv.hold(bot, Inv.bestWeapon(bot));
        this.brain.controls().lookAt(bot, target.getBoundingBox().getCenter());

        // A bow or crossbow and something to shoot: keep our distance and shoot it.
        // (Never at a breeze: it bounces every arrow straight back, 26.3's projectile deflection.
        // Breezes are melee only.)
        // Not off our feet (knocked off a ledge, mid-jump, in the water: that's not when you start
        // drawing a bow). And not an archer within reach of a shield: a bow can't block, so a bow
        // duel just trades arrows, and with a shield and a sword the way to beat a skeleton is to
        // walk in behind the shield and hit it. The bow's for the ones further off, or with no
        // shield to hand.
        boolean bowDuel = isRanged(target) && hasShield(bot) && dist < 14;
        if (dist > 5 && dist < 28 && rangedSlot(bot) >= 0 && bot.hasLineOfSight(target) && !deflectsArrows(target)
                && bot.onGround() && !bot.isInWater() && !bowDuel) return shoot(bot, target, dist);
        stopDrawing(bot);
        if (target instanceof Creeper creeper) return fightCreeper(bot, creeper, dist);
        if (isRanged(target) && dist > 3.5) return closeInOnArcher(bot, target, dist);
        // A real chance for a mace smash? Take it. A poor one: the normal weapon.
        if (tryMace(bot, target, dist)) return true;
        if (this.spearMode) return spear(bot, target, dist);
        return melee(bot, target, dist, nearby.size());
    }

    // --- melee ------------------------------------------------------------------------

    private boolean melee(BotPlayer bot, LivingEntity target, double dist, int enemies) {
        Navigator nav = this.brain.navigator();
        float charge = bot.getAttackStrengthScale(0.5f);
        List<Mob> crowd = meleeCrowd(bot, 6);
        if (crowdTactics(bot, crowd)) return true;
        boolean holding = this.gap != null && Navigator.feet(bot).equals(this.gap) && crowd.size() >= 2;

        // Shield up while waiting for the swing to recharge, if hurt, more than one of them on us,
        // or just a nervous sort. One zombie: footwork's enough.
        int onUs = (int) crowd.stream().filter(m -> m.distanceToSqr(bot) < 3 * 3).count();
        boolean defensive = hasShield(bot) && dist < 2.6 && charge < 0.8
                && (cautious() || bot.getHealth() < bot.getMaxHealth() * 0.5f || enemies >= 3 || onUs >= 2);
        if (defensive) {
            raiseShield(bot);
            nav.stop(bot);
            return true;
        }
        lowerShield(bot);

        // Just hit: step back out of its reach while the swing recharges. (Not out of the gap
        // we've backed into: that's the whole point of it.)
        if (this.backOff > 0) {
            this.backOff--;
            nav.stop(bot);
            if (!holding) {
                this.brain.controls().forward = -1f;
                this.brain.controls().strafe = this.weave % 2 == 0 ? 0.4f : -0.4f;
            }
            return true;
        }

        // In the gap: let them come to us, one at a time.
        if (holding && dist > 3.2) {
            nav.stop(bot);
            if (hasShield(bot) && dist < 5) raiseShield(bot);
            return true;
        }

        if (dist > 3.2) {
            if (--this.repathIn <= 0) {
                nav.sprint = true;
                nav.moveNear(bot, target.position(), 2.0);
                this.repathIn = 10;
            }
            if (nav.status() == Navigator.Status.FAILED && dist > 6) return false;
            return true;
        }
        nav.stop(bot);

        if (charge < 0.9f) {
            // Hover just outside its reach until we're ready.
            this.brain.controls().forward = dist < 2.4 ? -0.6f : 0f;
            return true;
        }

        // Charged: step in and go for a critical hit (jump, hit on the way down).
        this.brain.controls().forward = dist > 2.4 ? 1f : 0f;
        this.brain.controls().sprint = false;
        // Actually stop sprinting: letting go of the key isn't enough, a sprint carries on till
        // something stops it, and a sprinting hit is never a critical (it's a knockback hit).
        // Bots "never really got crits" for that.
        if (bot.isSprinting()) bot.setSprinting(false);
        boolean canJump = bot.onGround() && !bot.isInWater() && new WorldView(bot.level()).passable(bot.blockPosition().above(2));
        // Two or more in reach and a sword: a hit on the ground sweeps the lot (a jumping crit
        // doesn't sweep). One at a time: the crit.
        boolean sweep = onUs >= 2 && bot.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS);
        if (sweep) canJump = false;
        if (canJump && !this.jumpedForCrit && dist < 3.0) {
            this.brain.controls().jump = true;
            this.jumpedForCrit = true;
            return true;
        }
        boolean falling = !bot.onGround() && bot.getDeltaMovement().y < 0;
        boolean landedWithoutHit = this.jumpedForCrit && bot.onGround();
        // In reach the game's way (eyes to the nearest bit of it), as we come down.
        if (bot.isWithinEntityInteractionRange(target, -0.1) && (falling || !canJump || landedWithoutHit) && bot.hasLineOfSight(target)) {
            if (falling && bot.isSprinting()) bot.setSprinting(false);
            swing(bot, target);
            this.jumpedForCrit = false;
            this.backOff = 6 + bot.getRandom().nextInt(4);
            this.weave++;
        }
        return true;
    }

    // --- picking the target ---------------------------------------------------------------

    /**
     * Which one to hit: the most dangerous for how close it is. A creeper within seven blocks
     * beats everything (the zombie hurts, the creeper kills); whatever's hitting us right now
     * beats an archer across the field (charging the archer with a zombie on our back is how the
     * zombie gets the kill); and the one we're on gets a bit of a pull, so we don't flit about.
     */
    private @Nullable LivingEntity bestTarget(BotPlayer bot, List<Mob> nearby, LivingEntity current) {
        LivingEntity best = null;
        double bestScore = 0;
        for (Mob m : nearby) {
            double dist = Math.sqrt(m.distanceToSqr(bot));
            if (dist > 3 && !bot.hasLineOfSight(m)) continue;
            double score = danger(bot, m) / Math.max(1.0, dist);
            if (m instanceof Creeper && dist < 7) score += 100;
            if (m.getTarget() == bot) score *= 1.5;
            if (dist < 3 && !isRanged(m)) score *= 2.0; // on us now
            if (isRanged(m) && m.isUsingItem() && !hasShield(bot) && dist < 16 && nearestMelee(bot, nearby) > 4) score *= 1.5;
            if (m == current) score *= 1.3;
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }
        // The one we're on isn't a mob (a person who hit us): keep it unless a creeper's close.
        if (current != null && !(current instanceof Mob) && current.isAlive() && !(best instanceof Creeper)) return current;
        return best;
    }

    private static double nearestMelee(BotPlayer bot, List<Mob> mobs) {
        double best = Double.MAX_VALUE;
        for (Mob m : mobs) if (!isRanged(m)) best = Math.min(best, Math.sqrt(m.distanceToSqr(bot)));
        return best;
    }

    // --- crowds -----------------------------------------------------------------------------

    /** The gap we've backed into (feet), and how long we've been trying to get there. */
    private @Nullable BlockPos gap;
    private int gapTicks;
    private long gapTriedAt = -1000;

    /** The ones that fight up close, within {@code r}. */
    private List<Mob> meleeCrowd(BotPlayer bot, double r) {
        return hostilesNear(bot, r).stream().filter(m -> !isRanged(m) && !(m instanceof Creeper)).toList();
    }

    /**
     * Three or more coming at us in the open: back into a gap close by (a nook, a doorway, a
     * one-wide passage) so they can only come at us one or two at a time, from the front, and hold
     * it. That's what a player does with a horde: never fight it in the open. True while we're
     * on our way there.
     */
    private boolean crowdTactics(BotPlayer bot, List<Mob> crowd) {
        Navigator nav = this.brain.navigator();
        if (crowd.size() < 3) {
            this.gap = null;
            return false;
        }
        if (this.gap != null) {
            if (Navigator.feet(bot).equals(this.gap)) return false; // there: fight from here
            if (++this.gapTicks > 50 || nav.status() == Navigator.Status.FAILED) {
                this.gap = null; // couldn't get there in time: fight where we are
                return false;
            }
            BlockPos to = this.gap;
            if (!nav.isMoving()) nav.moveTo(bot, Vec3.atBottomCenterOf(to), p -> p.equals(to));
            nav.sprint = true;
            return true;
        }
        if (this.brain.now() - this.gapTriedAt < 20 * 6) return false;
        this.gapTriedAt = this.brain.now();
        WorldView w = new WorldView(bot.level());
        BlockPos feet = Navigator.feet(bot);
        if (openSides(w, feet) <= 1) {
            this.gap = feet.immutable(); // already in one
            return false;
        }
        Vec3 center = Vec3.ZERO;
        for (Mob m : crowd) center = center.add(m.position());
        center = center.scale(1.0 / crowd.size());
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : BlockPos.betweenClosed(feet.offset(-5, -1, -5), feet.offset(5, 1, 5))) {
            if (!w.passable(c) || !w.passable(c.above()) || !w.solidFloor(c.below())) continue;
            if (WorldView.isDangerous(w.state(c)) || WorldView.isDangerous(w.state(c.below()))) continue;
            int open = openSides(w, c);
            if (open == 0 || open > 2) continue; // shut in, or no better than here
            Vec3 at = Vec3.atBottomCenterOf(c);
            // Not into the middle of them: no closer to the crowd than we are now, and none on it.
            if (at.distanceTo(center) < bot.position().distanceTo(center) - 1.0) continue;
            boolean taken = false;
            for (Mob m : crowd) if (m.position().distanceTo(at) < 1.5) taken = true;
            if (taken) continue;
            double score = at.distanceTo(bot.position()) + (open == 2 ? 2 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = c.immutable();
            }
        }
        if (best == null) {
            this.brain.log("a crowd of " + crowd.size() + " and no gap close by: fighting it here");
            return false;
        }
        this.gap = best;
        this.gapTicks = 0;
        this.brain.log("a crowd of " + crowd.size() + ": backing into the gap at " + best.toShortString() + " so they come one at a time");
        nav.stop(bot);
        nav.sprint = true;
        BlockPos to = best;
        nav.moveTo(bot, Vec3.atBottomCenterOf(to), p -> p.equals(to));
        return true;
    }

    /** Ways in to this spot a mob could walk (two high, floor under it). */
    private static int openSides(WorldView w, BlockPos c) {
        int open = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos n = c.relative(d);
            if (w.passable(n) && w.passable(n.above()) && (w.solidFloor(n.below()) || w.passable(n.below()) && w.solidFloor(n.below(2)))) open++;
        }
        return open;
    }

    // --- spear ------------------------------------------------------------------------

    /** Fighting with a spear this time (decided when the fight starts: swapping resets the swing). */
    private boolean spearMode;

    /**
     * A spear jabs (26.3: PiercingWeapon) at anything along our aim from 2 to 4.5 blocks out,
     * but not closer than 2, and only on a full charge. So: keep it at that distance, step back
     * if it gets too close, and jab when charged. (It pierces: a line of them all get it.)
     */
    private boolean spear(BotPlayer bot, LivingEntity target, double dist) {
        Navigator nav = this.brain.navigator();
        lowerShield(bot);
        if (dist > 4.2) {
            if (--this.repathIn <= 0) {
                nav.sprint = true;
                nav.moveNear(bot, target.position(), 3.4);
                this.repathIn = 10;
            }
            if (nav.status() == Navigator.Status.FAILED && dist > 6) return false;
            return true;
        }
        nav.stop(bot);
        // Inside its minimum reach: back off (and hold it off with the shield if it's on us).
        if (dist < 2.3) {
            this.brain.controls().forward = -1f;
            this.brain.controls().strafe = this.weave % 2 == 0 ? 0.3f : -0.3f;
            if (hasShield(bot) && dist < 1.6) raiseShield(bot);
            return true;
        }
        ItemStack held = bot.getMainHandItem();
        var jab = held.get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON);
        if (jab == null) {
            this.spearMode = false;
            return true;
        }
        if (bot.getAttackStrengthScale(0.5f) < 1.0f || bot.cannotAttackWithItem(held, 5) || !bot.hasLineOfSight(target)) return true;
        net.joinedthegame.ai.skill.Placer.lookExactly(bot, target.getBoundingBox().getCenter());
        jab.attack(bot, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        bot.resetLastActionTime();
        this.weave++;
        return true;
    }

    // --- mace -----------------------------------------------------------------------

    /** 0: no smash on; 1: stepping off a ledge onto it; 2: riding a wind charge up. */
    private int smashStage;
    private int smashTicks;
    private long lastLaunch = -100000;

    /**
     * The mace's smash (26.3: falling more than 1.5 blocks when the hit lands; the damage climbs
     * with the fall, and a hit that lands cancels the fall damage). Only when there's a real,
     * safe chance:
     * <ul>
     *   <li>it's below us (2-6 blocks) with somewhere safe to land: step off onto it;</li>
     *   <li>or a wind charge at our feet throws us up, with plenty of headroom, solid ground all
     *       round, no lava or drop anywhere near, and not hurt: ride it up, smash on the way down.</li>
     * </ul>
     * Never from a dangerous height, never near lava or a cliff, never in water or a tight
     * cave, never on a creeper. Missed it: land, and carry on with the normal weapon.
     */
    private boolean tryMace(BotPlayer bot, LivingEntity target, double dist) {
        int mace = Inv.find(bot, s -> s.is(Items.MACE));
        if (mace < 0 || target instanceof Creeper) {
            this.smashStage = 0;
            return false;
        }
        Vec3 flatTo = target.position().subtract(bot.position()).multiply(1, 0, 1);
        double flat = flatTo.length();
        double below = bot.getY() - target.getY();
        if (this.smashStage == 0) {
            float hp = bot.getHealth() / bot.getMaxHealth();
            if (hp < 0.5f - this.brain.personality().reckless() * 0.15f || bot.isInWater() || !bot.onGround()) return false;
            WorldView w = new WorldView(bot.level());
            if (below >= 2 && below <= 6 && flat <= 4 && safeLanding(w, target.blockPosition())) {
                this.smashStage = 1;
                this.smashTicks = 0;
                this.brain.log("mace: dropping onto the " + target.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT));
            } else if (dist <= 3.0 && Math.abs(below) < 1.5 && Inv.count(bot, Items.WIND_CHARGE) > 0
                    && this.brain.now() - this.lastLaunch > 20 * 10 // not every charge we've got, one after another
                    && bot.getAttackStrengthScale(0.5f) > 0.9f && headroom(w, bot.blockPosition()) >= 7
                    && safeLanding(w, bot.blockPosition()) && safeLanding(w, target.blockPosition())) {
                // Wind charge straight down at our feet: up we go.
                if (!Inv.hold(bot, s -> s.is(Items.WIND_CHARGE))) return false;
                net.joinedthegame.ai.skill.Placer.lookExactly(bot, bot.position().add(0, -1, 0));
                bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
                this.smashStage = 2;
                this.smashTicks = 0;
                this.lastLaunch = this.brain.now();
                this.brain.log("mace: wind charge, going up for a smash");
                return true;
            } else {
                return false;
            }
        }
        this.smashTicks++;
        if (this.smashTicks > 60 || bot.isInWater()) {
            this.smashStage = 0;
            return false;
        }
        Inv.hold(bot, mace);
        this.brain.navigator().stop(bot);
        this.brain.controls().lookAt(bot, target.getBoundingBox().getCenter());
        if (this.smashStage == 1 && bot.onGround()) {
            // Walk off the edge towards it.
            this.brain.controls().faceTowards(bot, target.position());
            this.brain.controls().forward = 1f;
        } else if (!bot.onGround() && bot.getDeltaMovement().y < 0 && flat > 1.0) {
            // Coming down: steer over it.
            this.brain.controls().faceTowards(bot, target.position());
            this.brain.controls().forward = 0.6f;
        }
        if (bot.fallDistance > 1.5 && !bot.onGround() && dist <= 3.2 && bot.hasLineOfSight(target)) {
            double fell = bot.fallDistance; // (a smash that lands resets it)
            swing(bot, target);
            this.brain.log("mace: smash! (from " + String.format(java.util.Locale.ROOT, "%.1f", fell) + " blocks)");
            this.smashStage = 0;
            return true;
        }
        if (bot.onGround() && this.smashTicks > 10) this.smashStage = 0; // landed without the hit: carry on normally
        return true;
    }

    /** Clear air above (for a wind-charge jump). */
    private static int headroom(WorldView w, BlockPos feet) {
        int n = 0;
        for (int y = 2; y <= 9 && w.passable(feet.above(y)); y++) n++;
        return n;
    }

    /** Somewhere it's safe to come down: solid ground all round, no drop, no lava. */
    private static boolean safeLanding(WorldView w, BlockPos at) {
        if (w.lavaNear(at.offset(-2, -3, -2), at.offset(2, 1, 2), 1)) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos c = at.offset(dx, 0, dz);
                boolean floor = false;
                for (int d = 1; d <= 2 && !floor; d++) floor = w.solidFloor(c.below(d));
                if (!floor) return false; // a hole or a cliff edge
            }
        }
        return true;
    }

    private void swing(BotPlayer bot, LivingEntity target) {
        // The right thing in hand first. Some ways into a swing (something getting into the hole
        // with us, backing off, charging an archer) came straight from shooting, and hit with the
        // bow. Swapped just now? Then not this swing: the game resets the charge on a swap, so it'd
        // be a weak one anyway. Next time round.
        stopDrawing(bot);
        int weapon = this.spearMode && Inv.bestSpear(bot) >= 0 ? Inv.bestSpear(bot) : Inv.bestWeapon(bot);
        if (weapon >= 0 && !ItemStack.isSameItemSameComponents(bot.getMainHandItem(), bot.getInventory().getItem(weapon))) {
            Inv.hold(bot, weapon);
            return;
        }
        bot.attack(target);
        bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        bot.resetLastActionTime();
    }

    // --- shooting ------------------------------------------------------------------------

    private int drawTicks;

    /** A bow or crossbow we can shoot right now (have arrows for), or -1. */
    private static int rangedSlot(BotPlayer bot) {
        if (Inv.count(bot, s -> s.is(net.minecraft.tags.ItemTags.ARROWS)) == 0) return -1;
        int crossbow = Inv.find(bot, s -> s.is(Items.CROSSBOW));
        return crossbow >= 0 ? crossbow : Inv.find(bot, s -> s.is(Items.BOW));
    }

    /** Draw, aim a touch high (arrows drop), let go. Side-step a little between shots. */
    private boolean shoot(BotPlayer bot, LivingEntity target, double dist) {
        Navigator nav = this.brain.navigator();
        nav.stop(bot);
        // Shooting it out with an archer: when it draws and we're not nearly ready ourselves, bow
        // down, shield up to take its arrow, then back to it. (A bow can't block: drawing through
        // its shot is taking the hit.)
        if (isRanged(target) && target.isUsingItem() && hasShield(bot) && this.drawTicks < 14) {
            stopDrawing(bot);
            this.drawTicks = 0;
            this.brain.holdShield(8);
            raiseShield(bot);
            this.brain.controls().lookAt(bot, target.getBoundingBox().getCenter());
            return true;
        }
        if (this.brain.shieldHeld() && hasShield(bot)) {
            raiseShield(bot); // the arrow's still on its way
            return true;
        }
        lowerShield(bot);
        Inv.hold(bot, rangedSlot(bot));
        ItemStack held = bot.getMainHandItem();
        Vec3 aim = target.getBoundingBox().getCenter().add(0, 0.004 * dist * dist + 0.1, 0);
        Placer.lookExactly(bot, aim);
        this.brain.controls().lookAt(bot, aim);
        // Something rushing us: back off while drawing.
        if (dist < 8 && !(isRanged(target))) this.brain.controls().forward = -0.5f;
        else this.brain.controls().strafe = (this.ticks / 30) % 2 == 0 ? 0.3f : -0.3f;
        boolean usingMain = bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.MAIN_HAND;
        if (held.is(Items.CROSSBOW)) {
            if (net.minecraft.world.item.CrossbowItem.isCharged(held)) {
                bot.gameMode.useItem(bot, bot.level(), held, InteractionHand.MAIN_HAND); // fire
            } else if (!usingMain) {
                bot.gameMode.useItem(bot, bot.level(), held, InteractionHand.MAIN_HAND); // start loading
            } else if (bot.getTicksUsingItem() >= net.minecraft.world.item.CrossbowItem.getChargeDuration(held, bot)) {
                bot.releaseUsingItem(); // loaded
            }
        } else {
            if (!usingMain) {
                bot.gameMode.useItem(bot, bot.level(), held, InteractionHand.MAIN_HAND);
                this.drawTicks = 0;
            } else if (++this.drawTicks >= 22) {
                bot.releaseUsingItem(); // full draw: loose
                this.drawTicks = 0;
            }
        }
        return true;
    }

    /** Switching to the sword: put the bow down. */
    private static void stopDrawing(BotPlayer bot) {
        if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.MAIN_HAND
                && (bot.getMainHandItem().is(Items.BOW) || bot.getMainHandItem().is(Items.CROSSBOW))) {
            bot.stopUsingItem();
        }
    }

    // --- archers ------------------------------------------------------------------------

    /** Close the gap on a skeleton: sprint in, weaving, shield up while it draws. */
    private boolean closeInOnArcher(BotPlayer bot, LivingEntity archer, double dist) {
        Navigator nav = this.brain.navigator();
        // Shield up as it starts drawing (shields take a moment to start blocking). After the
        // shot, the arrow reflex keeps it up for exactly as long as an arrow is actually
        // coming at us, then it drops and we keep charging.
        // A skeleton drawing its bow, or a blaze glowing (that's it about to fire): shield up.
        boolean drawing = (archer.isUsingItem() || archer instanceof net.minecraft.world.entity.monster.Blaze blaze && blaze.isOnFire())
                && bot.hasLineOfSight(archer);
        if (drawing) this.brain.holdShield(3);
        // No shield, nothing to shoot back with, and it's well off: charging across open ground
        // is three or four arrows in the face. Get round a corner instead; a skeleton that can't
        // see you walks over to find you, and comes round it into sword range.
        if (!hasShield(bot) && rangedSlot(bot) < 0 && ambush(bot, archer, dist)) return true;
        if (this.brain.shieldHeld() && hasShield(bot)) {
            // Arrow incoming: face it with the shield and keep walking in behind it.
            raiseShield(bot);
            nav.stop(bot);
            this.brain.controls().forward = 1f;
            return true;
        }
        lowerShield(bot);
        if (--this.repathIn <= 0) {
            nav.sprint = true;
            nav.moveNear(bot, archer.position(), 2.0);
            this.repathIn = 10;
        }
        if (nav.status() == Navigator.Status.FAILED) {
            // No path: just run straight at it, side-stepping so the arrows miss.
            nav.stop(bot);
            this.brain.controls().faceTowards(bot, archer.position());
            this.brain.controls().forward = 1f;
            this.brain.controls().sprint = true;
            this.brain.controls().strafe = (this.ticks / 12) % 2 == 0 ? 0.6f : -0.6f;
            if (bot.horizontalCollision) this.brain.controls().jump = true;
        } else if (!hasShield(bot)) {
            // No shield: weave while we run in.
            this.brain.controls().strafe = (this.ticks / 12) % 2 == 0 ? 0.5f : -0.5f;
        }
        return dist < 24;
    }

    /** The corner we're waiting behind for an archer, and since when. */
    private @Nullable BlockPos cover;
    private int coverTicks;
    private long coverTriedAt = -1000;

    /**
     * Wait round a corner for an archer (see closeInOnArcher). True while we're getting there or
     * waiting; false once it's close (go and hit it), after a while (it's not coming: charge), or
     * if there's no corner handy.
     */
    private boolean ambush(BotPlayer bot, LivingEntity archer, double dist) {
        Navigator nav = this.brain.navigator();
        if (dist < 4.5 || archer instanceof net.minecraft.world.entity.monster.Blaze || archer instanceof net.minecraft.world.entity.monster.Ghast) {
            this.cover = null; // close enough to hit; and flyers come over the top anyway
            return false;
        }
        if (this.cover == null) {
            if (dist < 9 || this.brain.now() - this.coverTriedAt < 20 * 15) return false;
            this.coverTriedAt = this.brain.now();
            WorldView w = new WorldView(bot.level());
            BlockPos feet = Navigator.feet(bot);
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (BlockPos c : BlockPos.betweenClosed(feet.offset(-5, -1, -5), feet.offset(5, 1, 5))) {
                if (!w.passable(c) || !w.passable(c.above()) || !w.solidFloor(c.below()) || WorldView.isDangerous(w.state(c.below()))) continue;
                Vec3 head = Vec3.atBottomCenterOf(c).add(0, 1.6, 0);
                if (Math.sqrt(c.distToCenterSqr(archer.position())) < dist - 2) continue; // not towards it
                var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(archer.getEyePosition(), head,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, archer));
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) continue; // it can see there
                double d = c.distToCenterSqr(bot.position());
                if (d < bestDist) {
                    bestDist = d;
                    best = c.immutable();
                }
            }
            if (best == null) return false;
            this.cover = best;
            this.coverTicks = 0;
            this.brain.log("no shield against the " + archer.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT)
                    + ": round the corner at " + best.toShortString() + " to let it come to me");
        }
        if (++this.coverTicks > 20 * 15) {
            this.cover = null; // it's not coming: go for it after all
            return false;
        }
        BlockPos to = this.cover;
        if (!Navigator.feet(bot).equals(to)) {
            nav.sprint = true;
            if (!nav.isMoving()) nav.moveTo(bot, Vec3.atBottomCenterOf(to), p -> p.equals(to));
            if (nav.status() == Navigator.Status.FAILED) this.cover = null;
            return this.cover != null;
        }
        nav.stop(bot);
        this.brain.controls().lookAt(bot, archer.getBoundingBox().getCenter());
        return true;
    }

    // --- creepers -------------------------------------------------------------------------

    private int creeperBackOff;

    /**
     * Creepers: sprint-hit for knockback, then get well back (about 4 blocks, not a little hop),
     * and let it come again. Hissing close by: turn and sprint straight away, right now, onto
     * safe ground; shield up only if it's too late to get clear.
     */
    private boolean fightCreeper(BotPlayer bot, Creeper creeper, double dist) {
        Navigator nav = this.brain.navigator();
        boolean hissing = creeper.getSwellDir() > 0;
        if (hissing && dist < 6) {
            // A shield, facing it, takes the whole blast: better than turning our back to run and
            // being caught a few blocks off (the fuse is a second and a half; the blast reaches
            // further than that). Bots ran with their shields down and died with them.
            if (hasShield(bot) && dist < 5) {
                nav.stop(bot);
                this.brain.holdShield(3);
                raiseShield(bot);
                this.brain.controls().lookAt(bot, creeper.getBoundingBox().getCenter());
                return true;
            }
            lowerShield(bot);
            // Too close to get clear and no shield: a quick wall between us soaks up the blast.
            if (dist < 3 && Inv.scaffoldCount(bot) >= 1) wallOff(bot, creeper);
            return getAwayFrom(bot, creeper);
        }
        lowerShield(bot);
        // Just hit it: back right off while it recovers.
        if (this.creeperBackOff > 0) {
            this.creeperBackOff--;
            if (dist < 4.5) return getAwayFrom(bot, creeper);
            nav.stop(bot);
            this.brain.controls().lookAt(bot, creeper.getBoundingBox().getCenter());
            return true;
        }
        if (dist > 2.8) {
            if (--this.repathIn <= 0) {
                nav.sprint = true;
                nav.moveNear(bot, creeper.position(), 2.0);
                this.repathIn = 10;
            }
            return true;
        }
        nav.stop(bot);
        this.brain.controls().lookAt(bot, creeper.getBoundingBox().getCenter());
        if (bot.getAttackStrengthScale(0.5f) > 0.9f) {
            // Sprinting when the hit lands knocks it a good way back.
            this.brain.controls().forward = 1f;
            this.brain.controls().sprint = true;
            bot.setSprinting(true);
            swing(bot, creeper);
            this.creeperBackOff = 25;
        } else {
            this.brain.controls().forward = -0.6f; // don't stand in its face while recharging
        }
        return true;
    }

    /**
     * One block in the cell between us and it, at feet height: soaks up a good part of the
     * blast, while the creeper can still see us and goes off (a full wall just sends it round).
     */
    private void wallOff(BotPlayer bot, LivingEntity from) {
        Vec3 to = from.position().subtract(bot.position()).multiply(1, 0, 1);
        if (to.lengthSqr() < 0.01) return;
        net.minecraft.core.Direction dir = net.minecraft.core.Direction.getApproximateNearest(to.x, 0, to.z);
        BlockPos feet = Navigator.feet(bot).relative(dir);
        for (BlockPos c : new BlockPos[]{feet}) {
            if (bot.level().getBlockState(c).canBeReplaced() && !from.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(c))) {
                if (Placer.place(bot, c, Inv.scaffold(bot))) net.joinedthegame.ai.Scaffold.placed(bot, c);
            }
        }
    }

    /** Turn and sprint straight away from it (no route planning: there isn't time). */
    private boolean getAwayFrom(BotPlayer bot, LivingEntity from) {
        Navigator nav = this.brain.navigator();
        nav.stop(bot);
        Vec3 away = bot.position().subtract(from.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
        away = away.normalize();
        // Straight away if that's safe ground, otherwise whichever side is.
        Vec3 left = new Vec3(-away.z, 0, away.x);
        Vec3 dir = safeStep(bot, away) ? away : safeStep(bot, left) ? left : safeStep(bot, left.scale(-1)) ? left.scale(-1) : null;
        if (dir == null) {
            // Boxed in: face it and back up as best we can.
            this.brain.controls().lookAt(bot, from.getBoundingBox().getCenter());
            this.brain.controls().forward = -1f;
            return true;
        }
        this.brain.controls().faceTowards(bot, bot.position().add(dir.scale(4)));
        this.brain.controls().forward = 1f;
        this.brain.controls().sprint = bot.getFoodData().getFoodLevel() > 6;
        if (bot.horizontalCollision && bot.onGround()) this.brain.controls().jump = true;
        return true;
    }

    // --- retreating -------------------------------------------------------------------------

    /** Why we just decided to get out (for the log). */
    private String retreatWhy = "";

    private boolean shouldRetreat(BotPlayer bot, List<Mob> nearby) {
        if (nearby.isEmpty()) return false;
        this.retreatWhy = "low health";
        if (bot.getHealth() <= fleeHealth()) return true;
        // Sized it up and it's not going our way: go now, while there's health left to run on.
        this.retreatWhy = "outmatched";
        if (outmatched(bot, nearby)) return true;
        // A drowned throwing tridents from the river: get out of its sight, don't wade in.
        this.retreatWhy = "shot at from the water";
        if (nearby.stream().anyMatch(CombatGoal::shootingFromWater)) return true;
        // A crowd and already hurt.
        this.retreatWhy = "a crowd, and hurt";
        return nearby.size() >= 3 && bot.getHealth() <= 12 || nearby.size() >= 5 && bot.getHealth() <= 15;
    }

    /** The ground a step that way is solid, safe to stand on, and not a drop. */
    private static boolean safeStep(BotPlayer bot, Vec3 dir) {
        if (dir.lengthSqr() < 0.01) return false;
        WorldView w = new WorldView(bot.level());
        BlockPos next = BlockPos.containing(bot.getX() + dir.x * 1.2, bot.getY() + 0.2, bot.getZ() + dir.z * 1.2);
        if (!w.passable(next) || !w.passable(next.above())) return false;
        // Solid ground under it (or one down at most), and no lava or fire anywhere near the step.
        boolean floor = w.solidFloor(next.below()) || w.solidFloor(next.below(2)) && w.passable(next.below());
        if (!floor) return false;
        for (BlockPos c : new BlockPos[]{next, next.below(), next.below(2)}) {
            if (WorldView.isDangerous(w.state(c))) return false;
        }
        return true;
    }

    /** Standing on top of a one-wide column with drops all round (a pillar we built). */
    private static boolean onPillar(BotPlayer bot) {
        WorldView w = new WorldView(bot.level());
        BlockPos below = Navigator.feet(bot).below();
        if (w.passable(below)) return false;
        int open = 0;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (w.passable(below.relative(d)) && w.passable(below.relative(d).below())) open++;
        }
        return open >= 3;
    }

    private static double nearestDist(BotPlayer bot, List<Mob> mobs) {
        double best = Double.MAX_VALUE;
        for (Mob m : mobs) best = Math.min(best, Math.sqrt(m.distanceToSqr(bot)));
        return best;
    }

    private boolean retreat(BotPlayer bot, List<Mob> nearby) {
        if (!this.retreating) {
            this.retreating = true;
            this.retreatTicks = 0;
            this.exposedTicks = 0;
            this.noBuilding = false;
            this.safeTicks = 0;
            this.pillarBase = null;
            this.hide = null;
            this.hiddenTicks = 0;
            this.tunnelTo = null;
            this.tunnelling = false;
            // Remember who did this (the nearest archer, or whoever hit us).
            this.cause = nearby.stream().filter(CombatGoal::isRanged).min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(bot)))
                    .orElse(this.brain.recentAttacker() instanceof Mob m && isRanged(m) ? m : null);
            // Pick the way out that'll actually work:
            // - archers about: running in the open gets you shot in the back, so dig in
            //   (unless something's already on top of us, then get up a pillar first);
            // - can't sprint (hungry) or something's right on us: pillar up out of reach;
            // - otherwise: sprint away.
            boolean archers = nearby.stream().anyMatch(CombatGoal::isRanged);
            boolean canSprint = bot.getFoodData().getFoodLevel() > 6;
            double nearest = nearestDist(bot, nearby);
            boolean blocks = Inv.scaffoldCount(bot) >= 3;
            String how;
            boolean nether = bot.level().dimension() == net.minecraft.world.level.Level.NETHER;
            if (archers && nearest > 3.5 && !nether) {
                this.hide = new net.joinedthegame.ai.task.HideTask();
                how = "digging in";
            } else if (nether) {
                // Nether: blazes fly and wither skeletons reach high. Run, don't dig or pillar.
                how = "running";
            } else if (onPillar(bot)) {
                // Already up a pillar: stay put, don't build it taller (and fall off it).
                this.hide = net.joinedthegame.ai.task.HideTask.stay();
                how = "staying up here";
            } else if (Inv.scaffoldCount(bot) >= 9 && bot.onGround() && (!canSprint || nearest < 2.5) && !this.noBuilding) {
                // Can't outrun them: wall ourselves in right here (works on skeletons too).
                this.hide = net.joinedthegame.ai.task.HideTask.box();
                how = "boxing myself in";
            } else if (blocks && bot.onGround() && (!canSprint || nearest < 2.5) && !archers && !this.noBuilding) {
                // Only zombies and the like: up out of reach. Never with skeletons about.
                this.hide = net.joinedthegame.ai.task.HideTask.pillar();
                how = "pillaring up";
            } else {
                how = "running";
            }
            this.brain.log(String.format("not a fight I'd win (%s: %.1f hp, odds %.2f), %s", this.retreatWhy, bot.getHealth(),
                    power(bot) / Math.max(0.01, danger(bot, nearby)), how));
        }
        if (this.hide != null) return hideOut(bot, nearby);
        this.retreatTicks++;
        lowerShield(bot);
        Navigator nav = this.brain.navigator();

        // Safe for a couple of seconds? Done; eating and healing take over from here.
        // Safe means nothing close AND nothing that can still shoot us.
        boolean threatClose = !nearby.isEmpty();
        if (!threatClose) {
            if (++this.safeTicks > 40) {
                this.retreating = false;
                cooldown(this.brain.now(), 20 * 8);
                return false;
            }
        } else {
            this.safeTicks = 0;
        }

        // Up a pillar already: stay put.
        if (this.pillarBase != null && this.pillarHeight >= 2) {
            nav.stop(bot);
            if (!nearby.isEmpty()) this.brain.controls().lookAt(bot, nearby.getFirst());
            return this.retreatTicks < 20 * 45;
        }
        if (this.pillarBase != null) return pillarUp(bot);

        // Shooters about and a shield: back away facing them, shield up. Turning your back on
        // blazes or skeletons to run is how you get a fireball in the back.
        Mob shooter = nearby.stream().filter(CombatGoal::isRanged).min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(bot))).orElse(null);
        // (On land: in the water there's no stepping back onto anything, so it'd stand there with
        // its shield up for ever. Swim off instead, below.)
        if (shooter != null && hasShield(bot) && this.hide == null && this.pillarBase == null && !bot.isInWater()) {
            nav.stop(bot);
            this.brain.controls().lookAt(bot, shooter.getBoundingBox().getCenter());
            this.brain.holdShield(5);
            raiseShield(bot);
            // Only step back (or aside) onto safe ground: nobody wants to back into lava.
            Vec3 away = bot.position().subtract(shooter.position()).multiply(1, 0, 1);
            Vec3 back = away.lengthSqr() < 0.01 ? Vec3.ZERO : away.normalize();
            Vec3 side = new Vec3(-back.z, 0, back.x).scale((this.retreatTicks / 25) % 2 == 0 ? 1 : -1);
            boolean backOk = safeStep(bot, back);
            boolean sideOk = safeStep(bot, side);
            // Getting nowhere (in a hole, against a wall: nowhere safe to step) and still being hit
            // (several of them, from all round: a shield only covers the front)? Standing there is
            // the worst of both. Redlantern stood a minute and a half in a pit behind his shield
            // while the bogged took him from fifteen health to six. Wall in if we've the blocks,
            // otherwise go for them.
            boolean hurtLately = this.brain.now() - this.brain.lastAttackedAt() < 40;
            if (!backOk && !sideOk || hurtLately && bot.position().distanceToSqr(this.shieldFrom == null ? bot.position() : this.shieldFrom) < 1) this.stuckShielding++;
            else this.stuckShielding = 0;
            if (this.shieldFrom == null || this.retreatTicks % 40 == 0) this.shieldFrom = bot.position();
            if (this.stuckShielding > 60) {
                this.stuckShielding = 0;
                lowerShield(bot);
                if (Inv.scaffoldCount(bot) >= 9 && bot.onGround() && !this.noBuilding) {
                    this.hide = net.joinedthegame.ai.task.HideTask.box();
                    this.brain.log("can't back away from them and still getting hit: walling myself in");
                } else {
                    this.retreating = false;
                    this.fightItOutUntil = this.brain.now() + 20 * 20;
                    this.brain.log("can't back away from them and still getting hit: going for them");
                }
                return true;
            }
            this.brain.controls().forward = backOk ? -1f : 0f;
            this.brain.controls().strafe = sideOk ? ((this.retreatTicks / 25) % 2 == 0 ? 0.4f : -0.4f) : 0f;
            if (backOk && bot.horizontalCollision && bot.onGround()) this.brain.controls().jump = true;
            return this.retreatTicks < 20 * 45;
        }

        // Some room to breathe: eat on the move to get health back.
        if (nearestDist(bot, nearby) > 7 && bot.getHealth() < bot.getMaxHealth() * 0.7f && !bot.isUsingItem()) {
            int food = Inv.bestFood(bot, bot.getFoodData().getFoodLevel() <= 6);
            if (food >= 0 && bot.getFoodData().needsFood()) {
                Inv.hold(bot, food);
                bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
            }
        }

        // Run away from the middle of the pack (towards home if that's roughly the same way).
        Vec3 center = Vec3.ZERO;
        for (Mob mob : nearby) center = center.add(mob.position());
        center = nearby.isEmpty() ? bot.position() : center.scale(1.0 / nearby.size());
        Vec3 away = bot.position().subtract(center);
        if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
        away = away.normalize();
        var home = this.brain.record().home();
        if (home != null && home.dimension() == bot.level().dimension()) {
            Vec3 toHome = Vec3.atCenterOf(home.pos()).subtract(bot.position());
            if (toHome.lengthSqr() > 16 && toHome.normalize().dot(away) > 0.3) away = toHome.normalize().add(away).normalize();
        }
        Vec3 dest = bot.position().add(away.scale(16));
        nav.sprint = true;
        if (!nav.isMoving() || this.retreatTicks % 30 == 0) nav.moveNear(bot, dest, 4);

        // Cornered (can't get away, mob right on us): pillar up out of reach.
        // (Or it's keeping up with us: still right behind after a second and a half.)
        boolean cornered = nav.status() == Navigator.Status.FAILED || (this.retreatTicks > 30 && threatClose
                && nearestDist(bot, nearby) < 3);
        boolean shooters = nearby.stream().anyMatch(CombatGoal::isRanged);
        if (cornered && bot.onGround() && !onPillar(bot) && !this.noBuilding
                && bot.level().dimension() != net.minecraft.world.level.Level.NETHER) {
            if (Inv.scaffoldCount(bot) >= 9) {
                nav.stop(bot);
                this.hide = net.joinedthegame.ai.task.HideTask.box();
                this.brain.log("cornered, boxing myself in");
            } else if (Inv.scaffoldCount(bot) >= 3 && !shooters) {
                nav.stop(bot);
                this.hide = net.joinedthegame.ai.task.HideTask.pillar();
                this.brain.log("cornered, pillaring up");
            }
        }
        return this.retreatTicks < 20 * 45;
    }

    /** In the hole: eat to heal, and come out once things have calmed down. */
    private int exposedTicks;

    /** The archer that sent us into hiding (it counts while it's about, seen or not). */
    private @Nullable Mob cause;
    /** How long we've been holed up. */
    private int hiddenTicks;
    /** Getting out underground, away from an archer camping the hole. */
    private @Nullable BlockPos tunnelTo;
    private boolean tunnelling;

    /**
     * Archers still a danger to us down here: the one that sent us in (while it's about), any
     * that are after us, any with a clear shot at the way out (a skeleton 12 blocks off with a
     * view of the hole is worse than one 8 off behind a wall), and any right on top of us.
     */
    private List<Mob> waitingArchers(BotPlayer bot) {
        Vec3 exit = new Vec3(bot.getX(), bot.getY() + 4.0, bot.getZ()); // the top of the hole
        return hostilesNear(bot, 24).stream().filter(m -> isRanged(m) && (m == this.cause && m.distanceToSqr(bot) < 24 * 24
                || m.getTarget() == bot
                || m.distanceToSqr(bot) < 16 * 16 && clearShot(m, exit)
                || m.distanceToSqr(bot) < 6 * 6))
                .sorted(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(bot))).toList();
    }

    private static boolean clearShot(Mob from, Vec3 to) {
        var hit = from.level().clip(new net.minecraft.world.level.ClipContext(from.getEyePosition(), to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, from));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** Dig off sideways underground and come up well away from the archer at the hole. */
    private boolean tunnelOut(BotPlayer bot) {
        BlockPos to = this.tunnelTo;
        Navigator nav = this.brain.navigator();
        if (to == null) return false;
        if (!this.tunnelling) {
            this.tunnelling = true;
            // Strictly underground: no higher than the floor of the hole (the cheap way out is up and
            // over the top, right past the skeleton).
            nav.moveUnderground(bot, Vec3.atBottomCenterOf(to), p -> p.closerThan(to, 2.5), Navigator.feet(bot).getY());
        }
        if (nav.isMoving() && this.retreatTicks++ < 20 * 60 * 5) return true;
        this.brain.log(nav.status() == Navigator.Status.ARRIVED ? "tunnelled out" : "couldn't tunnel out: carrying on");
        this.retreating = false;
        this.hide = null;
        this.tunnelTo = null;
        this.tunnelling = false;
        cooldown(this.brain.now(), 20 * 5);
        return false;
    }

    private boolean hideOut(BotPlayer bot, List<Mob> nearby) {
        if (this.tunnelTo != null) return tunnelOut(bot);
        this.retreatTicks++;
        if (this.hide != this.lastHide) {
            this.lastHide = this.hide;
            this.buildStartHealth = bot.getHealth();
        }
        if (this.hide.building() && bot.hurtTime > 0 && bot.getHealth() < this.buildStartHealth - 2) {
            this.brain.log("getting hit while " + (this.hide.pillaring() ? "pillaring" : "boxing in") + ": forget it, run");
            this.hide.stop(bot, this.brain);
            this.hide = null;
            this.noBuilding = true;
            return true;
        }
        net.joinedthegame.ai.task.Task.Status status = this.hide.tick(bot, this.brain);
        if (status == net.joinedthegame.ai.task.Task.Status.FAILED) {
            this.hide = null; // couldn't dig in: fall back to running
            return true;
        }
        boolean hidden = net.joinedthegame.ai.task.HideTask.isHidden(bot) || this.hide.onPillar(bot);
        if (hidden) this.exposedTicks = 0;
        else if (++this.exposedTicks > 20 * 12 || this.exposedTicks > 20 * 2 && bot.getHealth() < this.buildStartHealth - 4) {
            // A while at it and still out in the open (half a box, a skeleton picking at us from
            // across the room, and chamber mobs never wander off), or taking a beating while we
            // try: hiding isn't working.
            // Fight it if it's a fight we'd win, otherwise just run.
            this.brain.log("can't get hidden here: " + (shouldRetreat(bot, nearby) ? "running instead" : "fighting it out"));
            this.hide.stop(bot, this.brain);
            this.hide = null;
            this.noBuilding = true;
            this.exposedTicks = 0;
            if (!shouldRetreat(bot, nearby)) this.retreating = false;
            return true;
        }
        // Something's got in with us (or is right at the edge): shield up while the swing
        // recharges, drop it and hit when it's ready. Don't just turtle.
        if (!this.hide.building()) {
            Mob close = nearby.stream().filter(m -> m.distanceToSqr(bot) < 3.2 * 3.2 && bot.hasLineOfSight(m))
                    .min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(bot))).orElse(null);
            if (close != null) {
                this.brain.controls().lookAt(bot, close.getBoundingBox().getCenter());
                if (bot.getAttackStrengthScale(0.5f) > 0.9f) {
                    lowerShield(bot);
                    if (Inv.bestWeapon(bot) >= 0) Inv.hold(bot, Inv.bestWeapon(bot));
                    swing(bot, close);
                } else if (hasShield(bot)) {
                    raiseShield(bot);
                }
                this.safeTicks = 0;
                return true;
            }
        }
        // Up a pillar with zombies underneath: whack them from up here, they can't hit back.
        if (this.hide.onPillar(bot) && bot.getAttackStrengthScale(0.5f) > 0.9f) {
            for (Mob mob : nearby) {
                if (!isRanged(mob) && mob.distanceToSqr(bot) < 3.2 * 3.2 && bot.hasLineOfSight(mob)) {
                    if (Inv.bestWeapon(bot) >= 0) Inv.hold(bot, Inv.bestWeapon(bot));
                    this.brain.controls().lookAt(bot, mob.getBoundingBox().getCenter());
                    swing(bot, mob);
                    break;
                }
            }
        }
        // Eat to heal: not only once perfectly hidden (Redlantern sat half a minute at eight health
        // in a hole a bogged could still see into, food at seventeen, which is one short of healing
        // at all, and never ate). Nothing right on us is enough. Low, a golden apple first.
        if ((hidden || nearestDist(bot, nearby) > 4) && bot.getFoodData().getFoodLevel() < 20 && bot.getHealth() < bot.getMaxHealth()) {
            int food = Inv.bestFood(bot, bot.getFoodData().getFoodLevel() <= 6);
            if (bot.getHealth() <= 6) {
                int golden = Inv.find(bot, st -> st.is(Items.GOLDEN_APPLE) || st.is(Items.ENCHANTED_GOLDEN_APPLE));
                if (golden >= 0) food = golden;
            }
            if (food >= 0 && !bot.isUsingItem()) {
                Inv.hold(bot, food);
                bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
            }
        }
        boolean threatClose = !nearby.isEmpty() && !hidden
                || nearby.stream().anyMatch(m -> m.distanceToSqr(bot) < 3 * 3);
        // A skeleton still waiting out there: it can't see into the hole, so it isn't in
        // "nearby", but popping out now just gets us shot and sends us back down, over and over,
        // until it walks in on us. So, once healed up:
        // - a fight we'd win: go and finish it;
        // - one we wouldn't: wait a while, then tunnel off underground and come up elsewhere;
        // - and never sit down here for ever (no nine-minute vacations).
        boolean healed = bot.getHealth() >= bot.getMaxHealth() * 0.7f;
        List<Mob> archers = hidden ? waitingArchers(bot) : List.of();
        if (hidden) this.hiddenTicks++;
        if (!archers.isEmpty() && !this.hide.onPillar(bot) && !this.hide.building()) {
            Mob archer = archers.getFirst();
            // (The same call as out there: everything about, archers at full danger.)
            List<Mob> all = new java.util.ArrayList<>(nearby);
            for (Mob a : archers) if (!all.contains(a)) all.add(a);
            if (healed && !shouldRetreat(bot, all)) {
                this.brain.log("healed up: going after the " + archer.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT));
                this.retreating = false;
                this.hide.stop(bot, this.brain);
                this.hide = null;
                this.target = archer;
                return true;
            }
            if (healed && this.hiddenTicks > 20 * 45 && this.tunnelTo == null) {
                Vec3 away = bot.position().subtract(archer.position()).multiply(1, 0, 1);
                if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
                away = away.normalize().scale(14);
                this.tunnelTo = Navigator.feet(bot).offset((int) Math.round(away.x), 0, (int) Math.round(away.z));
                this.brain.log("the " + archer.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT)
                        + " is still waiting up there: tunnelling out the other way, to " + this.tunnelTo.toShortString());
                this.hide.stop(bot, this.brain);
                return tunnelOut(bot);
            }
            // (Waited long enough, couldn't tunnel: chance it.)
            if (this.hiddenTicks < 20 * 90) threatClose = true;
        }
        if (threatClose || !healed) {
            this.safeTicks = 0;
        } else if (++this.safeTicks > (hidden ? 200 : 100)) {
            this.retreating = false;
            this.hide.stop(bot, this.brain);
            this.hide = null;
            cooldown(this.brain.now(), 20 * 5);
            return false;
        }
        return this.retreatTicks < 20 * 60 * 5;
    }

    private boolean pillarUp(BotPlayer bot) {
        BlockPos base = this.pillarBase.above(this.pillarHeight);
        this.brain.controls().face(bot.getYRot(), 90f);
        if (bot.onGround() && bot.getY() < base.getY() + 0.5) this.brain.controls().jump = true;
        if (bot.getY() > base.getY() + 1.05 && Placer.place(bot, base, Inv.scaffold(bot))) {
            net.joinedthegame.ai.Scaffold.placed(bot, base);
            this.pillarHeight++;
        }
        return true;
    }

    // --- shield -------------------------------------------------------------------------

    private static boolean hasShield(BotPlayer bot) {
        return bot.getOffhandItem().is(Items.SHIELD);
    }

    private void raiseShield(BotPlayer bot) {
        if (!hasShield(bot)) return;
        if (!(bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.OFF_HAND)) {
            bot.gameMode.useItem(bot, bot.level(), bot.getOffhandItem(), InteractionHand.OFF_HAND);
        }
        this.brain.controls().blocking = true;
    }

    private void lowerShield(BotPlayer bot) {
        if (this.brain.shieldHeld()) return; // an arrow's still on its way
        if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.OFF_HAND) bot.stopUsingItem();
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return this.retreating && this.hide != null; // holed up / on a pillar, waiting them out
    }

    @Override
    public String activity() {
        if (this.retreating) {
            if (this.hide != null) return this.hide.activity().equals("Hiding") ? "Hiding" : "Getting away";
            return "Running away";
        }
        LivingEntity t = this.target;
        return t == null ? "Fighting" : "Fighting " + t.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
    }
}
