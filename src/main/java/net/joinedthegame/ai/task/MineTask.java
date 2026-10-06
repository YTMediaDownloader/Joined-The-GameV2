package net.joinedthegame.ai.task;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.Sources;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Get N of a raw material: find the nearest visible block, walk over, dig it, grab the
 * drops, repeat. If nothing is in sight, wander (for trees) or dig a staircase down and
 * tunnel (for stone and ores) until something turns up.
 */
public final class MineTask implements Task {
    private final Sources source;
    private final int target;
    private final String label;

    private @Nullable BlockPos block;
    private @Nullable Task sub;
    private int searchCooldown;
    private int searches;
    private int ticks;
    private @Nullable Vec3 tunnelGoal;
    private Direction tunnelDir;
    private int blockTicks;
    private @Nullable BlockPos aim;
    /** The current target is a side-grab (copper/coal), not what we came for. */
    private boolean bonus;
    private boolean bonusDiamond;
    /** Already tried moving for a clear view of the current target. */
    private boolean triedReposition;
    /** Places not to take from (our own farm, say). */
    private java.util.function.Predicate<BlockPos> avoid = p -> false;

    public MineTask(BotPlayer bot, Sources source, int count, String label) {
        this.source = source;
        this.target = Inv.count(bot, source.yields()) + count;
        this.label = label;
        this.tunnelDir = Direction.Plane.HORIZONTAL.getRandomDirection(bot.getRandom());
    }

    public MineTask avoiding(java.util.function.Predicate<BlockPos> avoid) {
        this.avoid = avoid;
        return this;
    }

    @Override
    public String activity() {
        return this.label;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (Inv.count(bot, this.source.yields()) >= this.target) return Status.DONE;
        if (++this.ticks > 20 * 60 * 4) return Status.FAILED;

        if (this.sub != null) {
            Status status = this.sub.tick(bot, brain);
            if (status == Status.RUNNING) return Status.RUNNING;
            this.sub = null;
        }

        // Drops lying about: ore we dug through while tunnelling, or that rolled off somewhere.
        // Pick them up before carrying on (nobody walks away from dropped iron).
        if (!brain.breaker().busy() && this.ticks - this.lastStrayCheck >= 20) {
            this.lastStrayCheck = this.ticks;
            Task collect = collectStrays(bot, brain);
            if (collect != null) {
                this.sub = collect;
                return Status.RUNNING;
            }
        }

        if (this.block != null) return mineTarget(bot, brain);

        if (this.searchCooldown-- <= 0) {
            this.searchCooldown = 20;
            this.block = search(bot, brain);
            if (this.block != null) {
                this.triedReposition = false;
                this.tunnelGoal = null;
                brain.navigator().stop(bot);
                this.blockTicks = 0;
                return Status.RUNNING;
            }
            this.searches++;
        }
        return keepExploring(bot, brain);
    }

    private @Nullable BlockPos search(BotPlayer bot, BotBrain brain) {
        boolean surface = !this.source.isUnderground() || this.source == Sources.STONE;
        int radius = surface ? 24 : 16;
        java.util.function.Predicate<BlockPos> skip = p -> brain.isBlacklisted(p) || this.avoid.test(p);
        // Diamonds in sight and a pickaxe that can take them: nobody walks past diamonds.
        BlockPos found = null;
        if (this.source.isUnderground() && this.source != Sources.DIAMOND && Inv.pickaxeTier(bot) >= 4) {
            found = Senses.findBlock(bot, Sources.DIAMOND.blocks(), 8, 5, true, skip);
            if (found != null) {
                this.bonus = true;
                this.bonusDiamond = true;
                brain.log("spotted diamonds at " + found.toShortString());
            }
        }
        if (found == null) found = Senses.findBlock(bot, this.source.blocks(), radius, surface ? 12 : 10, true, skip);
        if (found == null && this.source.isUnderground()) {
            // Ore round a corner or showing a face in the tunnel wall: not in line of sight,
            // but anyone digging along would notice it.
            WorldView w = new WorldView(bot.level());
            found = Senses.findBlock(bot, s -> this.source.blocks().test(s), 6, 4, false,
                    p -> skip.test(p) || !Senses.isExposed(w, p));
        }
        if (found == null && this.source.isUnderground()) {
            found = Senses.findBlock(bot, this.source.blocks(), 4, 4, false, skip);
        }
        if (found == null && this.source.isUnderground()) {
            // Some we saw earlier and didn't take (busy, interrupted): go back for it.
            var here = net.minecraft.core.GlobalPos.of(bot.level().dimension(), bot.blockPosition());
            var known = brain.memory().nearest("ore:" + this.source.label(), here, 48);
            if (known != null) {
                if (bot.level().isLoaded(known.pos()) && this.source.blocks().test(bot.level().getBlockState(known.pos()))
                        && !skip.test(known.pos())) {
                    found = known.pos();
                    brain.log("going back for the " + this.source.label() + " I saw at " + found.toShortString());
                } else {
                    brain.memory().forget("ore:" + this.source.label(), known); // gone now
                }
            }
        }
        if (found == null && (this.source == Sources.IRON || this.source == Sources.DIAMOND)) {
            // Hunting the good stuff, but grab coal sitting right next to us anyway.
            found = Senses.findBlock(bot, Sources.COAL.blocks(), 4, 3, true, brain::isBlacklisted);
            if (found != null) this.bonus = true;
        }
        // Coal in sight while digging for anything else underground: nobody walks past coal.
        if (found == null && this.source.isUnderground() && this.source != Sources.COAL
                && Inv.count(bot, net.minecraft.world.item.Items.COAL) < 32) {
            found = Senses.findBlock(bot, Sources.COAL.blocks(), 6, 4, true, brain::isBlacklisted);
            if (found != null) this.bonus = true;
        }
        return found;
    }

    private Status mineTarget(BotPlayer bot, BotBrain brain) {
        BlockPos pos = this.block;
        WorldView world = new WorldView(bot.level());
        boolean stillThere = this.bonus
                ? Sources.COAL.blocks().test(world.state(pos)) || Sources.DIAMOND.blocks().test(world.state(pos))
                : this.source.blocks().test(world.state(pos));
        if (!stillThere) {
            // Worth remembering where the good stuff was.
            if (this.bonusDiamond) {
                brain.remember(net.joinedthegame.ai.BotMemory.EventType.FOUND, Sources.DIAMOND.label(),
                        net.minecraft.core.GlobalPos.of(bot.level().dimension(), pos), 1f);
            }
            this.bonusDiamond = false;
            if (!this.bonus && (this.source == Sources.DIAMOND || this.source == Sources.IRON)) {
                var where = net.minecraft.core.GlobalPos.of(bot.level().dimension(), pos);
                brain.remember(net.joinedthegame.ai.BotMemory.EventType.FOUND, this.source.label(), where, this.source == Sources.DIAMOND ? 1f : 0.4f);
                // Remember the vein only while there's more of it; a mined-out one is useless.
                String kind = "ore:" + this.source.label();
                BlockPos more = Senses.findBlock(bot, this.source.blocks(), 3, 3, false, p -> p.equals(pos) || brain.isBlacklisted(p));
                if (more != null && more.distSqr(pos) <= 9) brain.rememberPlace(bot, kind, more);
                else brain.memory().forgetAround(kind, where, 6);
            }
            this.bonus = false;
            this.block = null;
            this.aim = null;
            brain.breaker().cancel(bot);
            this.sub = new CollectItems(pos, 6, 120);
            return Status.RUNNING;
        }
        if (++this.blockTicks > 20 * 40) {
            brain.blacklist(pos);
            this.block = null;
            brain.breaker().cancel(bot);
            return Status.RUNNING;
        }
        Navigator nav = brain.navigator();
        if (nav.isMoving() && this.triedReposition && this.aim == null) return Status.RUNNING; // getting a better view
        if (BlockBreaker.inReach(bot, pos)) {
            nav.stop(bot);
            // Dig through whatever is between us and the block, like a player would.
            // Keep digging the same block until it's gone; re-aiming every tick (the view
            // shifts as the bot moves) would keep resetting the break progress.
            BlockPos aim = this.aim;
            if (aim == null || world.passable(aim)) {
                aim = firstObstruction(bot, pos);
                // The only way through is the block we're standing on? Don't dig our own floor
                // out (we'd drop, pillar back up and do it again): find a spot with a clear view.
                if (aim != null && isUnderUs(bot, aim) && !this.triedReposition) {
                    this.triedReposition = true;
                    BlockPos target = pos;
                    double r = BlockBreaker.REACH - 0.8;
                    nav.moveTo(bot, Vec3.atCenterOf(target), p -> {
                        Vec3 eye = Vec3.atBottomCenterOf(p).add(0, 1.62, 0);
                        if (eye.distanceToSqr(Vec3.atCenterOf(target)) > r * r) return false;
                        var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(target),
                                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
                        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(target);
                    });
                    if (nav.status() != Navigator.Status.FAILED) return Status.RUNNING;
                    nav.stop(bot); // nowhere better: dig through after all
                }
                if (aim == null) aim = pos;
                else if (!world.breakable(aim)) {
                    brain.blacklist(pos);
                    this.block = null;
                    this.aim = null;
                    return Status.RUNNING;
                }
                this.aim = aim;
            }
            brain.breaker().start(aim);
            BlockBreaker.Result result = brain.breaker().tick(bot, brain.controls());
            if (result != BlockBreaker.Result.RUNNING) this.aim = null;
            if (result == BlockBreaker.Result.FAILED) {
                brain.blacklist(pos);
                this.block = null;
            }
            brain.debug = "target " + pos.toShortString() + " aim " + aim.toShortString();
            return Status.RUNNING;
        }
        if (!nav.isMoving()) {
            nav.moveToReach(bot, pos);
            if (nav.status() == Navigator.Status.FAILED) {
                brain.blacklist(pos);
                this.block = null;
                nav.stop(bot);
            }
        }
        return Status.RUNNING;
    }

    /** Items given a try already (unreachable, say): don't keep going back for them. */
    private final java.util.Set<java.util.UUID> triedItems = new java.util.HashSet<>();
    private int lastStrayCheck = -100;

    /** A trip to pick up useful drops close by, or null if there aren't any worth it. */
    private @Nullable Task collectStrays(BotPlayer bot, BotBrain brain) {
        if (Inv.usedSlots(bot) >= Inv.MAIN_SLOTS - 1) return null;
        var items = Senses.itemsNear(bot, bot.blockPosition(), 6);
        items.removeIf(e -> !e.isAlive() || e.getItem().isEmpty() || this.triedItems.contains(e.getUUID())
                || e.getOwner() == bot // tossed it ourselves
                || !(net.joinedthegame.ai.Junk.isKeeper(e.getItem()) || net.joinedthegame.ai.Junk.isValuable(e.getItem()))
                || e.getY() < bot.getY() - 6 || e.getY() > bot.getY() + 3);
        if (items.isEmpty()) return null;
        for (var e : items) this.triedItems.add(e.getUUID());
        return new CollectItems(items.getFirst().blockPosition(), 6, 120);
    }

    /** One of the blocks holding us up (straight under our feet, or under the edge we're on). */
    private static boolean isUnderUs(BotPlayer bot, BlockPos block) {
        if (block.getY() != Mth.floor(bot.getY() - 0.01) ) return false;
        net.minecraft.world.phys.AABB feet = bot.getBoundingBox().move(0, -0.5, 0).deflate(0.01, 0, 0.01);
        return feet.intersects(new net.minecraft.world.phys.AABB(block));
    }

    /** The first solid block between the eyes and {@code pos}, or null if the view is clear. */
    private static @Nullable BlockPos firstObstruction(BotPlayer bot, BlockPos pos) {
        var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(bot.getEyePosition(), Vec3.atCenterOf(pos),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(pos)) return null;
        return hit.getBlockPos();
    }

    private Status keepExploring(BotPlayer bot, BotBrain brain) {
        Navigator nav = brain.navigator();
        if (nav.isMoving()) return Status.RUNNING;
        if (this.searches > 40) return Status.FAILED;

        if (!this.source.isUnderground()) {
            // Look for trees: head off somewhere new on the surface.
            double angle = bot.getRandom().nextDouble() * Math.PI * 2;
            int dist = 20 + bot.getRandom().nextInt(20);
            int x = Mth.floor(bot.getX() + Math.cos(angle) * dist);
            int z = Mth.floor(bot.getZ() + Math.sin(angle) * dist);
            if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) return Status.RUNNING;
            int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            nav.moveNear(bot, new Vec3(x + 0.5, y, z + 0.5), 3);
            if (nav.status() == Navigator.Status.FAILED) nav.stop(bot);
            return Status.RUNNING;
        }

        // Underground: staircase down to the right depth, then tunnel.
        int y = Mth.floor(bot.getY());
        int goalY = this.source == Sources.STONE ? Math.min(y - 3, this.source.mineY()) : this.source.mineY();
        goalY = Math.max(goalY, bot.level().getMinY() + 6);
        if (y > goalY + 2) {
            int dy = y - goalY;
            Vec3 goal = new Vec3(bot.getX() + this.tunnelDir.getStepX() * dy, goalY, bot.getZ() + this.tunnelDir.getStepZ() * dy);
            int finalGoalY = goalY;
            nav.moveTo(bot, goal, p -> p.getY() <= finalGoalY + 1);
        } else {
            if (this.tunnelGoal == null || bot.position().distanceToSqr(this.tunnelGoal) < 4) {
                if (this.tunnelGoal != null && bot.getRandom().nextInt(3) == 0) {
                    this.tunnelDir = bot.getRandom().nextBoolean() ? this.tunnelDir.getClockWise() : this.tunnelDir.getCounterClockWise();
                }
                this.tunnelGoal = bot.position().add(this.tunnelDir.getStepX() * 24, 0, this.tunnelDir.getStepZ() * 24);
            }
            nav.moveNear(bot, this.tunnelGoal, 1.5);
        }
        if (nav.status() == Navigator.Status.FAILED) {
            nav.stop(bot);
            this.tunnelDir = this.tunnelDir.getClockWise();
            this.tunnelGoal = null;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        brain.breaker().cancel(bot);
        brain.navigator().stop(bot);
    }
}
