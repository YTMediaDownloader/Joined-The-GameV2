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
        if (dist > 7 && dist < 30 && rangedSlot(bot) >= 0 && bot.hasLineOfSight(them)) return shoot(bot, them, dist);
        stopDrawing(bot);
        if (Inv.bestWeapon(bot) >= 0) Inv.hold(bot, Inv.bestWeapon(bot));
        return melee(bot, them, dist);
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
            bot.attack(them);
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
