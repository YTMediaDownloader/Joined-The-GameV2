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
    public record Options(boolean allowDig, boolean allowPlace, int maxFall, int maxNodes) {
        public static Options normal(BotPlayer bot) {
            return new Options(true, Inv.scaffoldCount(bot) > 0, 3, 3000);
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
        this.world = new WorldView(bot.level());
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
    }

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
                double g = node.g + c.cost;
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
        } else if (canPlace && w.hasFloor(p) && w.passable(p.above(2))) {
            // Pillar up
            out.add(new Candidate(new PathNode(p.above(), PathNode.Move.PILLAR, List.of(), p), 5.0));
        }

        // Dig down
        if (this.options.allowDig && !inWater) {
            BlockPos below = p.below();
            if (w.breakable(below) && w.solidFloor(below.below()) && !w.isLava(below.below())) {
                out.add(new Candidate(new PathNode(below, PathNode.Move.DIG_DOWN, List.of(below), null), 2.0 + digCost(below)));
            }
        }
        return out;
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
