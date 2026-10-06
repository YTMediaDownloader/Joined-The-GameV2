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
    public enum Kind { FARM, WOLF, DECORATE, BOW, RAID, VILLAGE_RAID, ENCHANT, TRIAL_CHAMBER }

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
                yield c != null && net.joinedthegame.ai.project.TrialChamberProject.notReady(bot, this.brain, false,
                        this.brain.memory().chamber(c).deaths) == null;
            }
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
        };
    }

    /** The home-life ones: everything but raiding. */
    private static boolean homely(Kind k) {
        return k != Kind.RAID && k != Kind.VILLAGE_RAID && k != Kind.TRIAL_CHAMBER;
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
        if (k == Kind.ENCHANT && !m.ever(BotMemory.EventType.BUILT, "an enchanting table")) w *= 1.5;
        return w;
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
            } catch (IllegalArgumentException ignored) {
            }
        }
        boolean homelyWaiting = homelyAvailable(bot) > 0;
        Map<Kind, Double> weights = new EnumMap<>(Kind.class);
        double total = 0;
        for (Kind k : Kind.values()) {
            if (!available(bot, k)) continue;
            double w = adjustedWeight(bot, k, homelyWaiting);
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
        this.restUntil.remove(kind);
        this.brain.memory().project = kind.name().toLowerCase(Locale.ROOT);
        this.brain.setFocus(BotBrain.Focus.PROJECT, now);
    }

    public @Nullable Kind current() {
        return this.kind;
    }

    // --- running --------------------------------------------------------------------

    @Override
    public int priority(BotPlayer bot) {
        if (this.brain.focus() != BotBrain.Focus.PROJECT) return 0;
        // In a trial chamber: committed. Above everyday gearing up and loot-grabbing (mining a bit
        // of iron on the way past isn't a reason to drop it); fights, food and emergencies still win.
        if (this.kind == Kind.TRIAL_CHAMBER && this.task != null) return 58;
        if (bot.level().isDarkOutside()) return 0;
        if (this.kind == null) {
            this.kind = pick(bot);
            if (this.kind == null) {
                this.brain.focusDone(BotBrain.Focus.NONE);
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
            };
        }
        Task.Status status = this.task.tick(bot, this.brain);
        if (status == Task.Status.RUNNING) return true;
        this.task.stop(bot, this.brain);
        this.brain.log("project " + kind + " " + status);
        this.task = null;
        this.kind = null;
        this.brain.memory().project = "";
        if (status == Task.Status.FAILED) {
            this.restUntil.put(kind, this.brain.now() + 20 * 60 * 20);
            this.brain.focusDone(BotBrain.Focus.NONE);
            cooldown(this.brain.now(), 20 * 30);
        } else {
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
        return this.task instanceof net.joinedthegame.ai.project.TrialChamberProject t && t.waitingOnTrial();
    }

    @Override
    public String activity() {
        Task task = this.task;
        return task == null ? "Thinking about what to do" : task.activity();
    }
}
