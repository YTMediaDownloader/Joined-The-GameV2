package net.joinedthegame.ai.skill;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

/**
 * Fighting a person (a bot or a player), the way a decent player does it. The mob fighting in
 * CombatGoal is built round mobs (creepers, archers, hiding from a horde); people are different:
 * they strafe, they block, they hit back on a recharged swing.
 * <ul>
 *   <li>Best weapon out. Wait for a full charge, step in, jump for the critical hit, step back out
 *       of reach while it recharges, weaving side to side.</li>
 *   <li>Shield up while our swing recharges and they're close and ready to hit (an axe can knock
 *       it out of our hands: that's the game's rule, not ours).</li>
 *   <li>A bow and arrows and they're some way off: shoot, strafing between shots.</li>
 *   <li>Their shield up: an axe knocks it out of their hands (five seconds without it), so out
 *       comes the axe for that one hit. No axe: don't hit the shield, get round the side of it.</li>
 *   <li>A mace coming down on us (they're in the air above us with one): shield up facing it
 *       (shields take a smash), or get out from under it.</li>
 *   <li>Potions: strength going in, healing when it's going badly.</li>
 * </ul>
 */
public final class Duelist {
    private final BotBrain brain;
    private int backOff;
    private int weave;
    private int repathIn;
    private int drawTicks;
    private int ticks;
    private boolean jumpedForCrit;

    public Duelist(BotBrain brain) {
        this.brain = brain;
    }

    /** One tick of the fight. False if we can't get at them at all. */
    public boolean fight(BotPlayer bot, LivingEntity them) {
        ++this.ticks;
        double dist = Math.sqrt(them.distanceToSqr(bot));
        this.brain.controls().lookAt(bot, them.getBoundingBox().getCenter());
        // Half way through a potion: keep our distance till it's down.
        if (net.joinedthegame.ai.Potions.drinking(bot)) {
            this.brain.navigator().stop(bot);
            if (dist < 4) this.brain.controls().forward = -1f;
            this.brain.controls().strafe = this.weave % 2 == 0 ? 0.5f : -0.5f;
            return true;
        }
        if (net.joinedthegame.ai.Potions.fight(bot, this.brain, dist, them instanceof Player ? 3.5 : 2.0, false)) return true;
        if (dodgeSmash(bot, this.brain, them)) return true;
        if (dist > 7 && dist < 30 && rangedSlot(bot) >= 0 && bot.hasLineOfSight(them)) return shoot(bot, them, dist);
        stopDrawing(bot);
        int axe = Inv.find(bot, s -> s.is(net.minecraft.tags.ItemTags.AXES));
        if (this.axeOut && axe >= 0) Inv.hold(bot, axe);
        else if (Inv.bestWeapon(bot) >= 0) Inv.hold(bot, Inv.bestWeapon(bot));
        return melee(bot, them, dist);
    }

    // --- shields and maces ------------------------------------------------------------

    /** The axe is out for knocking their shield away (until that hit lands). */
    private boolean axeOut;
    private long toldSmashAt = -1000;

    /** Their shield's up and pointing at us (it only covers the front). */
    static boolean blockingUs(LivingEntity them, BotPlayer bot) {
        // Up, or on its way up (a shield takes a quarter second to start blocking: a player sees
        // it coming up, and so do we).
        boolean raising = them.isUsingItem() && them.getUseItem().is(Items.SHIELD);
        if (!them.isBlocking() && !raising) return false;
        Vec3 toUs = bot.position().subtract(them.position()).multiply(1, 0, 1);
        if (toUs.lengthSqr() < 0.01) return true;
        return them.getViewVector(1f).multiply(1, 0, 1).normalize().dot(toUs.normalize()) > 0;
    }

    /**
     * Someone in the air above us with a mace: a smash is on its way down (26.3: more than 1.5
     * blocks of fall when the hit lands, and the damage climbs fast with the height). A shield
     * takes it (smashes don't get past shields), so: shield up, facing them. No shield: get out
     * from under them, sideways, sprinting. True while we're dealing with it.
     */
    public static boolean dodgeSmash(BotPlayer bot, BotBrain brain, LivingEntity them) {
        if (!them.getMainHandItem().is(Items.MACE) || them.onGround() || them.isFallFlying()) return false;
        double up = them.getY() - bot.getY();
        double flat = Math.hypot(them.getX() - bot.getX(), them.getZ() - bot.getZ());
        if (up < 1.5 || flat > 6) return false;
        brain.navigator().stop(bot);
        brain.controls().lookAt(bot, them.getBoundingBox().getCenter());
        boolean shield = bot.getOffhandItem().is(Items.SHIELD);
        if (brain.now() - lastToldSmash(brain) > 40) {
            brain.log((them instanceof Player p ? p.getGameProfile().name() : "they") + "'s coming down with a mace: "
                    + (shield ? "shield up" : "getting out from under"));
            SMASH_TOLD.put(brain, brain.now());
        }
        if (shield) {
            brain.holdShield(10);
            if (!(bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.OFF_HAND)) {
                bot.gameMode.useItem(bot, bot.level(), bot.getOffhandItem(), InteractionHand.OFF_HAND);
            }
            brain.controls().blocking = true;
            return true;
        }
        // Sideways, away from where they'll land (the smash knocks everyone within 3.5 back too).
        Vec3 away = bot.position().subtract(them.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
        away = away.normalize();
        brain.controls().faceTowards(bot, bot.position().add(away.add(new Vec3(-away.z, 0, away.x)).scale(4)));
        brain.controls().forward = 1f;
        brain.controls().sprint = bot.getFoodData().getFoodLevel() > 6;
        if (bot.horizontalCollision && bot.onGround()) brain.controls().jump = true;
        return true;
    }

    private static final java.util.Map<BotBrain, Long> SMASH_TOLD = new java.util.WeakHashMap<>();

    private static long lastToldSmash(BotBrain brain) {
        return SMASH_TOLD.getOrDefault(brain, -1000L);
    }

    private boolean melee(BotPlayer bot, LivingEntity them, double dist) {
        Navigator nav = this.brain.navigator();
        float charge = bot.getAttackStrengthScale(0.5f);
        // Shield up while ours recharges, if they're close and ready to swing.
        boolean theyreReady = them instanceof Player p && p.getAttackStrengthScale(0.5f) > 0.8f;
        if (hasShield(bot) && dist < 3.5 && charge < 0.8f && theyreReady) {
            raiseShield(bot);
            nav.stop(bot);
            this.brain.controls().strafe = this.weave % 2 == 0 ? 0.5f : -0.5f;
            return true;
        }
        lowerShield(bot);
        if (this.backOff > 0) {
            this.backOff--;
            nav.stop(bot);
            this.brain.controls().forward = -1f;
            this.brain.controls().strafe = this.weave % 2 == 0 ? 0.5f : -0.5f;
            return true;
        }
        // Can we land a hit from here? The game's own reach (eyes to the nearest bit of them),
        // not feet to feet: someone stood on a block over us is in reach; across a gap, not.
        boolean canHit = bot.isWithinEntityInteractionRange(them, -0.2);
        if (dist > 3.2 && !canHit) {
            // Getting no closer: up a pillar, walled in, round something there's no path round.
            // Walking at them isn't going to do it; what a player would do might.
            if (dist < this.closest - 0.3) {
                this.closest = dist;
                this.stalled = 0;
            } else {
                this.stalled++;
            }
            if (this.siege != null || this.stalled > 40 || nav.status() == Navigator.Status.FAILED) {
                Boolean sieging = siege(bot, them);
                if (sieging != null) return sieging;
            }
            if (--this.repathIn <= 0) {
                nav.sprint = true;
                nav.moveNear(bot, them.position(), 2.0);
                this.repathIn = 8;
            }
            return !(nav.status() == Navigator.Status.FAILED && dist > 8);
        }
        nav.stop(bot);
        // Close, but can't get a hit in (right under them on a pillar, a block in the way): not
        // just stand there (Dan stood at the foot of Gemini's pillar until the watchdog stepped in).
        if (!canHit || !bot.hasLineOfSight(them)) {
            if (++this.outOfReach > 30) {
                Boolean sieging = siege(bot, them);
                if (sieging != null) return sieging;
            }
            this.brain.controls().forward = 1f;
            return true;
        }
        this.outOfReach = 0;
        // Their shield's up at us. An axe knocks it out of their hands: get it out (swapping
        // resets the swing, so it's ready by the time we are). No axe: hitting the shield does
        // nothing, so circle round to the side it doesn't cover.
        if (blockingUs(them, bot)) {
            if (!this.axeOut && Inv.find(bot, s -> s.is(net.minecraft.tags.ItemTags.AXES)) >= 0) {
                this.axeOut = true;
                this.brain.log("their shield's up: axe out");
                return true;
            }
            if (!this.axeOut) {
                this.brain.controls().strafe = this.weave % 2 == 0 ? 0.9f : -0.9f;
                this.brain.controls().forward = dist > 2.2 ? 0.4f : 0f;
                if (this.ticks % 60 == 0) this.weave++;
                return true;
            }
        }
        if (charge < 0.9f) {
            // Just out of their reach until we're ready, side-stepping.
            this.brain.controls().forward = dist < 2.6 ? -0.6f : 0f;
            this.brain.controls().strafe = (this.ticks / 15) % 2 == 0 ? 0.35f : -0.35f;
            return true;
        }
        this.brain.controls().forward = dist > 2.4 ? 1f : 0f;
        this.brain.controls().sprint = false;
        boolean canJump = bot.onGround() && !bot.isInWater() && new WorldView(bot.level()).passable(bot.blockPosition().above(2));
        if (canJump && !this.jumpedForCrit && dist < 3.0) {
            this.brain.controls().jump = true;
            this.jumpedForCrit = true;
            return true;
        }
        boolean falling = !bot.onGround() && bot.getDeltaMovement().y < 0;
        boolean landed = this.jumpedForCrit && bot.onGround();
        if ((falling || !canJump || landed) && bot.hasLineOfSight(them)) {
            // The shield-breaker: on the ground, no crit needed (a crit doesn't matter to it).
            boolean axeHit = this.axeOut;
            this.axeOut = false;
            ItemStack blockingWith = axeHit ? them.getItemBlockingWith() : null;
            bot.attack(them);
            if (blockingWith != null) {
                // Did it actually knock the shield away? The game decides that inside the hit
                // (BlocksAttacks.disable): a player's shield goes on cooldown, anyone else just
                // stops blocking. Only say so if it did.
                boolean disabled = them instanceof Player p ? p.getCooldowns().isOnCooldown(blockingWith) : !them.isBlocking();
                this.brain.log(disabled ? "knocked their shield away" : "hit their raised shield with the axe");
            }
            bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            bot.resetLastActionTime();
            this.jumpedForCrit = false;
            this.backOff = 5 + bot.getRandom().nextInt(4);
            this.weave++;
        }
        return true;
    }

    private double closest = Double.MAX_VALUE;
    private int outOfReach;
    private int stalled;
    private net.minecraft.core.@org.jspecify.annotations.Nullable BlockPos siege;
    private int siegeTicks;

    /**
     * Out of reach and no way to walk there. Up a pillar close by: knock the block out from under
     * them (down they come). Walled in, boxed up, behind a door: break through what's between
     * us, theirs or not (it's a fight; they put it there to hide behind). Not the really hard stuff
     * (obsidian), and not chests and the like. Null if there's nothing to break: that's when we
     * give up (or they get shot at, if we've a bow).
     */
    private @org.jspecify.annotations.Nullable Boolean siege(BotPlayer bot, LivingEntity them) {
        WorldView w = new WorldView(bot.level());
        Navigator nav = this.brain.navigator();
        var target = this.siege;
        if (target == null || w.state(target).isAir()) {
            target = null;
            double flat = Math.hypot(them.getX() - bot.getX(), them.getZ() - bot.getZ());
            var under = them.blockPosition().below();
            String how = null;
            if (them.getY() - bot.getY() >= 1.5 && flat <= 4.5 && siegeable(w, under)) {
                target = under;
                how = "up a pillar: knocking it out from under them";
            } else {
                var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(bot.getEyePosition(), them.getEyePosition(),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && siegeable(w, hit.getBlockPos())) {
                    target = hit.getBlockPos();
                    how = "walled in: breaking through";
                }
            }
            if (target == null) {
                this.siege = null;
                return null;
            }
            if (this.siege == null) this.brain.log((them instanceof Player p ? p.getGameProfile().name() : "they") + "'s " + how);
            this.siege = target.immutable();
            this.siegeTicks = 0;
        }
        if (++this.siegeTicks > 20 * 30) {
            this.siege = null;
            return null;
        }
        var breaker = this.brain.breaker();
        if (BlockBreaker.inReach(bot, target)) {
            nav.stop(bot);
            if (!target.equals(breaker.target())) breaker.start(target);
            if (breaker.tick(bot, this.brain.controls()) != BlockBreaker.Result.RUNNING) {
                this.siege = null;
                this.stalled = 0;
                this.closest = Double.MAX_VALUE;
            }
            return true;
        }
        if (!nav.isMoving()) nav.moveToReach(bot, target);
        if (nav.status() == Navigator.Status.FAILED) {
            this.siege = null;
            return null;
        }
        return true;
    }

    private static boolean siegeable(WorldView w, net.minecraft.core.BlockPos p) {
        var s = w.state(p);
        return !s.isAir() && !s.hasBlockEntity() && w.breakableOnPurpose(p) && s.getDestroySpeed(w.level(), p) < 20;
    }

    private static int rangedSlot(BotPlayer bot) {
        if (Inv.count(bot, s -> s.is(net.minecraft.tags.ItemTags.ARROWS)) == 0) return -1;
        return Inv.find(bot, s -> s.is(Items.BOW));
    }

    private boolean shoot(BotPlayer bot, LivingEntity them, double dist) {
        this.brain.navigator().stop(bot);
        lowerShield(bot);
        Inv.hold(bot, rangedSlot(bot));
        ItemStack held = bot.getMainHandItem();
        Vec3 aim = them.getBoundingBox().getCenter().add(0, 0.004 * dist * dist + 0.1, 0);
        Placer.lookExactly(bot, aim);
        this.brain.controls().lookAt(bot, aim);
        this.brain.controls().strafe = (this.ticks / 30) % 2 == 0 ? 0.4f : -0.4f;
        boolean drawing = bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.MAIN_HAND;
        if (!drawing) {
            bot.gameMode.useItem(bot, bot.level(), held, InteractionHand.MAIN_HAND);
            this.drawTicks = 0;
        } else if (++this.drawTicks >= 22) {
            bot.releaseUsingItem();
            this.drawTicks = 0;
        }
        return true;
    }

    public void stop(BotPlayer bot) {
        this.axeOut = false;
        stopDrawing(bot);
        lowerShield(bot);
        if (this.siege != null) this.brain.breaker().cancel(bot);
        this.siege = null;
        this.closest = Double.MAX_VALUE;
        this.stalled = 0;
    }

    private static void stopDrawing(BotPlayer bot) {
        if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.MAIN_HAND) bot.stopUsingItem();
    }

    private static boolean hasShield(BotPlayer bot) {
        return bot.getOffhandItem().is(Items.SHIELD);
    }

    /** Shield up (backing off, or blocking). */
    public void raiseShield(BotPlayer bot) {
        if (!hasShield(bot)) return;
        if (!(bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.OFF_HAND)) {
            bot.gameMode.useItem(bot, bot.level(), bot.getOffhandItem(), InteractionHand.OFF_HAND);
        }
        this.brain.controls().blocking = true;
    }

    public void lowerShield(BotPlayer bot) {
        if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.OFF_HAND) bot.stopUsingItem();
    }
}
