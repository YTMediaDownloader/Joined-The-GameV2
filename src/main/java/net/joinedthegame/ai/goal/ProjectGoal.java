package net.joinedthegame.ai.goal;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Personality;
import net.joinedthegame.ai.project.DecorateProject;
import net.joinedthegame.ai.project.FarmProject;
import net.joinedthegame.ai.project.WolfProject;
import net.joinedthegame.ai.task.FetchTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Personal projects: the things a player does once they're geared up that aren't about gear.
 * Which one a bot goes for is a weighted roll on its personality (steady types farm, brave
 * and outdoorsy ones want a dog, sociable ones do the place up), and each is done for real,
 * one step at a time. Only runs when the bot's focus is {@link BotBrain.Focus#PROJECT}.
 */
public final class ProjectGoal extends Goal {
    public enum Kind { FARM, WOLF, DECORATE, BOW, RAID, VILLAGE_RAID, ENCHANT, TRIAL_CHAMBER, NETHERITE, PEN, TREES, CANE, GARDEN, PATHS, BREW }

    private @Nullable Kind kind;
    private @Nullable Task task;
    /** Projects that just failed get left alone for a while. */
    private final Map<Kind, Long> restUntil = new EnumMap<>(Kind.class);

    public ProjectGoal(BotBrain brain) {
        super(brain);
    }

    // --- choosing -------------------------------------------------------------------

    public boolean available(BotPlayer bot, Kind kind) {
        Long rest = this.restUntil.get(kind);
        if (rest != null && rest > this.brain.now()) return false;
        GlobalPos home = this.brain.record().home();
        boolean hasHome = home != null && home.dimension() == bot.level().dimension();
        BotMemory memory = this.brain.memory();
        return switch (kind) {
            case FARM -> hasHome && memory.nearest("farm", home, 64) == null;
            case WOLF -> !WolfProject.hasPet(this.brain)
                    && Inv.count(bot, Items.BONE) + FetchTask.stored(bot, this.brain, s -> s.is(Items.BONE)) >= 3;
            case DECORATE -> hasHome && this.brain.record().house() != null && !memory.ever(BotMemory.EventType.BUILT, "decorations");
            case BOW -> Inv.count(bot, Items.BOW) + Inv.count(bot, Items.CROSSBOW) == 0;
            case RAID -> net.joinedthegame.ai.project.RaidProject.pick(bot, this.brain, new net.joinedthegame.ai.Structures.Kind[1]) != null;
            case VILLAGE_RAID -> net.joinedthegame.ai.project.VillageRaidProject.ready(bot, this.brain);
            case ENCHANT -> net.joinedthegame.ai.project.EnchantProject.worthIt(bot, this.brain);
            case TRIAL_CHAMBER -> {
                GlobalPos c = net.joinedthegame.ai.project.TrialChamberProject.pick(bot, this.brain);
                if (c == null) yield false;
                String why = net.joinedthegame.ai.project.TrialChamberProject.notReady(bot, this.brain, false, this.brain.memory().chamber(c).deaths);
                // Knows of one but isn't fit for it yet: say what's missing now and then, so it's
                // plain why it hasn't gone (Redlantern found one and "just didn't").
                if (why != null && this.brain.now() - this.toldChamberAt > 20 * 60 * 10) {
                    this.toldChamberAt = this.brain.now();
                    this.brain.log("I know a trial chamber at " + c.pos().toShortString() + ", but I'm not ready for it yet (" + why + ")");
                }
                yield why == null;
            }
            // Late game: a diamond pickaxe and a real diamond loadout worth upgrading.
            case NETHERITE -> net.joinedthegame.ai.project.NetheriteProject.worthIt(bot);
            case PEN -> net.joinedthegame.ai.project.PenProject.available(bot, this.brain);
            case TREES -> net.joinedthegame.ai.project.TreeFarmProject.available(bot, this.brain);
            case CANE -> net.joinedthegame.ai.project.CaneProject.available(bot, this.brain);
            case GARDEN -> net.joinedthegame.ai.project.GardenProject.available(bot, this.brain);
            case PATHS -> net.joinedthegame.ai.project.PathsProject.available(bot, this.brain);
            case BREW -> net.joinedthegame.ai.project.BrewProject.worthIt(bot, this.brain);
        };
    }

    private double weight(BotPlayer bot, Kind kind) {
        Personality p = this.brain.personality();
        return switch (kind) {
            case FARM -> 0.5 + p.diligent() * 0.5 + p.social() * 0.2 + p.homebody() * 0.6 + p.animalLover() * 0.4;
            case WOLF -> 0.3 + p.brave() * 0.4 + p.explorer() * 0.3 + p.animalLover() * 1.5;
            case DECORATE -> 0.2 + p.social() * 0.5 + (1 - p.reckless()) * 0.2 + p.homebody() * 0.9 + p.builder() * 0.8;
            case BOW -> 0.2 + p.brave() * 0.2 + p.explorer() * 0.2;
            case RAID -> 0.3 + p.brave() * 0.5 + p.explorer() * 0.4 + p.reckless() * 0.2;
            case VILLAGE_RAID -> 0.2 + p.brave() * 0.8 + p.reckless() * 0.3;
            // Everyone wants their gear enchanted; levels piling up make it pressing.
            case ENCHANT -> 0.5 + p.diligent() * 0.4 + Math.min(1.0, bot.experienceLevel / 30.0) + p.scholar() * 1.5;
            // Adventure: the brave, the curious and the travellers; homebodies and builders less so.
            case TRIAL_CHAMBER -> Math.max(0.1, 0.4 + p.brave() * 0.6 + p.explorer() * 0.4 + p.reckless() * 0.3 + p.nomad() * 0.4
                    - p.homebody() * 0.3 - p.builder() * 0.2);
            // Everyone who gets this far wants it; diggers and the dogged most of all. Half way
            // there already (a template or an ingot put by) makes it pressing.
            case NETHERITE -> 0.6 + p.miner() * 0.6 + p.diligent() * 0.4 + p.brave() * 0.3
                    + (Inv.count(bot, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE) + Inv.count(bot, Items.NETHERITE_INGOT) > 0 ? 1.0 : 0);
            case PEN -> 0.4 + p.animalLover() * 1.0 + p.homebody() * 0.4 + p.diligent() * 0.2;
            case TREES -> 0.3 + p.diligent() * 0.3 + p.builder() * 0.4 + p.homebody() * 0.2;
            case CANE -> 0.2 + p.scholar() * 0.5 + p.diligent() * 0.2 + p.homebody() * 0.2;
            case GARDEN -> 0.2 + p.builder() * 0.6 + p.homebody() * 0.5 + p.social() * 0.2;
            case PATHS -> 0.2 + p.builder() * 0.6 + p.diligent() * 0.3 + p.homebody() * 0.3;
            // The studious like the craft of it; the brave like what it's for (fights).
            case BREW -> 0.5 + p.scholar() * 1.0 + p.brave() * 0.4 + p.diligent() * 0.3;
        };
    }

    /**
     * Possible now, and safe enough to set out on: the home-life ones whenever they're possible;
     * raids, trial chambers and netherite only once the bot's geared for them (about iron). This
     * replaces the old "nothing at all before full iron" rule: a farm or a dog can come early.
     */
    public boolean ready(BotPlayer bot, Kind kind) {
        if (!available(bot, kind)) return false;
        return homely(kind) || this.brain.needs().level(net.joinedthegame.ai.life.Needs.Need.GEAR) <= 0.45;
    }

    /** The most appealing project that's ready, by the needs it meets (0 if none). */
    private long toldChamberAt = Long.MIN_VALUE / 2;

    public double bestAppeal(BotPlayer bot) {
        double best = 0;
        for (Kind k : Kind.values()) {
            if (!ready(bot, k)) continue;
            double a = net.joinedthegame.ai.life.Activities.appeal(k, this.brain.needs(), this.brain.personality());
            if (this.brain.memory().unfinished.contains(k.name().toLowerCase(Locale.ROOT))) a += 0.4; // started, never finished
            // A trial chamber found close by, never done, and we're fit for it: that's exciting, not
            // one more thing on a list. (It sat at 0.4 against diamonds' 1.0 and lost every roll.)
            if (k == Kind.TRIAL_CHAMBER) {
                GlobalPos c = net.joinedthegame.ai.project.TrialChamberProject.pick(bot, this.brain);
                if (c != null && c.pos().distSqr(bot.blockPosition()) < 200 * 200 && this.brain.memory().chamber(c).deaths == 0) a += 0.8;
            }
            best = Math.max(best, a);
        }
        return best;
    }

    /** The home-life ones: everything but raiding. */
    public static boolean homely(Kind k) {
        return k != Kind.RAID && k != Kind.VILLAGE_RAID && k != Kind.TRIAL_CHAMBER && k != Kind.NETHERITE;
    }

    public int homelyAvailable(BotPlayer bot) {
        int n = 0;
        for (Kind k : Kind.values()) if (homely(k) && available(bot, k)) n++;
        return n;
    }

    /**
     * The roll's weight, adjusted: raiding counts for less while there are homely things to do,
     * and things never done before (a first dog, a first enchanting table) pull harder.
     */
    private double adjustedWeight(BotPlayer bot, Kind k, boolean homelyWaiting) {
        double w = weight(bot, k);
        if (!homely(k) && homelyWaiting) w *= 0.5;
        if (!homely(k)) w *= 1 - this.brain.personality().homebody() * 0.5;
        BotMemory m = this.brain.memory();
        if (k == Kind.WOLF && !m.ever(BotMemory.EventType.TAMED, "wolf")) w *= 2.0;
        // Lost a pet lately (killed, or just died): getting a new one pulls much harder.
        if (k == Kind.WOLF) {
            for (BotMemory.Event e : m.events()) {
                if (e.type() == BotMemory.EventType.PET_DIED && this.brain.now() - Math.max(e.time(), e.last()) < 24000L * 2) {
                    w *= 2.5;
                    break;
                }
            }
        }
        if (k == Kind.ENCHANT && !m.ever(BotMemory.EventType.BUILT, "an enchanting table")) w *= 1.5;
        return w;
    }

    /** An unfinished project that could be picked up again right now. */
    public boolean unfinishedAvailable(BotPlayer bot) {
        for (String s : this.brain.memory().unfinished) {
            try {
                if (available(bot, Kind.valueOf(s.toUpperCase(Locale.ROOT)))) return true;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return false;
    }

    public boolean anyAvailable(BotPlayer bot) {
        for (Kind k : Kind.values()) if (available(bot, k)) return true;
        return false;
    }

    private @Nullable Kind pick(BotPlayer bot) {
        // Carry on with one we started before a restart.
        String saved = this.brain.memory().project;
        if (!saved.isEmpty()) {
            try {
                Kind k = Kind.valueOf(saved.toUpperCase(Locale.ROOT));
                if (available(bot, k)) return k;
                this.brain.memory().setAside(saved); // can't carry on right now: not forgotten, though
            } catch (IllegalArgumentException ignored) {
            }
        }
        // Something we started before and never finished: get back to it, most recent first.
        var unfinished = this.brain.memory().unfinished;
        for (int i = unfinished.size() - 1; i >= 0; i--) {
            try {
                Kind k = Kind.valueOf(unfinished.get(i).toUpperCase(Locale.ROOT));
                if (ready(bot, k)) {
                    this.brain.log("getting back to an unfinished project: " + k);
                    return k;
                }
            } catch (IllegalArgumentException ignored) {
                unfinished.remove(i);
            }
        }
        boolean homelyWaiting = homelyAvailable(bot) > 0;
        Map<Kind, Double> weights = new EnumMap<>(Kind.class);
        double total = 0;
        for (Kind k : Kind.values()) {
            if (!ready(bot, k)) continue;
            // Personality's taste for it, times how much it meets what the bot needs right now.
            double w = adjustedWeight(bot, k, homelyWaiting)
                    * (0.2 + net.joinedthegame.ai.life.Activities.appeal(k, this.brain.needs(), this.brain.personality()));
            weights.put(k, w);
            total += w;
        }
        if (total <= 0) return null;
        double roll = bot.getRandom().nextDouble() * total;
        for (Map.Entry<Kind, Double> e : weights.entrySet()) {
            if ((roll -= e.getValue()) < 0) return e.getKey();
        }
        return null;
    }

    /** Start a particular project now (the /jtg project command). */
    public void force(Kind kind, long now) {
        if (this.task != null && this.kind != kind) this.task = null;
        this.kind = kind;
        this.forced = true;
        this.restUntil.remove(kind);
        // Told to go: the "come back to it later" on each chamber doesn't apply.
        if (kind == Kind.TRIAL_CHAMBER) {
            for (var c : this.brain.memory().chambers()) c.restUntil = 0;
        }
        this.brain.memory().project = kind.name().toLowerCase(Locale.ROOT);
        this.brain.setFocus(BotBrain.Focus.PROJECT, now);
    }

    /** A project's actually under way (not just picked), or someone told it to do this one. */
    public boolean running() {
        return this.task != null || this.forced;
    }

    /** Started by the /jtg project command: the "full iron first" rule leaves it alone. */
    private boolean forced;

    public @Nullable Kind current() {
        return this.kind;
    }

    // --- running --------------------------------------------------------------------

    /** What this project wants out of the chests: its keep list, or less where that's broad (brewing: this recipe's). */
    private boolean wantedForKit(BotPlayer bot, Kind kind, net.minecraft.world.item.ItemStack s) {
        if (kind == Kind.BREW) return net.joinedthegame.ai.project.BrewProject.fetchFor(bot, this.brain, s);
        return net.joinedthegame.ai.Junk.forTheJob(this.brain, s) > Inv.count(bot, s.getItem());
    }

    /**
     * The trip to the chests for the project's things, going by the remembered list of what's in
     * them (Storage), so it works from anywhere: far from home, walk back first. Null if nothing
     * it needs is put away.
     */
    private @Nullable Task kitFetch(BotPlayer bot, Kind kind) {
        // At home: a fresh look in the chests first (someone may have put something in since).
        net.joinedthegame.ai.Storage.refreshIfHome(bot, this.brain);
        boolean anyStored = false;
        for (var e : this.brain.memory().stored.entrySet()) {
            if (e.getValue() <= 0) continue;
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(e.getKey()));
            if (wantedForKit(bot, kind, new net.minecraft.world.item.ItemStack(item))) {
                anyStored = true;
                break;
            }
        }
        // The secret stash too (lapis, gold, enchanted books...): it isn't on the remembered list,
        // so only when home's close enough to look. (Lapis went into the stash and never came out:
        // enchanting mined more instead.)
        if (!anyStored && FetchTask.inStash(bot, this.brain, s -> wantedForKit(bot, kind, s))
                - FetchTask.stored(bot, this.brain, s -> wantedForKit(bot, kind, s)) > 0) anyStored = true;
        if (!anyStored) return null;
        GlobalPos home = this.brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return null;
        this.brain.log("project " + kind + ": getting my things for it out of the chests");
        Task fetch = net.joinedthegame.ai.task.Tasks.anyway(
                net.joinedthegame.ai.task.Tasks.lazy(() -> FetchTask.from(bot, this.brain, s -> wantedForKit(bot, kind, s),
                        64 * 6, "Getting my things out for it")),
                net.joinedthegame.ai.task.Tasks.lazy(() -> stashHas(bot, kind) ? FetchTask.fromStash(bot, this.brain,
                        s -> wantedForKit(bot, kind, s), 64, "Getting my things out of the stash for it") : null));
        if (home.pos().distSqr(bot.blockPosition()) <= 64 * 64) return fetch;
        return net.joinedthegame.ai.task.Tasks.anyway(new net.joinedthegame.ai.task.TravelTask(home.pos(), 6, "Heading home for my things"), fetch);
    }

    /** Anything for this project in the secret stash (not counting the ordinary chests)? */
    private boolean stashHas(BotPlayer bot, Kind kind) {
        return FetchTask.inStash(bot, this.brain, s -> wantedForKit(bot, kind, s))
                - FetchTask.stored(bot, this.brain, s -> wantedForKit(bot, kind, s)) > 0;
    }

    @Override
    public int priority(BotPlayer bot) {
        // Standing in a trial chamber and fit for it: that's the thing to do now, whatever today's
        // plan said. (Otherwise chest-looting walked it round the corridors setting spawners off and
        // fighting whatever came, never taking a spawner on or opening a vault.)
        // (Any time of day: a chamber's always deep underground, night or not.)
        if (this.kind != Kind.TRIAL_CHAMBER && this.brain.now() % 40 == 0
                && net.joinedthegame.ai.project.TrialChamberProject.inChamber(bot) && available(bot, Kind.TRIAL_CHAMBER)) {
            this.brain.log("I'm in a trial chamber and I'm ready for it: taking it on");
            this.task = null;
            this.kind = Kind.TRIAL_CHAMBER;
            this.brain.memory().project = this.kind.name().toLowerCase(Locale.ROOT);
            this.brain.setFocus(BotBrain.Focus.PROJECT, this.brain.now());
        }
        if (this.brain.focus() != BotBrain.Focus.PROJECT) return 0;
        // In a trial chamber: committed. Above everyday gearing up and loot-grabbing (mining a bit
        // of iron on the way past isn't a reason to drop it); fights, food and emergencies still win.
        // (Already standing in one counts too: otherwise looting its chests, at 46, beats starting.)
        if (this.kind == Kind.TRIAL_CHAMBER && (this.task != null
                || net.joinedthegame.ai.project.TrialChamberProject.inChamber(bot))) return 58;
        // Night: the surface projects (a farm, a pen, a garden) wait for morning. A trial chamber run
        // doesn't, if we're already down there: it's underground either way (Redlantern walked
        // straight through his chamber to mine diamonds, "because it was night").
        if (bot.level().isDarkOutside() && !(this.kind == Kind.TRIAL_CHAMBER
                && (net.joinedthegame.ai.goal.SleepGoal.deepUnderground(bot) || ShelterGoal.nightReady(bot)))) return 0;
        if (this.kind == null) {
            this.kind = pick(bot);
            if (this.kind == null) {
                this.brain.focusFailed(BotBrain.Focus.PROJECT);
                return 0;
            }
            this.brain.memory().project = this.kind.name().toLowerCase(Locale.ROOT);
            this.brain.log("personal project: " + this.kind);
        }
        return 45;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        Kind kind = this.kind;
        if (kind == null) return false;
        if (this.task == null) {
            this.task = switch (kind) {
                case FARM -> new FarmProject();
                case WOLF -> new WolfProject();
                case DECORATE -> new DecorateProject();
                case BOW -> new net.joinedthegame.ai.project.BowProject();
                case RAID -> new net.joinedthegame.ai.project.RaidProject();
                case VILLAGE_RAID -> new net.joinedthegame.ai.project.VillageRaidProject();
                case ENCHANT -> new net.joinedthegame.ai.project.EnchantProject();
                case TRIAL_CHAMBER -> new net.joinedthegame.ai.project.TrialChamberProject();
                case NETHERITE -> new net.joinedthegame.ai.project.NetheriteProject();
                case PEN -> new net.joinedthegame.ai.project.PenProject();
                case TREES -> new net.joinedthegame.ai.project.TreeFarmProject();
                case CANE -> new net.joinedthegame.ai.project.CaneProject();
                case GARDEN -> new net.joinedthegame.ai.project.GardenProject();
                case PATHS -> new net.joinedthegame.ai.project.PathsProject();
                case BREW -> new net.joinedthegame.ai.project.BrewProject();
            };
            // Its things are in the chests at home (everything but the everyday kit gets put away
            // there): get them out first, up to what the job keeps on hand. Otherwise a bot with
            // a chest full of leather goes hunting cows, and one with its wart put away "is out of
            // wart" (both happened).
            Task kit = kitFetch(bot, kind);
            if (kit != null) this.task = net.joinedthegame.ai.task.Tasks.anyway(kit, this.task);
        }
        Task.Status status = this.task.tick(bot, this.brain);
        if (status == Task.Status.RUNNING) return true;
        this.task.stop(bot, this.brain);
        this.brain.log("project " + kind + " " + status);
        this.task = null;
        this.kind = null;
        this.forced = false;
        this.brain.memory().project = "";
        if (status == Task.Status.FAILED) {
            this.restUntil.put(kind, this.brain.now() + 20 * 60 * 20);
            this.brain.focusFailed(BotBrain.Focus.PROJECT);
            cooldown(this.brain.now(), 20 * 30);
        } else {
            this.brain.memory().unfinished.remove(kind.name().toLowerCase(Locale.ROOT)); // done with it
            this.brain.focusDone(BotBrain.Focus.PROJECT);
        }
        return false;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        // Keep the task: the project carries on where it left off after the interruption.
        if (this.task != null) this.task.stop(bot, this.brain);
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return this.task instanceof net.joinedthegame.ai.project.TrialChamberProject t && t.waitingOnTrial()
                || this.task instanceof net.joinedthegame.ai.project.PenProject pen && pen.waiting();
    }

    @Override
    public String activity() {
        Task task = this.task;
        return task == null ? "Thinking about what to do" : task.activity();
    }
}
