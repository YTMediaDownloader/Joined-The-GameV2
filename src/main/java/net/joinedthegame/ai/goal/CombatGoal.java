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
        // Further off counts for less (it might not even come).
        double dist = Math.sqrt(mob.distanceToSqr(bot));
        return dist <= 6 ? d : d * 0.6;
    }

    private static double danger(BotPlayer bot, List<Mob> mobs) {
        double total = 0;
        for (Mob m : mobs) total += danger(bot, m);
        return total;
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
        return isRanged(mob) && mob.isInWater();
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
            boolean aggro = mobTarget == bot || (mobTarget instanceof Player p && !BotManager.isBot(p) && p.distanceToSqr(bot) < 16 * 16);
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

        if (this.retreating || shouldRetreat(bot, nearby)) return retreat(bot, nearby);

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

        // A creeper close by trumps everything: the zombie hurts, the creeper kills.
        Mob creeperNear = nearby.stream().filter(m -> m instanceof Creeper && m.distanceToSqr(bot) < 7 * 7)
                .min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(bot))).orElse(null);
        if (creeperNear != null) this.target = target = creeperNear;
        double dist = Math.sqrt(target.distanceToSqr(bot));
        // A mace smash under way (dropping off a ledge, or riding a wind charge up): see it through.
        if (this.smashStage != 0 && tryMace(bot, target, dist)) return true;
        if (this.spearMode && Inv.bestSpear(bot) >= 0) Inv.hold(bot, Inv.bestSpear(bot));
        else if (Inv.bestWeapon(bot) >= 0) Inv.hold(bot, Inv.bestWeapon(bot));
        this.brain.controls().lookAt(bot, target.getBoundingBox().getCenter());

        // A bow or crossbow and something to shoot: keep our distance and shoot it.
        // (Never at a breeze: it bounces every arrow straight back, 26.3's projectile deflection.
        // Breezes are melee only.)
        if (dist > 5 && dist < 28 && rangedSlot(bot) >= 0 && bot.hasLineOfSight(target) && !deflectsArrows(target)) return shoot(bot, target, dist);
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

        // Shield up while waiting for the swing to recharge, but only if hurt, outnumbered
        // or just a nervous sort. Everyone else trusts their footwork.
        boolean defensive = hasShield(bot) && dist < 2.6 && charge < 0.8
                && (cautious() || bot.getHealth() < bot.getMaxHealth() * 0.5f || enemies >= 3);
        if (defensive) {
            raiseShield(bot);
            nav.stop(bot);
            return true;
        }
        lowerShield(bot);

        // Just hit: step back out of its reach while the swing recharges.
        if (this.backOff > 0) {
            this.backOff--;
            nav.stop(bot);
            this.brain.controls().forward = -1f;
            this.brain.controls().strafe = this.weave % 2 == 0 ? 0.4f : -0.4f;
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
        boolean canJump = bot.onGround() && !bot.isInWater() && new WorldView(bot.level()).passable(bot.blockPosition().above(2));
        if (canJump && !this.jumpedForCrit && dist < 3.0) {
            this.brain.controls().jump = true;
            this.jumpedForCrit = true;
            return true;
        }
        boolean falling = !bot.onGround() && bot.getDeltaMovement().y < 0;
        boolean landedWithoutHit = this.jumpedForCrit && bot.onGround();
        if (dist <= 3.0 && (falling || !canJump || landedWithoutHit) && bot.hasLineOfSight(target)) {
            swing(bot, target);
            this.jumpedForCrit = false;
            this.backOff = 6 + bot.getRandom().nextInt(4);
            this.weave++;
        }
        return true;
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
            if (hasShield(bot) && dist < 2.5) {
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
                Placer.place(bot, c, Inv.scaffold(bot));
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

    private boolean shouldRetreat(BotPlayer bot, List<Mob> nearby) {
        if (nearby.isEmpty()) return false;
        if (bot.getHealth() <= fleeHealth()) return true;
        // Sized it up and it's not going our way: go now, while there's health left to run on.
        if (outmatched(bot, nearby)) return true;
        // A drowned throwing tridents from the river: get out of its sight, don't wade in.
        if (nearby.stream().anyMatch(CombatGoal::shootingFromWater)) return true;
        // A crowd and already hurt.
        return nearby.size() >= 3 && bot.getHealth() <= 10;
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
            this.noBuilding = false;
            this.safeTicks = 0;
            this.pillarBase = null;
            this.hide = null;
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
            this.brain.log(String.format("not a fight I'd win (%.1f hp, odds %.2f), %s", bot.getHealth(),
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
        if (shooter != null && hasShield(bot) && this.hide == null && this.pillarBase == null) {
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
    private boolean hideOut(BotPlayer bot, List<Mob> nearby) {
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
        if (hidden && bot.getFoodData().getFoodLevel() < 20 && bot.getHealth() < bot.getMaxHealth()) {
            int food = Inv.bestFood(bot, bot.getFoodData().getFoodLevel() <= 6);
            if (food >= 0 && !bot.isUsingItem()) {
                Inv.hold(bot, food);
                bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
            }
        }
        boolean threatClose = !nearby.isEmpty() && !hidden
                || nearby.stream().anyMatch(m -> m.distanceToSqr(bot) < 3 * 3);
        if (threatClose || bot.getHealth() < bot.getMaxHealth() * 0.7f) {
            this.safeTicks = 0;
        } else if (++this.safeTicks > 100) {
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
