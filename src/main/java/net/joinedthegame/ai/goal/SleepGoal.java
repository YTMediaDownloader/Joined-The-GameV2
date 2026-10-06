package net.joinedthegame.ai.goal;

import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.HousePlans;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Planner;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.CraftTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.WoolTask;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.Homes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * At night, go to bed. A bed always beats digging a hole, so in order of preference:
 * <ol>
 *   <li>its own home bed,</li>
 *   <li>a free bed nearby, or in a village it remembers,</li>
 *   <li>a bed it's carrying, put down on the spot,</li>
 *   <li>a bed it can craft from wool it has.</li>
 * </ol>
 * At dusk a homeless bot with sheep in sight grabs the wool for one. Whatever bed it sleeps
 * in becomes its home: it respawns there, and when it settles down properly it packs that bed
 * up and takes it to the new house. Only when none of that works does ShelterGoal dig in.
 */
public final class SleepGoal extends Goal {
    /** How far from home a bot will still walk back to sleep. */
    public static final int HOME_RANGE = 128;
    /** How far a remembered village can be for a bot to head there for the night. */
    private static final int VILLAGE_RANGE = 96;

    private enum Plan { NONE, BED, CAMP, MAKE, WOOL, PACK }

    private Plan plan = Plan.NONE;
    private @Nullable BlockPos bed;
    private @Nullable Task task;
    private int ticks;
    private long failedAt = -100000;
    private int useAttempts;
    /** A nomad's bed for the night, away from its base (picked up again in the morning). */
    private @Nullable GlobalPos campBed;

    public SleepGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (bot.isSleeping()) return 80;
        if (this.brain.inTrialChamber(bot)) return 0;
        boolean night = bot.level().isDarkOutside();
        // Mid-way through getting a bed together: see it through (wool only until dark).
        if (this.task != null) return this.plan == Plan.WOOL ? (night ? 0 : 55) : 76;
        this.plan = Plan.NONE;
        // A nomad sleeps where night finds it: on the road, or out roaming from its base (once it
        // has one, it'll still use the bed there if it's close by).
        boolean nomad = this.brain.roaming(bot) || this.brain.personality().nomad() > 0.6 && !baseNearby(bot);
        if (!night && campBedToPack(bot) != null) {
            // Morning on the road: bed back in the bag, and on we go.
            this.plan = Plan.PACK;
            return 50;
        }
        if (!night) {
            // Dusk, nowhere to sleep, and sheep about? Get the wool for a bed before dark.
            if (bot.level().getSkyDarken() >= 2 && needsBed(bot) && !WoolTask.hasEnough(bot) && sheepNearby(bot)) {
                this.plan = Plan.WOOL;
                return 55;
            }
            return 0;
        }
        // Bed didn't work out tonight (monsters about) and we're safely dug in? Stay put.
        if (this.brain.now() - this.failedAt < 20 * 60 * 3 && net.joinedthegame.ai.task.HideTask.isHidden(bot)) return 0;
        // A nomad sleeps where night finds it: no trek back to last night's spot or a village.
        BlockPos bed = nomad ? null : homeBed(bot);
        if (bed == null && (!nomad || Inv.count(bot, s -> s.is(ItemTags.BEDS)) == 0)) bed = claimableBed(bot);
        if (bed == null && !nomad) bed = villageBed(bot);
        this.bed = bed;
        if (bed != null) this.plan = Plan.BED;
        else if (Inv.count(bot, s -> s.is(ItemTags.BEDS)) > 0) this.plan = Plan.CAMP;
        else if (WoolTask.hasEnough(bot) && Inv.count(bot, s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS)) > 0) this.plan = Plan.MAKE;
        else return 0;
        // Someone's already in bed waiting on us? Hurry up.
        for (var player : bot.level().players()) {
            if (player.isSleeping() && !(player instanceof BotPlayer)) return 90;
        }
        return 75; // above digging in (72): a bed always wins
    }

    /** No home bed and not carrying one. */
    private boolean needsBed(BotPlayer bot) {
        return homeBed(bot) == null && Inv.count(bot, s -> s.is(ItemTags.BEDS)) == 0;
    }

    private static boolean sheepNearby(BotPlayer bot) {
        return !bot.level().getEntitiesOfClass(Sheep.class, bot.getBoundingBox().inflate(24), s -> s.isAlive() && !s.isBaby()).isEmpty();
    }

    private @Nullable BlockPos homeBed(BotPlayer bot) {
        GlobalPos home = this.brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return null;
        if (home.pos().distSqr(bot.blockPosition()) > (double) HOME_RANGE * HOME_RANGE) return null;
        if (!bot.level().isLoaded(home.pos())) return null;
        BlockState state = bot.level().getBlockState(home.pos());
        if (!state.is(BlockTags.BEDS)) {
            // Bed was broken: homeless again.
            BotManager.get(bot.level().getServer()).setHome(this.brain.record(), null);
            return null;
        }
        return home.pos();
    }

    private @Nullable BlockPos claimableBed(BotPlayer bot) {
        BotManager manager = BotManager.get(bot.level().getServer());
        // A villager asleep in it doesn't count: it gets turfed out (a sleeping player does count).
        return Senses.findBlock(bot, s -> s.is(BlockTags.BEDS), 32, 8, false,
                p -> {
                    BlockPos head = Homes.headOf(bot.level(), p);
                    if (bot.level().getBlockState(p).getValue(AbstractBedBlock.OCCUPIED) && !onlyVillagerIn(bot, head)) return true;
                    return manager.bedOwner(bot.level(), head) != null || Homes.isPlayerSpawnBed(bot.level(), head)
                            || this.brain.isBlacklisted(p);
                });
    }

    /** The bed's taken, but only by a sleeping villager (no player in it). */
    private static boolean onlyVillagerIn(BotPlayer bot, BlockPos head) {
        var box = new net.minecraft.world.phys.AABB(head).inflate(1.5);
        if (!bot.level().getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, box, p -> p.isSleeping()).isEmpty()) return false;
        return !bot.level().getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, box, v -> v.isSleeping()).isEmpty();
    }

    /** A free bed in a village we've been to, if it's not too far. */
    private @Nullable BlockPos villageBed(BotPlayer bot) {
        GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        GlobalPos village = this.brain.memory().nearest("village", here, VILLAGE_RANGE);
        if (village == null || !bot.level().isLoaded(village.pos())) return null;
        return Homes.findFreeBed(bot.level(), village.pos(), 32, this.brain::isBlacklisted);
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
        if (this.task == null) {
            this.task = switch (this.plan) {
                case WOOL -> new WoolTask();
                case MAKE -> makeBed(bot);
                case CAMP -> campBed(bot);
                case PACK -> packBed(bot);
                default -> null;
            };
        }
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (bot.isSleeping()) return true;
        if (++this.ticks > 20 * 90) return giveUp();
        if (this.task != null) {
            Task.Status status = this.task.tick(bot, this.brain);
            if (status == Task.Status.RUNNING) return true;
            this.task.stop(bot, this.brain);
            Task done = this.task;
            this.task = null;
            if (status == Task.Status.FAILED) return giveUp();
            if (this.plan == Plan.WOOL) return false;
            if (this.plan == Plan.PACK) {
                this.brain.log("packed my bed up, moving on");
                if (this.campBed != null) this.campBed = null;
                else BotManager.get(bot.level().getServer()).setHome(this.brain.record(), null);
                this.plan = Plan.NONE;
                return false;
            }
            if (this.plan == Plan.MAKE && Inv.count(bot, s -> s.is(ItemTags.BEDS)) == 0) {
                // That was a step on the way (planks, say). Next one.
                this.task = makeBed(bot);
                return this.task != null || giveUp();
            }
            if (this.plan == Plan.MAKE) {
                // Bed made: now put it down.
                this.plan = Plan.CAMP;
                this.task = campBed(bot);
                return this.task != null || giveUp();
            }
            if (this.plan == Plan.CAMP && done instanceof BuildTask) {
                this.bed = placedBed(bot);
                if (this.bed == null) return giveUp();
                this.brain.log("put a bed down for the night at " + this.bed.toShortString());
                this.plan = Plan.BED;
            }
        }
        if (this.plan != Plan.BED || this.bed == null) return giveUp();
        if (!bot.level().isDarkOutside()) return false;
        BlockPos bed = Homes.headOf(bot.level(), this.bed);
        // Lying down needs you right by the bed (3 blocks), closer than clicking range.
        if (!nearBed(bot, bed) && !nearBed(bot, this.bed)) {
            Navigator nav = this.brain.navigator();
            if (!nav.isMoving()) {
                nav.moveNear(bot, net.minecraft.world.phys.Vec3.atBottomCenterOf(this.bed), 1.5);
                if (nav.status() == Navigator.Status.FAILED) {
                    this.brain.blacklist(this.bed);
                    cooldown(this.brain.now(), 20 * 30);
                    return false;
                }
            }
            return true;
        }
        this.brain.navigator().stop(bot);
        BotManager manager = BotManager.get(bot.level().getServer());
        boolean campWithBase = this.brain.personality().nomad() > 0.6 && this.brain.record().house() != null
                && (this.brain.record().home() == null || !this.brain.record().home().pos().equals(bed));
        if (campWithBase) {
            // A nomad out roaming from its base: tonight's bed is just for tonight (packed up in
            // the morning), and home stays home.
            this.campBed = GlobalPos.of(bot.level().dimension(), bed);
        } else if (this.brain.record().home() == null || !this.brain.record().home().pos().equals(bed)) {
            manager.setHome(this.brain.record(), GlobalPos.of(bot.level().dimension(), bed));
            WorldView w = new WorldView(bot.level());
            if (HousePlans.isIndoors(w, bed)) this.brain.rememberPlace(bot, "village", bed);
        }
        if (this.ticks % 10 != 0) return true; // a moment between clicks
        Placer.use(bot, bed);
        if (!bot.isSleeping()) {
            // A villager in it gets kicked out by the first click; the next one gets us in.
            // Only after a few goes is it really "monsters nearby" or "not late enough".
            if (++this.useAttempts < 4) return true;
            this.useAttempts = 0;
            this.failedAt = this.brain.now();
            cooldown(this.brain.now(), 20 * 20);
            return false;
        }
        this.useAttempts = 0;
        return true;
    }

    /** Close enough to get into bed (vanilla allows 3 blocks each way; keep a margin). */
    private static boolean nearBed(BotPlayer bot, BlockPos half) {
        var c = net.minecraft.world.phys.Vec3.atBottomCenterOf(half);
        return Math.abs(bot.getX() - c.x) <= 2.4 && Math.abs(bot.getY() - c.y) <= 1.5 && Math.abs(bot.getZ() - c.z) <= 2.4;
    }

    private boolean giveUp() {
        this.task = null;
        this.plan = Plan.NONE;
        cooldown(this.brain.now(), 20 * 60);
        return false;
    }

    /** Our base's bed is within easy reach. */
    private boolean baseNearby(BotPlayer bot) {
        GlobalPos home = this.brain.record().home();
        return this.brain.record().house() != null && home != null && home.dimension() == bot.level().dimension()
                && home.pos().distSqr(bot.blockPosition()) < 48 * 48;
    }

    /** Last night's bed, if it's ours and close by (a nomad picks it up again in the morning). */
    private @Nullable BlockPos campBedToPack(BotPlayer bot) {
        // Out from a base: the bed we put down for the night.
        GlobalPos camp = this.campBed;
        if (camp != null) {
            if (camp.dimension() == bot.level().dimension() && bot.level().isLoaded(camp.pos())
                    && bot.level().getBlockState(camp.pos()).is(BlockTags.BEDS) && camp.pos().distSqr(bot.blockPosition()) < 24 * 24) {
                return camp.pos();
            }
            if (camp.pos().distSqr(bot.blockPosition()) < 24 * 24) this.campBed = null; // gone already
            return null;
        }
        if (!this.brain.roaming(bot)) return null;
        GlobalPos home = this.brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension() || !bot.level().isLoaded(home.pos())) return null;
        if (home.pos().distSqr(bot.blockPosition()) > 24 * 24) return null;
        if (!bot.level().getBlockState(home.pos()).is(BlockTags.BEDS)) return null;
        // Not one somebody else sleeps in (a village bed we borrowed): leave those.
        if (HousePlans.isIndoors(new WorldView(bot.level()), home.pos())) return null;
        return home.pos();
    }

    /** Dig the bed up and pick it up. */
    private @Nullable Task packBed(BotPlayer bot) {
        BlockPos bed = campBedToPack(bot);
        if (bed == null) return null;
        return net.joinedthegame.ai.task.Tasks.sequence(
                new BuildTask(List.of(BuildTask.Step.digEssential(bed)), "Packing up my bed"),
                new net.joinedthegame.ai.task.CollectItems(bed, 4, 100));
    }

    /** Craft a bed from the wool we've got (the planner sorts out planks and the table). */
    private @Nullable Task makeBed(BotPlayer bot) {
        DyeColor color = WoolTask.bestColor(bot);
        if (color == null) return null;
        Planner.Step step = new Planner(bot).next(WoolTask.bed(color), 1);
        // Only a quick craft at night; no wandering off chopping trees in the dark.
        return step instanceof Planner.Craft craft ? new CraftTask(craft) : null;
    }

    /** Put the bed we're carrying down somewhere close. */
    private @Nullable Task campBed(BotPlayer bot) {
        WorldView w = new WorldView(bot.level());
        BlockPos feet = Navigator.feet(bot);
        AABB body = bot.getBoundingBox().inflate(0.1);
        BlockPos best = null;
        Direction bestDir = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos foot = feet.offset(dx, dy, dz);
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos head = foot.relative(dir);
                        if (!bedFits(w, foot) || !bedFits(w, head)) continue;
                        if (body.intersects(new AABB(foot)) || body.intersects(new AABB(head))) continue;
                        double d = foot.distSqr(feet);
                        if (d < bestDist) {
                            bestDist = d;
                            best = foot;
                            bestDir = dir;
                        }
                    }
                }
            }
        }
        if (best == null) return null;
        this.bed = best;
        return new BuildTask(List.of(BuildTask.Step.placeEssential(best, s -> s.is(ItemTags.BEDS), bestDir)), "Putting my bed down");
    }

    private static boolean bedFits(WorldView w, BlockPos pos) {
        return w.state(pos).canBeReplaced() && w.state(pos).getFluidState().isEmpty()
                && w.solidFloor(pos.below()) && w.passable(pos.above());
    }

    private @Nullable BlockPos placedBed(BotPlayer bot) {
        if (this.bed != null && bot.level().getBlockState(this.bed).is(BlockTags.BEDS)) return this.bed;
        return null;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        if (this.task != null && this.plan == Plan.WOOL) {
            this.task.stop(bot, this.brain);
            this.task = null;
        }
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return bot.isSleeping() || this.plan == Plan.BED && bot.level().isDarkOutside();
    }

    @Override
    public String activity() {
        return switch (this.plan) {
            case WOOL -> "Getting wool for a bed";
            case MAKE -> "Making a bed";
            case CAMP -> "Putting my bed down";
            default -> "Sleeping";
        };
    }
}
