package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Planner;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.task.BurrowTask;
import net.joinedthegame.ai.task.CraftTask;
import net.joinedthegame.ai.task.MineTask;
import net.joinedthegame.ai.task.SmeltTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.WoolTask;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.Homes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Every bot wants somewhere to sleep. In order of laziness:
 * <ol>
 *   <li>Take a free bed nearby. A villager's counts: the bot just moves into their house.</li>
 *   <li>Otherwise get a bed (whack sheep for wool, craft it) and dig a burrow for it, into
 *       a hillside or down into the ground.</li>
 * </ol>
 * The bed becomes the bot's home: it sleeps and respawns there and stores loot in chests
 * around it.
 */
public final class HomeGoal extends Goal {
    private static final int SEARCH_RADIUS = 48;

    private @Nullable Task task;
    private @Nullable BlockPos claiming;
    private int ticks;

    public HomeGoal(BotBrain brain) {
        super(brain);
    }

    private boolean hasHome(BotPlayer bot) {
        GlobalPos home = this.brain.record().home();
        if (home == null) return false;
        if (home.dimension() != bot.level().dimension()) return true; // can't check from here
        if (!bot.level().isLoaded(home.pos())) return true;
        if (bot.level().getBlockState(home.pos()).is(BlockTags.BEDS)) return true;
        // The bed is gone (broken, or taken back): homeless again.
        BotManager.get(bot.level().getServer()).setHome(this.brain.record(), null);
        return false;
    }

    @Override
    public int priority(BotPlayer bot) {
        // Mid-move (old bed packed up, new house not built yet): don't grab another bed.
        if (this.brain.settling()) return 0;
        if (hasHome(bot)) return 0;
        // Get basic tools first; nobody house-hunts with bare hands.
        if (Inv.pickaxeTier(bot) < 1) return 0;
        // A nomad carries its bed and puts it down wherever it is at night: no home to make.
        if (this.brain.roaming(bot) && Inv.count(bot, s -> s.is(ItemTags.BEDS)) > 0) return 0;
        return 45;
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
        this.task = null;
        this.claiming = null;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (hasHome(bot)) return false;
        if (++this.ticks > 20 * 60 * 5) {
            cooldown(this.brain.now(), 20 * 120);
            return false;
        }
        if (this.claiming != null) return claim(bot);

        // Got handed (or looted) a bed mid-hunt? Skip straight to digging.
        if (this.task instanceof WoolTask && Inv.count(bot, s -> s.is(ItemTags.BEDS)) > 0) {
            this.task.stop(bot, this.brain);
            this.task = null;
        }
        if (this.task != null) {
            Task.Status status = this.task.tick(bot, this.brain);
            if (status == Task.Status.RUNNING) return true;
            this.task.stop(bot, this.brain);
            this.brain.log("home task '" + this.task.activity() + "' " + status);
            this.task = null;
            if (status == Task.Status.FAILED) {
                cooldown(this.brain.now(), 20 * 90);
                return false;
            }
            return true;
        }
        return decide(bot);
    }

    private boolean decide(BotPlayer bot) {
        // 1. A free bed nearby: take it.
        BlockPos bed = Homes.findFreeBed(bot.level(), bot.blockPosition(), SEARCH_RADIUS, this.brain::isBlacklisted);
        if (bed != null) {
            this.claiming = bed;
            this.brain.log("going to claim the bed at " + bed.toShortString());
            return true;
        }
        // 2. Have a bed item: on open ground with the blocks for it, a little hut; otherwise
        //    dig a hole for it (into a hillside, or down and sideways).
        if (Inv.count(bot, s -> s.is(ItemTags.BEDS)) > 0) {
            net.joinedthegame.ai.HousePlans.Plan hut = bot.level().canSeeSky(bot.blockPosition())
                    ? net.joinedthegame.ai.HousePlans.starterHut(bot) : null;
            if (hut != null) {
                java.util.List<net.joinedthegame.ai.task.BuildTask.Step> steps = new java.util.ArrayList<>(hut.build());
                steps.add(net.joinedthegame.ai.task.BuildTask.Step.placeEssential(hut.bedFoot(), s -> s.is(ItemTags.BEDS), hut.forward()));
                steps.add(net.joinedthegame.ai.task.BuildTask.Step.place(
                        net.joinedthegame.ai.HousePlans.at(hut.room(), hut.forward(), 1, 1, 1), s -> s.is(net.minecraft.world.item.Items.TORCH)));
                this.brain.log("building a hut at " + hut.room().toShortString());
                BlockPos bedFoot = hut.bedFoot();
                this.task = net.joinedthegame.ai.task.Tasks.sequence(
                        new net.joinedthegame.ai.task.BuildTask(steps, "Building a hut"), new MoveIn(bedFoot));
                return true;
            }
            this.task = new BurrowTask();
            return true;
        }
        // 3. Have the wool: craft the bed (and whatever that needs).
        if (WoolTask.hasEnough(bot)) {
            DyeColor color = WoolTask.bestColor(bot);
            Planner.Step step = new Planner(bot).next(WoolTask.bed(color), 1);
            this.task = switch (step) {
                case null -> null;
                case Planner.Craft craft -> new CraftTask(craft);
                case Planner.Mine mine -> new MineTask(bot, mine.source(), mine.count(), mine.label());
                case Planner.Smelt smelt -> new SmeltTask(bot, smelt.input(), smelt.count(), smelt.output(), smelt.label());
                case Planner.Fetch fetch -> new net.joinedthegame.ai.task.FetchFromHome(fetch);
                case Planner.Fail fail -> null;
            };
            if (this.task == null) {
                cooldown(this.brain.now(), 20 * 90);
                return false;
            }
            return true;
        }
        // 4. Go get wool.
        this.task = new WoolTask();
        return true;
    }

    /** The hut's done: the bed in it is home. */
    private record MoveIn(BlockPos bedFoot) implements Task {
        @Override
        public String activity() {
            return "Moving into my hut";
        }

        @Override
        public Status tick(BotPlayer bot, net.joinedthegame.ai.BotBrain brain) {
            if (!bot.level().getBlockState(this.bedFoot).is(BlockTags.BEDS)) return Status.FAILED;
            BlockPos head = Homes.headOf(bot.level(), this.bedFoot);
            BotManager.get(bot.level().getServer()).setHome(brain.record(), GlobalPos.of(bot.level().dimension(), head));
            brain.log("built a hut to live in at " + head.toShortString());
            return Status.DONE;
        }

        @Override
        public void stop(BotPlayer bot, net.joinedthegame.ai.BotBrain brain) {}
    }

    private boolean claim(BotPlayer bot) {
        BlockPos bed = this.claiming;
        if (!bot.level().getBlockState(bed).is(BlockTags.BEDS)
                || BotManager.get(bot.level().getServer()).bedOwner(bot.level(), bed) != null) {
            this.claiming = null;
            return true;
        }
        if (!BlockBreaker.inReach(bot, bed)) {
            Navigator nav = this.brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToReach(bot, bed);
                if (nav.status() == Navigator.Status.FAILED) {
                    this.brain.blacklist(bed);
                    this.claiming = null;
                    nav.stop(bot);
                }
            }
            return true;
        }
        this.brain.navigator().stop(bot);
        this.brain.controls().lookAt(bot, Vec3.atCenterOf(bed));
        BotManager.get(bot.level().getServer()).setHome(this.brain.record(), GlobalPos.of(bot.level().dimension(), bed));
        this.brain.log("claimed the bed at " + bed.toShortString());
        this.claiming = null;
        return false;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        if (this.task != null) this.task.stop(bot, this.brain);
        this.task = null;
    }

    @Override
    public String activity() {
        if (this.claiming != null) return "Moving into a house";
        Task task = this.task;
        return task == null ? "Looking for a home" : task.activity();
    }
}
