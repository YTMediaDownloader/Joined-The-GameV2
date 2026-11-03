package net.joinedthegame.ai.nav;

import java.util.List;
import java.util.function.Predicate;
import net.joinedthegame.ai.Controls;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Follows a path one node at a time, the way a player would: dig out what's in the way,
 * place a block to bridge or pillar, then walk, jump or swim to the next spot. Repaths when
 * it gets stuck or knocked off course.
 */
public final class Navigator {
    public enum Status { IDLE, MOVING, ARRIVED, FAILED }

    private final BlockBreaker breaker;
    private @Nullable List<PathNode> path;
    private int index;
    private @Nullable Vec3 goalPoint;
    private @Nullable Predicate<BlockPos> goal;
    private boolean partial;
    private Status status = Status.IDLE;

    private int stuckTicks;
    private Vec3 lastPos = Vec3.ZERO;
    private int repaths;
    private int pathAge;
    private int placeCooldown;
    /** The pillar step we're on, and how long we've been trying it. */
    private int pillarIndex = -1;
    private int pillarTicks;
    /** A door or gate we opened, to close again once we're through. */
    private @Nullable BlockPos openedDoor;
    public boolean sprint;

    public Navigator(BlockBreaker breaker) {
        this.breaker = breaker;
    }

    /** True while the navigator itself is digging (clearing its path), not some goal. */
    private boolean digging;

    /** Stop digging, but only if it's our digging: never cancel a goal's mining. */
    private void releaseBreaker(BotPlayer bot) {
        if (this.digging) this.breaker.cancel(bot);
        this.digging = false;
    }

    public Status status() {
        return this.status;
    }

    /** For the debug log: the node we're heading to. */
    public String debugString() {
        if (this.path == null || this.index >= this.path.size()) return this.status.toString();
        StringBuilder out = new StringBuilder(this.status + " (" + this.index + "/" + this.path.size() + ")");
        for (int k = Math.max(0, this.index - 1); k < Math.min(this.path.size(), this.index + 3); k++) {
            PathNode n = this.path.get(k);
            out.append(k == this.index ? " => " : " -> ").append(n.move()).append(' ').append(n.pos().toShortString());
            if (!n.breaks().isEmpty()) out.append(" break").append(n.breaks().size());
        }
        return out.toString();
    }

    /**
     * How much building the rest of the route takes: blocks to put down plus blocks to dig out.
     * (Worth it to get home; not worth it for one bit of cobblestone on a ledge.)
     */
    public int plannedWork() {
        if (this.path == null) return 0;
        int work = 0;
        for (int k = this.index; k < this.path.size(); k++) {
            PathNode n = this.path.get(k);
            work += n.breaks().size() + (n.place() != null ? 1 : 0);
        }
        return work;
    }

    public boolean isMoving() {
        return this.status == Status.MOVING;
    }

    /** Leading animals through a gate: leave it open behind us (they're following). */
    public boolean leaveDoorsOpen;

    private void closeDoorBehind(BotPlayer bot, WorldView world) {
        BlockPos door = this.openedDoor;
        if (door == null || this.placeCooldown > 0 || this.leaveDoorsOpen) return;
        if (!world.isOpenDoor(door)) {
            this.openedDoor = null;
            return;
        }
        double dist = bot.getEyePosition().distanceTo(Vec3.atCenterOf(door));
        if (dist > 5) {
            this.openedDoor = null; // too far now, never mind
            return;
        }
        // Through it and clear of its swing?
        boolean inDoorway = bot.getBoundingBox().inflate(0.2).intersects(new net.minecraft.world.phys.AABB(door))
                || bot.getBoundingBox().inflate(0.2).intersects(new net.minecraft.world.phys.AABB(door.below()));
        if (inDoorway || dist < 1.6) return;
        // Only if the path doesn't go back through it.
        if (this.path != null) {
            for (int i = this.index; i < Math.min(this.index + 4, this.path.size()); i++) {
                BlockPos p = this.path.get(i).pos();
                if (p.equals(door) || p.above().equals(door) || p.equals(door.below())) return;
            }
        }
        Placer.use(bot, door);
        this.placeCooldown = 6;
        this.openedDoor = null;
    }

    public void stop(BotPlayer bot) {
        releaseBreaker(bot);
        this.path = null;
        this.goal = null;
        this.goalPoint = null;
        this.status = Status.IDLE;
        this.sprint = false;
    }

    /** Walk to within {@code range} blocks of a point. */
    public void moveNear(BotPlayer bot, Vec3 point, double range) {
        double r2 = range * range;
        moveTo(bot, point, pos -> Vec3.atBottomCenterOf(pos).distanceToSqr(point) <= r2);
    }

    /** Get close enough to reach (dig or click) a block. */
    public void moveToReach(BotPlayer bot, BlockPos block) {
        Vec3 center = Vec3.atCenterOf(block);
        double r = BlockBreaker.REACH - 0.6;
        // Where we're standing counts only if the block's really in reach from where our eyes
        // actually are (we can be anywhere in the cell, not at its middle). Otherwise "arrived"
        // here while the breaker says "too far" and the bot just stood and stared at the block.
        BlockPos here = feet(bot);
        moveTo(bot, center, pos -> {
            if (pos.equals(block) || pos.above().equals(block)) return false;
            if (pos.equals(here)) return BlockBreaker.inReach(bot, block);
            Vec3 eye = Vec3.atBottomCenterOf(pos).add(0, 1.62, 0);
            return eye.distanceToSqr(center) <= r * r;
        });
    }

    /** Walk to somewhere a container can be used from: close, and with a clear view of it. */
    public void moveToUse(BotPlayer bot, BlockPos block) {
        Vec3 center = Vec3.atCenterOf(block);
        // Already clearing the way to this one: carry on.
        if (block.equals(this.clearView) && this.status == Status.MOVING) return;
        this.clearView = null;
        moveTo(bot, center, pos -> {
            Vec3 eye = Vec3.atBottomCenterOf(pos).add(0, 1.62, 0);
            return eye.distanceToSqr(center) <= 3.5 * 3.5 && !pos.equals(block) && !pos.above().equals(block)
                    && BlockBreaker.canSee(bot, eye, block);
        });
        // A chest with something sat on its lid won't open: that'll want clearing once we're there.
        if (this.status != Status.FAILED && lidBlocker(bot.level(), block) != null && !nearOwnHome(bot, block)) {
            this.clearView = block.immutable();
            if (this.status == Status.ARRIVED) this.status = Status.MOVING;
            return;
        }
        if (this.status != Status.FAILED || nearOwnHome(bot, block)) return;
        // Nowhere it can be seen from (a chest inside a shipwreck's hull, behind a ruined portal's
        // blackstone): get within reach anyway, then dig through to it, like anyone would.
        moveTo(bot, center, pos -> {
            Vec3 eye = Vec3.atBottomCenterOf(pos).add(0, 1.62, 0);
            return eye.distanceToSqr(center) <= 3.8 * 3.8 && !pos.equals(block) && !pos.above().equals(block);
        });
        if (this.status != Status.FAILED) this.clearView = block.immutable();
    }

    /**
     * The block keeping a chest shut, if any: something solid on its lid, or on the other half's
     * lid for a double chest (ChestBlock.isChestBlockedAt: a redstone-conducting block above).
     */
    public static @Nullable BlockPos lidBlocker(net.minecraft.world.level.Level level, BlockPos chest) {
        var state = level.getBlockState(chest);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock)) return null;
        java.util.List<BlockPos> halves = new java.util.ArrayList<>(List.of(chest));
        if (state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            halves.add(chest.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state)));
        }
        for (BlockPos half : halves) {
            BlockPos above = half.above();
            if (level.getBlockState(above).isRedstoneConductor(level, above)) return above;
        }
        return null;
    }

    /** Something to dig our way into view of once we're there (see moveToUse). */
    private @Nullable BlockPos clearView;

    /** Never dig into our own house to get at something: that's what the door's for. */
    private static boolean nearOwnHome(BotPlayer bot, BlockPos pos) {
        var brain = bot.brain();
        if (brain == null) return false;
        for (var gp : new net.minecraft.core.GlobalPos[]{brain.record().home(), brain.record().house()}) {
            if (gp != null && gp.dimension() == bot.level().dimension() && gp.pos().distSqr(pos) < 16 * 16) return true;
        }
        return false;
    }

    /**
     * Arrived within reach of a {@link #clearView} target that's still hidden: break whatever's
     * first in the way (a plank, a bit of blackstone), one at a time, till it can be seen and
     * used. Water beside it is fine (a shipwreck's hull is all water-side); lava isn't.
     */
    private void clearTheView(BotPlayer bot, Controls controls) {
        BlockPos target = this.clearView;
        if (target == null) return;
        WorldView view = new WorldView(bot.level());
        // Something on the chest's lid: off with it first.
        BlockPos lid = lidBlocker(bot.level(), target);
        if (lid != null) {
            if (!BlockBreaker.inReach(bot, lid) || !view.breakableToReach(lid)) {
                releaseBreaker(bot);
                this.clearView = null;
                this.status = Status.FAILED;
                return;
            }
            this.digging = true;
            controls.lookAt(bot, Vec3.atCenterOf(lid));
            this.breaker.start(lid);
            if (this.breaker.tick(bot, controls) == BlockBreaker.Result.FAILED) {
                releaseBreaker(bot);
                this.clearView = null;
                this.status = Status.FAILED;
            }
            return;
        }
        if (BlockBreaker.canUse(bot, target)) {
            releaseBreaker(bot);
            this.clearView = null;
            this.status = Status.ARRIVED;
            return;
        }
        Vec3 eye = bot.getEyePosition();
        var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(target),
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, bot));
        BlockPos block = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getBlockPos() : null;
        WorldView world = new WorldView(bot.level());
        if (block == null || block.equals(target) || !BlockBreaker.inReach(bot, block) || !world.breakableToReach(block)) {
            releaseBreaker(bot);
            this.clearView = null;
            this.status = Status.FAILED;
            return;
        }
        this.digging = true;
        controls.lookAt(bot, Vec3.atCenterOf(block));
        this.breaker.start(block);
        if (this.breaker.tick(bot, controls) == BlockBreaker.Result.FAILED) {
            releaseBreaker(bot);
            this.clearView = null;
            this.status = Status.FAILED;
        }
    }

    /** Never route above this (see {@link #moveUnderground}); reset by every other move. */
    private int ceiling = Integer.MAX_VALUE;

    /** Stay under cover (set by the brain each tick: mining at night from a hole, see BotBrain). */
    public boolean underCover;
    /**
     * Walking only: no putting blocks down and no digging to get there (tidying up: no pillaring
     * up to take a pillar down, no tunnelling through the hill to fetch one block).
     */
    public boolean noPlacing;

    /**
     * Get there without coming up above {@code maxY}: tunnelling off underground, out of sight
     * of whatever's waiting on the surface (the cheap way is usually up and over).
     */
    public void moveUnderground(BotPlayer bot, Vec3 point, Predicate<BlockPos> isGoal, int maxY) {
        this.status = Status.IDLE;
        this.goalPoint = null;
        moveTo(bot, point, isGoal);
        this.ceiling = maxY;
        computePath(bot);
    }

    public void moveTo(BotPlayer bot, Vec3 point, Predicate<BlockPos> isGoal) {
        this.ceiling = Integer.MAX_VALUE;
        if (this.status == Status.MOVING && this.goalPoint != null && this.goalPoint.distanceToSqr(point) < 1.0) {
            this.goal = isGoal;
            return;
        }
        this.goalPoint = point;
        this.goal = isGoal;
        this.repaths = 0;
        this.lastPartialDist = Double.MAX_VALUE;
        computePath(bot);
    }

    /**
     * Path searches allowed per server tick, between all the bots. At sunrise everyone set off at
     * once and planned long routes on the same tick: the lag catcher caught the server spending
     * its whole tick in their searches. Over the budget, a bot waits its turn (a tick or two).
     */
    private static final int SEARCHES_PER_TICK = 1;
    /** Only routes this far count against it: a fight's quick re-plans (a few blocks) never wait. */
    private static final double LONG_ROUTE = 32;
    private static long searchTick = -1;
    private static int searches;
    /** Asked for a path while the budget was spent: plan it next tick. */
    private boolean waitingToPlan;

    private void computePath(BotPlayer bot) {
        long now = bot.level().getGameTime();
        if (searchTick != now) {
            searchTick = now;
            searches = 0;
        }
        boolean longRoute = this.goalPoint != null && bot.position().distanceTo(this.goalPoint) > LONG_ROUTE;
        if (longRoute && searches >= SEARCHES_PER_TICK) {
            this.waitingToPlan = true;
            this.status = Status.MOVING;
            this.path = null;
            return;
        }
        if (longRoute) searches++;
        this.waitingToPlan = false;
        releaseBreaker(bot);
        BlockPos start = groundedStart(bot);
        if (this.goal.test(start)) {
            this.status = Status.ARRIVED;
            this.path = null;
            return;
        }
        Pathfinder.Options options = Pathfinder.Options.normal(bot).below(this.ceiling);
        if (this.underCover) options = options.underCover();
        if (this.noPlacing) options = new Pathfinder.Options(false, false, options.maxFall(), options.maxNodes(), options.maxY(), options.covered());
        Pathfinder finder = new Pathfinder(bot, options);
        Pathfinder.Path found = finder.find(start, this.goalPoint, this.goal);
        if (found == null || found.nodes().size() < 2) {
            this.status = Status.FAILED;
            this.path = null;
            return;
        }
        this.path = found.nodes();
        this.partial = !found.complete();
        this.index = 1;
        this.status = Status.MOVING;
        this.stuckTicks = 0;
        this.bestNodeDist = Double.MAX_VALUE;
        this.pathAge = 0;
        this.lastPos = bot.position();
    }

    /**
     * Where a route should start. Planning mid-jump would otherwise start the route a block
     * too high, and the first step becomes an impossible two-block climb the bot hops at
     * forever. So if we're in the air, start from where we'll land.
     */
    private static BlockPos groundedStart(BotPlayer bot) {
        WorldView world = new WorldView(bot.level());
        BlockPos pos = feet(bot);
        if (world.standable(pos) && (bot.onGround() || bot.isInWater() || world.climbable(pos))) return pos;
        for (int k = 0; k <= 3; k++) {
            BlockPos below = pos.below(k);
            if (world.standable(below)) return below;
        }
        if (world.standable(pos.above())) return pos.above();
        return pos;
    }

    public static BlockPos feet(BotPlayer bot) {
        BlockPos pos = BlockPos.containing(bot.getX(), bot.getY() + 0.2, bot.getZ());
        // Standing on top of something shorter than a block (bed, slab, chest): our feet are
        // inside that block's cell, but as far as walking goes we're in the cell above it.
        var level = bot.level();
        if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()) {
            return pos.above();
        }
        return pos;
    }

    public void tick(BotPlayer bot, Controls controls) {
        if (this.waitingToPlan) {
            // Our turn to plan (or we've been stopped meanwhile: then never mind).
            if (this.status == Status.MOVING) computePath(bot);
            else this.waitingToPlan = false;
            return;
        }
        if (this.clearView != null && this.status == Status.MOVING && this.path == null) {
            clearTheView(bot, controls);
            return;
        }
        if (this.status != Status.MOVING || this.path == null) return;
        if (this.placeCooldown > 0) this.placeCooldown--;
        this.pathAge++;

        if (this.index >= this.path.size()) {
            // A partial path only helps if it actually got us closer. If not, the target
            // is out of reach: give up now instead of hopping around underneath it.
            double remaining = this.goalPoint == null ? 0 : bot.position().distanceTo(this.goalPoint);
            if (this.partial && this.repaths++ < 6 && remaining < this.lastPartialDist - 1.0) {
                this.lastPartialDist = remaining;
                computePath(bot);
            } else {
                this.status = this.goal != null && this.goal.test(feet(bot)) ? Status.ARRIVED : Status.FAILED;
                this.path = null;
                // Got within reach of something we still have to dig our way into view of.
                if (this.status == Status.ARRIVED && this.clearView != null) this.status = Status.MOVING;
            }
            return;
        }

        PathNode node = this.path.get(this.index);
        PathNode from = this.path.get(this.index - 1);
        WorldView world = new WorldView(bot.level());

        // 0a. Lava where the path goes. It flows: a lavafall spread across a route planned a moment
        // before and Redlantern walked into it. Stop and find another way before stepping in (new
        // routes never go through lava, and keep a block from it where they can).
        for (int i = this.index; i < Math.min(this.index + 3, this.path.size()); i++) {
            BlockPos cell = this.path.get(i).pos();
            if (world.isLava(cell) || world.isLava(cell.above()) || world.isLava(cell.below())) {
                controls.forward = 0;
                repath(bot);
                return;
            }
        }

        // 0b. Shut the door behind us (keeps the zombies out of the house and the farm).
        closeDoorBehind(bot, world);

        // 0. A shut door just ahead: open it, like a player would.
        if (this.placeCooldown == 0) {
            for (int i = this.index; i < Math.min(this.index + 2, this.path.size()); i++) {
                BlockPos cell = this.path.get(i).pos();
                for (BlockPos door : new BlockPos[]{cell, cell.above()}) {
                    if (world.isClosedDoor(door) && BlockBreaker.inReach(bot, door)) {
                        controls.lookAt(bot, Vec3.atCenterOf(door));
                        Placer.use(bot, door);
                        this.openedDoor = door;
                        this.placeCooldown = 10;
                        this.stuckTicks = 0;
                        return;
                    }
                }
            }
        }

        // 1. Clear the way.
        for (BlockPos block : node.breaks()) {
            if (!world.passable(block)) {
                if (!world.breakable(block) && !world.passable(block)) {
                    repath(bot);
                    return;
                }
                this.digging = true;
            this.breaker.start(block);
                BlockBreaker.Result result = this.breaker.tick(bot, controls);
                if (result == BlockBreaker.Result.FAILED) repath(bot);
                this.stuckTicks = 0;
                this.bestNodeDist = Double.MAX_VALUE;
                return;
            }
        }

        // 2. Place what's needed. A torch or flower in the way gets knocked out first.
        if (node.place() != null && !world.state(node.place()).canBeReplaced() && world.passable(node.place())
                && world.breakable(node.place())) {
            this.digging = true;
            this.breaker.start(node.place());
            if (this.breaker.tick(bot, controls) == BlockBreaker.Result.FAILED) repath(bot);
            this.stuckTicks = 0;
            return;
        }
        if (node.place() != null && world.state(node.place()).canBeReplaced()) {
            // Somebody's stood where the block has to go (they walked in after we planned): it
            // can't go down while they're there. A moment for them to move on, then another way.
            if (someoneIn(bot, node.place())) {
                if (++this.crowdedTicks > 15) {
                    this.crowdedTicks = 0;
                    this.pillarTicks = 0;
                    repath(bot);
                }
                return;
            }
            this.crowdedTicks = 0;
            if (node.move() == PathNode.Move.PILLAR) {
                controls.face(bot.getYRot(), 90f);
                if (this.pillarIndex != this.index) {
                    this.pillarIndex = this.index;
                    this.pillarTicks = 0;
                }
                // Get over the middle of the column first. Jumping from the edge, our body pokes
                // into the next column, and a block over there stops the jump dead: we'd just
                // keep hopping and never get high enough to put the block down.
                Vec3 column = Vec3.atBottomCenterOf(node.place());
                double off = Math.hypot(column.x - bot.getX(), column.z - bot.getZ());
                if (off > 0.2 && bot.onGround()) {
                    steerTowards(bot, controls, column, 0.1);
                } else {
                    controls.jump = true;
                    if (bot.getY() > node.place().getY() + 1.05 && this.placeCooldown == 0) {
                        if (Placer.place(bot, node.place(), Inv.scaffold(bot))) net.joinedthegame.ai.Scaffold.placed(bot, node.place());
                        this.placeCooldown = 4;
                    }
                }
                // Still not up after a couple of seconds: something's overhead. Dig it out if we
                // can, otherwise find another way.
                if (++this.pillarTicks > 40) {
                    BlockPos overhead = null;
                    for (int y = 2; y <= 3; y++) {
                        BlockPos c = node.place().above(y);
                        if (!world.passable(c)) {
                            overhead = c;
                            break;
                        }
                    }
                    if (overhead != null && world.breakable(overhead)) {
                        this.digging = true;
                        this.breaker.start(overhead);
                        if (this.breaker.tick(bot, controls) == BlockBreaker.Result.FAILED) repath(bot);
                    } else {
                        this.pillarTicks = 0;
                        repath(bot);
                        return;
                    }
                }
            } else {
                // Bridge: crouch at the edge, look down at the gap and place.
                controls.sneak = true;
                controls.lookAt(bot, Vec3.atCenterOf(node.place()));
                Vec3 center = Vec3.atBottomCenterOf(from.pos());
                steerTowards(bot, controls, center, 0.25);
                if (controls.isLookingAtTarget(bot, 30f) && this.placeCooldown == 0) {
                    if (!Placer.place(bot, node.place(), Inv.scaffold(bot))) {
                        this.placeCooldown = 10;
                        if (Inv.scaffoldCount(bot) == 0) repath(bot);
                    } else {
                        net.joinedthegame.ai.Scaffold.placed(bot, node.place());
                        this.placeCooldown = 4;
                    }
                }
            }
            this.stuckTicks = 0;
            this.bestNodeDist = Double.MAX_VALUE;
            return;
        }

        // 3. Move. On flat walking stretches, aim for the furthest node we can walk to in a
        // straight line instead of visiting every block centre: smoother, like a player.
        int aim = this.index;
        int feetY = feet(bot).getY();
        if (isPlainWalk(node) && node.pos().getY() == feetY && !bot.isInWater()) {
            for (int k = this.index + 1; k < Math.min(this.path.size(), this.index + 6); k++) {
                PathNode next = this.path.get(k);
                if (!isPlainWalk(next) || next.pos().getY() != feetY) break;
                if (!straightWalkable(world, bot.position(), Vec3.atBottomCenterOf(next.pos()), feetY)) break;
                aim = k;
            }
        }
        PathNode aimNode = this.path.get(aim);
        Vec3 target = Vec3.atBottomCenterOf(aimNode.pos());
        double dx = target.x - bot.getX();
        double dz = target.z - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        boolean wet = bot.isInWater();

        if (node.move() == PathNode.Move.PILLAR) {
            // Block already placed beneath us: just finish the jump.
            controls.jump = !bot.onGround();
        }

        if (horizontal > 0.15) {
            controls.faceTowards(bot, target);
            float yawError = Math.abs(net.minecraft.util.Mth.wrapDegrees(bot.getYRot() - controls.targetYaw()));
            controls.forward = yawError < 60 ? 1.0f : 0.2f;
            if (horizontal < 0.5 && aim + 1 >= this.path.size()) controls.forward = 0.5f;
            // Sprint whenever there's a decent way left to go, like anyone would.
            int left = this.path.size() - this.index;
            controls.sprint = (this.sprint || left >= 8) && !wet && yawError < 30 && isPlainWalk(node);
        }

        Vec3 nodeCenter = Vec3.atBottomCenterOf(node.pos());
        double nodeHorizontal = Math.sqrt(Math.pow(nodeCenter.x - bot.getX(), 2) + Math.pow(nodeCenter.z - bot.getZ(), 2));
        boolean needsUp = node.pos().getY() > from.pos().getY() || node.pos().getY() > Math.floor(bot.getY() + 0.01);
        if (needsUp && (nodeHorizontal < 1.4 || node.move() == PathNode.Move.UP)) controls.jump = true;
        if (wet && node.pos().getY() >= Math.floor(bot.getY())) controls.jump = true;
        if (bot.horizontalCollision && bot.onGround() && needsUp) controls.jump = true;
        if (world.climbable(feet(bot)) && needsUp) {
            controls.jump = true;
            controls.forward = 1.0f;
        }

        // 4. Arrived? Any node reached along the stretch counts, so cutting a corner doesn't
        // make the bot double back to the block it skipped.
        double tolerance = wet ? 0.6 : 0.45;
        for (int k = aim; k >= this.index; k--) {
            PathNode n = this.path.get(k);
            Vec3 c = Vec3.atBottomCenterOf(n.pos());
            double h = Math.sqrt(Math.pow(c.x - bot.getX(), 2) + Math.pow(c.z - bot.getZ(), 2));
            double tol = (k + 1 < this.path.size() && n.move() == PathNode.Move.WALK) ? tolerance : (wet ? 0.6 : 0.3);
            // Somebody's stood on it: we'll only be shoved off the middle, so near enough is there.
            if (h >= tol && h < 0.9 && someoneIn(bot, n.pos())) tol = 0.9;
            boolean yOk = n.move() == PathNode.Move.DROP || n.move() == PathNode.Move.DIG_DOWN
                    ? bot.getY() < n.pos().getY() + 0.6 && (bot.onGround() || wet)
                    : Math.abs(bot.getY() - n.pos().getY()) < (wet ? 1.3 : 0.75);
            if (h < tol && yOk) {
                this.index = k + 1;
                this.stuckTicks = 0;
                this.bestNodeDist = Double.MAX_VALUE;
                if (this.index >= this.path.size() && this.goal != null && this.goal.test(feet(bot))) {
                    this.status = Status.ARRIVED;
                    this.path = null;
                    controls.forward = 0;
                    controls.sprint = false;
                }
                return;
            }
        }

        // 5. Stuck? Judge by progress toward the node, not just movement: a bot hopping in
        // place against a wall is moving but getting nowhere.
        double distToNode = nodeHorizontal + Math.abs(bot.getY() - node.pos().getY());
        if (distToNode < this.bestNodeDist - 0.05) {
            this.bestNodeDist = distToNode;
            this.stuckTicks = 0;
        } else {
            this.stuckTicks++;
            if ((this.stuckTicks == 20 || this.stuckTicks == 40) && (bot.horizontalCollision || needsUp)) controls.jump = true;
            // Getting nowhere because people are stood in the way (we just get pushed back): don't
            // keep shoving, go round (a fresh plan steers clear of where they are).
            if (this.stuckTicks > 60 || this.stuckTicks == 20 && someoneIn(bot, node.pos())) repath(bot);
        }
        this.lastPos = bot.position();
        if (nodeHorizontal > 4 || Math.abs(bot.getY() - node.pos().getY()) > 4) repath(bot);
    }

    private double bestNodeDist = Double.MAX_VALUE;
    private double lastPartialDist = Double.MAX_VALUE;

    /** A step that's just walking: no digging, no placing, no climbing. */
    private static boolean isPlainWalk(PathNode node) {
        return node.move() == PathNode.Move.WALK && node.breaks().isEmpty() && node.place() == null;
    }

    /**
     * Can a player walk from {@code from} to {@code to} in a straight line at this height
     * without falling off anything or bumping into anything? Checks the body's width too.
     */
    public static boolean straightWalkable(WorldView world, Vec3 from, Vec3 to, int y) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len / 0.3));
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = from.x + dx * t;
            double z = from.z + dz * t;
            for (double ox = -0.3; ox <= 0.3; ox += 0.6) {
                for (double oz = -0.3; oz <= 0.3; oz += 0.6) {
                    cell.set(net.minecraft.util.Mth.floor(x + ox), y, net.minecraft.util.Mth.floor(z + oz));
                    if (!world.standable(cell) || world.isWater(cell)) return false;
                }
            }
        }
        return true;
    }

    private static void steerTowards(BotPlayer bot, Controls controls, Vec3 point, double tolerance) {
        double dx = point.x - bot.getX();
        double dz = point.z - bot.getZ();
        if (dx * dx + dz * dz < tolerance * tolerance) return;
        // Move relative to where we're looking, without turning the camera.
        float yaw = bot.getYRot() * net.minecraft.util.Mth.DEG_TO_RAD;
        double forward = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
        double strafe = Math.cos(yaw) * dx + Math.sin(yaw) * dz;
        double len = Math.max(Math.abs(forward), Math.abs(strafe));
        controls.forward = (float) (forward / len) * 0.3f;
        controls.strafe = (float) (strafe / len) * 0.3f;
    }

    private int crowdedTicks;

    /** Anyone else (a bot, a player, a mob) in this cell: no block goes in there. */
    private static boolean someoneIn(BotPlayer bot, BlockPos cell) {
        return !bot.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(cell).deflate(0.01),
                e -> e != bot && e.isAlive() && !e.isSpectator()).isEmpty();
    }

    private void repath(BotPlayer bot) {
        if (++this.repaths > 5 || this.goal == null) {
            releaseBreaker(bot);
            this.status = Status.FAILED;
            this.path = null;
            return;
        }
        computePath(bot);
    }
}
