package net.joinedthegame.bot;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Beds and the storage around them. */
public final class Homes {
    /** Chests within this many blocks of a bot's bed are its storage. */
    /** How far from the bed the home chests can be (a house's storage room is at the back). */
    public static final int STORAGE_RADIUS = 8;

    private Homes() {}

    /** Beds are two blocks; ownership is always tracked on the head half. */
    public static BlockPos headOf(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof AbstractBedBlock && state.getValue(AbstractBedBlock.PART) == BedPart.FOOT) {
            return pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
        }
        return pos;
    }

    /** True if a real player has this bed set as their spawn point. */
    public static boolean isPlayerSpawnBed(ServerLevel level, BlockPos head) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player instanceof BotPlayer) continue;
            ServerPlayer.RespawnConfig respawn = player.getRespawnConfig();
            if (respawn == null) continue;
            if (respawn.respawnData().dimension() == level.dimension() && respawn.respawnData().pos().closerThan(head, 1.5)) return true;
        }
        return false;
    }

    /**
     * Nearest bed nobody has dibs on: not another bot's home, not a player's spawn point,
     * nobody sleeping in it. Villager beds count, so a homeless bot will happily move into a
     * village house. Uses the game's point-of-interest index (every bed is registered as a
     * "home"), so this is cheap even over a big radius.
     */
    public static @org.jspecify.annotations.Nullable BlockPos findFreeBed(ServerLevel level, BlockPos near, int radius,
                                                                          java.util.function.Predicate<BlockPos> skip) {
        BotManager manager = BotManager.get(level.getServer());
        return level.getPoiManager()
                .findAll(type -> type.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.HOME), pos -> true, near, radius,
                        net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
                .map(pos -> headOf(level, pos))
                .distinct()
                .filter(head -> {
                    BlockState state = level.getBlockState(head);
                    if (!state.is(net.minecraft.tags.BlockTags.BEDS) || state.getValue(AbstractBedBlock.OCCUPIED)) return false;
                    return manager.bedOwner(level, head) == null && !isPlayerSpawnBed(level, head) && !skip.test(head);
                })
                .min(java.util.Comparator.comparingDouble(pos -> pos.distSqr(near)))
                .orElse(null);
    }

    /** Chests and barrels near a bed. */
    public static List<BlockPos> storageNear(ServerLevel level, BlockPos bed) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int r = STORAGE_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -5; dy <= 5; dy++) { // (a two-storey house: the bed upstairs, storage down)
                for (int dz = -r; dz <= r; dz++) {
                    pos.set(bed.getX() + dx, bed.getY() + dy, bed.getZ() + dz);
                    if (!level.isLoaded(pos)) continue;
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity) {
                        if (level.getBlockState(pos).getBlock() instanceof ChestBlock && ChestBlock.isChestBlockedAt(level, pos)) continue;
                        out.add(pos.immutable());
                    }
                }
            }
        }
        return out;
    }
}
