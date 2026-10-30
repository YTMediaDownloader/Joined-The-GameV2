package net.joinedthegame.ai;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which logs are wood to chop: trees, not buildings. The game doesn't say whether a log grew or
 * was put there, so it's told the way a player tells at a glance, from the logs joined to it:
 * <ol>
 *   <li>Built into something? If any of them touches building stuff (planks, stairs, slabs, doors,
 *       fences, glass, cobble, bricks...), it's part of a building: never wood, whatever leaves are
 *       about. Every house's posts and beams touch its walls or roof; trees don't. (A treehouse
 *       nailed onto a tree leaves that tree alone too, which is only right.)</li>
 *   <li>A tree wears its own leaves near its top: natural leaves (the kind a tree grows, that rot
 *       away without it) within a couple of blocks of the top few layers of logs. That takes in
 *       acacias, big oaks and cherries, whose branch tips don't always have leaves straight on
 *       top. Leaves anyone places are marked persistent, so a house decorated with leaves doesn't
 *       turn into a forest.</li>
 *   <li>Or it's a fallen tree: a few logs lying on the ground, nothing built about them.</li>
 * </ol>
 * The bot's own house, another bot's, a village's, a player's build: none of them are wood.
 */
public final class Trees {
    private Trees() {}

    /** Answers kept a few seconds: a search asks about the same logs over and over. */
    private static final Map<Long, Boolean> NATURAL = new HashMap<>();
    private static long cachedAt = Long.MIN_VALUE;

    /** A log that's wood to chop: part of a tree, not part of anything anybody built. */
    public static boolean choppable(BotPlayer bot, BlockPos log) {
        return natural(bot.level(), log);
    }

    public static boolean natural(Level level, BlockPos log) {
        long now = level.getGameTime();
        if (now - cachedAt > 100) {
            NATURAL.clear();
            cachedAt = now;
        }
        Boolean known = NATURAL.get(log.asLong());
        if (known != null) return known;
        Set<BlockPos> logs = joined(level, log);
        boolean tree = verdict(level, logs) == null;
        for (BlockPos p : logs) NATURAL.put(p.asLong(), tree); // one tree, or one building
        return tree;
    }

    /** Why it isn't a tree (null if it is): for the debug command and the log. */
    public static String explain(Level level, BlockPos log) {
        if (!level.getBlockState(log).is(BlockTags.LOGS)) return "not a log";
        Set<BlockPos> logs = joined(level, log);
        String why = verdict(level, logs);
        return (why == null ? "a tree" : "not a tree: " + why) + " (" + logs.size() + " logs joined)";
    }

    /** The logs joined to this one (sides, edges, corners: branches go diagonally). */
    private static Set<BlockPos> joined(Level level, BlockPos log) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(log.immutable());
        queue.add(log.immutable());
        while (!queue.isEmpty() && seen.size() < 300) {
            BlockPos p = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = p.offset(dx, dy, dz);
                        if (!seen.contains(n) && level.isLoaded(n) && level.getBlockState(n).is(BlockTags.LOGS)) {
                            seen.add(n);
                            queue.add(n);
                        }
                    }
                }
            }
        }
        return seen;
    }

    private static @org.jspecify.annotations.Nullable String verdict(Level level, Set<BlockPos> logs) {
        // 1. Built into something.
        for (BlockPos p : logs) {
            for (Direction d : Direction.values()) {
                BlockState s = level.getBlockState(p.relative(d));
                if (built(s)) return "touches " + s.getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT)
                        + " at " + p.relative(d).toShortString();
            }
        }
        // 2. Its own leaves near the top.
        int top = Integer.MIN_VALUE;
        for (BlockPos p : logs) top = Math.max(top, p.getY());
        for (BlockPos p : logs) {
            if (p.getY() < top - 2) continue;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (naturalLeaves(level.getBlockState(p.offset(dx, dy, dz)))) return null;
                    }
                }
            }
        }
        // 3. A fallen tree: a few logs, every one lying on the ground or on another of them.
        if (logs.size() <= 10) {
            boolean lying = true;
            for (BlockPos p : logs) {
                BlockState below = level.getBlockState(p.below());
                if (!logs.contains(p.below()) && !ground(below)) {
                    lying = false;
                    break;
                }
            }
            if (lying) return null;
        }
        return "no leaves of its own";
    }

    private static boolean naturalLeaves(BlockState s) {
        return s.is(BlockTags.LEAVES) && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT);
    }

    private static boolean ground(BlockState s) {
        return s.is(BlockTags.SUBSTRATE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.CLAY);
    }

    /** Stuff people build with (and never grows on a tree). */
    private static boolean built(BlockState s) {
        return s.is(BlockTags.PLANKS) || s.is(BlockTags.STAIRS) || s.is(BlockTags.SLABS) || s.is(BlockTags.DOORS)
                || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.FENCES) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WALLS)
                || s.is(BlockTags.BEDS) || s.is(BlockTags.WOOL) || s.is(BlockTags.IMPERMEABLE) // glass
                || s.is(Blocks.GLASS_PANE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.STONE_BRICKS)
                || s.is(Blocks.BRICKS) || s.is(Blocks.CHEST) || s.is(Blocks.CRAFTING_TABLE) || s.is(Blocks.FURNACE)
                || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH) || s.is(Blocks.LANTERN) || s.is(Blocks.LADDER);
    }
}
