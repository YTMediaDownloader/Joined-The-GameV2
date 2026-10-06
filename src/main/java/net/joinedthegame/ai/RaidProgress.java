package net.joinedthegame.ai;

import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

/**
 * How far through a structure's loot a bot is. A structure only counts as raided once every
 * chest, barrel, minecart chest and vault in it is done (emptied by us, or found empty).
 * Progress is kept in memory, so it can be finished over several visits.
 */
public final class RaidProgress {
    private RaidProgress() {}

    public record Progress(int done, int total) {
        public boolean complete() {
            return this.total > 0 && this.done >= this.total;
        }
    }

    /** Count the containers in the structure (in loaded chunks) and how many are done. */
    public static Progress of(BotPlayer bot, BotBrain brain, Structures.Known k) {
        var box = k.box();
        var level = bot.level();
        BotManager manager = BotManager.get(level.getServer());
        int total = 0;
        int done = 0;
        int minCx = box.minX() >> 4, maxCx = box.maxX() >> 4, minCz = box.minZ() >> 4, maxCz = box.maxZ() >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    if (!box.isInside(p)) continue;
                    boolean vault = be instanceof VaultBlockEntity;
                    if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || vault)) continue;
                    if (manager.isBotStorage(level, p)) continue; // a bot's home, not loot
                    total++;
                    GlobalPos gp = GlobalPos.of(level.dimension(), p);
                    if (brain.memory().isLooted(gp) || !vault && be instanceof Container c && c.isEmpty()) done++;
                }
            }
        }
        AABB area = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
        for (MinecartChest cart : level.getEntitiesOfClass(MinecartChest.class, area, c -> c.isAlive())) {
            total++;
            if (cart.isEmpty() || brain.memory().isLooted(GlobalPos.of(level.dimension(), cart.blockPosition()))) done++;
        }
        return new Progress(done, total);
    }

    /** Everything in it done? Then (once) it goes in the diary as raided. */
    public static void checkComplete(BotPlayer bot, BotBrain brain, Structures.Known k) {
        if (!k.kind().loot) return;
        GlobalPos where = GlobalPos.of(bot.level().dimension(), k.center());
        if (alreadyRaided(brain, where)) return;
        Progress p = of(bot, brain, k);
        if (p.complete()) {
            brain.remember(BotMemory.EventType.LOOTED, k.kind().label, where, (float) Math.max(0.3, k.kind().danger));
            brain.log("finished raiding the " + k.kind().label + " (" + p.total() + " containers)");
        }
    }

    public static boolean alreadyRaided(BotBrain brain, GlobalPos where) {
        for (BotMemory.Event e : brain.memory().events()) {
            if (e.type() != BotMemory.EventType.LOOTED || e.where().isEmpty()) continue;
            GlobalPos w = e.where().get();
            if (w.dimension() == where.dimension() && w.pos().distSqr(where.pos()) < 16 * 16) return true;
        }
        return false;
    }

    /** For the chunk math above (kept here so callers don't need it). */
    static long key(int cx, int cz) {
        return ChunkPos.pack(cx, cz);
    }
}
