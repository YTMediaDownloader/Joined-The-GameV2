package net.joinedthegame.ai;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Who's working what right now, and whose drops are whose. Other bots can see both, the way a
 * player sees someone up a tree with an axe and the logs lying under it.
 * <ul>
 *   <li>A claim is the whole thing being worked, not one block: every log joined to the one being
 *       chopped (the trunk and its branches, however tall), or the connected vein of the same ore.
 *       Another log on the same tree is the same tree. One claim per bot, renewed every tick while
 *       it works, gone a few seconds after it stops.</li>
 *   <li>A break is a block a bot just broke: what drops from it is that bot's for a minute.
 *       Picking that up on purpose is taking what's theirs, not just competing for the tree.</li>
 * </ul>
 * Server-wide and only in memory: nothing to save.
 */
public final class Claims {
    /** @param since when it started on this tree (kept through renewals: who got there first) */
    public record Claim(UUID owner, String name, ResourceKey<Level> dim, BlockPos pos, Set<BlockPos> blocks, long since, long at) {}

    /** @param treeOwner whose tree it came off, if it was someone else's (their drops, really) */
    public record Break(UUID owner, String name, ResourceKey<Level> dim, BlockPos pos, @Nullable UUID treeOwner, long at) {}

    private static final Map<UUID, Claim> CLAIMS = new ConcurrentHashMap<>();
    private static final List<Break> BREAKS = new CopyOnWriteArrayList<>();
    /**
     * How long a claim lasts without being renewed: long enough to step away and pick up the
     * drops, or put a sapling down, without the tree stopping being ours.
     */
    private static final int FRESH = 20 * 12;
    /** How long a bot's drops count as its own. */
    private static final int DROPS_THEIRS = 20 * 60;

    private Claims() {}

    /**
     * Working {@code pos}: claim it and everything joined to it of the same kind (a tree's logs, a
     * vein's ore), up to {@code cap} blocks. Still on the same tree or vein: just renewed.
     */
    public static void claim(BotPlayer bot, BlockPos pos, Predicate<BlockState> sameKind, int cap) {
        long now = bot.level().getGameTime();
        Claim had = CLAIMS.get(bot.getUUID());
        if (had != null && had.dim() == bot.level().dimension() && had.blocks().contains(pos) && now - had.at() <= FRESH) {
            CLAIMS.put(bot.getUUID(), new Claim(had.owner(), had.name(), had.dim(), had.pos(), had.blocks(), had.since(), now));
            return;
        }
        CLAIMS.put(bot.getUUID(), new Claim(bot.getUUID(), bot.getScoreboardName(), bot.level().dimension(), pos.immutable(),
                connected(bot.level(), pos, sameKind, cap), now, now));
    }

    /** Everything joined to {@code start} (sides, edges and corners: branches go diagonally). */
    private static Set<BlockPos> connected(Level level, BlockPos start, Predicate<BlockState> sameKind, int cap) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start.immutable());
        queue.add(start.immutable());
        while (!queue.isEmpty() && seen.size() < cap) {
            BlockPos p = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = p.offset(dx, dy, dz);
                        if (seen.contains(n) || !level.isLoaded(n) || !sameKind.test(level.getBlockState(n))) continue;
                        seen.add(n);
                        queue.add(n);
                    }
                }
            }
        }
        return Set.copyOf(seen);
    }

    public static void release(BotPlayer bot) {
        CLAIMS.remove(bot.getUUID());
    }

    private static boolean live(Claim c, BotPlayer bot, long now) {
        return !c.owner().equals(bot.getUUID()) && c.dim() == bot.level().dimension() && now - c.at() <= FRESH;
    }

    /**
     * Part of a tree (or vein) that's someone else's right now: the one who got there first. If
     * that's us (we were on it before they piled in), it's ours, not theirs.
     */
    public static @Nullable Claim someoneElses(BotPlayer bot, BlockPos pos) {
        long now = bot.level().getGameTime();
        Claim mine = CLAIMS.get(bot.getUUID());
        long mySince = mine != null && now - mine.at() <= FRESH && mine.blocks().contains(pos) ? mine.since() : Long.MAX_VALUE;
        Claim first = null;
        for (Claim c : CLAIMS.values()) {
            if (!live(c, bot, now) || !c.blocks().contains(pos) || c.since() >= mySince) continue;
            if (first == null || c.since() < first.since()) first = c;
        }
        return first;
    }

    /** Someone else working within {@code radius} of {@code pos} (for trees seen from far off). */
    public static @Nullable Claim someoneElsesNear(BotPlayer bot, BlockPos pos, int radius) {
        long now = bot.level().getGameTime();
        for (Claim c : CLAIMS.values()) {
            if (!live(c, bot, now)) continue;
            // Flat: a treetop twenty blocks up is still that tree.
            double dx = c.pos().getX() - pos.getX(), dz = c.pos().getZ() - pos.getZ();
            if (dx * dx + dz * dz <= (double) radius * radius) return c;
        }
        return null;
    }

    /** That bot's current claim, if it's still working it. */
    public static @Nullable Claim of(UUID owner, long now) {
        Claim c = CLAIMS.get(owner);
        return c != null && now - c.at() <= FRESH ? c : null;
    }

    // --- drops ---------------------------------------------------------------------

    public static void noteBreak(BotPlayer bot, BlockPos pos) {
        long now = bot.level().getGameTime();
        BREAKS.removeIf(b -> now - b.at() > DROPS_THEIRS);
        Claim tree = someoneElses(bot, pos);
        BREAKS.add(new Break(bot.getUUID(), bot.getScoreboardName(), bot.level().dimension(), pos.immutable(),
                tree == null ? null : tree.owner(), now));
    }

    /**
     * Someone else's drop: it came out of a block another bot broke (it appeared then, just there
     * or fallen straight down from it), in the last minute.
     */
    public static @Nullable Break whoseDrop(BotPlayer finder, ItemEntity item) {
        long now = finder.level().getGameTime();
        for (Break b : BREAKS) {
            if (b.owner().equals(finder.getUUID()) || b.dim() != finder.level().dimension() || now - b.at() > DROPS_THEIRS) continue;
            // Only what came off their own tree is plainly theirs. Chopped off a tree someone else
            // had first (they piled in on it), it's contested: picking it up isn't stealing from
            // them, and if it was our tree, it's our wood back.
            if (b.treeOwner() != null) continue;
            double dx = item.getX() - (b.pos().getX() + 0.5), dz = item.getZ() - (b.pos().getZ() + 0.5);
            if (dx * dx + dz * dz > 3 * 3 || item.getY() > b.pos().getY() + 1.5) continue;
            // Born when that block went (give or take), not something lying there already.
            if (Math.abs(item.tickCount - (now - b.at())) > 10) continue;
            return b;
        }
        return null;
    }
}
