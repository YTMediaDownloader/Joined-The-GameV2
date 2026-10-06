package net.joinedthegame.ai.project;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Structures;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.TravelTask;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: raid a structure we know about but haven't been through yet. Go there,
 * then poke about inside: the fighting (CombatGoal) and the chests (LootGoal) take care of
 * themselves once we're in. Trial chambers get their vaults opened with any trial keys we
 * picked up. Ancient cities are left alone: nobody goes into the deep dark for fun.
 */
public final class RaidProject implements Task {
    private @Nullable GlobalPos target;
    private Structures.@Nullable Kind kind;
    private @Nullable Task sub;
    private int insideTicks;
    private int ticks;

    /** A known structure worth raiding, not raided yet, not too far. Null if none. */
    public static @Nullable GlobalPos pick(BotPlayer bot, BotBrain brain, Structures.Kind[] out) {
        GlobalPos here = GlobalPos.of(bot.level().dimension(), bot.blockPosition());
        GlobalPos best = null;
        double bestDist = 300.0 * 300.0;
        // How much a bot can take on depends on its gear: temples and shipwrecks for anyone,
        // outposts and trial chambers once in iron with a shield, the nasty stuff in diamond.
        double canHandle = Inv.fullDiamond(bot) ? 0.95 : Inv.fullIron(bot) ? 0.75 : 0.35;
        for (Structures.Kind k : Structures.Kind.values()) {
            if (k.danger > canHandle) continue;
            if (!k.loot || k == Structures.Kind.ANCIENT_CITY || k == Structures.Kind.BASTION || k == Structures.Kind.FORTRESS) continue;
            if (k == Structures.Kind.VILLAGE) continue; // passing through is enough; not a "raid"
            if (k == Structures.Kind.TRIAL_CHAMBERS) continue; // their own project (TrialChamberProject)
            var list = brain.memory().places().get(k.placeKey());
            if (list == null) continue;
            for (var place : list) {
                GlobalPos p = place.pos();
                if (p.dimension() != here.dimension()) continue;
                double d = p.pos().distSqr(here.pos());
                if (d >= bestDist || raided(brain, p)) continue;
                best = p;
                bestDist = d;
                out[0] = k;
            }
        }
        return best;
    }

    private static boolean raided(BotBrain brain, GlobalPos where) {
        return net.joinedthegame.ai.RaidProgress.alreadyRaided(brain, where);
    }

    @Override
    public String activity() {
        if (this.sub != null) return this.sub.activity();
        return this.kind == null ? "Going raiding" : "Raiding the " + this.kind.label;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (++this.ticks > 20 * 60 * 12) return Status.DONE;
        // Don't set off half-dead or without food.
        if (this.target == null && (bot.getHealth() < 16 || Inv.count(bot, s -> Inv.isFood(s) && !Inv.isBadFood(s)) < 6)) {
            brain.log("raid: not ready (health/food), later");
            return Status.FAILED;
        }
        // Getting beaten up in there: get out, come back another time.
        if (this.target != null && bot.getHealth() < 8) {
            brain.log("raid: too hot in the " + this.kind.label + ", backing off");
            return Status.FAILED;
        }
        if (this.target == null) {
            Structures.Kind[] k = new Structures.Kind[1];
            this.target = pick(bot, brain, k);
            this.kind = k[0];
            if (this.target == null) return Status.FAILED;
            brain.log("raid: going for the " + this.kind.label + " at " + this.target.pos().toShortString());
        }
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (s == Status.FAILED) return Status.FAILED;
        }
        BlockPos center = this.target.pos();
        Structures.Known here = brain.structureHere();
        boolean inside = here != null && here.kind() == this.kind;
        if (!inside && center.distSqr(bot.blockPosition()) > 24 * 24) {
            this.sub = new TravelTask(center, 12, "Heading to the " + this.kind.label);
            return Status.RUNNING;
        }
        // In (or right by) it: open any vault we have a key for; otherwise look around.
        if (this.ticks % 20 == 0 && openVault(bot, brain)) return Status.RUNNING;
        if (++this.insideTicks > 20 * 60 * 4) {
            // Had a good look round. Whatever's left (locked vaults, chests we couldn't reach)
            // stays on the list for next time; it only counts as raided once it's all done.
            if (here != null) net.joinedthegame.ai.RaidProgress.checkComplete(bot, brain, here);
            return Status.DONE;
        }
        Navigator nav = brain.navigator();
        if (!nav.isMoving()) {
            // Wander the structure so the chests come into view.
            BlockPos goal = here != null ? randomInside(bot, here) : center;
            nav.moveNear(bot, Vec3.atCenterOf(goal), 3);
            if (nav.status() == Navigator.Status.FAILED) nav.stop(bot);
        }
        return Status.RUNNING;
    }

    private static BlockPos randomInside(BotPlayer bot, Structures.Known k) {
        var box = k.box();
        int x = box.minX() + bot.getRandom().nextInt(Math.max(1, box.getXSpan()));
        int z = box.minZ() + bot.getRandom().nextInt(Math.max(1, box.getZSpan()));
        int y = Math.min(box.maxY(), Math.max(box.minY(), bot.getBlockY()));
        return new BlockPos(x, y, z);
    }

    /** A trial key and a vault in reach: open it. */
    private static boolean openVault(BotPlayer bot, BotBrain brain) {
        boolean ominous = Inv.count(bot, Items.OMINOUS_TRIAL_KEY) > 0;
        if (Inv.count(bot, Items.TRIAL_KEY) == 0 && !ominous) return false;
        BlockPos vault = Senses.findBlock(bot, s -> s.is(Blocks.VAULT), 16, 8, false, brain::isBlacklisted);
        if (vault == null) return false;
        if (!BlockBreaker.inReach(bot, vault)) {
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) nav.moveToReach(bot, vault);
            return true;
        }
        Direction face = Direction.getApproximateNearest(bot.getEyePosition().subtract(Vec3.atCenterOf(vault)));
        boolean used = Placer.useOn(bot, vault, face, s -> s.is(Items.TRIAL_KEY) || s.is(Items.OMINOUS_TRIAL_KEY));
        brain.blacklist(vault); // one go per vault (each only opens once per player anyway)
        if (used) {
            brain.markLooted(vault);
            brain.log("opened a vault");
        }
        return true;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }
}
