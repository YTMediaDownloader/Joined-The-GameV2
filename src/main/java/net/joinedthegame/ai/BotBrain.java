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
    private final net.joinedthegame.ai.goal.NetherGoal nether;
    private @Nullable Goal current;
    private int currentPriority;
    // --- commitment (see choose) ---
    /** When the current goal took over, and the bag as it was then (to tell a quick win from a quick quit). */
    private long currentSince;
    private int currentStartInventory;
    /** Goals that keep taking over and quitting at once, and how many times running. */
    private final java.util.Map<Goal, Integer> quickQuits = new java.util.IdentityHashMap<>();
    /** The last few switches, to catch flip-flopping between two goals. */
    private final java.util.ArrayDeque<Switch> switches = new java.util.ArrayDeque<>();
    private record Switch(Goal from, Goal to, long at) {}
    /** When goals took over lately (any goal, after a quit too): a scattered bot settles down. */
    private final java.util.ArrayDeque<Long> starts = new java.util.ArrayDeque<>();
    /** Caught flip-flopping: stick with {@code stickWith} over {@code stickAgainst} until then. */
    private @Nullable Goal stickWith;
    private @Nullable Goal stickAgainst;
    private long stickUntil;

    /** At or above this, it's an emergency: commitment doesn't hold it back. */
    public static final int URGENT = 60;
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

    /** When we last drank (or threw) a potion: see {@link Potions}. */
    public long lastPotionAt = Long.MIN_VALUE / 2;

    /** When a monster last hurt us (game time). */
    public long lastAttackedAt() {
        return this.lastAttackedAt;
    }
    // Death recovery
    /** Long-term memory, saved on the bot (see {@link BotMemory}). Refreshed every tick. */
    private BotMemory memory = new BotMemory();

    private boolean sneakForSculk;

    /** Free-form detail for the debug log. */
    public String debug = "";

    /** What it needs right now (gear, food, a home, something new to see...). */
    private final net.joinedthegame.ai.life.Needs needs = new net.joinedthegame.ai.life.Needs();

    public net.joinedthegame.ai.life.Needs needs() {
        return this.needs;
    }

    public BotBrain(BotRecord record) {
        this.record = record;
        this.personality = Personality.roll(record.name());
        this.settle = new SettleGoal(this);
        this.projects = new net.joinedthegame.ai.goal.ProjectGoal(this);
        this.nether = new net.joinedthegame.ai.goal.NetherGoal(this);
        this.goals = List.of(
                this.settle,
                this.projects,
                new net.joinedthegame.ai.goal.FarmGoal(this),
                new net.joinedthegame.ai.goal.PetGoal(this),
                new net.joinedthegame.ai.goal.FurnaceGoal(this),
                this.nether,
                new net.joinedthegame.ai.goal.BreatheGoal(this),
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
                new net.joinedthegame.ai.goal.SpawnerGoal(this),
                new PickupGoal(this),
                new net.joinedthegame.ai.goal.LeisureGoal(this),
                new net.joinedthegame.ai.goal.SocialGoal(this),
                new net.joinedthegame.ai.goal.GreetGoal(this),
                new net.joinedthegame.ai.goal.MoveInGoal(this),
                new net.joinedthegame.ai.goal.FeudGoal(this),
                new net.joinedthegame.ai.goal.SpiteGoal(this),
                new net.joinedthegame.ai.goal.ShareFoodGoal(this),
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
    /** The bot this brain belongs to (its last tick's). */
    private @Nullable BotPlayer lastBot;
    private BotPlayer bot() { return java.util.Objects.requireNonNull(this.lastBot, "brain hasn't ticked yet"); }
    public Controls controls() { return this.controls; }
    public Navigator navigator() { return this.navigator; }
    public net.joinedthegame.ai.skill.BlockBreaker breaker() { return this.breaker; }
    public long now() { return this.now; }

    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension = net.minecraft.world.level.Level.OVERWORLD;

    /** The dimension the bot was in at its last tick. */
    public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension() { return this.dimension; }

    public String activity() {
        Goal goal = this.current;
        String doing = goal == null ? "Idle" : goal.activity();
        // Something it means to do but is holding off on, and why (shown alongside, so a player
        // can see it hasn't just forgotten: "my stuff's guarded by a skeleton").
        return this.asideNote != null && this.now < this.asideUntil ? doing + " (" + this.asideNote + ")" : doing;
    }

    private @Nullable String asideNote;
    private long asideUntil;

    /** Holding off on something for a reason: say so next to what we're doing, for a couple of seconds (renew to keep it). */
    public void aside(String note) {
        this.asideNote = note;
        this.asideUntil = this.now + 40;
    }

    // --- tick -----------------------------------------------------------------

    /**
     * Joined: use the bot's own saved memory from the start, not the blank one this brain was made
     * with. (It only picked it up on its first tick, so anything that happened before that, like
     * feelings set the moment it arrived, went into the blank one and was lost: two "teammates"
     * set as close friends came in still hating each other from the round before.)
     */
    public void bind(BotPlayer bot) {
        this.memory = bot.getData(net.joinedthegame.JtgAttachments.MEMORY);
        this.lastBot = bot;
    }

    public void tick(BotPlayer bot) {
        this.now = bot.level().getGameTime();
        this.lastBot = bot;
        this.dimension = bot.level().dimension();
        this.memory = bot.getData(net.joinedthegame.JtgAttachments.MEMORY);
        if (this.now % 20 == 0 && net.joinedthegame.DebugLog.on()) this.secondsOn.merge(this.current == null ? (bot.isSleeping() ? "Sleeping" : "Idle") : name(this.current), 1, Integer::sum);
        if (this.now % 1200 == 0) this.memory.tidy(this.now);
        if (this.now % 1200 == 7) fadeFeelings();
        noteJump(bot);
        if (net.joinedthegame.ai.goal.ShelterGoal.onDryLand(bot)) this.lastDryLand = net.joinedthegame.ai.nav.Navigator.feet(bot);
        if (this.now % 20 == 9 && bot.onGround() && bot.level().canSeeSky(bot.blockPosition())) this.lastSky = bot.blockPosition();
        this.controls.reset();

        if (bot.isSleeping()) {
            // (Still knows its stuff's out there: says so, "not going back for it in the dark".)
            if (this.now % 10 == 0) for (Goal g : this.goals) if (g instanceof RecoverGoal) g.priority(bot);
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
        this.needs.update(bot, this);
        if (this.current == null || this.now % 10 == 0) choose(bot);
        // Mining at night from under cover (dug in, not geared for the dark): every route stays
        // under cover, so it doesn't wander back up to the surface and get sent down again.
        this.navigator.underCover = this.current instanceof ProgressionGoal && bot.level().isDarkOutside()
                && !net.joinedthegame.ai.goal.ShelterGoal.nightReady(bot) && !bot.level().canSeeSky(bot.blockPosition().above());
        runCurrent(bot);
        Potions.reflex(bot, this);
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
        if (this.now % 200 == 0 && net.joinedthegame.DebugLog.on()) debugLog(bot);
    }

    /** Debug-only log line tagged with the bot's name (see {@link net.joinedthegame.DebugLog}). */
    public void log(String message) {
        if (!net.joinedthegame.DebugLog.on()) return;
        net.joinedthegame.DebugLog.write("[bot] " + this.record.name() + ": " + message);
        // Running counts for the snapshot (logs/joinedthegame-bots.json): the trouble at a glance,
        // without reading hours of log.
        if (message.startsWith("stuck at")) count("stuck");
        else if (message.startsWith("flip-flopping")) count("flipFlops");
        else if (message.startsWith("watchdog:")) count("watchdogs");
        else if (message.endsWith(" FAILED")) count("failedTasks");
        else if (message.startsWith("memory: RECOVERED_ITEMS")) count("itemsRecovered");
        else if (message.startsWith("memory: LOST_ITEMS")) count("itemsLost");
        else if (message.startsWith("memory: DIED ")) {
            count("deaths");
            count("died to " + message.substring("memory: DIED ".length()).replace("minecraft:", ""));
        }
    }

    // --- debug stats (testing jar: see Snapshot) ---------------------------------------------
    private final java.util.Map<String, Integer> stats = new java.util.TreeMap<>();
    private final java.util.Map<String, Integer> secondsOn = new java.util.HashMap<>();
    private final java.util.ArrayDeque<String> recentDeaths = new java.util.ArrayDeque<>();

    private void count(String what) {
        this.stats.merge(what, 1, Integer::sum);
    }

    public java.util.Map<String, Integer> stats() {
        return this.stats;
    }

    /** Seconds spent on each goal since the game started. */
    public java.util.Map<String, Integer> secondsOn() {
        return this.secondsOn;
    }

    public java.util.Collection<String> recentDeaths() {
        return this.recentDeaths;
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
        net.joinedthegame.DebugLog.write(String.format("[bot] %s @ %s %s %s | %s | hp %d food %d%s | goal %s(%d) focus %s | nav %s | armor [%s] | %s",
                this.record.name(), bot.level().dimension().identifier().getPath(), bot.blockPosition().toShortString(),
                bot.onGround() ? "ground" : "air", activity() + (bot.isUsingItem() && bot.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND ? " [shield up]" : ""),
                Math.round(bot.getHealth()), bot.getFoodData().getFoodLevel(),
                bot.isUnderWater() ? " air " + bot.getAirSupply() : "",
                this.current == null ? "none" : this.current.getClass().getSimpleName().replace("Goal", ""), this.currentPriority, focusValue(),
                this.navigator.debugString() + (this.debug.isEmpty() ? "" : " " + this.debug)
                        + (this.breaker.target() == null ? "" : " | breaking " + this.breaker.debugString()), armor.toString().trim(), items.toString().trim()));
        this.debug = "";
    }

    /** Each goal's score at the last decision (for /jtg debug), highest first when shown. */
    private final java.util.Map<String, Integer> lastScores = new java.util.LinkedHashMap<>();

    public java.util.Map<String, Integer> lastScores() {
        return this.lastScores;
    }

    public @Nullable Goal currentGoal() {
        return this.current;
    }

    public int currentPriority() {
        return this.currentPriority;
    }

    /** The big goal right now (for /jtg debug). */
    public Focus focusNow() {
        return focusValue();
    }

    private void choose(BotPlayer bot) {
        Goal best = null;
        int bestPriority = 0;
        int currentNow = 0;
        boolean overworld = bot.level().dimension() == net.minecraft.world.level.Level.OVERWORLD;
        // Night, not geared for it, and tucked into a hole near the surface: nothing that walks us
        // back out (loot, a hunt, a wander, company). Mining on down from the hole is fine. Bots
        // kept climbing out after a chest or a cow and diving back in, a hundred times a night.
        boolean tucked = bot.level().isDarkOutside() && !net.joinedthegame.ai.goal.ShelterGoal.nightReady(bot)
                && !bot.level().canSeeSky(bot.blockPosition()) && !net.joinedthegame.ai.goal.SleepGoal.deepUnderground(bot);
        for (Goal goal : this.goals) {
            if (goal.onCooldown(this.now)) continue;
            if (tucked && outdoors(goal)) continue;
            // Away from the overworld: no beds (they explode), no digging for iron down into
            // the lava sea, no wandering about looking for cows.
            if (!overworld && overworldOnly(goal)) continue;
            int p = goal.priority(bot);
            this.lastScores.put(goal.getClass().getSimpleName().replace("Goal", ""), p);
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
        if (this.current != null && best != null && currentNow > 0 && bestPriority < currentNow + interruptMargin(best, bestPriority)) return;
        // Caught flip-flopping between these two: stay put for now.
        if (this.current != null && best != null && bestPriority < URGENT && this.now < this.stickUntil
                && this.current == this.stickWith && best == this.stickAgainst) return;
        if (this.current != null && best != null && bestPriority < URGENT && flipFlopping(this.current, best)) {
            this.stickWith = this.current;
            this.stickAgainst = best;
            this.stickUntil = this.now + 20 * 45;
            log("flip-flopping between " + name(this.current) + " and " + name(best) + ": sticking with " + name(this.current) + " for a bit");
            return;
        }
        if (net.joinedthegame.DebugLog.on() && best != null) {
            log("switch: " + (this.current == null ? "nothing" : this.current.getClass().getSimpleName().replace("Goal", "") + "(" + currentNow + ")")
                    + " -> " + best.getClass().getSimpleName().replace("Goal", "") + "(" + bestPriority + ") \"" + best.activity() + "\"");
        }
        if (this.current != null && best != null) {
            this.switches.addLast(new Switch(this.current, best, this.now));
            while (this.switches.size() > 8) this.switches.removeFirst();
        }
        stopCurrent(bot);
        this.current = best;
        this.currentPriority = bestPriority;
        this.currentSince = this.now;
        this.currentStartInventory = inventorySignature(bot);
        if (best != null) {
            this.starts.addLast(this.now);
            while (this.starts.size() > 12) this.starts.removeFirst();
            best.start(bot);
        }
    }

    /** Goals that take a bot out and about (not ones that keep it where it is, or underground). */
    private static boolean outdoors(Goal goal) {
        return goal instanceof net.joinedthegame.ai.goal.LootGoal || goal instanceof net.joinedthegame.ai.goal.HuntGoal
                || goal instanceof net.joinedthegame.ai.goal.ExploreGoal || goal instanceof net.joinedthegame.ai.goal.SpawnerGoal
                || goal instanceof net.joinedthegame.ai.goal.SocialGoal || goal instanceof net.joinedthegame.ai.goal.LeisureGoal
                || goal instanceof net.joinedthegame.ai.goal.GreetGoal || goal instanceof net.joinedthegame.ai.goal.TagAlongGoal;
    }

    private static String name(Goal goal) {
        return goal.getClass().getSimpleName().replace("Goal", "");
    }

    /**
     * How much more urgent {@code challenger} has to be to pull us off what we're doing. Five, plus
     * commitment: the longer at the current job, the more it takes (up to ten more after a
     * minute), like anyone who's got into something. Emergencies, and the few things allowed to
     * break into a mining job, only need the five.
     */
    private int interruptMargin(Goal challenger, int priority) {
        if (priority >= URGENT || this.current == null) return 5;
        if (this.current instanceof ProgressionGoal progression && progression.isMining() && mayInterruptMining(challenger, priority)) return 5;
        // Chopping and changing all over the place lately (eight new goals in a minute, round three
        // or more of them, which the two-goal flip-flop check can't see): settle on something.
        int recent = 0;
        for (long at : this.starts) if (this.now - at < 20 * 60) recent++;
        int scattered = recent >= 8 ? 10 : 0;
        return 5 + scattered + (int) Math.min(10, (this.now - this.currentSince) / (20 * 6));
    }

    /** Switched between these two (either way) twice or more in the last minute. */
    private boolean flipFlopping(Goal a, Goal b) {
        int n = 0;
        for (Switch s : this.switches) {
            if (this.now - s.at() > 20 * 60) continue;
            if (s.from() == a && s.to() == b || s.from() == b && s.to() == a) n++;
        }
        return n >= 3;
    }

    private boolean overworldOnly(Goal goal) {
        // A project that's about the nether (netherite) carries on over there; the rest wait.
        if (goal instanceof net.joinedthegame.ai.goal.ProjectGoal p
                && p.current() == net.joinedthegame.ai.goal.ProjectGoal.Kind.NETHERITE) return false;
        return goal instanceof SleepGoal || goal instanceof HomeGoal || goal instanceof ShelterGoal || goal instanceof SettleGoal
                || goal instanceof ProgressionGoal || goal instanceof HuntGoal || goal instanceof ExploreGoal
                || goal instanceof CopyMiningGoal || goal instanceof net.joinedthegame.ai.goal.ProjectGoal
                || goal instanceof net.joinedthegame.ai.goal.FarmGoal || goal instanceof DepositGoal
                || goal instanceof net.joinedthegame.ai.goal.SpawnerGoal // a fortress's blaze spawner stays
                || goal instanceof net.joinedthegame.ai.goal.LeisureGoal || goal instanceof net.joinedthegame.ai.goal.SocialGoal;
    }

    private static boolean mayInterruptMining(Goal goal, int priority) {
        return priority >= 60 || goal instanceof net.joinedthegame.ai.goal.SocialGoal s && s.invited()
                || goal instanceof net.joinedthegame.ai.goal.GreetGoal || goal instanceof CombatGoal || goal instanceof EatGoal || goal instanceof InterruptedGoal
                || goal instanceof FollowGoal || goal instanceof LeashGoal
                // Only for something valuable: the mining job picks up its own drops as it goes.
                // (Any item at all used to break into it every second or two, each time resetting
                // the route and the swing: the bot stood staring at the block and never broke it.)
                || goal instanceof PickupGoal pickup && pickup.forSomethingValuable()
                || goal instanceof DepositGoal || goal instanceof net.joinedthegame.ai.goal.FurnaceGoal
                // Broke into a structure while mining: anyone stops to raid the chests.
                || goal instanceof LootGoal loot && loot.raidingStructure()
                // Dug into a dungeon: deal with the spawner before it buries us in zombies.
                || goal instanceof net.joinedthegame.ai.goal.SpawnerGoal;
    }

    private void runCurrent(BotPlayer bot) {
        Goal goal = this.current;
        if (goal == null) return;
        if (!goal.tick(bot)) {
            goal.stop(bot);
            // Took over and quit within a second, with nothing to show for it (the bag's the
            // same): it shouldn't have taken over. Each time in a row it waits longer to try
            // again (2s, 4s, 8s... half a minute), instead of breaking into whatever's going on
            // every second or two.
            int rest = 20;
            if (this.now - this.currentSince < 20 && inventorySignature(bot) == this.currentStartInventory) {
                int n = this.quickQuits.merge(goal, 1, Integer::sum);
                rest = (int) Math.min(20 * 30, 20L << Math.min(5, n));
                if (n == 3) log("'" + goal.activity() + "' keeps quitting as soon as it starts: resting it longer");
            } else {
                this.quickQuits.remove(goal);
            }
            goal.cooldown(this.now, rest);
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
            if (other == bot || other.isSpectator() || other.distanceToSqr(bot) > 24 * 24) continue;
            String name = other.getGameProfile().name();
            if (other.distanceToSqr(bot) <= 8 * 8 && !this.memory.ever(BotMemory.EventType.MET, name)) {
                remember(BotMemory.EventType.MET, name);
                feel(other, net.joinedthegame.ai.social.Feelings.Act.MET, 1);
            }
            // Time around someone: getting to know them (every couple of seconds).
            if (bot.hasLineOfSight(other)) feel(other, net.joinedthegame.ai.social.Feelings.Act.NEAR, 0.25);
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
        // Lava pools (surface or cave): where obsidian gets made, when the time comes.
        if (bot.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            BlockPos lava = Senses.findBlock(bot, s -> s.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.LAVA),
                    16, 8, true, p -> false);
            if (lava != null) this.memory.remember("lava", net.minecraft.core.GlobalPos.of(bot.level().dimension(), lava), this.now);
        }
        if (bot.level().canSeeSky(bot.blockPosition())) {
            // Out and about: sugar cane by the water is worth remembering (a farm, one day).
            if (this.now % 600 < 100) {
                BlockPos cane = Senses.findBlock(bot, s -> s.is(net.minecraft.world.level.block.Blocks.SUGAR_CANE), 16, 6, true, p -> false);
                if (cane != null) this.memory.remember("sugar_cane", net.minecraft.core.GlobalPos.of(bot.level().dimension(), cane), this.now);
            }
            return; // ore's a cave thing
        }
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
                remember(BotMemory.EventType.FOUND, "a " + k.describe(), where, (float) Math.max(0.5, k.kind().danger));
                log("found a " + k.describe() + " at " + k.center().toShortString());
                tempt(net.joinedthegame.ai.life.Activities.Activity.WANDER, "a " + k.describe(), k.center());
            }
            this.memory.remember(k.kind().placeKey(), where, this.now);
            if ("treasure".equals(k.variant())) this.memory.remember(Structures.TREASURE_BASTION, where, this.now);
        }
    }

    /** Open water nobody's noticed before, out under the sky: a lake (maybe worth an afternoon's fishing). */
    private void noticeLakes(BotPlayer bot) {
        if (!bot.level().canSeeSky(bot.blockPosition()) || bot.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        boolean canFish = Inv.count(bot, Items.FISHING_ROD) > 0 || Inv.count(bot, Items.STRING) >= 2;
        BlockPos water = Senses.findBlock(bot, s -> s.getFluidState().isSourceOfType(net.minecraft.world.level.material.Fluids.WATER), 20, 6, true,
                p -> !bot.level().canSeeSky(p.above()) || !bot.level().getBlockState(p.above()).isAir());
        if (water == null) return;
        int open = 0;
        for (BlockPos q : BlockPos.betweenClosed(water.offset(-2, 0, -2), water.offset(2, 0, 2))) {
            if (bot.level().getFluidState(q).isSourceOfType(net.minecraft.world.level.material.Fluids.WATER)) open++;
        }
        if (open < 15) return; // a puddle, not a lake
        var where = net.minecraft.core.GlobalPos.of(bot.level().dimension(), water);
        if (this.memory.nearest("lake", where, 32) != null) return;
        this.memory.remember("lake", where, this.now);
        if (canFish) tempt(net.joinedthegame.ai.life.Activities.Activity.LEISURE, "a lake", water);
    }

    private long toldBundleAt = Long.MIN_VALUE / 2;

    /**
     * Fighting, or hit in the last ten seconds: no sorting the bag now. (It's instant, but nobody
     * tucks their feathers into a bundle at three hearts with bogged shooting at them.)
     */
    public boolean inAFight() {
        return this.current instanceof net.joinedthegame.ai.goal.CombatGoal || this.current instanceof net.joinedthegame.ai.goal.FeudGoal
                || this.now - this.lastAttackedAt < 20 * 10 || this.now - this.personAttackedAt < 20 * 10;
    }

    private void habits(BotPlayer bot) {
        shieldReflex(bot);
        fireReflex(bot);
        if (this.now % 200 == 113) noticeLakes(bot);
        if (this.now % 100 == 23) noticeStructures(bot);
        // Home: keep what's in the chests in mind (someone may have put something in them).
        if (this.now % 600 == 37) Storage.refreshIfHome(bot, this);
        if (this.now % 100 == 61 && !inAFight()) {
            // Bags filling up: odds and ends into bundles (making one if need be) before anything
            // gets thrown out; plenty of room again, out they come.
            if (Inv.usedSlots(bot) >= Bundles.FULLISH) {
                int packed = Bundles.pack(bot);
                if (Bundles.makeOneIfWorthIt(bot, this)) packed += Bundles.pack(bot);
                if (packed > 0 && this.now - this.toldBundleAt > 20 * 60 * 5) {
                    this.toldBundleAt = this.now;
                    log("tucked " + packed + " odds and ends into my bundle" + (Inv.count(bot, st -> st.is(net.minecraft.tags.ItemTags.BUNDLES)) > 1 ? "s" : ""));
                }
            } else if (Inv.usedSlots(bot) <= Inv.MAIN_SLOTS - 12) {
                Bundles.unpack(bot);
            }
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
        if (this.now % 100 == 31 && !inAFight()) Junk.tossIfFull(bot, this);
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
        } else if (this.lookTicks > 0) {
            // Someone tapped us: a glance their way (while stood still; walking, we watch the path).
            this.lookTicks--;
            Player target = this.lookTarget == null ? null : bot.level().getPlayerByUUID(this.lookTarget);
            if (target != null && !this.navigator.isMoving() && !this.breaker.busy()) this.controls.lookAt(bot, target);
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
        } else {
            // Feet inside a full block (the head's in a torch or air, so it isn't "in a wall",
            // but it can't walk out either): break that one.
            BlockPos feet = bot.blockPosition();
            var state = bot.level().getBlockState(feet);
            if (state.isCollisionShapeFullBlock(bot.level(), feet) && bot.getY() - feet.getY() < 0.5) {
                this.breaker.start(feet);
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
    public enum Focus { NONE, DIAMONDS, HOUSE, WANDER, PROJECT, NETHER, GEAR, LEISURE, SOCIAL }


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
        // Moving on from a project that wasn't finished: remember it, to come back to.
        if (focusValue() == Focus.PROJECT && focus != Focus.PROJECT && this.projects.current() != null) {
            this.memory.setAside(this.projects.current().name().toLowerCase(java.util.Locale.ROOT));
        }
        this.memory.focus = focus.name();
    }

    /** Something under way that the current focus is for (a visit): don't let the stint run out on it. */
    public void keepFocusUntil(long until) {
        if (this.memory.focusUntil < until) this.memory.focusUntil = until;
    }

    /** Force a focus (the /jtg focus command). */
    public void setFocus(Focus focus, long now) {
        setFocusValue(focus);
        // Every timed focus gets its stint (leisure and company used to run out the moment they were set).
        if (focus == Focus.WANDER || focus == Focus.DIAMONDS || focus == Focus.GEAR) this.memory.focusUntil = now + 20 * 60 * 15;
        if (focus == Focus.LEISURE || focus == Focus.SOCIAL) this.memory.focusUntil = now + 20 * 60 * 6;
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
    /** On a raid (an outpost, a village): its chests are what we came for. */
    public boolean raidingNow() {
        var k = this.projects.current();
        return this.focusValue() == Focus.PROJECT && (k == net.joinedthegame.ai.goal.ProjectGoal.Kind.RAID || k == net.joinedthegame.ai.goal.ProjectGoal.Kind.VILLAGE_RAID);
    }

    public boolean inTrialChamber(BotPlayer bot) {
        return this.projects.current() == net.joinedthegame.ai.goal.ProjectGoal.Kind.TRIAL_CHAMBER
                && this.focusValue() == Focus.PROJECT && net.joinedthegame.ai.project.TrialChamberProject.inChamber(bot);
    }

    // --- freeze watchdog ------------------------------------------------------------------

    private @Nullable Vec3 wdPos;
    private long wdAt;
    private int wdInventory;
    private @Nullable Goal wdGoal;
    private int wdBroken;

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
                // A block actually broken (not just "partway through one": a bot chipping at a block
                // that never breaks used to count as busy for ever)
                || this.breaker.broken() != this.wdBroken
                || bot.isUsingItem() || bot.isSleeping()
                || this.now - this.lastAttackedAt < 40 || bot.getLastHurtMob() != null && this.now - bot.getLastHurtMobTimestamp() < 40
                || goal.waitingOnPurpose(bot);
        if (progress) {
            this.wdGoal = goal;
            this.wdPos = bot.position();
            this.wdInventory = inventorySignature(bot);
            this.wdBroken = this.breaker.broken();
            this.wdAt = this.now;
            return;
        }
        if (this.now - this.wdAt < 20 * 30) return;
        log("watchdog: '" + goal.activity() + "' got nowhere for 30s (stood at " + bot.blockPosition().toShortString() + "); dropping it for now");
        // Everything about what it was stuck on, for the debug log.
        log(String.format("watchdog snapshot: goal %s | %s | nav %s | breaker %s | holding %s | look %.0f/%.0f | eye %s",
                goal.getClass().getSimpleName(), this.debug, this.navigator.debugString(), this.breaker.debugString(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(bot.getMainHandItem().getItem()).getPath(),
                bot.getYRot(), bot.getXRot(), BlockPos.containing(bot.getEyePosition()).toShortString()));
        goal.cooldown(this.now, 20 * 60);
        stopCurrent(bot);
        this.wdGoal = null;
        this.wdAt = this.now;
    }

    /**
     * The big goal is actually under way (a project running, a nether trip started, a house
     * being built): it owns the focus, and the "full iron first" rule doesn't take it away
     * half way through (a worn-out pickaxe or a broken boot mid-trip is no reason to drop it).
     */
    private boolean focusUnderWay() {
        return switch (focusValue()) {
            case PROJECT -> this.projects.running();
            case NETHER -> this.nether.inProgress();
            case HOUSE -> this.settle.inProgress();
            default -> false;
        };
    }

    /**
     * On (or getting ready for) a nether trip of any kind: blaze rods, or a netherite project.
     * The portal kit (obsidian, flint and steel, the gold helmet) stays on us then.
     */
    public boolean onNetherTrip() {
        if (focusValue() == Focus.NETHER) return true;
        return focusValue() == Focus.PROJECT && this.projects.current() == net.joinedthegame.ai.goal.ProjectGoal.Kind.NETHERITE;
    }

    /**
     * Off on a long trip: the nether, or an away project under way (a trial chamber, netherite,
     * a raid). The leash leaves these alone by default.
     */
    public boolean onExpedition(BotPlayer bot) {
        if (bot.level().dimension() == net.minecraft.world.level.Level.NETHER || focusValue() == Focus.NETHER) return true;
        var kind = this.projects.current();
        return focusValue() == Focus.PROJECT && this.projects.running() && kind != null
                && !net.joinedthegame.ai.goal.ProjectGoal.homely(kind);
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
    /**
     * The big goal {@code mine} didn't work out: drop it (if it's still the focus; one goal
     * giving up mustn't wipe out a different one that's under way) and roll again in a while.
     */
    /** Today's plan: the big things it means to get done today (see updateFocus). */
    private final java.util.Set<Focus> dayPlan = java.util.EnumSet.noneOf(Focus.class);
    private long planDay = -1;

    public java.util.Set<Focus> dayPlan() {
        return this.dayPlan;
    }

    /** Big goals that went wrong lately, and when (put off for a while: see updateFocus). */
    private final java.util.Map<Focus, Long> failedFocus = new java.util.EnumMap<>(Focus.class);

    public void focusFailed(Focus mine) {
        this.failedFocus.put(mine, this.now);
        if (focusValue() != mine) return;
        setFocusValue(Focus.NONE);
        this.memory.nextRollAt = this.now + 20 * 60 * 3;
    }

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
        Focus focus = focusValue();
        // Done with houses: a proper house built, or a cabin that's still big enough.
        boolean houseDone = "LARGE".equals(this.memory.homeType)
                || ("HOUSE".equals(this.memory.homeType) || "CABIN".equals(this.memory.homeType))
                && !net.joinedthegame.ai.goal.SettleGoal.outgrown(bot, this);
        boolean diamondsDone = Inv.fullDiamond(bot);
        boolean expired = (focus == Focus.WANDER || focus == Focus.DIAMONDS || focus == Focus.GEAR || focus == Focus.LEISURE
                || focus == Focus.SOCIAL) && this.now > this.memory.focusUntil;
        if ((focus == Focus.HOUSE && houseDone && !this.settle.inProgress()) || (focus == Focus.DIAMONDS && diamondsDone) || expired) {
            setFocusValue(Focus.NONE);
            this.memory.nextRollAt = this.now + 20 * 10;
        }
        // Set out for the nether, then lost the gear for it (died, say) before getting going: not now.
        double gear = this.needs.level(net.joinedthegame.ai.life.Needs.Need.GEAR);
        if (focusValue() == Focus.NETHER && gear > 0.3 && !focusUnderWay()) setFocusValue(Focus.NONE);
        // Meant to take it easy, but its stuff is lying where it died: that comes first.
        if ((focusValue() == Focus.LEISURE || focusValue() == Focus.SOCIAL) && this.memory.deathSpot != null) {
            setFocusValue(Focus.NONE);
            this.memory.nextRollAt = this.now + 20 * 20;
        }
        considerTemptation(bot, houseDone, diamondsDone, gear);
        if (focusValue() != Focus.NONE || this.now < this.memory.nextRollAt) return;

        // What to do with itself next: whatever meets what it needs most, odds by appeal (see
        // Needs, Activities). No ladder and no "full iron first": gear matters most early on, so
        // early life is mostly gearing up, but a bot that badly wants a home builds one first.
        var n = this.needs;
        Personality p = this.personality;
        var appeal = appeals(bot, houseDone, diamondsDone, gear);
        // A new day: what it means to get done today (two or three things, picked from what appeals
        // this morning). Those pull extra all day; each is ticked off once it's been done.
        long day = bot.level().getOverworldClockTime() / 24000L;
        if (day != this.planDay) {
            this.planDay = day;
            this.dayPlan.clear();
            var left = new java.util.EnumMap<>(appeal);
            int count = 2 + bot.getRandom().nextInt(2);
            while (this.dayPlan.size() < count && !left.isEmpty()) {
                var a = net.joinedthegame.ai.life.Activities.pick(left, bot.getRandom().nextDouble());
                if (a == null) break;
                this.dayPlan.add(focusFor(a));
                left.remove(a);
            }
            if (!this.dayPlan.isEmpty()) log("plan for today: " + this.dayPlan.toString().toLowerCase(java.util.Locale.ROOT));
        }
        for (var e : appeal.entrySet()) if (this.dayPlan.contains(focusFor(e.getKey()))) e.setValue(e.getValue() * 1.4);
        // Boredom: just did that? Less appealing for a while. (Gearing up is the exception early on:
        // nobody gets bored of not having a pickaxe.) And one that just went wrong: put off a bit.
        for (var e : appeal.entrySet()) {
            Focus f = focusFor(e.getKey());
            long since = this.now - lastFocusAt(f);
            boolean urgentGear = f == Focus.GEAR && gear > 0.6;
            if (!urgentGear && since < 20 * 60 * 5) e.setValue(e.getValue() * 0.4);
            else if (!urgentGear && since < 20 * 60 * 20) e.setValue(e.getValue() * 0.7);
            Long failed = this.failedFocus.get(f);
            if (failed != null && this.now - failed < 20 * 60 * 15) e.setValue(e.getValue() * 0.5);
        }
        var chosen = net.joinedthegame.ai.life.Activities.pick(appeal, bot.getRandom().nextDouble());
        if (chosen == null) {
            this.memory.nextRollAt = this.now + 20 * 60 * 2;
            return;
        }
        Focus next = focusFor(chosen);
        setFocusValue(next);
        this.dayPlan.remove(next); // on with it: ticked off today's list
        switch (next) {
            // Open-ended ones get a stint, not a life sentence: then something else for a while.
            case GEAR -> this.memory.focusUntil = this.now + 20 * 60 * (6 + bot.getRandom().nextInt(7));
            case DIAMONDS -> this.memory.focusUntil = this.now + 20 * 60 * (15 + bot.getRandom().nextInt(11));
            case WANDER -> this.memory.focusUntil = this.now + 20 * 60 * (10 + bot.getRandom().nextInt(11));
            case LEISURE -> this.memory.focusUntil = this.now + 20 * 60 * (3 + bot.getRandom().nextInt(4));
            case SOCIAL -> this.memory.focusUntil = this.now + 20 * 60 * (5 + bot.getRandom().nextInt(4));
            default -> {
            }
        }
        remember(BotMemory.EventType.FOCUS, next.name().toLowerCase(java.util.Locale.ROOT));
        log("next big goal: " + next + " (appeal: " + net.joinedthegame.ai.life.Activities.describe(appeal) + " | needs: " + n.describe(p) + ")");
    }

    /** How appealing each big thing it could do right now is (only the ones it's ready for). */
    private java.util.Map<net.joinedthegame.ai.life.Activities.Activity, Double> appeals(BotPlayer bot, boolean houseDone, boolean diamondsDone, double gear) {
        var n = this.needs;
        Personality p = this.personality;
        int pick = Inv.pickaxeTier(bot);
        java.util.Map<net.joinedthegame.ai.life.Activities.Activity, Double> appeal =
                net.joinedthegame.ai.life.Activities.newMap(net.joinedthegame.ai.life.Activities.Activity.class);
        appeal.put(net.joinedthegame.ai.life.Activities.Activity.GEAR_UP,
                net.joinedthegame.ai.life.Activities.appeal(net.joinedthegame.ai.life.Activities.Activity.GEAR_UP, n, p));
        if (pick >= 4 && !diamondsDone) {
            appeal.put(net.joinedthegame.ai.life.Activities.Activity.DIAMONDS,
                    net.joinedthegame.ai.life.Activities.appeal(net.joinedthegame.ai.life.Activities.Activity.DIAMONDS, n, p));
        }
        if (!houseDone && !roaming(bot) && pick >= 1) {
            double house = net.joinedthegame.ai.life.Activities.appeal(net.joinedthegame.ai.life.Activities.Activity.HOUSE, n, p);
            // A nomad that's geared up, or desperate for somewhere to put things, wants a base now.
            if (p.nomad() > 0.6 && this.record.house() == null) house += needsStorageBadly(bot) ? 1.5 : 0.5;
            if (this.settle.inProgress()) house += 0.4; // half built: that's what people get back to
            appeal.put(net.joinedthegame.ai.life.Activities.Activity.HOUSE, house);
        }
        double project = this.projects.bestAppeal(bot);
        if (project > 0) appeal.put(net.joinedthegame.ai.life.Activities.Activity.PROJECT, project);
        boolean canGetThere = pick >= 5 || this.memory.places().containsKey("portal");
        if (canGetThere && gear <= 0.3 && (Inv.count(bot, Items.BLAZE_ROD) < net.joinedthegame.ai.project.NetherTrip.RODS_WANTED
                || net.joinedthegame.ai.project.BrewProject.wantsWart(bot, this))) {
            appeal.put(net.joinedthegame.ai.life.Activities.Activity.NETHER,
                    net.joinedthegame.ai.life.Activities.appeal(net.joinedthegame.ai.life.Activities.Activity.NETHER, n, p));
        }
        if (pick >= 1) {
            appeal.put(net.joinedthegame.ai.life.Activities.Activity.WANDER,
                    net.joinedthegame.ai.life.Activities.appeal(net.joinedthegame.ai.life.Activities.Activity.WANDER, n, p));
        }
        // Company: someone it likes to go and see, and it's safe to.
        if (pick >= 1 && safeToRelax(bot) && companion(bot) != null) {
            appeal.put(net.joinedthegame.ai.life.Activities.Activity.SOCIAL,
                    net.joinedthegame.ai.life.Activities.appeal(net.joinedthegame.ai.life.Activities.Activity.SOCIAL, n, p));
        }
        if (pick >= 1 && safeToRelax(bot)) {
            appeal.put(net.joinedthegame.ai.life.Activities.Activity.LEISURE,
                    net.joinedthegame.ai.life.Activities.appeal(net.joinedthegame.ai.life.Activities.Activity.LEISURE, n, p));
        }
        return appeal;
    }

    /**
     * Something it noticed that might pull it away from what it's doing (a lake, a structure,
     * a cave): one chance, then it's gone. See {@link #tempt}.
     */
    private record Temptation(net.joinedthegame.ai.life.Activities.Activity activity, String what, BlockPos where, long at) {}

    private @Nullable Temptation temptation;

    /** Where a temptation to go fishing came from (the lake it saw), for the leisure goal. */
    private @Nullable BlockPos fishingSpot;

    public @Nullable BlockPos takeFishingSpot() {
        BlockPos s = this.fishingSpot;
        this.fishingSpot = null;
        return s;
    }

    /** Noticed something that might tempt it away from its work. */
    public void tempt(net.joinedthegame.ai.life.Activities.Activity activity, String what, BlockPos where) {
        this.temptation = new Temptation(activity, what, where.immutable(), this.now);
    }

    /**
     * Busy with something (a house, a project, gearing up) and something caught its eye. Whether
     * it goes depends on who it is: explorers, the laid-back and the reckless are easily tempted;
     * a diligent builder notes it and carries on. And on whether it's really drawn to that (a bot
     * that loves fishing, and a lake). The work's set aside, not forgotten: it comes back to it.
     * Never a timer: nothing noticed, nothing to be distracted by.
     */
    private void considerTemptation(BotPlayer bot, boolean houseDone, boolean diamondsDone, double gear) {
        Temptation t = this.temptation;
        this.temptation = null;
        if (t == null || this.now - t.at() > 20 * 30) return;
        Focus cur = focusValue();
        if (cur != Focus.HOUSE && cur != Focus.PROJECT && cur != Focus.GEAR) return;
        if (cur == Focus.GEAR && gear > 0.6) return; // no pickaxe to its name yet: first things first
        if (this.current instanceof net.joinedthegame.ai.goal.CombatGoal) return;
        Personality p = this.personality;
        double distractible = Math.max(0.02, p.explorer() * 0.5 + p.reckless() * 0.3 + (1 - p.diligent()) * 0.5
                - p.builder() * 0.3 - p.homebody() * 0.1);
        var appeal = appeals(bot, houseDone, diamondsDone, gear);
        Double pull = appeal.get(t.activity());
        if (pull == null) return; // not something it could do right now
        var mineKind = switch (cur) {
            case HOUSE -> net.joinedthegame.ai.life.Activities.Activity.HOUSE;
            case PROJECT -> net.joinedthegame.ai.life.Activities.Activity.PROJECT;
            default -> net.joinedthegame.ai.life.Activities.Activity.GEAR_UP;
        };
        double mine = Math.max(0.1, appeal.getOrDefault(mineKind, 0.5));
        // Seeing it makes it more appealing than the idea of it: x1.5.
        double chance = Math.min(0.9, distractible * (pull * 1.5 / mine));
        if (bot.getRandom().nextDouble() >= chance) {
            log("noticed " + t.what() + ", but carrying on (" + cur.name().toLowerCase(java.util.Locale.ROOT) + ")");
            return;
        }
        Focus next = focusFor(t.activity());
        String doing = switch (cur) {
            case HOUSE -> "the house";
            case PROJECT -> this.projects.current() == null ? "my project" : "the " + this.projects.current().name().toLowerCase(java.util.Locale.ROOT);
            default -> "gearing up";
        };
        log("found " + t.what() + " while working on " + doing + ": going to " + next.name().toLowerCase(java.util.Locale.ROOT)
                + " for a bit (" + doing + " can wait)");
        if (next == Focus.LEISURE && t.activity() == net.joinedthegame.ai.life.Activities.Activity.LEISURE) this.fishingSpot = t.where();
        setFocusValue(next);
        if (next == Focus.WANDER || next == Focus.LEISURE || next == Focus.DIAMONDS) {
            this.memory.focusUntil = this.now + 20 * 60 * (next == Focus.LEISURE ? 4 : 10);
        }
        remember(BotMemory.EventType.FOCUS, next.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Focus focusFor(net.joinedthegame.ai.life.Activities.Activity a) {
        return switch (a) {
            case GEAR_UP -> Focus.GEAR;
            case DIAMONDS -> Focus.DIAMONDS;
            case HOUSE -> Focus.HOUSE;
            case PROJECT -> Focus.PROJECT;
            case NETHER -> Focus.NETHER;
            case WANDER -> Focus.WANDER;
            case LEISURE -> Focus.LEISURE;
            case SOCIAL -> Focus.SOCIAL;
        };
    }

    /** When this big goal was last picked (long ago if never). */
    private long lastFocusAt(Focus focus) {
        String subject = focus.name().toLowerCase(java.util.Locale.ROOT);
        long last = Long.MIN_VALUE / 2;
        for (BotMemory.Event e : this.memory.events()) {
            if (e.type() == BotMemory.EventType.FOCUS && e.subject().equals(subject)) last = Math.max(last, Math.max(e.time(), e.last()));
        }
        return last;
    }

    public net.joinedthegame.ai.goal.ProjectGoal projects() {
        return this.projects;
    }

    // --- events -----------------------------------------------------------------

    /** A player gave us a friendly punch: drop everything and look at them. */
    /**
     * A player hit us (any hit, before the damage lands). It used to stop everything: the bot
     * froze to stare, stopped following, put its job off for half a minute; and froze again on
     * every hit, so a player could beat one to death while it stood there "deciding" to fight back.
     * Now:
     * <ul>
     *   <li>In a fight with them already: just part of the fight (FeudGoal has it).</li>
     *   <li>A real hit (anything in hand): an attack; onHurt has it (feelings, self defence).</li>
     *   <li>A bare-hand tap: "hey!". A glance their way, and carry on (still following, if we
     *       were); a friend crouches hello back. Keep tapping (four in a few seconds) and it gets
     *       annoying: friends put up with it, others think a bit less of them, and a hot-headed
     *       one may shove back. Past eight, it's not tapping any more: it's an attack.</li>
     * </ul>
     */
    public void onPunched(BotPlayer bot, ServerPlayer puncher) {
        UUID id = puncher.getUUID();
        long now = bot.level().getGameTime();
        if (id.equals(feudOpponent()) || id.equals(personAttacker(20 * 10))) return;
        if (!puncher.getMainHandItem().isEmpty()) return;
        long[] t = this.taps.computeIfAbsent(id, k -> new long[]{0, 0});
        if (now - t[1] > 20 * 6) t[0] = 0;
        t[0]++;
        t[1] = now;
        String name = puncher.getGameProfile().name();
        this.lookTarget = id;
        this.lookTicks = 30;
        if (t[0] == 1) {
            remember(BotMemory.EventType.HIT_BY_PLAYER, name, null, 0.1f);
            log(name + " tapped me: hey!");
            if (friendly(id)) {
                this.greetTicks = 16;
                this.greetTarget = id;
            }
        } else if (t[0] == 4) {
            if (friendly(id) || personality.social() > 0.7 && relationLiking(id) >= 0) {
                log(name + " keeps punching me: putting up with it");
            } else {
                feel(puncher, net.joinedthegame.ai.social.Feelings.Act.ANNOYED_ME, 1);
                double temper = 0.05 + (1 - net.joinedthegame.ai.social.Feelings.forgiving(this.personality)) * 0.25 + (relationLiking(id) <= -10 ? 0.3 : 0);
                if (bot.getRandom().nextDouble() < temper && bot.distanceToSqr(puncher) < 3.5 * 3.5) {
                    log(name + " keeps punching me: had enough, shoving them back");
                    this.controls.lookAt(bot, puncher);
                    bot.attack(puncher);
                    bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
                } else {
                    log(name + " keeps punching me: annoying");
                }
            }
        } else if (t[0] == 8) {
            log(name + " won't stop hitting me: that's not a joke any more");
        }
    }

    /** Bare-hand taps per player: {count, last at}. Eight inside a few seconds counts as an attack. */
    private final java.util.Map<UUID, long[]> taps = new java.util.HashMap<>();
    private @Nullable UUID lookTarget;
    private int lookTicks;

    /** Being tapped over and over by them right now (eight or more): it's an attack, not a "hey". */
    public boolean tappedHard(UUID id) {
        long[] t = this.taps.get(id);
        return t != null && t[0] >= 8 && this.now - t[1] <= 20 * 6;
    }

    private double relationLiking(UUID id) {
        var r = this.memory.relations.get(id);
        return r == null ? 0 : r.liking;
    }

    /**
     * Would we go along with what this player's doing (tag along exploring, mine beside them)?
     * Not with someone we dislike, a rival, or whoever's just gone for us (or killed us).
     */
    public boolean wouldTagAlong(UUID id) {
        if (id.equals(personAttacker(20 * 60 * 5))) return false;
        var r = this.memory.relations.get(id);
        return r == null || r.rivalSince == 0 && !r.wick && r.liking > -10;
    }

    /** A player crouched near us. If they were looking at us, follow them. */
    /** When each player last said hi to us by crouching (it counts once every ten minutes). */
    private final java.util.Map<UUID, Long> crouchedHiAt = new java.util.HashMap<>();
    private final java.util.Map<UUID, Long> crouchAskedAt = new java.util.HashMap<>();

    /**
     * A player crouched at us: that's saying hi, and (if they were looking right at us) asking us
     * to come along. What we do about it is how we feel about them:
     * <ul>
     *   <li>Hi: we crouch back (unless we can't stand them: then just a look), and it counts like
     *       any hello (knowing them a bit better), once every ten minutes.</li>
     *   <li>"Come with me?": a partner or close friend comes for the full time, a friend or someone
     *       we like for a while, a stranger only if we're the sociable sort (and briefly); someone
     *       we dislike, or a rival, gets a no.</li>
     * </ul>
     */
    public void onPlayerCrouched(BotPlayer bot, ServerPlayer player, boolean lookingAtMe) {
        var r = this.memory.relations.get(player.getUUID());
        String name = player.getGameProfile().name();
        boolean enemy = r != null && (r.rivalSince > 0 || r.wick);
        boolean dislike = r != null && r.liking <= -30;
        long now = bot.level().getGameTime();
        // Hi back (or, for someone we can't stand, just a look).
        if (!enemy && !dislike) this.greetTicks = 24;
        this.greetTarget = player.getUUID();
        Long last = this.crouchedHiAt.get(player.getUUID());
        if (!enemy && (last == null || now - last >= 20 * 60 * 10)) {
            this.crouchedHiAt.put(player.getUUID(), now);
            feel(player, net.joinedthegame.ai.social.Feelings.Act.GREETED, 1);
        }
        if (!lookingAtMe) return;
        // One crouch (or a quick bob up and down) is one ask, not five.
        Long asked = this.crouchAskedAt.get(player.getUUID());
        if (asked != null && now - asked < 20 * 3) return;
        this.crouchAskedAt.put(player.getUUID(), now);
        // "Come with me?"
        long full = JtgConfig.FOLLOW_SECONDS.get() * 20L;
        long time;
        String why;
        if (player.getUUID().equals(personAttacker(20 * 60 * 5))) {
            time = 0;
            why = "they just went for me";
        } else if (enemy) {
            time = 0;
            why = "we're rivals";
        } else if (dislike) {
            time = 0;
            why = "I don't like them";
        } else if (r != null && (r.partnerSince > 0 || r.liking >= 60)) {
            time = full;
            why = r.partnerSince > 0 ? "my partner" : "a close friend";
        } else if (r != null && r.liking >= 30) {
            time = (long) (full * 0.6);
            why = "a friend";
        } else if (r != null && r.liking >= 10) {
            time = Math.max(20 * 60, (long) (full * 0.35));
            why = "I like them";
        } else if (this.personality.social() >= 0.6) {
            time = Math.min(full, 20 * 60);
            why = "don't know them well, but why not";
        } else {
            time = 0;
            why = r == null || r.familiarity < 8 ? "I don't know them well enough" : "not bothered";
        }
        if (time <= 0) {
            log(name + " asked me to come along: no (" + why + ")");
            return;
        }
        this.followTarget = player.getUUID();
        this.followUntil = now + time;
        log(name + " asked me to come along: yes, for " + time / 20 / 60 + " min (" + why + ")");
    }

    /** When each person last counted as hurting us (several hits in a moment are one thing). */
    private final java.util.Map<UUID, Long> lastHurtBy = new java.util.HashMap<>();

    public void onHurt(BotPlayer bot, @Nullable Entity attacker, float amount) {
        if (attacker instanceof net.minecraft.world.entity.player.Player who && who != bot && amount > 0) {
            // A tap with an empty hand is how players get a bot's attention, not an attack.
            boolean friendlyPunch = !(who instanceof BotPlayer) && who.getMainHandItem().isEmpty() && amount <= 1.5f
                    && !tappedHard(who.getUUID());
            // The blow that kills us counts as the killing (onDeath), not as a hit as well.
            boolean lethal = amount >= bot.getHealth() + bot.getAbsorptionAmount();
            // Caught by their sword sweep while they were swinging at something else (the same
            // sheep, say): usually shrugged off as an accident. Now and then it isn't: the
            // short-tempered, the unforgiving, and anyone who didn't like them much already take it
            // badly and swing back, like anyone might. Never a friend.
            if (!friendlyPunch && net.joinedthegame.ai.Sweeps.accident(who, bot)) {
                String key = "sweep:" + who.getUUID();
                String before = recentDecision(key);
                int times = before == null ? 1 : before.startsWith("accident") ? Integer.parseInt(before.substring(9)) + 1 : 0;
                boolean mad = times == 0 || takesSweepBadly(who.getUUID(), times);
                decide(key, mad ? "mad" : "accident:" + times);
                if (!mad) {
                    if (!lethal) feel(who, net.joinedthegame.ai.social.Feelings.Act.SWEPT_ME, 1);
                    if (times == 1) log(who.getScoreboardName() + " caught me with a sweep (going for something else): an accident");
                    return;
                }
                if (before == null || !before.equals("mad")) log(who.getScoreboardName() + " caught me with a sweep" + (times > 1 ? " again" : "") + ": not having that");
            }
            Long last = this.lastHurtBy.get(who.getUUID());
            if (!friendlyPunch && !lethal && (last == null || bot.level().getGameTime() - last >= 10)) {
                this.lastHurtBy.put(who.getUUID(), bot.level().getGameTime());
                // In a fight we're both in, a hit is what you'd expect: it counts for a third.
                double mutual = who.getUUID().equals(feudOpponent()) ? 0.33 : 1.0;
                feel(who, net.joinedthegame.ai.social.Feelings.Act.HIT_ME, Math.clamp(amount / 4.0, 0.25, 2.5) * mutual);
            }
            if (!friendlyPunch) {
                this.personAttacker = who.getUUID();
                this.personAttackedAt = bot.level().getGameTime();
                // Following them? Not after that.
                if (who.getUUID().equals(this.followTarget)) this.followTarget = null;
                var hits = this.hitTimes.computeIfAbsent(who.getUUID(), k -> new java.util.ArrayDeque<>());
                hits.addLast(bot.level().getGameTime());
                while (hits.size() > 8) hits.removeFirst();
                // Unfinished business: a hit after a break (they ran off and came back) is a new round.
                long[] u = this.unsettled.computeIfAbsent(who.getUUID(), k -> new long[]{0, Long.MIN_VALUE / 2});
                long t = bot.level().getGameTime();
                if (t - u[1] > 20 * 10) u[0]++;
                u[1] = t;
            }
        }
        if (attacker instanceof LivingEntity living && !(attacker instanceof Player)) {
            remember(BotMemory.EventType.ATTACKED_BY,
                    net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString(),
                    net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition()), Math.min(1f, amount / 20f));
            this.lastAttacker = living;
            this.lastAttackedAt = bot.level().getGameTime();
        }
    }

    /**
     * Gets properly annoyed at an accidental sweep (and swings back)? Rarely: a few percent for
     * the easygoing, up to about a fifth for hot-headed, unforgiving types; much likelier with
     * someone it already doesn't like, and with every repeat in a few minutes. Never a friend or
     * partner; always a rival.
     */
    private boolean takesSweepBadly(UUID who, int times) {
        if (friendly(who)) return false;
        var r = knownRelation(who);
        if (r != null && (r.rivalSince > 0 || r.wick)) return true;
        Personality p = this.personality;
        double chance = 0.03 + (1 - net.joinedthegame.ai.social.Feelings.forgiving(p)) * 0.12 + p.reckless() * 0.06;
        if (r != null && r.liking <= -10) chance += 0.3;
        return this.lastBot != null && this.lastBot.getRandom().nextDouble() < chance * times;
    }

    // --- respawns and teleports ----------------------------------------------------
    // Nothing walks, swims or rides 32 blocks in one tick: a jump like that is a respawn (just
    // died) or a teleport (a command, say). Telling them apart for certain beats guessing from
    // how far away things are: a respawn at world spawn can be any distance from the build.

    private net.minecraft.world.phys.@Nullable Vec3 lastPos;
    private net.minecraft.resources.@Nullable ResourceKey<net.minecraft.world.level.Level> lastDim;
    private long lastDeathAt = Long.MIN_VALUE / 2;
    private long lastTeleportAt = Long.MIN_VALUE / 2;

    private void noteJump(BotPlayer bot) {
        var pos = bot.position();
        var prev = this.lastPos;
        if (prev != null && this.lastDim == this.dimension && pos.distanceToSqr(prev) > 32 * 32) {
            int far = (int) Math.sqrt(pos.distanceToSqr(prev));
            if (this.now - this.lastDeathAt < 20 * 60) {
                log("respawned " + far + " blocks from where I died");
            } else {
                this.lastTeleportAt = this.now;
                log("teleported " + far + " blocks");
            }
        }
        this.lastPos = pos;
        this.lastDim = this.dimension;
    }

    /** The last place it stood on dry ground (a way back to land from the water). */
    private @Nullable BlockPos lastDryLand;

    /** The last place we stood out under the sky (the way back up out of a mine). */
    private @Nullable BlockPos lastSky;

    public @Nullable BlockPos lastSky() {
        return this.lastSky;
    }

    public @Nullable BlockPos lastDryLand() {
        return this.lastDryLand;
    }

    /**
     * About to put something down (a crafting table, a furnace) but we're in the water: get out
     * onto dry ground first. True while still on the way (keep returning RUNNING); false once we're
     * out, or if there's no known dry ground near enough to try. Redlantern spent four minutes
     * bobbing in an underground pool trying to set up a furnace in it.
     */
    public boolean gettingOutOfWater(BotPlayer bot) {
        if (!bot.isInWater()) return false;
        BlockPos land = this.lastDryLand;
        if (land == null || land.distSqr(bot.blockPosition()) > 48 * 48) return false;
        if (!this.navigator.isMoving()) {
            this.navigator.moveNear(bot, net.minecraft.world.phys.Vec3.atBottomCenterOf(land), 1.2);
            if (this.navigator.status() == net.joinedthegame.ai.nav.Navigator.Status.FAILED) return false;
            if (this.now - this.toldWaterAt > 20 * 30) {
                this.toldWaterAt = this.now;
                log("can't set up in the water: getting out onto dry ground first");
            }
        }
        return true;
    }

    private long toldWaterAt = Long.MIN_VALUE / 2;

    // --- wool hunts ---------------------------------------------------------------------
    // No sheep about (a dark forest, a desert): a hunt wanders for two minutes and comes back
    // with nothing. Bots went straight out again a few minutes later, seventeen times in an hour
    // and a half. Now each failure in a row means a longer wait before the next: five minutes,
    // then fifteen, then half an hour. Sheep in sight, or wool got some other way, starts over.

    private int woolFails;
    private long woolFailAt = Long.MIN_VALUE / 2;

    public void woolHunt(BotPlayer bot, boolean gotIt) {
        if (gotIt) {
            this.woolFails = 0;
            return;
        }
        this.woolFails++;
        this.woolFailAt = this.now;
        int wait = this.woolFails == 1 ? 5 : this.woolFails == 2 ? 15 : 30;
        log("no sheep to be found: not looking again for " + wait + " minutes");
    }

    /** Time for another go at finding sheep (or there are some right here). */
    public boolean woolHuntDue(BotPlayer bot) {
        if (this.woolFails == 0) return true;
        if (!bot.level().getEntitiesOfClass(net.minecraft.world.entity.animal.sheep.Sheep.class, bot.getBoundingBox().inflate(24),
                s -> s.isAlive() && !s.isBaby()).isEmpty()) return true;
        long wait = 20L * 60 * (this.woolFails == 1 ? 5 : this.woolFails == 2 ? 15 : 30);
        return this.now - this.woolFailAt > wait;
    }

    // --- searching --------------------------------------------------------------------
    // Nothing in sight: a player picks a way and keeps going (with a bit of drift), not a new
    // random direction every forty blocks. Random steps go round in circles, which at sea (or on
    // a big plain) means never getting anywhere. Kept across searches, changed when it's blocked
    // or it's been the same way a good while.

    private double searchHeading = Double.NaN;
    private long searchHeadingAt;

    /** The way to head when searching (radians); picks a new one if there's none or it's stale. */
    public double searchHeading(BotPlayer bot) {
        if (Double.isNaN(this.searchHeading) || this.now - this.searchHeadingAt > 20 * 60 * 4) {
            BlockPos land = this.lastDryLand;
            if (bot.isInWater() && land != null && land.distSqr(bot.blockPosition()) > 6 * 6) {
                this.searchHeading = Math.atan2(land.getZ() + 0.5 - bot.getZ(), land.getX() + 0.5 - bot.getX());
                log("searching: heading back towards land at " + land.toShortString());
            } else {
                this.searchHeading = bot.getRandom().nextDouble() * Math.PI * 2;
            }
            this.searchHeadingAt = this.now;
        }
        return this.searchHeading;
    }

    /** Trees seen from afar that, close up, had no wood it could get at (when, by where). */
    private final java.util.Map<BlockPos, Long> dudTrees = new java.util.HashMap<>();

    public void markDudTrees(BlockPos where) {
        this.dudTrees.put(where, this.now);
        log("no wood I can get at by " + where.toShortString() + ", looking elsewhere");
    }

    /** Near a treetop that was a dud in the last ten minutes. */
    public boolean isDudTrees(BlockPos pos) {
        this.dudTrees.values().removeIf(at -> this.now - at > 20 * 60 * 10);
        for (BlockPos d : this.dudTrees.keySet()) if (d.distSqr(pos) < 16 * 16) return true;
        return false;
    }

    // --- feelings (part 3) ------------------------------------------------------------
    // How it feels about everyone it's met, bots and real players: saved in its memory. See
    // ai/social/Feelings for what everything is worth.

    /** Its feelings about that person, starting fresh (neutral) if it's never met them. */
    public net.joinedthegame.ai.social.Relation relation(UUID id, String name, boolean player) {
        var r = this.memory.relations.get(id);
        if (r == null) {
            r = new net.joinedthegame.ai.social.Relation(id, name, player);
            this.memory.relations.put(id, r);
            fromDiary(r);
        }
        r.name = name;
        return r;
    }

    /**
     * First feelings about someone it already has history with (from before it had feelings at
     * all, in an older world): what the diary says about them counts.
     */
    private void fromDiary(net.joinedthegame.ai.social.Relation r) {
        int seen = 0;
        for (var e : this.memory.events()) {
            if (!e.subject().equalsIgnoreCase(r.name)) continue;
            var act = switch (e.type()) {
                case MET -> net.joinedthegame.ai.social.Feelings.Act.MET;
                case CROWDED_BY -> net.joinedthegame.ai.social.Feelings.Act.CROWDED_ME;
                case STOLEN_FROM -> net.joinedthegame.ai.social.Feelings.Act.STOLE_FROM_ME;
                default -> null;
            };
            if (act == null) continue;
            for (int i = 0; i < Math.max(1, e.count()); i++) {
                net.joinedthegame.ai.social.Feelings.apply(r, act, 1, this.personality, this.now);
            }
            seen++;
        }
        if (seen > 0) {
            log(String.format(java.util.Locale.ROOT, "feelings: remembered %s from before: liking %+.0f, trust %.0f, %s", r.name, r.liking, r.trust,
                    r.status()));
        }
    }

    public net.joinedthegame.ai.social.@Nullable Relation knownRelation(UUID id) {
        return this.memory.relations.get(id);
    }

    /** Someone did something to us: how we feel about them shifts (and the log says how and why). */
    public void feel(UUID id, String name, boolean player, net.joinedthegame.ai.social.Feelings.Act act, double amount) {
        var r = relation(id, name, player);
        double d = net.joinedthegame.ai.social.Feelings.apply(r, act, amount, this.personality, this.now);
        if (act == net.joinedthegame.ai.social.Feelings.Act.NEAR) return;
        log(String.format(java.util.Locale.ROOT, "feelings: %s %s: liking %+.0f (%+.1f), trust %.0f, %s", name, act.why, r.liking, d,
                r.trust, r.status()));
        // Hatred deep enough: sworn enemies. How deep depends on who we are (vengeful bots get
        // there sooner, forgiving ones hardly ever).
        double threshold = -60 - net.joinedthegame.ai.social.Feelings.forgiving(this.personality) * 30;
        if (r.rivalSince == 0 && r.liking <= threshold) becomeRivals(r);
        attraction(r, act, amount, d);
        // Partners: betrayal ends it at once; falling out (not even liking them now) ends it too.
        if (r.partnerSince > 0) {
            boolean betrayal = act == net.joinedthegame.ai.social.Feelings.Act.KILLED_ME
                    || act == net.joinedthegame.ai.social.Feelings.Act.KILLED_MY_PET || act == net.joinedthegame.ai.social.Feelings.Act.STOLE_FROM_ME
                    || act == net.joinedthegame.ai.social.Feelings.Act.BROKE_TRUCE || act == net.joinedthegame.ai.social.Feelings.Act.KILLED_AFTER_PEACE
                    || act == net.joinedthegame.ai.social.Feelings.Act.HIT_ME && amount >= 1 && !id.equals(feudOpponent());
            if (betrayal) breakUp(r, "they " + act.why);
            else if (r.liking < 0 || r.rivalSince > 0) breakUp(r, "we fell out");
        }
        // Making up: very rare. Rivals under a truce, something good between them, and a forgiving
        // bot: a small chance to bury the hatchet (neutral, not friends). Never after John Wick.
        if (r.rivalSince != 0 && !r.wick && r.truceUntil > this.now && d > 0
                && act != net.joinedthegame.ai.social.Feelings.Act.MADE_PEACE
                && this.bot().getRandom().nextDouble() < 0.04 * net.joinedthegame.ai.social.Feelings.forgiving(this.personality)) {
            r.rivalSince = 0;
            r.liking = Math.max(r.liking, -10);
            r.lasting = Math.max(r.lasting, -15);
            log("feelings: buried the hatchet with " + name);
        }
    }

    /**
     * Someone killed our pet. On purpose? Then it depends who we are: the peaceful grieve (and go
     * and find a new pet: the wolf project's open again), the middling make them a rival, the
     * vengeful go John Wick (certain for the most vengeful: see PetKills.vengeance).
     */
    public void onPetKilled(net.minecraft.world.entity.player.Player killer, String what, boolean onPurpose, boolean selfDefence) {
        // (PET_DIED is already in the diary: JtgEvents notes every bot's pet that dies.)
        if (!what.equals("wolf")) remember(BotMemory.EventType.PET_DIED, what);
        if (!onPurpose) {
            feel(killer, net.joinedthegame.ai.social.Feelings.Act.PET_ACCIDENT, 1);
            return;
        }
        if (selfDefence) {
            feel(killer, net.joinedthegame.ai.social.Feelings.Act.KILLED_MY_PET_DEFENDING, 1);
            return;
        }
        feel(killer, net.joinedthegame.ai.social.Feelings.Act.KILLED_MY_PET, 1);
        var r = relation(killer.getUUID(), killer.getGameProfile().name(), !(killer instanceof BotPlayer));
        double v = net.joinedthegame.ai.social.PetKills.vengeance(this.personality);
        double wickChance = Math.clamp((v - 0.45) / 0.25, 0.0, 1.0); // 0.70+ certain
        boolean wick = this.bot().getRandom().nextDouble() < wickChance;
        if (wick) {
            r.wick = true;
            r.liking = -100;
            r.lasting = -100;
            r.trust = 0;
            r.truceUntil = 0;
            becomeRivals(r);
            log(String.format(java.util.Locale.ROOT, "%s killed my %s. John Wick mode (vengeance %.2f)", r.name, what, v));
        } else if (v >= 0.3) {
            becomeRivals(r);
            log(String.format(java.util.Locale.ROOT, "%s killed my %s: they're my rival now (vengeance %.2f)", r.name, what, v));
        } else {
            log(String.format(java.util.Locale.ROOT, "%s killed my %s. Grieving; I'll find a new one (vengeance %.2f)", r.name, what, v));
        }
    }

    // --- romance (part 3, step 4) ---------------------------------------------------------

    private net.joinedthegame.ai.social.Identity.@Nullable Who identity;

    /** Gender and orientation for this world (rolled once and saved: see Identity). */
    public net.joinedthegame.ai.social.Identity.Who identity() {
        if (this.identity == null) {
            var rec = record();
            this.identity = net.joinedthegame.ai.social.Identity.resolve(rec.name(), rec.identity(), this.bot().getRandom());
            var server = this.bot().level().getServer();
            if (server != null && !this.identity.save().equals(rec.identity())) {
                net.joinedthegame.bot.BotManager.get(server).setIdentity(rec, this.identity.save());
            }
        }
        return this.identity;
    }

    /** Who we're with, if anyone. */
    public @Nullable UUID partner() {
        for (var r : this.memory.relations.values()) if (r.partnerSince > 0) return r.id;
        return null;
    }

    private @Nullable BotBrain brainOf(UUID id) {
        var server = this.bot().level().getServer();
        return server == null ? null : net.joinedthegame.bot.BotManager.get(server).brain(id);
    }

    /** How romantic a sort it is (sociable, homely ones fall harder). */
    private double romantic() {
        return 0.5 + this.personality.social() * 0.3 + this.personality.homebody() * 0.2;
    }

    /** How well two personalities fit (alike in what matters day to day): 0.5..1.5. */
    private double fit(Personality other) {
        Personality p = this.personality;
        double diff = Math.abs(p.social() - other.social()) + Math.abs(p.homebody() - other.homebody())
                + Math.abs(p.explorer() - other.explorer()) + Math.abs(p.brave() - other.brave())
                + Math.abs(p.builder() - other.builder()) + Math.abs(p.animalLover() - other.animalLover());
        return 0.5 + (1 - diff / 6.0);
    }

    /**
     * Attraction, after something that happened between us: only between bots who could be into
     * each other, and only once they're good friends who know each other well. Good times build it
     * (faster for a romantic sort, and if their personalities fit); bad things knock it back.
     */
    private void attraction(net.joinedthegame.ai.social.Relation r, net.joinedthegame.ai.social.Feelings.Act act, double amount, double dl) {
        if (r.player) return; // romance is between bots only
        BotBrain theirs = brainOf(r.id);
        if (theirs == null || !net.joinedthegame.ai.social.Identity.compatible(identity(), theirs.identity())) return;
        if (dl < 0) {
            r.attraction = Math.max(0, r.attraction + dl * 0.6);
            return;
        }
        if (r.liking < 40 || r.familiarity < 30 || r.rivalSince > 0) return;
        double gain = switch (act) {
            case NEAR -> 0.03 * amount;
            case SPENT_TIME -> 4;
            case GAVE_ME -> Math.min(3, 1.5 * amount);
            case HELPED_ME -> 3;
            case WAITED_FOR_ME -> 1;
            default -> 0;
        };
        if (gain <= 0) return;
        r.attraction = Math.min(100, r.attraction + gain * romantic() * fit(theirs.personality()));
    }

    /**
     * The end of a good while together: ask them out? Only if not with anyone, really into them,
     * and not turned down lately. A flower first, if it has one. They decide from their own
     * feelings: into us too, they like us, they're free. Someone else's partner says no (and their
     * partner, if they see it, won't like us for it).
     */
    public void considerAskingOut(BotPlayer bot, BotPlayer other) {
        var r = this.memory.relations.get(other.getUUID());
        BotBrain theirs = brainOf(other.getUUID());
        if (r == null || theirs == null || partner() != null || r.partnerSince > 0) return;
        if (r.attraction < 60 || r.liking < 50 || this.now - r.askedAt < 24000L * 3) return;
        if (!net.joinedthegame.ai.social.Identity.compatible(identity(), theirs.identity())) return;
        r.askedAt = this.now;
        giveFlower(bot, other);
        var back = theirs.relation(bot.getUUID(), bot.getScoreboardName(), false);
        UUID theirPartner = theirs.partner();
        if (theirPartner != null) {
            log("asked " + r.name + " out: they're with someone");
            theirs.log(bot.getScoreboardName() + " asked me out: I'm with someone");
            r.attraction *= 0.6;
            remember(BotMemory.EventType.TURNED_DOWN, r.name);
            ServerPlayerHolder.with(bot, theirPartner, p -> {
                BotBrain partners = brainOf(theirPartner);
                if (partners != null && BotBrain.sees(p, bot)) partners.feel(bot, net.joinedthegame.ai.social.Feelings.Act.JEALOUS, 1);
            });
            return;
        }
        double yes = back.attraction >= 35 && back.liking >= 40 ? 0.3 + back.attraction / 100.0 * 0.6 : 0.03;
        if (bot.getRandom().nextDouble() < yes) {
            r.partnerSince = this.now;
            back.partnerSince = this.now;
            r.exSince = 0;
            back.exSince = 0;
            remember(BotMemory.EventType.PARTNERED, r.name);
            theirs.remember(BotMemory.EventType.PARTNERED, bot.getScoreboardName());
            log("asked " + r.name + " out: yes. we're together now");
            theirs.log(bot.getScoreboardName() + " asked me out: yes. we're together now");
        } else {
            r.attraction *= 0.6;
            r.liking = Math.max(-100, r.liking - 3);
            remember(BotMemory.EventType.TURNED_DOWN, r.name);
            theirs.remember(BotMemory.EventType.TURNED_DOWN, bot.getScoreboardName());
            log("asked " + r.name + " out: they said no");
            theirs.log(bot.getScoreboardName() + " asked me out: I said no");
        }
    }

    /** A flower for them, if we've got one: thrown their way (a gift: see Gifts). */
    public void giveFlower(BotPlayer bot, BotPlayer other) {
        int slot = Inv.find(bot, s -> s.is(net.minecraft.tags.BlockItemTags.FLOWERS.item()) || s.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item()));
        if (slot < 0) return;
        this.controls.lookAt(bot, other.getEyePosition());
        bot.setYRot((float) Math.toDegrees(Math.atan2(-(other.getX() - bot.getX()), other.getZ() - bot.getZ())));
        var stack = bot.getInventory().getItem(slot).split(1);
        var thrown = bot.drop(stack, true, net.minecraft.util.Prediction.SERVER_ONLY);
        if (thrown != null) net.joinedthegame.ai.social.Gifts.given(thrown, bot.getUUID(), other.getUUID(), this.now);
        log("gave " + other.getScoreboardName() + " a " + stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Moved in with them, and it's over: move out. The bed by theirs isn't ours any more and their
     * house isn't home: we'll find (or build) a place of our own again.
     */
    public void moveOutFrom(UUID ex) {
        var list = this.memory.places().get("moved_in_with:" + ex);
        if (list == null || list.isEmpty()) return;
        this.memory.places().remove("moved_in_with:" + ex);
        // Not moving back into their place by accident (our old bed there looks free now).
        for (var p : list) this.memory.remember("exs_house", p.pos(), this.now);
        var server = this.bot().level().getServer();
        if (server == null) return;
        var mgr = net.joinedthegame.bot.BotManager.get(server);
        mgr.setHome(this.record, null);
        mgr.setHouse(this.record, null, null);
        this.memory.homeType = "";
        log("moving out (we broke up)");
    }

    /** Over between us (both sides). Exes keep their distance a while. */
    public void breakUp(net.joinedthegame.ai.social.Relation r, String why) {
        if (r.partnerSince == 0) return;
        moveOutFrom(r.id);
        r.partnerSince = 0;
        r.exSince = this.now;
        r.attraction *= 0.4;
        remember(BotMemory.EventType.BROKE_UP, r.name);
        log("broke up with " + r.name + " (" + why + ")");
        BotBrain theirs = brainOf(r.id);
        if (theirs != null) {
            var back = theirs.knownRelation(this.bot().getUUID());
            if (back != null && back.partnerSince > 0) {
                back.partnerSince = 0;
                back.exSince = this.now;
                back.attraction *= 0.5;
                back.liking = Math.max(-100, back.liking - 10);
                theirs.remember(BotMemory.EventType.BROKE_UP, this.bot().getScoreboardName());
                theirs.moveOutFrom(this.bot().getUUID());
                theirs.log(this.bot().getScoreboardName() + " broke up with me (" + why + ")");
            }
        }
    }

    /** Our partner's taking it easy and wants us along: come, if we're free and it's safe. */
    public void invitedBy(BotPlayer bot, UUID from) {
        if (focusValue() == Focus.SOCIAL || focusValue() == Focus.HOUSE || focusValue() == Focus.NETHER) {
            log("my partner asked me along, but I'm busy (" + focusValue().name().toLowerCase(java.util.Locale.ROOT) + ")");
            return;
        }
        if (!safeToRelax(bot) || this.currentPriority >= 50) {
            log("my partner asked me along, but " + (safeToRelax(bot) ? "I'm in the middle of something" : "it's not safe right now"));
            return;
        }
        for (Goal g : this.goals) if (g instanceof net.joinedthegame.ai.goal.SocialGoal s) s.joinOn(from);
        setFocus(Focus.SOCIAL, this.now);
        log("my partner's taking it easy: going along");
    }

    public void becomeRivals(net.joinedthegame.ai.social.Relation r) {
        if (r.rivalSince != 0) return;
        r.rivalSince = this.now;
        remember(BotMemory.EventType.RIVAL, r.name);
        log("feelings: " + r.name + " is my rival now");
    }

    public java.util.Collection<net.joinedthegame.ai.social.Relation> relations() {
        return this.memory.relations.values();
    }

    // People who've hurt us (for real), people we've killed, and peace offers we were refused.
    private @Nullable UUID personAttacker;
    private long personAttackedAt = Long.MIN_VALUE / 2;
    private final java.util.Map<UUID, Long> killedAt = new java.util.HashMap<>();
    private final java.util.Map<UUID, BlockPos> killedWhere = new java.util.HashMap<>();
    private @Nullable UUID refusedPeaceBy;
    private long refusedPeaceAt = Long.MIN_VALUE / 2;

    /** Someone (a bot or a player) who hurt us for real in the last {@code within} ticks. */
    public @Nullable UUID personAttacker(int within) {
        return this.now - this.personAttackedAt <= within ? this.personAttacker : null;
    }

    /**
     * Who hit us lately and hasn't been dealt with: {rounds, last hit at}. A round is a hit after
     * a break, so hit, run off, come back and hit again is two. Kept five minutes from the last hit
     * (see FeudGoal: they show up again, we may well go for them); settled by either of us dying.
     */
    private final java.util.Map<UUID, long[]> unsettled = new java.util.HashMap<>();

    /** Rounds of hit-and-run from them still unsettled (0 if none, or it's been five minutes). */
    public int unsettledRounds(UUID id) {
        long[] u = this.unsettled.get(id);
        return u != null && this.now - u[1] < 20 * 60 * 5 ? (int) u[0] : 0;
    }

    /** Real hits from each person lately (for "a hit or two from a friend" versus a beating). */
    private final java.util.Map<UUID, java.util.ArrayDeque<Long>> hitTimes = new java.util.HashMap<>();

    /** How many times they've hit us in the last {@code within} ticks. */
    public int hitsFrom(UUID who, int within) {
        var hits = this.hitTimes.get(who);
        if (hits == null) return 0;
        int n = 0;
        for (long t : hits) if (this.now - t <= within) n++;
        return n;
    }

    public void onKilledPerson(UUID who, BlockPos where) {
        this.unsettled.remove(who); // that's that settled
        this.killedAt.put(who, this.now);
        this.killedWhere.put(who, where.immutable());
    }

    /** We killed them in the last {@code within} ticks. */
    public boolean killed(UUID who, int within) {
        Long at = this.killedAt.get(who);
        return at != null && this.now - at <= within;
    }

    public @Nullable BlockPos killedAt(@Nullable UUID who) {
        return who == null ? null : this.killedWhere.get(who);
    }

    public void forceSpite() {
        for (Goal g : this.goals) if (g instanceof net.joinedthegame.ai.goal.SpiteGoal s) s.force();
    }

    public void forgetEncounters() {
        for (Goal g : this.goals) if (g instanceof net.joinedthegame.ai.goal.FeudGoal f) f.forgetEncounters();
    }

    /** Who we're fighting right now (FeudGoal), if anyone. */
    public @Nullable UUID feudOpponent() {
        for (Goal g : this.goals) if (g instanceof net.joinedthegame.ai.goal.FeudGoal f) return f.opponent();
        return null;
    }

    /** We called peace on them: killing us before answering is as bad as saying no and killing us. */
    public void calledPeaceOn(UUID who) {
        refusedPeaceBy(who);
    }

    public void refusedPeaceBy(UUID who) {
        this.refusedPeaceBy = who;
        this.refusedPeaceAt = this.now;
    }

    /** They made peace with us from their side: our fight with them is over. */
    public void endFeud(UUID with) {
        for (Goal g : this.goals) {
            if (g instanceof net.joinedthegame.ai.goal.FeudGoal f) f.endFrom(with);
        }
    }

    public void feel(net.minecraft.world.entity.player.Player who, net.joinedthegame.ai.social.Feelings.Act act, double amount) {
        feel(who.getUUID(), who.getGameProfile().name(), !(who instanceof BotPlayer), act, amount);
    }

    /**
     * Someone to go and spend time with: online, in this world, not too far, and someone it likes
     * and knows at least a bit (never someone it dislikes). Better liked and better known first, with
     * a bit of chance in it, so it isn't always the same one.
     */
    public @Nullable ServerPlayer companion(BotPlayer bot) {
        var server = bot.level().getServer();
        if (server == null) return null;
        ServerPlayer best = null;
        double bestScore = 0;
        for (var r : this.memory.relations.values()) {
            // Someone it likes and knows a bit; a very sociable bot just needs to know them a bit.
            double minLiking = this.personality.social() >= 0.6 ? 0 : 5;
            if (r.liking < minLiking || r.familiarity < 5 || r.rivalSince > 0 || r.wick) continue;
            if (r.exSince > 0 && this.now - r.exSince < 24000L * 2) continue; // an ex: not yet
            ServerPlayer p = server.getPlayerList().getPlayer(r.id);
            if (p == null || p == bot || !p.isAlive() || p.isSpectator() || p.level() != bot.level()) continue;
            double dist = Math.sqrt(p.distanceToSqr(bot));
            if (dist > 250) continue;
            double score = r.liking + r.familiarity * 0.5 - dist * 0.1 + bot.getRandom().nextDouble() * 10 + (r.partnerSince > 0 ? 60 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /** Someone's coming over to say hi: stop a moment and say it back. */
    public void greetedBy(UUID from, String name) {
        for (Goal g : this.goals) if (g instanceof net.joinedthegame.ai.goal.GreetGoal gg) gg.greetedBy(from, name);
    }

    /** Liking it would take to count someone a friend (shares with them, defends them, lets things go). */
    public static final double FRIEND = 30;

    /** It likes them enough to count as a friend. */
    public boolean friendly(UUID id) {
        var r = this.memory.relations.get(id);
        return r != null && (r.liking >= FRIEND || r.partnerSince > 0);
    }

    /** It dislikes them enough to keep away from them. */
    public boolean dislikes(UUID id) {
        var r = this.memory.relations.get(id);
        return r != null && r.liking <= -30;
    }

    /** Where the people it dislikes are right now (online, this world): to keep away from them. */
    public java.util.List<net.minecraft.world.phys.Vec3> dislikedNearby(BotPlayer bot, double within) {
        var out = new java.util.ArrayList<net.minecraft.world.phys.Vec3>();
        for (var p : bot.level().players()) {
            if (p != bot && dislikes(p.getUUID()) && p.distanceToSqr(bot) < within * within) out.add(p.position());
        }
        return out;
    }

    /** Once a minute: feelings fade a little with time (see Feelings.fade). */
    private void fadeFeelings() {
        for (var r : this.memory.relations.values()) {
            if (r.lastFaded == 0) {
                r.lastFaded = this.now;
                continue;
            }
            double days = (this.now - r.lastFaded) / 24000.0;
            if (days < 0.05) continue;
            net.joinedthegame.ai.social.Feelings.fade(r, days, this.personality, this.now);
            r.lastFaded = this.now;
            if (r.partnerSince > 0 && this.now - r.lastSeen > 24000L * 4 && r.attraction < 25) breakUp(r, "we drifted apart");
        }
    }

    // --- other bots ------------------------------------------------------------------
    // A start on getting along (or not): part 3 turns this into proper relationships.

    /** How much it puts itself first: reckless, a hoarder, not one for company. 0..1. */
    public double selfish() {
        Personality p = this.personality;
        return Math.clamp(p.reckless() * 0.4 + p.hoarder() * 0.35 + (1 - p.social()) * 0.35 - p.diligent() * 0.1, 0.0, 1.0);
    }

    /** How happy it is to wait its turn. 0..1. */
    public double patient() {
        Personality p = this.personality;
        return Math.clamp(p.social() * 0.45 + p.diligent() * 0.35 + p.homebody() * 0.2 - p.reckless() * 0.3, 0.0, 1.0);
    }

    /**
     * Bad blood with them: how much it dislikes them (crowding our tree a little, taking what we'd
     * chopped more, hurting us a lot). Capped low here: this is only the nudge towards doing it back.
     */
    public double grudgeAgainst(UUID id) {
        var r = this.memory.relations.get(id);
        return r == null || r.liking >= 0 ? 0 : Math.min(0.3, -r.liking / 250.0);
    }

    /** What it decided about sharing with another bot lately (their tree, their drops), and when. */
    private final java.util.Map<String, Long> sharingDecidedAt = new java.util.HashMap<>();
    private final java.util.Map<String, String> sharingDecision = new java.util.HashMap<>();

    /** A decision about {@code key} (e.g. "tree:<uuid>") made in the last few minutes, or null. */
    public @Nullable String recentDecision(String key) {
        Long at = this.sharingDecidedAt.get(key);
        return at != null && this.now - at < 20 * 60 * 3 ? this.sharingDecision.get(key) : null;
    }

    public void decide(String key, String decision) {
        this.sharingDecidedAt.put(key, this.now);
        this.sharingDecision.put(key, decision);
    }

    public void forgetDecision(String key) {
        this.sharingDecidedAt.remove(key);
    }

    /** Would {@code watcher} notice {@code seen} right now: close enough, nothing in the way. */
    public static boolean sees(net.minecraft.world.entity.LivingEntity watcher, net.minecraft.world.entity.Entity seen) {
        return watcher.level() == seen.level() && watcher.distanceToSqr(seen) < 24 * 24 && watcher.hasLineOfSight(seen);
    }

    /** That way is blocked (or got nowhere): turn off somewhere else. */
    public void turnSearchHeading(BotPlayer bot) {
        double h = Double.isNaN(this.searchHeading) ? 0 : this.searchHeading;
        this.searchHeading = h + Math.PI * (0.5 + bot.getRandom().nextDouble());
        this.searchHeadingAt = this.now;
    }

    /** When it was last moved somewhere else outright (not a respawn, not a portal). */
    public long lastTeleportAt() {
        return this.lastTeleportAt;
    }

    public void onDeath(BotPlayer bot, net.minecraft.world.damagesource.DamageSource source) {
        this.memory = bot.getData(net.joinedthegame.JtgAttachments.MEMORY);
        this.lastDeathAt = bot.level().getGameTime();
        String cause = source.getEntity() != null
                ? net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(source.getEntity().getType()).toString()
                : source.typeHolder().unwrapKey().map(k -> k.identifier().toString()).orElse("unknown");
        var where = net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        remember(BotMemory.EventType.DIED, cause, where, 1f);
        if (net.joinedthegame.DebugLog.on()) {
            this.recentDeaths.addLast(java.time.LocalTime.now().withNano(0) + " " + cause.replace("minecraft:", "") + " at "
                    + bot.blockPosition().toShortString() + " while '" + activity() + "'");
            while (this.recentDeaths.size() > 5) this.recentDeaths.removeFirst();
        }
        // Who killed us: whoever struck the blow, or (knocked off a ledge, into lava) the game's credit.
        var credit = source.getEntity() != null ? source.getEntity() : bot.getKillCredit();
        if (credit instanceof net.minecraft.world.entity.player.Player killer && killer != bot) {
            feel(killer, net.joinedthegame.ai.social.Feelings.Act.KILLED_ME, 1);
            // We'd called peace and they said no, then killed us: that's the worst of it. Anyone who
            // saw it thinks less of them too.
            if (killer.getUUID().equals(this.refusedPeaceBy) && this.now - this.refusedPeaceAt < 20 * 60) {
                feel(killer, net.joinedthegame.ai.social.Feelings.Act.KILLED_AFTER_PEACE, 1);
                var server = bot.level().getServer();
                for (var w : bot.level().players()) {
                    if (!(w instanceof BotPlayer witness) || w == bot || w == killer || !BotBrain.sees(witness, killer)) continue;
                    var wb = server == null ? null : net.joinedthegame.bot.BotManager.get(server).brain(witness.getUUID());
                    if (wb != null) wb.feel(killer, net.joinedthegame.ai.social.Feelings.Act.CRUEL, 1);
                }
            }
        }
        this.refusedPeaceBy = null;
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
        for (Goal g : this.goals) g.onOwnerDeath(bot);
        // Off for a view or a wander when it died: that's over. A house or a project is
        // remembered and picked up again once it's back on its feet.
        Focus was = focusValue();
        if (was == Focus.LEISURE || was == Focus.WANDER || was == Focus.SOCIAL) {
            setFocusValue(Focus.NONE);
            this.memory.nextRollAt = this.now + 20 * 20;
        }
        this.followTarget = null;
        this.lastAttacker = null;
        this.unsettled.clear(); // a fresh start (what they did is in the feelings)
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

    /**
     * Fit to take it easy: in good health, nothing has gone for it in the last minute, nothing
     * hostile about, and no stuff lying where it died. Nobody sits back to enjoy the view with a
     * skeleton still shooting at them.
     */
    public boolean safeToRelax(BotPlayer bot) {
        if (bot.getHealth() < bot.getMaxHealth() * 0.7f) return false;
        if (this.memory.deathSpot != null) return false;
        if (bot.level().getGameTime() - this.lastAttackedAt < 20 * 60) return false;
        return bot.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, bot.getBoundingBox().inflate(16),
                m -> m.isAlive() && m instanceof net.minecraft.world.entity.monster.Enemy).isEmpty();
    }

    public @Nullable LivingEntity recentAttacker() {
        return this.now - this.lastAttackedAt < 200 ? this.lastAttacker : null;
    }

    /** Run something with an online player by id (if they're online). */
    private static final class ServerPlayerHolder {
        static void with(BotPlayer bot, UUID id, java.util.function.Consumer<ServerPlayer> what) {
            var server = bot.level().getServer();
            ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(id);
            if (p != null) what.accept(p);
        }
    }
}
