package net.joinedthegame.ai.goal;

import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Blueprints;
import net.joinedthegame.ai.HousePlans;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Planner;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.CollectItems;
import net.joinedthegame.ai.task.CraftTask;
import net.joinedthegame.ai.task.FetchTask;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.ai.task.TravelTask;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.SmeltTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.WoolTask;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.Homes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.jspecify.annotations.Nullable;

/**
 * Settling down. Once a bot has iron and decides it wants a real home (see
 * {@link BotBrain.Focus}), it gets one the laziest way available: move into a village house,
 * dig a room into a hill, or slap together a hut. Then it moves its bed in, puts down a
 * chest, buries a secret chest under the floor for its valuables and hangs a sign out front
 * with its name on it.
 */
public final class SettleGoal extends Goal {
    private enum Stage { CHOOSE, GATHER, BUILD, FURNISH, MOVE }

    private Stage stage = Stage.CHOOSE;
    private HousePlans.@Nullable Plan plan;
    private @Nullable Task task;
    private int failures;
    private int gatherRounds;
    private int furnishRetries;
    /** Building a proper house to replace the one we've got (and moving our stuff over). */
    private boolean upgrade;
    private final java.util.List<BlockPos> oldStorage = new java.util.ArrayList<>();
    private final java.util.Set<String> skipped = new java.util.HashSet<>();
    private final java.util.Set<String> fetched = new java.util.HashSet<>();
    private Tasks.@Nullable Need currentNeed;
    private int gatherFailures;
    private int moveRounds;
    private boolean packing;
    private boolean traveling;
    private boolean packFailed;

    public SettleGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (this.brain.focus() != BotBrain.Focus.HOUSE) return 0;
        if (this.brain.roaming(bot) && this.forced == null) return 0; // a bed on the road is home enough, for now
        if (bot.level().isDarkOutside()) return 0; // bedtime first
        // Halfway through a house: finish it. Only fights, starving and bedtime come first.
        return inProgress() ? 50 : 42;
    }

    /** Gathering for or building a house (not moving stuff in, which needs the chests). */
    public boolean building() {
        return inProgress() && this.stage != Stage.MOVE;
    }

    public boolean inProgress() {
        return this.stage != Stage.CHOOSE || this.task != null;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        // Teleported miles away from a site that isn't by our home either? Start over from here.
        // (A new house next to home is worth the walk back, however far out we've wandered.)
        // (A site by our home bed or our house, or an upgrade, is anchored there: walk back to
        // it instead. Restarting on those picked the same far-off spot again, and it flipped
        // between "looking for a place" and "getting ready to move" every tick, frozen.)
        HousePlans.Plan current = this.plan;
        if (current != null && this.stage != Stage.MOVE && !this.upgrade && !anchored(bot, current)
                && current.room().distSqr(bot.blockPosition()) > 128 * 128) {
            this.brain.log("my building site is too far away now, starting over");
            if (this.task != null) this.task.stop(bot, this.brain);
            reset();
            return restarted(bot);
        }
        if (this.task != null) {
            Task.Status status = this.task.tick(bot, this.brain);
            if (status == Task.Status.RUNNING) return true;
            this.task.stop(bot, this.brain);
            this.brain.log("settle: '" + this.task.activity() + "' " + status);
            this.task = null;
            if (this.traveling) {
                // Just got back to the site (or couldn't): the stage itself isn't done yet.
                this.traveling = false;
                return status == Task.Status.FAILED ? fail(bot, "can't get to my building site") : true;
            }
            if (status == Task.Status.FAILED && this.stage == Stage.GATHER && this.packing) {
                // Can't get to the old bed: make a new one instead.
                this.packing = false;
                this.packFailed = true;
                return true;
            }
            this.packing = false;
            if (status == Task.Status.FAILED && this.stage == Stage.GATHER) {
                // Couldn't get one of the materials. Windows are optional; the walls aren't.
                Tasks.Need need = this.currentNeed;
                if (need != null && need.optional()) {
                    this.skipped.add(need.label());
                    return true;
                }
                if (++this.gatherFailures <= 4) {
                    // Give it a little while (sheep wander back, the bag gets emptied...).
                    cooldown(this.brain.now(), 20 * 30);
                    return false;
                }
                return fail(bot, "couldn't get " + (need == null ? "everything" : need.label()));
            }
            if (status == Task.Status.FAILED && this.stage == Stage.MOVE) return true;
            if (status == Task.Status.FAILED) {
                // Moving in came up short (lost the chest, say)? Go get it again rather than
                // abandoning the house we just built.
                if (this.stage == Stage.FURNISH && ++this.furnishRetries <= 2) {
                    this.stage = Stage.GATHER;
                    return true;
                }
                return fail(bot, "a step failed");
            }
            if (this.stage == Stage.BUILD) this.stage = Stage.FURNISH;
            else if (this.stage == Stage.FURNISH) return finish(bot);
            return true;
        }
        return switch (this.stage) {
            case CHOOSE -> choose(bot);
            case GATHER -> gather(bot);
            case BUILD -> {
                if (farFromSite(bot)) yield true;
                this.task = new BuildTask(this.plan.build(), switch (this.plan.type()) {
                    case CAVE -> "Digging out a cave home";
                    case CABIN -> "Building a house";
                    case HOUSE -> {
                        Blueprints.Blueprint bp = Blueprints.byName(this.plan.variant());
                        yield "Building " + (bp == null ? "a bigger house" : bp.label());
                    }
                    default -> "Building a hut";
                });
                saveProgress();
                yield true;
            }
            case MOVE -> move(bot);
            case FURNISH -> {
                if (farFromSite(bot)) yield true;
                this.task = new BuildTask(this.plan.furnish(), "Moving in");
                yield true;
            }
        };
    }

    /** Is this site by our home bed or our house (somewhere worth coming back to)? */
    private boolean anchored(BotPlayer bot, HousePlans.Plan plan) {
        for (GlobalPos g : new GlobalPos[]{this.brain.record().home(), this.brain.record().house()}) {
            if (g != null && g.dimension() == bot.level().dimension() && plan.room().distSqr(g.pos()) < 48 * 48) return true;
        }
        return false;
    }

    /** When we last started over, and how many times lately: never let it loop. */
    private final java.util.ArrayDeque<Long> restarts = new java.util.ArrayDeque<>();

    /** After starting over: a breather, and if it keeps happening, give houses a rest altogether. */
    private boolean restarted(BotPlayer bot) {
        long now = this.brain.now();
        this.restarts.addLast(now);
        while (!this.restarts.isEmpty() && now - this.restarts.peekFirst() > 20 * 60) this.restarts.removeFirst();
        if (this.restarts.size() > 4) {
            this.brain.log("settling keeps starting over: leaving the house for now");
            this.restarts.clear();
            this.brain.focusDone(BotBrain.Focus.NONE);
            cooldown(now, 20 * 60 * 10);
            return false;
        }
        cooldown(now, 20 * 5);
        return false;
    }

    // --- choosing --------------------------------------------------------------------

    private boolean choose(BotPlayer bot) {
        if (this.forced != null) return chooseForced(bot);
        WorldView w = new WorldView(bot.level());
        // Picking up a hut or cave we started before a restart?
        var saved = this.brain.memory().housePlan;
        if (saved != null && this.plan == null) {
            this.brain.memory().housePlan = null;
            try {
                this.plan = HousePlans.rebuild(bot, saved.type(), saved.room(), saved.forward());
                this.failures = saved.failures();
                this.stage = "MOVE".equals(saved.stage()) ? Stage.MOVE : Stage.GATHER;
                this.upgrade = this.brain.record().house() != null && !"CABIN".equals(this.brain.memory().homeType);
                this.brain.memory().places().getOrDefault("old_storage", List.of()).forEach(p -> this.oldStorage.add(p.pos().pos()));
                this.brain.log("back to the house I was working on at " + saved.room().toShortString());
                saveProgress();
                return true;
            } catch (IllegalArgumentException e) {
                this.plan = null;
            }
        }
        GlobalPos home = this.brain.record().home();
        GlobalPos house = this.brain.record().house();
        // Outgrown the house too? Two storeys.
        if (house != null && "HOUSE".equals(this.brain.memory().homeType)) {
            BlockPos near = home != null && home.dimension() == bot.level().dimension() ? home.pos() : house.pos();
            for (Blueprints.Blueprint bp : Blueprints.LARGE) {
                this.plan = Blueprints.find(bot, near, 40, bp);
                if (this.plan != null) break;
            }
            if (this.plan == null) return fail(bot, "no room near home for a bigger house");
            beginUpgrade(bot, near);
            this.brain.log("building " + Blueprints.byName(this.plan.variant()).label() + " at " + this.plan.room().toShortString() + " (the house is too small now)");
            return true;
        }
        // Outgrown the cabin (the chests are full of stuff)? A bigger house, with a storage
        // room, next to it: the kind this bot likes best that fits.
        if (house != null && "CABIN".equals(this.brain.memory().homeType)) {
            BlockPos near = home != null && home.dimension() == bot.level().dimension() ? home.pos() : house.pos();
            for (Blueprints.Blueprint bp : Blueprints.preferred(this.brain.personality())) {
                this.plan = Blueprints.find(bot, near, 40, bp);
                if (this.plan != null) break;
            }
            if (this.plan == null) return fail(bot, "no room near home for a bigger house");
            beginUpgrade(bot, near);
            this.brain.log("building " + Blueprints.byName(this.plan.variant()).label() + " at " + this.plan.room().toShortString() + " (the cabin's too small now)");
            return true;
        }
        // Already have a home, but not one we built? Time for a proper house nearby.
        if (house != null && !"CABIN".equals(this.brain.memory().homeType)) {
            BlockPos near = home != null && home.dimension() == bot.level().dimension() ? home.pos() : house.pos();
            this.plan = HousePlans.findCabin(bot, near, 40);
            if (this.plan == null) return fail(bot, "no room near home for a house");
            beginUpgrade(bot, near);
            this.brain.log("building a proper house at " + this.plan.room().toShortString() + " to replace my " + (this.brain.memory().homeType.isEmpty() ? "old place" : this.brain.memory().homeType.toLowerCase(java.util.Locale.ROOT)));
            this.stage = Stage.GATHER;
            saveProgress();
            return true;
        }
        // Builders would rather make their own than squat in a villager's.
        net.joinedthegame.ai.Personality traits = this.brain.personality();
        if (traits.diligent() + traits.social() * 0.5 > 0.9 || traits.builder() > 0.6) this.plan = HousePlans.findCabin(bot, bot.blockPosition(), 40);
        if (this.plan != null) {
            this.brain.log("building my own house at " + this.plan.room().toShortString());
            this.stage = Stage.GATHER;
            saveProgress();
            return true;
        }
        // Already sleeping somewhere indoors (a village bed we took earlier)? That'll do.
        if (home != null && home.dimension() == bot.level().dimension() && w.state(home.pos()).is(BlockTags.BEDS)
                && HousePlans.isIndoors(w, home.pos())) {
            this.plan = HousePlans.village(bot, home.pos());
        }
        if (this.plan == null) {
            BlockPos villageBed = Homes.findFreeBed(bot.level(), bot.blockPosition(), 64,
                    p -> !HousePlans.isIndoors(w, p) || this.brain.isBlacklisted(p));
            if (villageBed != null) {
                BotManager.get(bot.level().getServer()).setHome(this.brain.record(), GlobalPos.of(bot.level().dimension(), villageBed));
                this.plan = HousePlans.village(bot, villageBed);
                this.brain.log("moving into a village house at " + villageBed.toShortString());
                this.brain.rememberPlace(bot, "village", villageBed);
            }
        }
        if (this.plan == null) this.plan = HousePlans.findCaveNear(bot, 16);
        if (this.plan == null) this.plan = HousePlans.findHut(bot);
        if (this.plan == null) return fail(bot, "nowhere to build");
        this.brain.log("settling: " + this.plan.type() + " at " + this.plan.room().toShortString());
        this.stage = Stage.GATHER;
        saveProgress();
        return true;
    }

    /** Asked for a particular house (the /jtg build command): drop whatever was planned. */
    private @Nullable String forced;

    public void force(BotPlayer bot, String which) {
        if (this.task != null) this.task.stop(bot, this.brain);
        reset();
        this.forced = which;
    }

    /** The forced house, near home (or here if homeless). */
    private boolean chooseForced(BotPlayer bot) {
        String which = this.forced;
        this.forced = null;
        GlobalPos home = this.brain.record().home();
        BlockPos near = home != null && home.dimension() == bot.level().dimension() ? home.pos() : bot.blockPosition();
        if (which.equals("cabin")) {
            this.plan = HousePlans.findCabin(bot, near, 40);
        } else {
            this.plan = Blueprints.find(bot, near, 40, Blueprints.byName(which));
        }
        if (this.plan == null) return fail(bot, "no room for a " + which.replace('_', ' ') + " round here");
        if (this.brain.record().house() != null || home != null) {
            beginUpgrade(bot, near);
        } else {
            this.stage = Stage.GATHER;
            saveProgress();
        }
        this.brain.log("building a " + which.replace('_', ' ') + " at " + this.plan.room().toShortString() + " (asked to)");
        return true;
    }

    /** Moving up from the old place: note its chests (to empty into the new one later). */
    private void beginUpgrade(BotPlayer bot, BlockPos near) {
        this.upgrade = true;
        this.oldStorage.clear();
        this.oldStorage.addAll(Homes.storageNear(bot.level(), near));
        GlobalPos stash = this.brain.record().stash();
        if (stash != null && stash.dimension() == bot.level().dimension()) this.oldStorage.add(stash.pos());
        this.brain.memory().places().remove("old_storage");
        for (BlockPos p : this.oldStorage) this.brain.rememberPlace(bot, "old_storage", p);
        this.stage = Stage.GATHER;
        saveProgress();
    }

    /**
     * A cabin's done the job, but there's more stuff than it holds: time for a proper house.
     * (About two chests' worth stored, or the chests at home are full.)
     */
    public static boolean outgrown(BotPlayer bot, BotBrain brain) {
        String type = brain.memory().homeType;
        int limit = "CABIN".equals(type) ? 200 : "HOUSE".equals(type) ? 600 : -1;
        if (limit < 0) return false;
        int stored = brain.memory().stored.values().stream().mapToInt(Integer::intValue).sum();
        // Builders can't wait to build something bigger.
        return stored >= limit * (1 - brain.personality().builder() * 0.8);
    }

    // --- gathering ----------------------------------------------------------------------

    private boolean gather(BotPlayer bot) {
        HousePlans.Plan plan = this.plan;
        if (++this.gatherRounds > 25 + plan.needs().size() * 8) return fail(bot, "couldn't get everything together");
        this.currentNeed = null;

        // Materials for a proper house: check the chests at home before chopping a forest.
        // (Only if there's building left to do: back here after the walls are up, say for
        // a hiccup moving in, it shouldn't go and gather a whole house's worth again.)
        boolean built = buildDone(bot, plan);
        for (Tasks.Need need : built ? List.<Tasks.Need>of() : stillNeeded(bot, plan)) {
            if (this.skipped.contains(need.label()) || need.missing(bot) <= 0) continue;
            this.currentNeed = need;
            if (FetchTask.stored(bot, this.brain, need.have()) > 0 && this.fetched.add(need.label() + "#" + this.gatherRounds / 4)) {
                Task fetch = FetchTask.from(bot, this.brain, need.have(), need.missing(bot), "Getting " + need.label() + " from my chests");
                if (fetch != null) {
                    this.task = fetch;
                    return true;
                }
            }
            boolean[] failed = {false};
            Task step = Tasks.toward(bot, need, failed);
            if (step != null) {
                this.task = step;
                return true;
            }
            if (failed[0]) {
                if (need.optional()) {
                    this.skipped.add(need.label());
                    continue;
                }
                return fail(bot, "can't get " + need.label());
            }
        }
        this.currentNeed = null;

        // Building blocks for a hut.
        if (!built && plan.blocksNeeded() > 0 && Inv.scaffoldCount(bot) < plan.blocksNeeded() + 4) {
            int short_ = plan.blocksNeeded() + 4 - Inv.scaffoldCount(bot);
            this.task = new MineTask(bot, Sources.STONE, Math.min(short_, 64), "Gathering building blocks");
            return true;
        }
        // Only what isn't already in place (on a retry the bed may be in already).
        int bedsNeeded = stillToPlace(bot, plan, new ItemStack(Items.BED.red()));
        int chestsNeeded = stillToPlace(bot, plan, new ItemStack(Items.CHEST));
        int signsNeeded = stillToPlace(bot, plan, new ItemStack(Items.OAK_SIGN));

        // A bed to bring.
        if (bedsNeeded > 0 && Inv.count(bot, s -> s.is(ItemTags.BEDS)) == 0) {
            GlobalPos old = this.brain.record().home();
            if (!this.packFailed && old != null && old.dimension() == bot.level().dimension()
                    && bot.level().isLoaded(old.pos()) && bot.level().getBlockState(old.pos()).is(BlockTags.BEDS)
                    && old.pos().distSqr(bot.blockPosition()) < 160 * 160) {
                // Pack up the old bed and bring it along.
                this.packing = true;
                this.task = Tasks.sequence(new TravelTask(old.pos(), 4, "Heading home for my bed"), new BuildTask(List.of(BuildTask.Step.digEssential(old.pos())), "Packing up my old bed"),
                        new CollectItems(old.pos(), 4, 100));
                return true;
            }
            if (!WoolTask.hasEnough(bot)) {
                this.task = new WoolTask();
                return true;
            }
            DyeColor color = WoolTask.bestColor(bot);
            return planFor(bot, WoolTask.bed(color), 1);
        }
        // Chests: one for stuff, one to hide.
        if (Inv.count(bot, Items.CHEST) < chestsNeeded) return planFor(bot, Items.CHEST, chestsNeeded);
        // A sign for the door.
        if (signsNeeded > 0 && Inv.count(bot, s -> s.is(ItemTags.SIGNS)) == 0) {
            Item sign = signFor(bot);
            // No wood left? An oak sign it is (the planner sends us to chop some).
            return planFor(bot, sign != null ? sign : Items.OAK_SIGN, 1);
        }
        this.stage = plan.build().isEmpty() ? Stage.FURNISH : Stage.BUILD;
        return true;
    }

    /** Are the house's walls and roof all in? (Placing steps only: digging is just clearing the site.) */
    private boolean buildDone(BotPlayer bot, HousePlans.Plan plan) {
        WorldView w = new WorldView(bot.level());
        List<BlockPos> left = new java.util.ArrayList<>();
        for (BuildTask.Step step : plan.build()) {
            if (step.kind() == BuildTask.Kind.DIG) continue;
            if (!BuildTask.isDone(w, step)) left.add(step.pos());
        }
        if (!left.isEmpty()) this.brain.log("house: " + left.size() + " blocks not in yet, e.g. " + left.subList(0, Math.min(6, left.size())));
        return left.size() <= 3; // a few missed windows don't count
    }

    /**
     * The plan's materials, cut down to what the unfinished steps still need: part-way through
     * (or after a restart), gather for the blocks that are missing, not the whole house again.
     */
    private static List<Tasks.Need> stillNeeded(BotPlayer bot, HousePlans.Plan plan) {
        WorldView w = new WorldView(bot.level());
        List<BuildTask.Step> undone = new java.util.ArrayList<>();
        for (List<BuildTask.Step> list : List.of(plan.build(), plan.furnish())) {
            for (BuildTask.Step step : list) {
                if (step.item() != null && step.kind() != BuildTask.Kind.DIG && step.kind() != BuildTask.Kind.USE && !BuildTask.isDone(w, step)) undone.add(step);
            }
        }
        List<Tasks.Need> out = new java.util.ArrayList<>();
        for (Tasks.Need need : plan.needs()) {
            ItemStack sample = new ItemStack(need.make().apply(bot));
            int left = 0;
            for (BuildTask.Step step : undone) if (step.item().test(sample)) left++;
            int count = Math.min(need.count(), left == 0 ? 0 : left + 2);
            if (count > 0) out.add(new Tasks.Need(need.label(), need.have(), count, need.make(), need.optional()));
        }
        return out;
    }

    /** How many furnishing steps for this kind of item haven't been placed yet. */
    private static int stillToPlace(BotPlayer bot, HousePlans.Plan plan, ItemStack sample) {
        int n = 0;
        for (BuildTask.Step step : plan.furnish()) {
            if (step.kind() != BuildTask.Kind.PLACE || step.item() == null || !step.item().test(sample)) continue;
            // Done only if the right thing is actually there: a cave's chest spot is still
            // solid rock before it's dug out, which doesn't mean the chest is placed.
            var state = bot.level().getBlockState(step.pos());
            if (!step.item().test(new ItemStack(state.getBlock().asItem()))) n++;
        }
        return n;
    }

    private boolean planFor(BotPlayer bot, Item item, int count) {
        Planner.Step step = new Planner(bot).next(item, count);
        this.task = switch (step) {
            case null -> null;
            case Planner.Craft craft -> new CraftTask(craft);
            case Planner.Mine mine -> new MineTask(bot, mine.source(), mine.count(), mine.label());
            case Planner.Smelt smelt -> new SmeltTask(bot, smelt.input(), smelt.count(), smelt.output(), smelt.label());
            case Planner.Fetch fetch -> new net.joinedthegame.ai.task.FetchFromHome(fetch);
            case Planner.Fail fail -> null;
        };
        if (this.task == null && step instanceof Planner.Fail) return fail(bot, "can't get " + Planner.name(item));
        return true;
    }

    /** A sign made from whichever wood we've got most of (signs need one wood type). */
    private static @Nullable Item signFor(BotPlayer bot) {
        Item best = null;
        int most = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.is(ItemTags.PLANKS) && !stack.is(ItemTags.LOGS)) continue;
            String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            String wood = path.replace("_planks", "").replace("stripped_", "").replace("_log", "").replace("_stem", "").replace("_wood", "");
            Item sign = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(wood + "_sign"));
            if (sign == Items.AIR) continue;
            if (stack.getCount() > most) {
                most = stack.getCount();
                best = sign;
            }
        }
        return best;
    }

    // --- finishing ----------------------------------------------------------------------

    private boolean finish(BotPlayer bot) {
        HousePlans.Plan plan = this.plan;
        BotManager manager = BotManager.get(bot.level().getServer());
        BlockPos bedHead = Homes.headOf(bot.level(), plan.bedFoot());
        if (!bot.level().getBlockState(bedHead).is(BlockTags.BEDS)) return fail(bot, "bed didn't go in");
        var dim = bot.level().dimension();
        manager.setHome(this.brain.record(), GlobalPos.of(dim, bedHead));
        BlockPos stash = plan.stash() != null && bot.level().getBlockState(plan.stash()).is(net.minecraft.world.level.block.Blocks.CHEST) ? plan.stash() : null;
        manager.setHouse(this.brain.record(), GlobalPos.of(dim, plan.room()), stash == null ? null : GlobalPos.of(dim, stash));
        if (stash != null) this.brain.rememberPlace(bot, "stash", stash);
        if (plan.type() != HousePlans.Type.EXISTING) writeSign(bot, plan);
        String kind = switch (plan.type()) {
            case HUT -> "hut I built";
            case CAVE -> "cave home I dug";
            case EXISTING -> "house I took over";
            case CABIN -> "house I built myself";
            case HOUSE -> {
                Blueprints.Blueprint bp = Blueprints.byName(plan.variant());
                yield (bp == null ? "bigger house" : bp.label().replace("a ", "")) + " I built myself";
            }
        };
        this.brain.remember(net.joinedthegame.ai.BotMemory.EventType.SETTLED, kind, GlobalPos.of(dim, plan.room()), 1f);
        if (plan.type() == HousePlans.Type.CABIN) this.brain.remember(net.joinedthegame.ai.BotMemory.EventType.BUILT, "a house", GlobalPos.of(dim, plan.room()), 1f);
        if (plan.type() == HousePlans.Type.HOUSE) {
            Blueprints.Blueprint bp = Blueprints.byName(plan.variant());
            this.brain.remember(net.joinedthegame.ai.BotMemory.EventType.BUILT, bp == null ? "a bigger house" : bp.label(), GlobalPos.of(dim, plan.room()), 1f);
        }
        this.brain.memory().homeType = plan.type() == HousePlans.Type.HOUSE && Blueprints.isLarge(plan.variant()) ? "LARGE" : plan.type().name();
        this.brain.rememberPlace(bot, "home", bedHead);
        net.joinedthegame.ai.Pets.moveHouse(bot, this.brain); // the dogs come too, to the new yard
        if (this.upgrade && !this.oldStorage.isEmpty()) {
            // New house, old chests full of stuff: carry it all over.
            this.stage = Stage.MOVE;
            this.moveRounds = 0;
            saveProgress();
            return true;
        }
        this.brain.memory().housePlan = null;
        this.brain.focusDone(BotBrain.Focus.HOUSE);
        reset();
        return false;
    }

    /** A long way from the building site (out mining, say)? Walk back first. */
    private boolean farFromSite(BotPlayer bot) {
        if (this.plan.room().distSqr(bot.blockPosition()) <= 40 * 40) return false;
        this.task = new TravelTask(this.plan.room(), 8, "Heading to my building site");
        this.traveling = true;
        return true;
    }

    // --- moving house ---------------------------------------------------------------------

    /** Empty the old chests a bagful at a time; storing it in the new ones is DepositGoal's job. */
    private boolean move(BotPlayer bot) {
        this.oldStorage.removeIf(p -> !(bot.level().getBlockEntity(p) instanceof net.minecraft.world.Container c) || c.isEmpty());
        if (this.oldStorage.isEmpty() || ++this.moveRounds > 10) {
            this.brain.log("moved my stuff into the new house");
            this.brain.memory().places().remove("old_storage");
            this.brain.memory().housePlan = null;
            this.brain.focusDone(BotBrain.Focus.HOUSE);
            reset();
            return false;
        }
        if (Inv.usedSlots(bot) >= 27) {
            // Bag's full: go put it away first (storing outranks this), then come back.
            cooldown(this.brain.now(), 20 * 10);
            return false;
        }
        this.task = FetchTask.emptying(this.oldStorage, "Moving my stuff to the new house");
        return true;
    }

    /** "MossyMango's House" on the sign by the door. */
    private void writeSign(BotPlayer bot, HousePlans.Plan plan) {
        List<BlockPos> spots = HousePlans.signSpots(plan.room(), plan.forward());
        // The planned spot didn't take (solid hillside there, say)? Try the others.
        boolean placed = spots.stream().anyMatch(p -> bot.level().getBlockEntity(p) instanceof SignBlockEntity);
        if (!placed && Inv.find(bot, s -> s.is(ItemTags.SIGNS)) >= 0) {
            for (BlockPos p : spots) {
                if (!bot.level().getBlockState(p).canBeReplaced()) continue;
                if (net.joinedthegame.ai.skill.Placer.place(bot, p, s -> s.is(ItemTags.SIGNS))) break;
            }
        }
        for (BlockPos pos : spots) {
            if (bot.level().getBlockEntity(pos) instanceof SignBlockEntity sign) {
                String name = this.brain.record().name();
                sign.setText(sign.getText(SignTextSlot.FRONT).asMutable()
                        .setLine(1, Component.literal(name))
                        .setLine(2, Component.literal("'s House"))
                        .asImmutable(), SignTextSlot.FRONT);
                sign.setWaxed(true);
                this.brain.log("put up a sign at " + pos.toShortString());
                return;
            }
        }
        this.brain.log("no sign by the door (couldn't place one)");
    }

    private boolean fail(BotPlayer bot, String why) {
        this.brain.log("settling failed: " + why);
        boolean started = this.plan != null && (this.stage == Stage.BUILD || this.stage == Stage.FURNISH || this.stage == Stage.MOVE);
        if (started && this.failures < 2) {
            // Don't walk away from a half-built house: regroup and finish the same one.
            this.failures++;
            this.stage = Stage.GATHER;
            this.gatherRounds = 0;
            this.task = null;
            saveProgress();
            cooldown(this.brain.now(), 20 * 20);
            return false;
        }
        if (this.plan != null && this.plan.type() == HousePlans.Type.EXISTING) this.brain.blacklist(this.plan.room());
        reset();
        if (++this.failures >= 3) {
            // Give it a rest; maybe go do something else for a while.
            this.failures = 0;
            this.brain.focusDone(BotBrain.Focus.NONE);
        }
        cooldown(this.brain.now(), 20 * 60 * 2);
        return false;
    }

    /**
     * Note the hut or cave in long-term memory so a restart doesn't throw it away. (Village
     * houses don't need it: the bed claim already remembers those.)
     */
    private void saveProgress() {
        if (this.plan == null || this.plan.type() == HousePlans.Type.EXISTING) return;
        this.brain.memory().housePlan = new net.joinedthegame.ai.BotMemory.HousePlan(
                this.plan.key(), this.plan.room(), this.plan.forward(), this.stage.name(), this.failures);
    }

    private void reset() {
        this.brain.memory().housePlan = null;
        this.upgrade = false;
        this.oldStorage.clear();
        this.skipped.clear();
        this.fetched.clear();
        this.currentNeed = null;
        this.gatherFailures = 0;
        this.moveRounds = 0;
        this.packing = false;
        this.packFailed = false;
        this.traveling = false;
        this.gatherRounds = 0;
        this.furnishRetries = 0;
        this.stage = Stage.CHOOSE;
        this.plan = null;
        this.task = null;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        if (this.task != null) this.task.stop(bot, this.brain);
        this.task = null; // the stage is kept, so we pick up where we left off
        this.traveling = false;
        this.packing = false;
    }

    @Override
    public String activity() {
        Task task = this.task;
        if (task != null) return task.activity();
        return switch (this.stage) {
            case CHOOSE -> "Looking for a place to live";
            case GATHER -> "Getting ready to move";
            case BUILD -> "Building a home";
            case FURNISH -> "Moving in";
            case MOVE -> "Moving my stuff over";
        };
    }
}
