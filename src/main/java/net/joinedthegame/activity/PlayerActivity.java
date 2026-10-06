package net.joinedthegame.activity;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps track of what each real player is up to, so bots can follow their lead: mine
 * near them when they're mining, come along when they head off exploring.
 */
public final class PlayerActivity {
    private PlayerActivity() {}

    public record Mining(UUID player, BlockPos pos, long time) {}

    private static final Map<UUID, Mining> LAST_MINING = new HashMap<>();
    /** Positions sampled once a second, last 30 seconds. */
    private static final Map<UUID, ArrayDeque<Vec3>> TRAIL = new HashMap<>();

    public static void clear() {
        LAST_MINING.clear();
        TRAIL.clear();
    }

    public static void onBlockBroken(ServerPlayer player, BlockPos pos, BlockState state) {
        if (player instanceof BotPlayer) return;
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.ORES) || state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            LAST_MINING.put(player.getUUID(), new Mining(player.getUUID(), pos.immutable(), player.level().getGameTime()));
        }
    }

    public static void sample(ServerPlayer player) {
        if (player instanceof BotPlayer) return;
        ArrayDeque<Vec3> trail = TRAIL.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        trail.addLast(player.position());
        while (trail.size() > 30) trail.removeFirst();
    }

    public static void forget(UUID player) {
        LAST_MINING.remove(player);
        TRAIL.remove(player);
    }

    /** Most recent mining by a real player within {@code range} of the bot, if recent enough. */
    public static @Nullable Mining recentMiningNear(BotPlayer bot, double range, long maxAgeTicks) {
        long now = bot.level().getGameTime();
        Mining best = null;
        for (Mining mining : LAST_MINING.values()) {
            if (now - mining.time() > maxAgeTicks) continue;
            ServerPlayer player = bot.level().getServer().getPlayerList().getPlayer(mining.player());
            if (player == null || player.level() != bot.level()) continue;
            if (mining.pos().distToCenterSqr(bot.position()) > range * range) continue;
            if (best == null || mining.time() > best.time()) best = mining;
        }
        return best;
    }

    /** Has this player covered real ground in the last ~20 seconds? */
    public static boolean isExploring(ServerPlayer player) {
        ArrayDeque<Vec3> trail = TRAIL.get(player.getUUID());
        if (trail == null || trail.size() < 10) return false;
        Vec3[] points = trail.toArray(new Vec3[0]);
        Vec3 then = points[Math.max(0, points.length - 20)];
        Vec3 nowPos = points[points.length - 1];
        double dx = nowPos.x - then.x;
        double dz = nowPos.z - then.z;
        return dx * dx + dz * dz > 24 * 24;
    }

    public static @Nullable ServerPlayer exploringPlayerNear(BotPlayer bot, double range) {
        for (ServerPlayer player : bot.level().players()) {
            if (player instanceof BotPlayer || player.isSpectator()) continue;
            if (player.distanceToSqr(bot) > range * range) continue;
            if (isExploring(player)) return player;
        }
        return null;
    }
}
