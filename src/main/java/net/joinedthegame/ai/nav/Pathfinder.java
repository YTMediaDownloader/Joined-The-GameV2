package net.joinedthegame.ai.nav;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A* search over block positions, using the moves a survival player actually has:
 * walk, jump up a block, drop down, swim, climb, dig through, dig down, bridge across a
 * gap and pillar up. Costs are roughly "ticks of effort", so digging through stone is
 * avoided when there's a way around.
 */
public final class Pathfinder {
    /**
     * {@code maxY}: never route above this height; {@code covered}: never anywhere open to the sky
     * (both for getting away underground, out of sight: a tunnel along a hillside can come out of
     * the slope right in front of what we're hiding from).
     */
    public record Options(boolean allowDig, boolean allowPlace, int maxFall, int maxNodes, int maxY, boolean covered) {
        public static Options normal(BotPlayer bot) {
            // The reckless take the quick way down (five blocks, and the knock that comes with it),
            // when they've the health for it; everyone else climbs down properly.
            var brain = bot.brain();
            boolean daredevil = brain != null && brain.personality().reckless() > 0.6 && bot.getHealth() > 12;
            return new Options(true, Inv.scaffoldCount(bot) > 0, daredevil ? 5 : 3, 3000, Integer.MAX_VALUE, false);
        }

        public Options below(int y) {
            return new Options(this.allowDig, this.allowPlace, this.maxFall, this.maxNodes, y, this.covered || y != Integer.MAX_VALUE);
        }

        /** Never out in the open (mining at night from a hole: no wandering back up to the surface). */
        public Options underCover() {
            return new Options(this.allowDig, this.allowPlace, this.maxFall, this.maxNodes, this.maxY, true);
        }
    }

    /** Result of a search. {@code complete} is false for a best-effort partial path. */
    public record Path(List<PathNode> nodes, boolean complete) {}

    private static final class Node implements Comparable<Node> {
        final PathNode step;
        final double g;
        final double f;
        final double h;
        final @Nullable Node parent;
        final int placed;

        Node(PathNode step, double g, double h, @Nullable Node parent, int placed) {
            this.step = step;
            this.g = g;
            this.h = h;
            this.f = g + h * 1.2;
            this.parent = parent;
            this.placed = placed;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.f, o.f);
        }
    }

    private final WorldView world;
    private final BotPlayer bot;
    private final Options options;
    private final int scaffoldBudget;

    public Pathfinder(BotPlayer bot, Options options) {
        this.bot = bot;
        this.world = new WorldView(bot.level()).cached(); // one search, one tick: safe to remember
        // Frost Walker boots on, and on our feet (it does nothing while swimming): water's a road.
        this.world.frostWalk = !bot.isInWater() && frostWalker(bot);
        this.options = options;
        this.scaffoldBudget = Inv.scaffoldCount(bot);
        // Our own house and home spot: walls there are ours, so use the door.
        var brain = bot.brain();
        if (brain != null) {
            for (var gp : new net.minecraft.core.GlobalPos[] {brain.record().house(), brain.record().home()}) {
                if (gp != null && gp.dimension() == bot.level().dimension()) this.homes.add(gp.pos());
            }
            var house = brain.record().house();
            if (house != null && house.dimension() == bot.level().dimension()) this.house = house.pos();
        }
        // Where other people (and mobs) are standing: no block can go down there while they are
        // (the game won't place one into anybody), and squeezing in on top of them is a last resort.
        // Three bots in a one-wide hole each tried to pillar out on the others' heads, for ever.
        for (var e : bot.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, bot.getBoundingBox().inflate(48),
                e -> e != bot && e.isAlive() && !e.isSpectator())) {
            var box = e.getBoundingBox().deflate(0.01);
            for (BlockPos c : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
                this.occupied.add(c.asLong());
            }
        }
    }

    /** Boots with Frost Walker on (from a ruined portal chest, or enchanted). */
    public static boolean frostWalker(BotPlayer bot) {
        var boots = bot.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET);
        for (var e : boots.getEnchantments().entrySet()) {
            if (e.getKey().unwrapKey().map(k -> k.identifier().getPath().equals("frost_walker")).orElse(false)) return true;
        }
        return false;
    }

    /** Cells somebody else is in right now (as of planning). */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet occupied = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    /** Our house's door (the house reaches up to 11 blocks from it, two storeys and a roof up). */
    private @Nullable BlockPos house;

    /** Part of our own house: walls, floors, the roof, the foundation. Never a shortcut. */
    private boolean inOurHouse(BlockPos pos) {
        BlockPos h = this.house;
        return h != null && Math.abs(pos.getX() - h.getX()) <= 11 && Math.abs(pos.getZ() - h.getZ()) <= 11
                && pos.getY() >= h.getY() - 2 && pos.getY() <= h.getY() + 9;
    }

    private final List<BlockPos> homes = new ArrayList<>(2);

    /**
     * Someone built this: planks, glass, bricks, wool, stairs... Digging through a house wall
     * because the door is round the other side is not what players do.
     */
    public static boolean builtBlock(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.PLANKS) || s.is(net.minecraft.tags.BlockTags.STAIRS)
                || s.is(net.minecraft.tags.BlockTags.SLABS) || s.is(net.minecraft.tags.BlockTags.WOOL)
                || s.is(net.minecraft.tags.BlockTags.FENCES) || s.is(net.minecraft.tags.BlockTags.WALLS)
                || s.is(net.minecraft.tags.BlockTags.STONE_BRICKS) || s.is(net.minecraft.world.level.block.Blocks.BRICKS)
                || s.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_BLOCKS) || s.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_PANES)
                || s.is(net.minecraft.tags.BlockTags.TRAPDOORS) || s.is(net.minecraft.world.level.block.Blocks.BOOKSHELF);
    }

    private boolean nearHome(BlockPos pos) {
        for (BlockPos h : this.homes) {
            if (Math.abs(h.getX() - pos.getX()) <= 7 && Math.abs(h.getZ() - pos.getZ()) <= 7 && Math.abs(h.getY() - pos.getY()) <= 5) return true;
        }
        return false;
    }

    public WorldView world() {
        return this.world;
    }

    public @Nullable Path find(BlockPos start, Vec3 target, Predicate<BlockPos> isGoal) {
        PriorityQueue<Node> open = new PriorityQueue<>();
        Long2ObjectOpenHashMap<Node> best = new Long2ObjectOpenHashMap<>();
        Node first = new Node(PathNode.start(start), 0, heuristic(start, target), null, 0);
        open.add(first);
        best.put(start.asLong(), first);
        Node closest = first;
        int expanded = 0;

        while (!open.isEmpty() && expanded < this.options.maxNodes) {
            Node node = open.poll();
            if (node.g > best.get(node.step.pos().asLong()).g) continue;
            expanded++;
            if (isGoal.test(node.step.pos())) return new Path(trace(node), true);
            if (node.h < closest.h) closest = node;
            for (Candidate c : neighbours(node.step.pos(), node.placed)) {
                if (c.step.pos().getY() > this.options.maxY) continue;
                if (this.options.covered && !c.step.pos().closerThan(start, 1.8) && outInTheOpen(c.step.pos())) continue;
                if (c.step.place() != null && this.occupied.contains(c.step.place().asLong())) continue; // someone's in the way
                double g = node.g + c.cost + (this.occupied.contains(c.step.pos().asLong()) ? 3 : 0)
                        + (besideLava(c.step.pos()) ? 12 : 0);
                long key = c.step.pos().asLong();
                Node existing = best.get(key);
                if (existing != null && existing.g <= g) continue;
                Node next = new Node(c.step, g, heuristic(c.step.pos(), target), node, node.placed + (c.step.place() != null ? 1 : 0));
                best.put(key, next);
                open.add(next);
            }
        }
        if (closest == first) return null;
        return new Path(trace(closest), false);
    }

    /**
     * Lava right beside this spot (any of the eight cells round it, at our feet or the ground
     * we'd step on): a player gives it a block's room. A bot is 0.6 wide and cuts the corner
     * a little steering; walking the very edge of a pool, one nudge and it's in (Redlantern, on
     * his way to some trees). Not forbidden (sometimes it's the only way, the nether), just dear.
     */
    private boolean besideLava(BlockPos feet) {
        // Once per cell a search (the same cell comes up again and again as a neighbour).
        long key = feet.asLong();
        byte known = this.lavaBeside.get(key);
        if (known != 0) return known == 2;
        boolean near = lavaAround(feet);
        this.lavaBeside.put(key, near ? (byte) 2 : (byte) 1);
        return near;
    }

    /** Remembered answers for this search: 0 not worked out, 1 no lava beside, 2 lava beside. */
    private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap lavaBeside = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();

    private boolean lavaAround(BlockPos feet) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (this.world.isLava(feet.offset(dx, 0, dz)) || this.world.isLava(feet.offset(dx, -1, dz))) return true;
            }
        }
        return false;
    }

    /**
     * Standing here, could something outside see us? Any open cell beside us (or overhead) at
     * foot or head height that daylight gets into: the sky, or a hillside face opening onto it.
     */
    private boolean outInTheOpen(BlockPos feet) {
        var level = this.bot.level();
        for (BlockPos cell : new BlockPos[]{feet, feet.above()}) {
            if (level.canSeeSky(cell)) return true;
            for (Direction d : Direction.values()) {
                if (d == Direction.DOWN) continue;
                BlockPos n = cell.relative(d);
                if (this.world.passable(n) && level.getBrightness(net.minecraft.world.level.LightLayer.SKY, n) >= 8) return true;
            }
        }
        return false;
    }

    private static double heuristic(BlockPos pos, Vec3 target) {
        double dx = pos.getX() + 0.5 - target.x;
        double dy = pos.getY() - target.y;
        double dz = pos.getZ() + 0.5 - target.z;
        return Math.sqrt(dx * dx + dy * dy * 1.5 + dz * dz);
    }

    private static List<PathNode> trace(Node node) {
        List<PathNode> out = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent) out.add(n.step);
        Collections.reverse(out);
        return out;
    }

    private record Candidate(PathNode step, double cost) {}

    private List<Candidate> neighbours(BlockPos p, int placedSoFar) {
        List<Candidate> out = new ArrayList<>(12);
        WorldView w = this.world;
        boolean canPlace = this.options.allowPlace && placedSoFar < this.scaffoldBudget;
        boolean inWater = w.isWater(p);

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos q = p.relative(dir);

            double here = w.floorTop(p);

            // Walk (players step up anything under ~0.6 blocks without jumping)
            if (w.standable(q) && w.floorTop(q) - here <= 0.6) {
                double cost = w.strongCurrent(q) ? 8.0 : w.isWater(q) ? 3.0 : 1.0;
                out.add(new Candidate(new PathNode(q, PathNode.Move.WALK, List.of(), null), cost));
                continue;
            }

            // Jump up one block
            BlockPos up = q.above();
            // A real jump only gets you ~1.25 blocks up: from a bed or slab, a full block is too high.
            // Nobody jumps up a waterfall: the current pushes you straight back down.
            if (w.passable(p.above()) && w.passable(p.above(2)) && w.standable(up) && !w.passable(q)
                    && w.floorTop(up) - here <= 1.2 && !w.strongCurrent(up) && !w.strongCurrent(up.above())) {
                out.add(new Candidate(new PathNode(up, PathNode.Move.JUMP_UP, List.of(), null), 2.0));
                continue;
            }

            // Drop down
            if (w.passable(q) && w.passable(q.above()) && !w.hasFloor(q)) {
                boolean landed = false;
                for (int k = 1; k <= 24; k++) {
                    BlockPos r = q.below(k);
                    if (w.isWater(r)) {
                        out.add(new Candidate(new PathNode(r, PathNode.Move.DROP, List.of(), null), 2.0 + k * 0.3));
                        landed = true;
                        break;
                    }
                    if (!w.passable(r)) break;
                    if (w.hasFloor(r) && w.passable(r.above())) {
                        if (k <= this.options.maxFall) {
                            out.add(new Candidate(new PathNode(r, PathNode.Move.DROP, List.of(), null), 1.0 + k * 0.5));
                            landed = true;
                        }
                        break;
                    }
                }
                // Bridge across the gap instead
                if (canPlace && w.passable(q.below()) && !w.isWater(q.below()) && w.solidFloor(p.below())) {
                    out.add(new Candidate(new PathNode(q, PathNode.Move.BRIDGE, List.of(), q.below()), landed ? 6.0 : 4.0));
                }
                continue;
            }

            if (!this.options.allowDig) continue;

            // Dig straight through (feet + head)
            if (w.hasFloor(q) && w.clearOrBreakable(q) && w.clearOrBreakable(q.above())) {
                double cost = 1.0 + digCost(q) + digCost(q.above());
                out.add(new Candidate(new PathNode(q, PathNode.Move.WALK, breaks(q.above(), q), null), cost));
            }

            // Dig a step up
            if (!w.passable(q) && w.solidFloor(q) && w.clearOrBreakable(up) && w.clearOrBreakable(up.above())
                    && w.clearOrBreakable(p.above(2)) && (q.getY() + 1) - here <= 1.2) {
                double cost = 2.0 + digCost(p.above(2)) + digCost(up) + digCost(up.above());
                out.add(new Candidate(new PathNode(up, PathNode.Move.JUMP_UP, breaks(p.above(2), up.above(), up), null), cost));
            }

            // Dig a step down (staircase)
            BlockPos down = q.below();
            if (w.hasFloor(down) && w.clearOrBreakable(down) && w.clearOrBreakable(q) && w.clearOrBreakable(q.above())
                    && !w.isLava(down.below())) {
                double cost = 1.5 + digCost(q.above()) + digCost(q) + digCost(down);
                out.add(new Candidate(new PathNode(down, PathNode.Move.DROP, breaks(q.above(), q, down), null), cost));
            }
        }

        // Swim or climb up
        BlockPos above = p.above();
        // Swim or climb up, but only where there's room for the head: no swimming up into a
        // pocket under a stone ceiling.
        if ((inWater || w.climbable(p)) && w.passable(above) && w.passable(above.above())
                && (w.isWater(above) || w.climbable(above) || w.standable(above))) {
            out.add(new Candidate(new PathNode(p.above(), PathNode.Move.UP, List.of(), null), 1.5));
        } else if (canPlace && w.hasFloor(p) && w.passable(p.above(2)) && w.floorTop(p) >= p.getY() - 0.01) {
            // Pillar up (not off a slab or a bed: from half a block down a jump can't get clear
            // of the block that has to go in under us, so it'd jump on the spot for ever)
            out.add(new Candidate(new PathNode(p.above(), PathNode.Move.PILLAR, List.of(), p), 5.0));
        }

        // Dig down
        if (this.options.allowDig && !inWater) {
            BlockPos below = p.below();
            // (Never beside water: the hole floods, and whoever's in it, or falls in later, is
            // stuck treading water in a pit. Two bots at a pond's edge were, for minutes.)
            if (w.breakable(below) && w.solidFloor(below.below()) && !w.isLava(below.below()) && !floods(w, below)) {
                out.add(new Candidate(new PathNode(below, PathNode.Move.DIG_DOWN, List.of(below), null), 2.0 + digCost(below)));
            }
        }
        return out;
    }

    /** Water right beside this cell (or over it): dig it out and it fills. */
    private static boolean floods(WorldView w, BlockPos cell) {
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) if (w.isWater(cell.relative(d))) return true;
        return false;
    }

    private double digCost(BlockPos pos) {
        if (this.world.passable(pos)) return 0;
        double cost = this.world.breakTicks(this.bot, pos) / 6.0 + 1.0;
        // Walls: worth a long walk round to the door (still possible if there's no way out at all).
        if (builtBlock(this.world.state(pos)) || inOurHouse(pos)) cost += 40;
        else if (nearHome(pos)) cost += 8; // a dug-out home's dirt walls
        return cost;
    }

    /** Only the cells that actually need digging, in the given order. */
    private List<BlockPos> breaks(BlockPos... cells) {
        List<BlockPos> out = new ArrayList<>(cells.length);
        for (BlockPos cell : cells) if (!this.world.passable(cell)) out.add(cell.immutable());
        return out;
    }
}
