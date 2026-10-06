package net.joinedthegame.ai.project;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.task.BuildTask.Check;
import net.joinedthegame.ai.task.BuildTask.Step;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * The bots' wheat farm: a 5x5 field with a water block in the middle (it keeps every bit of
 * the field watered), inside a fence with a gate and a torch on each corner post.
 * <pre>
 *   F F F F F F F
 *   F . . . . . F      . = farmland and wheat
 *   F . . . . . F      W = water
 *   F . . W . . F      F = fence (torch on the corners)
 *   F . . . . . F      G = gate, on the side facing home
 *   F . . . . . F
 *   F F F G F F F
 * </pre>
 * The farm's position is the water block's cell, at ground level (one below where you stand).
 */
public final class Farms {
    private Farms() {}

    public static final int HALF = 2;

    /** Seeds and the crops that plant themselves. */
    public static boolean isSeed(ItemStack s) {
        return s.is(Items.WHEAT_SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT_SEEDS);
    }

    public static boolean isTillable(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.COARSE_DIRT);
    }

    /** The 24 field cells (ground level), nearest the gate side first. */
    public static List<BlockPos> field(BlockPos center) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = -HALF; x <= HALF; x++) {
            for (int z = -HALF; z <= HALF; z++) {
                if (x != 0 || z != 0) out.add(center.offset(x, 0, z));
            }
        }
        return out;
    }

    /** The fence ring (one above ground), gate cell first. */
    public static List<BlockPos> fence(BlockPos center, net.minecraft.core.Direction gateSide) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos gate = center.above().relative(gateSide, HALF + 1);
        out.add(gate);
        for (int x = -HALF - 1; x <= HALF + 1; x++) {
            for (int z = -HALF - 1; z <= HALF + 1; z++) {
                if (Math.abs(x) != HALF + 1 && Math.abs(z) != HALF + 1) continue;
                BlockPos c = center.offset(x, 1, z);
                if (!c.equals(gate)) out.add(c);
            }
        }
        return out;
    }

    public static List<BlockPos> corners(BlockPos center) {
        int r = HALF + 1;
        return List.of(center.offset(-r, 1, -r), center.offset(r, 1, -r), center.offset(-r, 1, r), center.offset(r, 1, r));
    }

    /** Most ground a bot will fix up (fill a dip, swap sand for dirt) to make a farm fit. */
    public static final int MAX_FIXES = 18;

    /**
     * A flattish bit of open ground near home for a farm, or null. Returns the centre (water)
     * cell at ground level. Prefers spots that need the least fixing, then the closest.
     */
    /** Why the last site search came up empty (debug log). */
    public static String lastRejections = "";
    private static final java.util.Map<String, Integer> REASONS = new java.util.TreeMap<>();

    private static int reject(String why) {
        REASONS.merge(why, 1, Integer::sum);
        return -1;
    }

    public static @Nullable BlockPos findSite(BotPlayer bot, BlockPos home, int radius) {
        REASONS.clear();
        WorldView w = new WorldView(bot.level());
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = home.getX() + dx;
                int z = home.getZ() + dz;
                if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos center = new BlockPos(x, y, z);
                double dist = center.distSqr(home);
                if (dist < 7 * 7) continue;
                int fixes = fixesNeeded(w, center);
                if (fixes < 0) continue;
                double score = Math.sqrt(dist) + fixes * 4;
                if (score < bestScore) {
                    best = center;
                    bestScore = score;
                }
            }
        }
        lastRejections = REASONS.toString();
        return best;
    }

    /**
     * How many ground blocks need fixing for a farm here (a dip to fill, sand or stone to swap
     * for dirt), or -1 if it's no good: water, a cliff, a building, or too much work.
     */
    private static int fixesNeeded(WorldView w, BlockPos center) {
        int r = HALF + 1;
        int fixes = 0;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                BlockPos ground = center.offset(x, 0, z);
                boolean field = Math.abs(x) <= HALF && Math.abs(z) <= HALF;
                BlockState g = w.state(ground);
                if (!g.getFluidState().isEmpty() && !(x == 0 && z == 0)) return reject("water");
                if (field ? !isGoodSoil(g) : !w.solidFloor(ground)) {
                    // A one-block dip or the wrong kind of ground: fixable. A hole: no.
                    if (g.canBeReplaced() && !w.solidFloor(ground.below())) return reject("hole");
                    if (!g.canBeReplaced() && !w.breakable(ground)) return reject("unbreakable ground");
                    fixes++;
                }
                for (int y = 1; y <= 2; y++) {
                    BlockState s = w.state(ground.above(y));
                    if (!s.getFluidState().isEmpty()) return reject("water above");
                    if (!s.canBeReplaced() && !isClearable(s)) return reject("blocked by " + s.getBlock().getName().getString());
                }
            }
        }
        if (fixes > MAX_FIXES) return reject("too bumpy");
        return w.level().canSeeSky(center.above()) ? fixes : reject("no sky");
    }

    private static boolean isGoodSoil(BlockState s) {
        return isTillable(s) || s.getBlock() instanceof FarmlandBlock;
    }

    /** Flowers and saplings can be pulled up; torches and everything else stay. */
    private static boolean isClearable(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.FLOWERS) || s.is(net.minecraft.tags.BlockTags.SAPLINGS);
    }

    /**
     * Fix the ground up first: fill dips and swap sand or stone for dirt in the field, and
     * make sure every fence post has something under it.
     */
    public static List<Step> prepare(WorldView w, BlockPos center) {
        List<Step> steps = new ArrayList<>();
        int r = HALF + 1;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x == 0 && z == 0) continue;
                BlockPos ground = center.offset(x, 0, z);
                boolean field = Math.abs(x) <= HALF && Math.abs(z) <= HALF;
                for (int y = 2; y >= 1; y--) {
                    if (isClearable(w.state(ground.above(y)))) steps.add(Step.clearOut(ground.above(y)));
                }
                if (field && !isGoodSoil(w.state(ground))) {
                    steps.add(Step.replace(ground, s -> s.is(Items.DIRT)));
                } else if (!field && !w.solidFloor(ground)) {
                    steps.add(Step.place(ground, net.joinedthegame.ai.Inv::isPlaceableBlock));
                }
            }
        }
        return steps;
    }

    /** How many dirt blocks {@link #prepare} will want. */
    public static int dirtNeeded(WorldView w, BlockPos center) {
        int n = 0;
        for (BlockPos c : field(center)) if (!isGoodSoil(w.state(c))) n++;
        return n;
    }

    /** Is the farm still there (water in the middle, farmland around)? */
    public static boolean intact(WorldView w, BlockPos center) {
        if (!w.isLoaded(center)) return true; // can't tell; assume so
        // Frozen over counts: it's still the farm, it just needs the ice broken some time.
        if (!w.state(center).getFluidState().is(Fluids.WATER) && !w.state(center).is(Blocks.ICE)) return false;
        int farmland = 0;
        for (BlockPos c : field(center)) if (w.state(c).getBlock() instanceof FarmlandBlock) farmland++;
        return farmland >= 8;
    }

    public static boolean isRipe(BlockState s) {
        return s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s);
    }

    public static int ripe(WorldView w, BlockPos center) {
        int n = 0;
        for (BlockPos c : field(center)) if (isRipe(w.state(c.above()))) n++;
        return n;
    }

    /** Farmland with nothing growing, or ground that's gone back to dirt. */
    public static int bare(WorldView w, BlockPos center) {
        int n = 0;
        for (BlockPos c : field(center)) {
            BlockState s = w.state(c);
            if ((s.getBlock() instanceof FarmlandBlock || isTillable(s)) && w.state(c.above()).isAir()) n++;
        }
        return n;
    }

    public static final Check TILLED = (w, pos) -> w.state(pos).getBlock() instanceof FarmlandBlock;
    public static final Check PLANTED = (w, pos) -> w.state(pos.above()).getBlock() instanceof CropBlock;

    /** Hoe and plant the field: for each cell, till it if needed, then sow it. */
    public static List<Step> tillAndPlant(WorldView w, BlockPos center) {
        List<Step> steps = new ArrayList<>();
        for (BlockPos c : field(center)) {
            if (w.state(c.above()).getBlock() instanceof CropBlock) continue;
            if (!w.state(c.above()).isAir()) steps.add(Step.clearOut(c.above()));
            steps.add(Step.useOnTop(c, s -> s.is(ItemTags.HOES), TILLED));
            steps.add(Step.useOnTop(c, Farms::isSeed, PLANTED));
        }
        return steps;
    }

    /** Harvest what's ripe and put seeds straight back in, like any tidy farmer. */
    public static List<Step> harvest(WorldView w, BlockPos center) {
        List<Step> steps = new ArrayList<>();
        // Frozen over: break the ice (it melts back to water) and cover it so it stays that way.
        if (w.state(center).is(Blocks.ICE)) {
            steps.add(new Step(net.joinedthegame.ai.task.BuildTask.Kind.DIG, center, null, null, false,
                    (wv, p) -> !wv.state(p).is(Blocks.ICE)));
        }
        if (w.state(center.above()).isAir()) {
            steps.add(Step.place(center.above(), net.joinedthegame.ai.Inv::isPlaceableBlock));
            steps.add(Step.place(center.above(2), s -> s.is(Items.TORCH)));
        }
        for (BlockPos c : field(center)) {
            BlockState crop = w.state(c.above());
            if (isRipe(crop)) steps.add(Step.clearOut(c.above()));
            else if (!crop.isAir() && !(crop.getBlock() instanceof CropBlock) && crop.canBeReplaced()) steps.add(Step.clearOut(c.above()));
            if (isRipe(crop) || !(crop.getBlock() instanceof CropBlock)) {
                if (!(w.state(c).getBlock() instanceof FarmlandBlock)) steps.add(Step.useOnTop(c, s -> s.is(ItemTags.HOES), TILLED));
                steps.add(Step.useOnTop(c, Farms::isSeed, PLANTED));
            }
        }
        return steps;
    }
}
