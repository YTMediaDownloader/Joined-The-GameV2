package net.joinedthegame.ai;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/** What the bot can see around it. Scans never load chunks. */
public final class Senses {
    private Senses() {}

    /**
     * Nearest block matching {@code match} within a box around the bot.
     *
     * @param exposedOnly only blocks with at least one open face (what a player could see)
     * @param skip        positions to ignore (e.g. recently failed targets)
     */
    public static @Nullable BlockPos findBlock(BotPlayer bot, Predicate<BlockState> match, int radius, int yRadius,
                                               boolean exposedOnly, Predicate<BlockPos> skip) {
        WorldView world = new WorldView(bot.level());
        BlockPos origin = bot.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -yRadius; dy <= yRadius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Blocks overhead count as further away: a player chops the bottom of a
                    // tree before worrying about the top.
                    double dist = dx * dx + dz * dz + dy * dy * (dy > 1 ? 4.0 : 1.5);
                    if (dist >= bestDist) continue;
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = world.state(pos);
                    if (!match.test(state)) continue;
                    if (exposedOnly && !isExposed(world, pos)) continue;
                    if (skip.test(pos)) continue;
                    best = pos.immutable();
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    public static boolean isExposed(WorldView world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos n = pos.relative(dir);
            if (world.passable(n) || world.isWater(n)) return true;
        }
        return false;
    }

    public static List<ItemEntity> itemsNear(BotPlayer bot, BlockPos center, double radius) {
        AABB box = new AABB(center).inflate(radius);
        List<ItemEntity> items = bot.level().getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && !e.getItem().isEmpty());
        items.sort(Comparator.comparingDouble(e -> e.distanceToSqr(bot)));
        return items;
    }

    public static <T extends Entity> List<T> entitiesNear(BotPlayer bot, Class<T> type, double radius, Predicate<T> filter) {
        List<T> list = bot.level().getEntitiesOfClass(type, bot.getBoundingBox().inflate(radius), e -> e != bot && filter.test(e));
        list.sort(Comparator.comparingDouble(e -> e.distanceToSqr(bot)));
        return list;
    }

    public static boolean canSee(BotPlayer bot, Entity entity) {
        return bot.hasLineOfSight(entity);
    }
}
