package net.joinedthegame.ai;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.List;
import java.util.UUID;
import net.joinedthegame.JtgConfig;
import net.joinedthegame.ai.goal.CombatGoal;
import net.joinedthegame.ai.goal.CopyMiningGoal;
import net.joinedthegame.ai.goal.DepositGoal;
import net.joinedthegame.ai.goal.EatGoal;
import net.joinedthegame.ai.goal.ExploreGoal;
import net.joinedthegame.ai.goal.FollowGoal;
import net.joinedthegame.ai.goal.Goal;
import net.joinedthegame.ai.goal.HomeGoal;
import net.joinedthegame.ai.goal.HuntGoal;
import net.joinedthegame.ai.goal.InterruptedGoal;
import net.joinedthegame.ai.goal.LeashGoal;
import net.joinedthegame.ai.goal.LootGoal;
import net.joinedthegame.ai.goal.PickupGoal;
import net.joinedthegame.ai.goal.ProgressionGoal;
import net.joinedthegame.ai.goal.RecoverGoal;
import net.joinedthegame.ai.goal.SettleGoal;
import net.joinedthegame.ai.goal.ShelterGoal;
import net.joinedthegame.ai.goal.SleepGoal;
import net.joinedthegame.ai.goal.TagAlongGoal;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.BotRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import org.jspecify.annotations.Nullable;

/**
 * One bot's mind. Each tick it: picks the most urgent goal, runs it, does a few background
 * habits (put on better armor, light up dark places, toss junk), then hands the resulting
 * inputs to the body.
 *
 * <p>Lives in {@link BotManager}, keyed by the bot's UUID, so it survives the bot dying and
 * being rebuilt by a respawn.
 */
public final class BotBrain {
    private final BotRecord record;
    private final Personality personality;
    private final Controls controls = new Controls();
    /** The bot's one "left hand": whatever it's digging, every goal shares the same progress. */
    private final net.joinedthegame.ai.skill.BlockBreaker breaker = new net.joinedthegame.ai.skill.BlockBreaker();
    private final Navigator navigator = new Navigator(this.breaker);
    private final List<Goal> goals;
    private final SettleGoal settle;
    private final net.joinedthegame.ai.goal.ProjectGoal projects;
    private @Nullable Goal current;
    private int currentPriority;
    private long now;

    // --- memory ---
    private final Long2LongOpenHashMap blacklist = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap looted = new Long2LongOpenHashMap();
    private @Nullable UUID followTarget;
    private long followUntil;
    private long interruptedUntil;
    private @Nullable UUID interruptedBy;
    private @Nullable BlockPos lastTorch;
    private int greetTicks;
    /** Keep the shield up until this tick, no matter what else is going on. */
    private long shieldUntil;
    private net.minecraft.world.phys.Vec3 stuckCheckPos = net.minecraft.world.phys.Vec3.ZERO;
    private int stuckTrying;
    private int unstickTicks;
    private float unstickYaw;
    private @Nullable UUID greetTarget;
    private @Nullable LivingEntity lastAttacker;
    private long lastAttackedAt;
    // Death recovery
    /** Long-term memory, saved on the bot (see {@link BotMemory}). Refreshed every tick. */
    private BotMemory memory = new BotMemory();

    private boolean sneakForSculk;

    /** Free-form detail for the debug log. */
    public String debug = "";

    public BotBrain(BotRecord record) {
        this.record = record;
        this.personality = Personality.roll(record.name());
        this.settle = new SettleGoal(this);
        this.projects = new net.joinedthegame.ai.goal.ProjectGoal(this);
        this.goals = List.of(
                this.settle,
                this.projects,
                new net.joinedthegame.ai.goal.FarmGoal(this),
                new net.joinedthegame.ai.goal.PetGoal(this),
                new net.joinedthegame.ai.goal.FurnaceGoal(this),
                new net.joinedthegame.ai.goal.NetherGoal(this),
                new InterruptedGoal(this),
                new CombatGoal(this),
                new EatGoal(this),
                new SleepGoal(this),
                new ShelterGoal(this),
                new RecoverGoal(this),
                new FollowGoal(this),
                new LeashGoal(this),
                new DepositGoal(this),
                new HomeGoal(this),
                new HuntGoal(this),
                new CopyMiningGoal(this),
                new TagAlongGoal(this),
                new LootGoal(this),
                new PickupGoal(this),
                new ProgressionGoal(this),
                new ExploreGoal(this));
    }

    // --- accessors ----------------------------------------------------------

    public BotRecord record() { return this.record; }
    public BotMemory memory() { return this.memory; }

    /** Remember something that happened (structured; see {@link BotMemory.Event}). */
    public void remember(BotMemory.EventType type, String subject, net.minecraft.core.@Nullable GlobalPos where, float value) {
        this.memory.record(type, subject, where, value, this.now);
        log("memory: " + type + " " + subject);
    }

    /** Remember (or refresh) a place of some kind. */
    public void rememberPlace(BotPlayer bot, String kind, BlockPos pos) {
        this.memory.remember(kind, net.minecraft.core.GlobalPos.of(bot.level().dimension(), pos), this.now);
    }

    public void remember(BotMemory.EventType type, String subject) {
        remember(type, subject, null, 0.5f);
    }
    public Personality personality() { return this.personality; }
    public Controls controls() { return this.controls; }
    public Navigator navigator() { return this.navigator; }
    public net.joinedthegame.ai.skill.BlockBreaker breaker() { return this.breaker; }
    public long now() { return this.now; }

    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension = net.minecraft.world.level.Level.OVERWORLD;

    /** The dimension the bot was in at its last tick. */
    public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension() { return this.dimension; }

    public String activity() {
        Goal goal = this.current;
        return goal == null ? "Idle" : goal.activity();
    }

    // --- tick -----------------------------------------------------------------

    public void tick(BotPlayer bot) {
        this.now = bot.level().getGameTime();
        this.dimension = bot.level().dimension();
        this.memory = bot.getData(net.joinedthegame.JtgAttachments.MEMORY);
        if (this.now % 1200 == 0) this.memory.tidy(this.now);
        this.controls.reset();

        if (bot.isSleeping()) {
            runCurrent(bot);
            this.controls.apply(bot);
            return;
        }
        if (JtgConfig.PAUSE_WITHOUT_PLAYERS.get() && !BotManager.anyRealPlayers(bot.level().getServer())) {
            stopCurrent(bot);
            this.controls.apply(bot);
            return;
        }

        if (unstick(bot)) {
            this.controls.apply(bot);
            return;
        }
        if (this.now % 100 == 17) updateFocus(bot);
        if (this.current == null || this.now % 10 == 0) choose(bot);
        runCurrent(bot);
        watchdog(bot);
        this.navigator.tick(bot, this.controls);
        habits(bot);
        // Bow or crossbow still drawn with no fight going on (the target died mid-shot): lower it.
        if (!(this.current instanceof CombatGoal) && bot.isUsingItem()
                && bot.getUsedItemHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                && (bot.getMainHandItem().is(net.minecraft.world.item.Items.BOW) || bot.getMainHandItem().is(net.minecraft.world.item.Items.CROSSBOW))) {
            bot.stopUsingItem();
        }
        // Shield down unless something wanted it up this tick (blocking in a fight, or an arrow
        // actually on its way). Otherwise it stays raised forever after a fight, even while mining.
        if (!this.controls.blocking && !shieldHeld() && bot.isUsingItem()
                && bot.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND
                && bot.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) {
            bot.stopUsingItem();
        }
        this.controls.apply(bot);
        if (DEBUG && this.now % 200 == 0) debugLog(bot);
    }

    /** Dev runs: {@code -Djoinedthegame.debug=true} logs what every bot is doing every 10 seconds. */
    public static final boolean DEBUG = Boolean.getBoolean("joinedthegame.debug");

    /** Debug-only log line tagged with the bot's name. */
    public void log(String message) {
        if (DEBUG) net.joinedthegame.JoinedTheGame.LOGGER.info("[bot] {}: {}", this.record.name(), message);
    }

    private void debugLog(BotPlayer bot) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            items.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()).append('x').append(stack.getCount()).append(' ');
        }
        StringBuilder armor = new StringBuilder();
        for (var slot : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD,
                net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
            ItemStack worn = bot.getItemBySlot(slot);
            if (!worn.isEmpty()) armor.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(worn.getItem()).getPath()).append(' ');
        }
        net.joinedthegame.JoinedTheGame.LOGGER.info("[bot] {} @ {} {} {} | {} | hp {} food {} | nav {} | armor [{}] | {}",
                this.record.name(), bot.level().dimension().identifier().getPath(), bot.blockPosition().toShortString(),
                bot.onGround() ? "ground" : "air", activity() + (bot.isUsingItem() && bot.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND ? " [shield up]" : ""),
                Math.round(bot.getHealth()), bot.getFoodData().getFoodLevel(),
                this.navigator.debugString() + (this.debug.isEmpty() ? "" : " " + this.debug), armor.toString().trim(), items.toString().trim());
        this.debug = "";
    }

    private void choose(BotPlayer bot) {
        Goal best = null;
        int bestPriority = 0;
        int currentNow = 0;
        boolean overworld = bot.level().dimension() == net.minecraft.world.level.Level.OVERWORLD;
        for (Goal goal : this.goals) {
            if (goal.onCooldown(this.now)) continue;
            // Away from the overworld: no beds (they explode), no digging for iron down into
            // the lava sea, no wandering about looking for cows.
            if (!overworld && overworldOnly(goal)) continue;
            int p = goal.priority(bot);
            if (goal == this.current) currentNow = p;
            if (p > bestPriority) {
                best = goal;
                bestPriority = p;
            }
        }
        if (best == this.current) {
            this.currentPriority = bestPriority;
            return;
        }
        // Halfway through breaking a block? Finish it first, like anyone would. Only real
        // emergencies (a monster, starving, being punched) interrupt.
        if (this.current != null && this.breaker.busy() && bestPriority < 80) return;
        // Mining for gear: dead set on it. Only a fight, food, smelting, a full bag, the player
        // or something urgent pulls it away; wool hunts, house plans and projects wait their turn.
        if (this.current instanceof ProgressionGoal progression && progression.isMining() && best != null
                && !mayInterruptMining(best, bestPriority)) {
            this.currentPriority = currentNow;
            return;
        }
        // Only interrupt for something clearly more urgent than what the current goal is worth
        // right now (not what it was worth when it started: a bot dug in for the night drops
        // its claim once it's safe, so mining can take over).
        this.currentPriority = currentNow;
        if (this.current != null && best != null && bestPriority < currentNow + 5 && currentNow > 0) return;
        stopCurrent(bot);
        this.current = best;
        this.currentPriority = bestPriority;
        if (best != null) best.start(bot);
    }

    private static boolean overworldOnly(Goal goal) {
        return goal instanceof SleepGoal || goal instanceof HomeGoal || goal instanceof ShelterGoal || goal instanceof SettleGoal
                || goal instanceof ProgressionGoal || goal instanceof HuntGoal || goal instanceof ExploreGoal
                || goal instanceof CopyMiningGoal || goal instanceof net.joinedthegame.ai.goal.ProjectGoal
                || goal instanceof net.joinedthegame.ai.goal.FarmGoal || goal instanceof DepositGoal;
    }

    private static boolean mayInterruptMining(Goal goal, int priority) {
        return priority >= 60 || goal instanceof CombatGoal || goal instanceof EatGoal || goal instanceof InterruptedGoal
                || goal instanceof FollowGoal || goal instanceof LeashGoal || goal instanceof PickupGoal
                || goal instanceof DepositGoal || goal instanceof net.joinedthegame.ai.goal.FurnaceGoal
                // Broke into a structure while mining: anyone stops to raid the chests.
                || goal instanceof LootGoal loot && loot.raidingStructure();
    }

    private void runCurrent(BotPlayer bot) {
        Goal goal = this.current;
        if (goal == null) return;
        if (!goal.tick(bot)) {
            goal.stop(bot);
            goal.cooldown(this.now, 20);
            this.current = null;
            this.currentPriority = 0;
        }
    }

    private void stopCurrent(BotPlayer bot) {
        if (this.current != null) {
            this.current.stop(bot);
            this.current = null;
            this.currentPriority = 0;
        }
        this.navigator.stop(bot);
    }

    /**
     * Last-resort escape. If the bot has been trying to walk somewhere but hasn't actually
     * gone anywhere for half a minute (a water current, a weird corner the pathfinder doesn't
     * understand), do what a player does: mash jump and wiggle off in a random direction for
     * a couple of seconds, then rethink.
     */
    private boolean unstick(BotPlayer bot) {
        if (this.unstickTicks > 0) {
            this.unstickTicks--;
            this.controls.face(this.unstickYaw, 0);
            this.controls.forward = 1.0f;
            this.controls.strafe = (this.unstickTicks / 10) % 2 == 0 ? 0.5f : -0.5f;
            this.controls.jump = true;
            return true;
        }
        // Count time spent trying to walk without getting anywhere. Brief pauses between
        // re-plans don't reset it; actually moving does. Standing still on purpose (mining,
        // smelting) doesn't count as trying.
        double movedX = bot.getX() - this.stuckCheckPos.x;
        double movedZ = bot.getZ() - this.stuckCheckPos.z;
        if (movedX * movedX + movedZ * movedZ > 1.5 * 1.5) {
            this.stuckCheckPos = bot.position();
            this.stuckTrying = 0;
            return false;
        }
        if (this.navigator.isMoving()) this.stuckTrying++;
        if (this.stuckTrying > 20 * 20) {
            log("stuck at " + bot.blockPosition().toShortString() + ", wiggling free");
            net.joinedthegame.ai.Pets.giveUsRoom(bot); // (often it's our own dog in the way)
            if (this.current != null) this.current.cooldown(this.now, 20 * 20);
            stopCurrent(bot);
            this.unstickTicks = 50;
            this.unstickYaw = bot.getRandom().nextFloat() * 360f;
            this.stuckTrying = 0;
            return true;
        }
        return false;
    }

    // --- shield -------------------------------------------------------------------

    /** Keep the shield up for at least this long (covers an arrow's flight after the shot). */
    public void holdShield(int ticks) {
        this.shieldUntil = Math.max(this.shieldUntil, this.now + ticks);
    }

    /** Is the shield being held up on purpose right now (so nothing should lower it)? */
    public boolean shieldHeld() {
        return this.now < this.shieldUntil;
    }

    /**
     * Arrow reflex: anything flying at us gets the shield, even mid-mining. Keeps it up and
     * facing the arrow until it lands.
     */
    /**
     * Totem of undying: in the offhand (instead of the shield) when death's close, back to the
     * shield once things calm down. A totem only saves you if it's in a hand when you'd die.
     */
    private boolean totemUp(BotPlayer bot) {
        var totem = net.minecraft.world.item.Items.TOTEM_OF_UNDYING;
        boolean inHand = bot.getOffhandItem().is(totem);
        boolean fight = this.current instanceof CombatGoal || recentAttacker() != null;
        boolean danger = bot.getHealth() <= 8 && fight || bot.isInLava() || bot.isOnFire() && bot.getHealth() <= 10;
        boolean calm = bot.getHealth() >= 14 && !fight && !bot.isInLava() && !bot.isOnFire();
        if (danger && !inHand) {
            int slot = Inv.find(bot, s -> s.is(totem));
            if (slot >= 0) {
                ItemStack off = bot.getOffhandItem().copy();
                bot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, bot.getInventory().getItem(slot).copy());
                bot.getInventory().setItem(slot, off);
                log("totem out, just in case");
                return true;
            }
        }
        return inHand && !calm;
    }

    private void shieldReflex(BotPlayer bot) {
        if (this.now % 5 == 0) totemUp(bot);
        // A ghast fireball nearly on us: hit it back (better than any shield).
        for (var fireball : bot.level().getEntitiesOfClass(net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball.class,
                bot.getBoundingBox().inflate(4), f -> f.getOwner() != bot)) {
            net.minecraft.world.phys.Vec3 toBot = bot.getEyePosition().subtract(fireball.position());
            if (fireball.getDeltaMovement().dot(toBot) <= 0) continue; // heading away
            if (bot.distanceTo(fireball) > 3.5) continue;
            net.joinedthegame.ai.skill.Placer.lookExactly(bot, fireball.position());
            if (Inv.bestWeapon(bot) >= 0) Inv.hold(bot, Inv.bestWeapon(bot));
            bot.attack(fireball);
            bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            log("hit a ghast fireball back");
            return;
        }
        if (!bot.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) return;
        // Arrows, and fireballs too (blazes, ghasts): anything shot at us.
        var arrows = bot.level().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class,
                bot.getBoundingBox().inflate(16), a -> a.getOwner() != bot && a.getDeltaMovement().lengthSqr() > 0.05
                        && (a instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow && !arrow.isNoPhysics()
                        || a instanceof net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile));
        net.minecraft.world.entity.Entity incoming = null;
        for (var arrow : arrows) {
            net.minecraft.world.phys.Vec3 v = arrow.getDeltaMovement();
            net.minecraft.world.phys.Vec3 toBot = bot.getBoundingBox().getCenter().subtract(arrow.position());
            if (v.dot(toBot) <= 0) continue; // flying away
            // Closest it'll pass to us.
            double t = toBot.dot(v) / v.lengthSqr();
            double miss = toBot.subtract(v.scale(t)).length();
            if (miss < 1.5) {
                incoming = arrow;
                break;
            }
        }
        // Up exactly while something is actually flying at us (refreshed every tick), then
        // it drops a moment after the arrow lands or passes.
        if (incoming != null) {
            holdShield(3);
            this.controls.lookAt(bot, incoming.position());
        }
        if (shieldHeld()) {
            if (!(bot.isUsingItem() && bot.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND)) {
                if (bot.isUsingItem()) bot.stopUsingItem();
                bot.gameMode.useItem(bot, bot.level(), bot.getOffhandItem(), net.minecraft.world.InteractionHand.OFF_HAND);
            }
        }
    }

    /** First time near someone (a real player or another bot): note it in the diary. */
    private void noticePlayers(BotPlayer bot) {
        for (var other : bot.level().players()) {
            if (other == bot || other.distanceToSqr(bot) > 8 * 8 || other.isSpectator()) continue;
            String name = other.getGameProfile().name();
            if (!this.memory.ever(BotMemory.EventType.MET, name)) remember(BotMemory.EventType.MET, name);
        }
    }

    /** Small things players do without thinking about them. */
    /** The structure we're in right now (refreshed every few seconds), or null. */
    private Structures.@Nullable Known structureHere;

    public Structures.@Nullable Known structureHere() {
        return this.structureHere;
    }

    /** In the nether: a gold helmet on (piglins leave you be), the rest as good as we've got. */
    private static void wearGoldHelmet(BotPlayer bot) {
        int slot = Inv.find(bot, s -> s.is(Items.GOLDEN_HELMET));
        if (slot >= 0 && !bot.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET)) {
            ItemStack old = bot.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
            bot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, bot.getInventory().getItem(slot).copy());
            bot.getInventory().setItem(slot, old);
        }
        // Everything else: best available, but never swap the gold helmet back out here.
        ItemStack head = bot.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
        Inv.equipBestArmor(bot);
        if (head.is(Items.GOLDEN_HELMET) && !bot.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.GOLDEN_HELMET)) {
            int back = Inv.find(bot, s -> s.is(Items.GOLDEN_HELMET));
            if (back >= 0) {
                ItemStack better = bot.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
                bot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, bot.getInventory().getItem(back).copy());
                bot.getInventory().setItem(back, better);
            }
        }
    }

    /**
     * Ore we can see but aren't after right now (iron while chopping, diamonds while getting
     * coal): remember where, so when we do need it we go straight back.
     */
    private void noticeOres(BotPlayer bot) {
        if (bot.level().canSeeSky(bot.blockPosition())) return; // ore's a cave thing
        for (Sources src : new Sources[]{Sources.IRON, Sources.DIAMOND, Sources.COAL}) {
            BlockPos ore = Senses.findBlock(bot, src.blocks(), 10, 6, true, p -> false);
            if (ore != null) this.memory.remember("ore:" + src.label(), net.minecraft.core.GlobalPos.of(bot.level().dimension(), ore), this.now);
        }
    }

    /** Look about for structures; remember any we haven't seen before. */
    private void noticeStructures(BotPlayer bot) {
        this.structureHere = null;
        for (Structures.Known k : Structures.near(bot, 4)) {
            if (k.contains(bot.blockPosition(), 2)) {
                this.structureHere = k;
                RaidProgress.checkComplete(bot, this, k);
            }
            if (k.kind() == Structures.Kind.OTHER) continue;
            var where = net.minecraft.core.GlobalPos.of(bot.level().dimension(), k.center());
            if (this.memory.nearest(k.kind().placeKey(), where, 48) == null) {
                remember(BotMemory.EventType.FOUND, "a " + k.kind().label, where, (float) Math.max(0.5, k.kind().danger));
                log("found a " + k.kind().label + " at " + k.center().toShortString());
            }
            this.memory.remember(k.kind().placeKey(), where, this.now);
        }
    }

    private void habits(BotPlayer bot) {
        shieldReflex(bot);
        fireReflex(bot);
        if (this.now % 100 == 23) noticeStructures(bot);
        if (this.now % 100 == 61) {
            if (Inv.usedSlots(bot) >= Inv.MAIN_SLOTS - 4) Bundles.pack(bot);
            else if (Inv.usedSlots(bot) <= Inv.MAIN_SLOTS - 10) Bundles.unpack(bot);
        }
        if (this.now % 60 == 41) noticeOres(bot);
        // Deep dark: creep about. Sculk sensors hear footsteps; crouching is silent.
        if (this.now % 20 == 3) this.sneakForSculk = Senses.findBlock(bot,
                s -> s.is(net.minecraft.world.level.block.Blocks.SCULK_SENSOR) || s.is(net.minecraft.world.level.block.Blocks.SCULK_SHRIEKER)
                        || s.is(net.minecraft.world.level.block.Blocks.CALIBRATED_SCULK_SENSOR), 8, 4, false, p -> false) != null;
        if (this.sneakForSculk) this.controls.sneak = true;
        if (this.now % 40 == 11) noticePlayers(bot);
        if (this.now % 40 == 7) {
            if (bot.level().dimension() == net.minecraft.world.level.Level.NETHER) {
                wearGoldHelmet(bot);
            } else {
                Inv.equipBestArmor(bot);
            }
            if (!totemUp(bot)) Inv.equipShield(bot);
        }
        if (this.now % 100 == 13) Crafting.craftTorchesIfUseful(bot);
        if (this.now % 100 == 31) Junk.tossIfFull(bot, this);
        if (this.now % 400 == 151) Junk.dropObsolete(bot, this);
        // Hotbar laid out properly, when we're not busy using anything.
        if (this.now % 200 == 77 && !this.breaker.busy() && !bot.isUsingItem() && !(this.current instanceof net.joinedthegame.ai.goal.CombatGoal)) {
            Inv.organize(bot);
        }
        // At home: keep the list of what's in the chests up to date.
        if (this.now % 600 == 97 && this.record.home() != null && this.record.home().dimension() == bot.level().dimension()
                && this.record.home().pos().distSqr(bot.blockPosition()) < 32 * 32) Storage.refresh(bot, this);
        if (this.now % 10 == 3) placeTorchIfDark(bot);
        // Our dogs crowding us while we dig (they plug a tunnel): send them back a bit.
        if (this.now % 20 == 9 && this.breaker.busy() && !bot.level().canSeeSky(bot.blockPosition())) net.joinedthegame.ai.Pets.giveUsRoom(bot);

        // Greeting: crouch up and down at whoever crouched at us.
        if (this.greetTicks > 0) {
            this.greetTicks--;
            this.controls.sneak = (this.greetTicks / 4) % 2 == 0;
            Player target = this.greetTarget == null ? null : bot.level().getPlayerByUUID(this.greetTarget);
            if (target != null && !this.navigator.isMoving()) this.controls.lookAt(bot, target);
        }

        // Don't drown.
        if (bot.isUnderWater() && bot.getAirSupply() < bot.getMaxAirSupply() - 20) this.controls.jump = true;

        // Stuck inside a block (sand fell on us, glitched into a wall)? Dig out of it now.
        if (bot.isInWall()) {
            BlockPos head = BlockPos.containing(bot.getEyePosition());
            if (!new net.joinedthegame.ai.nav.WorldView(bot.level()).passable(head)) {
                this.breaker.start(head);
                this.breaker.tick(bot, this.controls);
            }
        }
    }

    /** Where we put water down to put ourselves out (to scoop back up after), and when. */
    private @Nullable BlockPos fireWater;
    private long fireWaterAt;

    /**
     * On fire: water bucket at our feet puts it straight out, like any player would; then
     * scoop the water back up. Not in lava (no time, and it'd make obsidian round us) and
     * not in the nether (water just boils away).
     */
    private void fireReflex(BotPlayer bot) {
        if (this.fireWater != null) {
            BlockPos w = this.fireWater;
            boolean water = bot.level().getFluidState(w).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER);
            if (!water || this.now - this.fireWaterAt > 20 * 10) {
                this.fireWater = null;
            } else if (!bot.isOnFire() && this.now - this.fireWaterAt >= 10 && Inv.count(bot, net.minecraft.world.item.Items.BUCKET) > 0) {
                if (net.joinedthegame.ai.skill.Placer.useAimedAt(bot, Vec3.atCenterOf(w).add(0, -0.3, 0), s -> s.is(net.minecraft.world.item.Items.BUCKET))) {
                    log("fire's out, water back in the bucket");
                    this.fireWater = null;
                }
            }
            return;
        }
        if (!bot.isOnFire() || bot.isInWater() || bot.isInLava() || bot.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)) return;
        if (bot.level().dimension() == net.minecraft.world.level.Level.NETHER) return;
        if (Inv.count(bot, net.minecraft.world.item.Items.WATER_BUCKET) == 0 || !bot.onGround()) return;
        BlockPos feet = bot.blockPosition();
        if (!bot.level().getBlockState(feet).canBeReplaced()) return;
        // Aim at the top of the block we're standing on: the water lands where we are.
        Vec3 aim = new Vec3(bot.getX(), feet.getY() + 0.02, bot.getZ());
        if (net.joinedthegame.ai.skill.Placer.useAimedAt(bot, aim, s -> s.is(net.minecraft.world.item.Items.WATER_BUCKET))) {
            log("on fire: water bucket");
            // Where it actually went: aiming down through a fire block puts it a block up
            // (it runs down over us all the same).
            this.fireWater = null;
            for (BlockPos p : new BlockPos[]{feet, feet.above(), feet.below()}) {
                if (bot.level().getFluidState(p).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) {
                    this.fireWater = p;
                    break;
                }
            }
            this.fireWaterAt = this.now;
        }
    }

    private void placeTorchIfDark(BotPlayer bot) {
        if (this.current instanceof CombatGoal || bot.isSleeping()) return;
        BlockPos feet = Navigator.feet(bot);
        int blockLight = bot.level().getBrightness(LightLayer.BLOCK, feet);
        int skyLight = bot.level().getBrightness(LightLayer.SKY, feet);
        if (blockLight > 3 || skyLight > 7) return;
        if (this.lastTorch != null && this.lastTorch.distSqr(feet) < 64) return;
        if (Inv.count(bot, Items.TORCH) == 0) return;
        if (!bot.onGround()) return;
        ItemStack held = bot.getMainHandItem();
        int heldSlot = bot.getInventory().getSelectedSlot();
        if (Placer.place(bot, feet, s -> s.is(Items.TORCH))) this.lastTorch = feet;
        // Switch back to what we were holding.
        int slot = Inv.find(bot, s -> ItemStack.isSameItemSameComponents(s, held));
        if (slot >= 0 && !held.isEmpty()) Inv.hold(bot, slot);
        else bot.getInventory().setSelectedSlot(heldSlot);
    }

    // --- life plan ----------------------------------------------------------------

    /**
     * What a bot works toward once it has full iron. Picked by a weighted roll from its
     * personality, not a fixed rule: miners lean toward diamond trips, steady types toward a
     * proper house, the rest toward personal projects (a farm, a dog, doing the place up),
     * explorers toward wandering off. Any of them can roll anything, and diamond trips keep
     * coming up until the bot is in full diamond.
     */
    public enum Focus { NONE, DIAMONDS, HOUSE, WANDER, PROJECT, NETHER }


    public Focus focus() {
        return focusValue();
    }

    private Focus focusValue() {
        try {
            return Focus.valueOf(this.memory.focus);
        } catch (IllegalArgumentException e) {
            return Focus.NONE;
        }
    }

    private void setFocusValue(Focus focus) {
        this.memory.focus = focus.name();
    }

    /** Force a focus (the /jtg focus command). */
    public void setFocus(Focus focus, long now) {
        setFocusValue(focus);
        if (focus == Focus.WANDER || focus == Focus.DIAMONDS) this.memory.focusUntil = now + 20 * 60 * 15;
        this.memory.nextRollAt = now + 20 * 60;
    }

    /** Things just taken out of a chest for a job: kept on us a few minutes, not put straight back. */
    private final java.util.Map<net.minecraft.world.item.Item, Long> fetched = new java.util.HashMap<>();

    public void markFetched(net.minecraft.world.item.Item item) {
        this.fetched.put(item, this.now);
    }

    public boolean fetchedRecently(net.minecraft.world.item.Item item) {
        Long at = this.fetched.get(item);
        return at != null && this.now - at < 20 * 60 * 4;
    }

    /** The /jtg build command: build this kind of house next (cabin, or a blueprint's name). */
    public void buildHouse(BotPlayer bot, String which) {
        this.settle.force(bot, which);
        setFocus(Focus.HOUSE, this.now);
    }

    /**
     * A nomad still on the road: no base yet, and no reason to make one. It sets one up once it's
     * geared up (full diamond), or when it badly needs somewhere to put things (the bag's full
     * and more than a couple of stacks of it is stuff that wants storing).
     */
    public boolean roaming(BotPlayer bot) {
        if (this.personality.nomad() <= 0.6 || this.record.house() != null) return false;
        return !Inv.fullDiamond(bot) && !needsStorageBadly(bot);
    }

    public boolean needsStorageBadly(BotPlayer bot) {
        if (Inv.usedSlots(bot) < Inv.MAIN_SLOTS - 2) return false;
        int storable = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            var s = bot.getInventory().getItem(i);
            if (s.isEmpty() || !Junk.isKeeper(s) && !Junk.isValuable(s)) continue;
            if (Junk.keepQuota(s) == 0) storable += s.getCount();
        }
        return storable >= 128;
    }

    /** On a Trial Chamber run and in the chamber now (underground, committed: bedtime can wait). */
    public boolean inTrialChamber(BotPlayer bot) {
        return this.projects.current() == net.joinedthegame.ai.goal.ProjectGoal.Kind.TRIAL_CHAMBER
                && this.focusValue() == Focus.PROJECT && net.joinedthegame.ai.project.TrialChamberProject.inChamber(bot);
    }

    // --- freeze watchdog ------------------------------------------------------------------

    private @Nullable Vec3 wdPos;
    private long wdAt;
    private int wdInventory;
    private @Nullable Goal wdGoal;

    private static int inventorySignature(BotPlayer bot) {
        int sig = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            var s = bot.getInventory().getItem(i);
            sig = sig * 31 + (s.isEmpty() ? 0 : s.getItem().hashCode() * 7 + s.getCount());
        }
        return sig;
    }

    /**
     * Any goal has to be getting somewhere: moving, its bag changing, breaking something, using
     * something, fighting, or waiting on the game on purpose. "I've a plan" isn't enough. Thirty
     * seconds of none of that and the goal's dropped (for a minute) and something else picked,
     * with the reason logged, instead of standing frozen till some outer timeout runs out.
     */
    private void watchdog(BotPlayer bot) {
        if (this.now % 20 != 5) return;
        Goal goal = this.current;
        boolean progress = goal == null || goal != this.wdGoal || this.wdPos == null
                || bot.position().distanceToSqr(this.wdPos) > 1.5 * 1.5
                || inventorySignature(bot) != this.wdInventory
                || this.breaker.busy() || bot.isUsingItem() || bot.isSleeping()
                || this.now - this.lastAttackedAt < 40 || bot.getLastHurtMob() != null && this.now - bot.getLastHurtMobTimestamp() < 40
                || goal.waitingOnPurpose(bot);
        if (progress) {
            this.wdGoal = goal;
            this.wdPos = bot.position();
            this.wdInventory = inventorySignature(bot);
            this.wdAt = this.now;
            return;
        }
        if (this.now - this.wdAt < 20 * 30) return;
        log("watchdog: '" + goal.activity() + "' got nowhere for 30s (stood at " + bot.blockPosition().toShortString() + "); dropping it for now");
        goal.cooldown(this.now, 20 * 60);
        stopCurrent(bot);
        this.wdGoal = null;
        this.wdAt = this.now;
    }

    /** Out mining for gear right now. */
    public boolean mining() {
        return this.current instanceof ProgressionGoal p && p.isMining();
    }

    public boolean settling() {
        return this.settle.inProgress();
    }

    /** Carrying the materials for a house: don't store or toss them. */
    public boolean buildingHouse() {
        return this.settle.building();
    }

    /** The current focus is done (or failed, with NONE): pick again soon or later. */
    public void focusDone(Focus which) {
        if (which == Focus.NONE) {
            setFocusValue(Focus.NONE);
            this.memory.nextRollAt = this.now + 20 * 60 * 3;
            return;
        }
        if (focusValue() == which) setFocusValue(Focus.NONE);
        this.memory.nextRollAt = this.now + 20 * 10;
    }

    private void updateFocus(BotPlayer bot) {
        // Gear first. Nothing else until iron tools, iron armour and a shield.
        if (!Inv.fullIron(bot)) {
            setFocusValue(Focus.NONE);
            return;
        }
        Focus focus = focusValue();
        // Done with houses: a proper house built, or a cabin that's still big enough.
        boolean houseDone = "LARGE".equals(this.memory.homeType)
                || ("HOUSE".equals(this.memory.homeType) || "CABIN".equals(this.memory.homeType))
                && !net.joinedthegame.ai.goal.SettleGoal.outgrown(bot, this);
        boolean diamondsDone = Inv.fullDiamond(bot);
        boolean expired = (focus == Focus.WANDER || focus == Focus.DIAMONDS) && this.now > this.memory.focusUntil;
        if ((focus == Focus.HOUSE && houseDone && !this.settle.inProgress()) || (focus == Focus.DIAMONDS && diamondsDone) || expired) {
            setFocusValue(Focus.NONE);
            this.memory.nextRollAt = this.now + 20 * 10;
        }
        if (focusValue() != Focus.NONE || this.now < this.memory.nextRollAt) return;

        Personality p = this.personality;
        boolean homeless = this.record.house() == null;
        // Once there's a diamond pickaxe, diamonds turn up while mining for anything else: a
        // dedicated diamond trip matters less than the rest of life.
        double diamonds = diamondsDone ? 0 : Inv.pickaxeTier(bot) >= 5 ? 0.25 + p.miner() * 0.4
                : 0.5 + p.miner() * 0.6 + p.diligent() * 0.3 - p.homebody() * 0.2 - p.builder() * 0.2;
        // A first home matters a lot; upgrading to a house you built yourself a bit less.
        double house = houseDone ? 0 : homeless
                ? 0.4 + p.social() * 0.6 + p.diligent() * 0.4 + (1 - p.reckless()) * 0.2
                : 0.2 + p.diligent() * 0.5 + p.social() * 0.3;
        house = house + (houseDone ? 0 : p.builder() * 0.8);
        // A nomad on the road doesn't want a house; one that's geared up, or desperate for
        // storage, wants one now.
        if (roaming(bot)) house = 0;
        else if (p.nomad() > 0.6 && this.record.house() == null) house += needsStorageBadly(bot) ? 3 : 1;
        // Personal projects: the more homely ones there are to do (a dog, a farm, enchanting...),
        // the more they pull.
        double project = this.projects.anyAvailable(bot)
                ? 0.35 + p.social() * 0.3 + (1 - p.miner()) * 0.3 + 0.25 * Math.min(3, this.projects.homelyAvailable(bot))
                + p.homebody() * 0.6 + p.animalLover() * 0.3 + p.scholar() * 0.3 : 0;
        double wander = 0.05 + p.explorer() * p.explorer() * 0.8 + p.nomad() * 1.2;
        // The nether: needs a diamond pickaxe for obsidian (or a portal we already know), and
        // a reason to go (blaze rods). Brave and curious types go first.
        boolean canGetThere = Inv.pickaxeTier(bot) >= 5 || this.memory.places().containsKey("portal");
        double nether = canGetThere && Inv.count(bot, Items.BLAZE_ROD) < net.joinedthegame.ai.project.NetherTrip.RODS_WANTED
                ? 0.25 + p.brave() * 0.4 + p.explorer() * 0.3 : 0;
        if (diamonds + house + project + nether == 0) {
            // Nothing big left: now and then, go see the world.
            this.memory.nextRollAt = this.now + 20 * 60 * 15;
            if (bot.getRandom().nextFloat() > 0.3f) return;
        }
        double roll = bot.getRandom().nextDouble() * (diamonds + house + project + nether + wander);
        if ((roll -= diamonds) < 0) {
            setFocusValue(Focus.DIAMONDS);
            // A diamond trip, not a life sentence: come back up and do other things after.
            this.memory.focusUntil = this.now + 20 * 60 * (15 + bot.getRandom().nextInt(11));
        } else if ((roll -= house) < 0) {
            setFocusValue(Focus.HOUSE);
        } else if ((roll -= project) < 0) {
            setFocusValue(Focus.PROJECT);
        } else if ((roll -= nether) < 0) {
            setFocusValue(Focus.NETHER);
        } else {
            setFocusValue(Focus.WANDER);
            this.memory.focusUntil = this.now + 20 * 60 * (10 + bot.getRandom().nextInt(11));
        }
        remember(BotMemory.EventType.FOCUS, focusValue().name().toLowerCase(java.util.Locale.ROOT));
        log("next big goal: " + focusValue() + String.format(" (odds: diamonds %.2f, house %.2f, project %.2f, nether %.2f, wander %.2f)",
                diamonds, house, project, nether, wander));
    }

    public net.joinedthegame.ai.goal.ProjectGoal projects() {
        return this.projects;
    }

    // --- events -----------------------------------------------------------------

    /** A player gave us a friendly punch: drop everything and look at them. */
    public void onPunched(BotPlayer bot, ServerPlayer puncher) {
        remember(BotMemory.EventType.HIT_BY_PLAYER, puncher.getGameProfile().name(), null, 0.1f);
        if (this.current != null && !(this.current instanceof InterruptedGoal)) this.current.cooldown(bot.level().getGameTime(), 20 * 30);
        stopCurrent(bot);
        this.followTarget = null;
        this.interruptedBy = puncher.getUUID();
        this.interruptedUntil = bot.level().getGameTime() + 60 + bot.getRandom().nextInt(60);
    }

    /** A player crouched near us. If they were looking at us, follow them. */
    public void onPlayerCrouched(BotPlayer bot, ServerPlayer player, boolean lookingAtMe) {
        this.greetTicks = 24;
        this.greetTarget = player.getUUID();
        if (lookingAtMe) {
            this.followTarget = player.getUUID();
            this.followUntil = bot.level().getGameTime() + JtgConfig.FOLLOW_SECONDS.get() * 20L;
        }
    }

    public void onHurt(BotPlayer bot, @Nullable Entity attacker, float amount) {
        if (attacker instanceof LivingEntity living && !(attacker instanceof Player)) {
            remember(BotMemory.EventType.ATTACKED_BY,
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString(),
                    net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition()), Math.min(1f, amount / 20f));
            this.lastAttacker = living;
            this.lastAttackedAt = bot.level().getGameTime();
        }
    }

    public void onDeath(BotPlayer bot, net.minecraft.world.damagesource.DamageSource source) {
        this.memory = bot.getData(net.joinedthegame.JtgAttachments.MEMORY);
        String cause = source.getEntity() != null
                ? net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).toString()
                : source.typeHolder().unwrapKey().map(k -> k.identifier().toString()).orElse("unknown");
        var where = net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        remember(BotMemory.EventType.DIED, cause, where, 1f);
        // Died in a trial chamber: note it against the chamber. Every death there raises the bar
        // for going back in (better kit), and it stays away a good while first.
        var chamber = net.joinedthegame.ai.Chambers.here(bot);
        if (chamber != null) {
            var notes = this.memory.chamber(net.minecraft.core.GlobalPos.of(bot.level().dimension(), chamber.center()));
            notes.deaths++;
            notes.leftBecause = "died (" + cause.replace("minecraft:", "") + ")";
            notes.restUntil = this.now + 20 * 60 * (20L + 20L * Math.min(3, notes.deaths));
            log("trial chamber: died in there (" + notes.deaths + " so far); not going back in a hurry");
        }
        // Died around here before? That's a place to be careful of, and it stays remembered.
        if (this.memory.deathsNear(where, 24) >= 2 && this.memory.nearest("death_hotspot", where, 24) == null) {
            this.memory.remember("death_hotspot", where, this.now);
            log("memory: this spot is dangerous");
        }
        stopCurrent(bot);
        this.followTarget = null;
        this.lastAttacker = null;
        // Remember where our stuff is. Dying again before getting it back counts up; after a
        // few in a row, give up and start fresh like a new spawn.
        this.memory.deathsInARow = this.memory.deathSpot != null ? this.memory.deathsInARow + 1 : 1;
        if (this.memory.deathsInARow >= RecoverGoal.MAX_DEATHS) {
            log("died " + this.memory.deathsInARow + " times in a row, starting over");
            forgetDeath(false);
            return;
        }
        this.memory.deathSpot = net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        this.memory.deathTime = bot.level().getGameTime();
    }

    public net.minecraft.core.@Nullable GlobalPos deathSpot() {
        return this.memory.deathSpot;
    }

    public long deathTime() {
        return this.memory.deathTime;
    }

    /** Done with the death trip, one way or another. */
    public void forgetDeath(boolean recovered) {
        this.memory.deathSpot = null;
        this.memory.deathsInARow = 0;
    }

    // --- memory helpers -----------------------------------------------------------

    public boolean isBlacklisted(BlockPos pos) {
        long until = this.blacklist.get(pos.asLong());
        return until > this.now;
    }

    public void blacklist(BlockPos pos) {
        this.blacklist.put(pos.asLong(), this.now + 20 * 120);
        if (this.blacklist.size() > 512) this.blacklist.long2LongEntrySet().removeIf(e -> e.getLongValue() < this.now);
    }

    public boolean recentlyLooted(BlockPos pos) {
        return this.looted.get(pos.asLong()) > this.now
                || this.memory.isLooted(net.minecraft.core.GlobalPos.of(this.dimension, pos));
    }

    /** Done with this container (emptied it, or it was empty): never look in it again. */
    public void markLooted(BlockPos pos) {
        this.looted.put(pos.asLong(), this.now + 20 * 60 * 10);
        this.memory.markLooted(net.minecraft.core.GlobalPos.of(this.dimension, pos));
    }

    public @Nullable UUID followTarget() {
        return this.now < this.followUntil ? this.followTarget : null;
    }

    public void stopFollowing() {
        this.followTarget = null;
    }

    public boolean isInterrupted() {
        return this.now < this.interruptedUntil;
    }

    public @Nullable UUID interruptedBy() {
        return this.interruptedBy;
    }

    public @Nullable LivingEntity recentAttacker() {
        return this.now - this.lastAttackedAt < 200 ? this.lastAttacker : null;
    }
}
