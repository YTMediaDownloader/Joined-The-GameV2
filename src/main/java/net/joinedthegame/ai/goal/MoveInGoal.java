package net.joinedthegame.ai.goal;

import java.util.List;
import java.util.UUID;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.task.BuildTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.BotRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import org.jspecify.annotations.Nullable;

/**
 * Moving in together (part 3, step 5). Partners who've been together a while end up under one
 * roof: the one with the lesser home moves into the other's (a hut into a house, a house into a
 * bigger one; same again, the less homely one goes). It brings a bed (its own, if it has one on it,
 * or makes one) and puts it down beside its partner's, inside. From then on the house is home to
 * both: the same chests (sharing them isn't theft), the same door, beds side by side. Break up,
 * and the one who moved in moves out (see BotBrain.breakUp).
 * <p>
 * The house isn't fussed over once it's built (nobody re-places blocks a partner changed), so
 * a partner's renovations stick.
 */
public final class MoveInGoal extends Goal {
    private @Nullable Task task;
    private @Nullable BlockPos foot;
    private @Nullable Direction facing;
    private int tries;

    public MoveInGoal(BotBrain brain) {
        super(brain);
    }

    /** How good a home is: a big house, a house, a cabin, a hut or nothing. */
    private static int rank(String homeType) {
        return switch (homeType) {
            case "LARGE" -> 3;
            case "HOUSE" -> 2;
            case "CABIN" -> 1;
            default -> 0;
        };
    }

    @Override
    public int priority(BotPlayer bot) {
        if (this.task != null) return 55; // under way: above building our own place
        if (this.brain.now() % 100 != 0) return 0; // (the brain asks on multiples of ten)
        UUID partner = this.brain.partner();
        if (partner == null || bot.level().isDarkOutside() || !this.brain.safeToRelax(bot)) return 0;
        var r = this.brain.knownRelation(partner);
        if (r == null || this.brain.now() - r.partnerSince < 12000 || this.tries > 3) return 0;
        var server = bot.level().getServer();
        BotManager mgr = BotManager.get(server);
        BotBrain theirs = mgr.brain(partner);
        BotRecord theirRecord = theirs == null ? null : theirs.record();
        if (theirRecord == null || theirRecord.home() == null || theirRecord.house() == null) return 0;
        GlobalPos theirHome = theirRecord.home();
        if (theirHome.dimension() != bot.level().dimension()) return 0;
        GlobalPos mine = this.brain.record().home();
        if (mine != null && mine.dimension() == theirHome.dimension() && mine.pos().distSqr(theirHome.pos()) < 10 * 10) return 0; // already
        // Who moves: the lesser home; the less homely one if they're as good as each other.
        int me = rank(this.brain.memory().homeType), them = rank(theirs.memory().homeType);
        boolean iMove = me < them || me == them && this.brain.personality().homebody() < theirs.personality().homebody();
        if (!iMove) return 0;
        // Moving in beats carrying on with a place of our own: that's what we're giving up.
        return 60;
    }

    @Override
    public void start(BotPlayer bot) {
        if (this.task != null) return;
        this.brain.log("moving in with my partner");
        this.foot = null;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (this.task != null) {
            Task.Status s = this.task.tick(bot, this.brain);
            if (s == Task.Status.RUNNING) return true;
            this.task.stop(bot, this.brain);
            this.task = null;
            if (s == Task.Status.FAILED) {
                this.tries++;
                this.brain.log("moving in: that didn't work, another time");
                return false;
            }
            if (this.foot != null && bot.level().getBlockState(this.foot).is(net.minecraft.tags.BlockTags.BEDS)) return movedIn(bot);
        }
        UUID partner = this.brain.partner();
        var server = bot.level().getServer();
        BotBrain theirs = partner == null || server == null ? null : BotManager.get(server).brain(partner);
        if (theirs == null || theirs.record().home() == null) return false;
        BlockPos theirBed = theirs.record().home().pos();
        // A bed first: on us, or made.
        if (Inv.count(bot, s -> s.is(ItemTags.BEDS)) == 0) {
            boolean[] failed = {false};
            this.task = Tasks.toward(bot, Tasks.Need.of("a bed", s -> s.is(ItemTags.BEDS), 1, b -> net.minecraft.world.item.Items.BED.red()), failed);
            if (failed[0] || this.task == null) {
                this.tries++;
                this.brain.log("moving in: can't get a bed together yet");
                return false;
            }
            return true;
        }
        // Somewhere beside theirs, inside.
        if (this.foot == null && !bedSpot(bot.level(), theirBed)) {
            this.tries++;
            this.brain.log("moving in: no room for a second bed by " + theirs.record().name() + "'s");
            return false;
        }
        BlockPos f = this.foot;
        this.task = new BuildTask(List.of(BuildTask.Step.placeEssential(f, s -> s.is(ItemTags.BEDS), this.facing)), "Moving in with my partner");
        return true;
    }

    /** A free spot by their bed, under the roof: two floor cells, room to stand. */
    private boolean bedSpot(ServerLevel level, BlockPos theirBed) {
        WorldView w = new WorldView(level);
        BlockPos best = null;
        Direction bestDir = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(theirBed.offset(-4, -1, -4), theirBed.offset(4, 1, 4))) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos head = p.relative(d);
                if (!fits(level, w, p) || !fits(level, w, head)) continue;
                double dist = p.distSqr(theirBed);
                if (dist < 2 || dist >= bestD) continue;
                best = p.immutable();
                bestDir = d;
                bestD = dist;
            }
        }
        this.foot = best;
        this.facing = bestDir;
        return best != null;
    }

    private static boolean fits(ServerLevel level, WorldView w, BlockPos cell) {
        return w.state(cell).isAir() && w.state(cell.above()).isAir() && w.solidFloor(cell.below()) && !level.canSeeSky(cell);
    }

    private boolean movedIn(BotPlayer bot) {
        UUID partner = this.brain.partner();
        var server = bot.level().getServer();
        BotBrain theirs = partner == null || server == null ? null : BotManager.get(server).brain(partner);
        if (theirs == null || this.foot == null) return false;
        BlockPos head = net.joinedthegame.bot.Homes.headOf(bot.level(), this.foot);
        BotManager mgr = BotManager.get(server);
        mgr.setHome(this.brain.record(), GlobalPos.of(bot.level().dimension(), head));
        mgr.setHouse(this.brain.record(), theirs.record().house(), null);
        this.brain.memory().homeType = theirs.memory().homeType;
        this.brain.memory().housePlan = null; // a half-built place of our own: not needed now
        this.brain.memory().remember("moved_in_with:" + partner, GlobalPos.of(bot.level().dimension(), head), this.brain.now());
        this.brain.remember(BotMemory.EventType.MOVED_IN, theirs.record().name());
        theirs.remember(BotMemory.EventType.MOVED_IN, this.brain.record().name());
        this.brain.log("moved in with " + theirs.record().name());
        theirs.log(this.brain.record().name() + " moved in with me");
        return false;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        if (this.task != null) this.task.stop(bot, this.brain);
    }

    @Override
    public String activity() {
        return "Moving in with my partner";
    }
}
