package net.joinedthegame.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import net.joinedthegame.JtgTickets;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bots keep a smaller patch of the world running around them than real players do.
 * <p>
 * Every player (and a bot is one) normally loads and ticks the world out to the server's view
 * and simulation distance. In singleplayer those are your own video settings, so at 20 each bot
 * kept about 1,700 chunks loaded and ticking, and seven bots spread out cost as much as eight
 * people each playing on render distance 20: the server fell seconds behind. A bot doesn't look
 * at anything that far away, so it gets one ticket of its own instead, {@link JtgTickets#BOT_RADIUS}
 * chunks out (or the server's simulation distance if that's smaller). Bots still count as players
 * for mob spawning, so the night is just as dangerous around them as before.
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerMixin {
    @Shadow @Final private Long2ObjectMap<ObjectSet<ServerPlayer>> playersPerChunk;
    @Shadow @Final private TicketStorage ticketStorage;
    @Shadow private int simulationDistance;

    /** Chunks holding a bot ticket, and the level it went on at (so exactly that comes off again). */
    @Unique private final Long2IntMap joinedthegame$botTickets = new Long2IntOpenHashMap();

    // --- arriving in a chunk: a bot skips the player-sized tickets and takes its own ---------

    @WrapWithCondition(method = "addPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/DistanceManager$PlayerTicketTracker;update(JIZ)V"))
    private boolean joinedthegame$noPlayerLoading(@Coerce Object tracker, long chunk, int level, boolean add, SectionPos pos, ServerPlayer player) {
        return !(player instanceof BotPlayer);
    }

    @WrapWithCondition(method = "addPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/TicketStorage;addTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V"))
    private boolean joinedthegame$noPlayerSimulation(TicketStorage storage, Ticket ticket, ChunkPos chunk, SectionPos pos, ServerPlayer player) {
        return !(player instanceof BotPlayer);
    }

    @Inject(method = "addPlayer", at = @At("TAIL"))
    private void joinedthegame$botTicket(SectionPos pos, ServerPlayer player, CallbackInfo ci) {
        if (!(player instanceof BotPlayer)) return;
        ChunkPos chunk = pos.chunk();
        long key = chunk.pack();
        if (this.joinedthegame$botTickets.containsKey(key)) return; // another bot's already here
        int radius = Math.min(JtgTickets.BOT_RADIUS, this.simulationDistance);
        int level = Math.max(0, ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING) - radius);
        this.ticketStorage.addTicket(new Ticket(JtgTickets.BOT.get(), level), chunk);
        this.joinedthegame$botTickets.put(key, level);
    }

    // --- leaving a chunk ----------------------------------------------------------------------

    /**
     * Vanilla takes the player tickets off a chunk only once nobody's left in it. With only bots
     * left nobody needs them either (bots hold their own), or they'd linger wherever a bot
     * stood still after you walked on.
     */
    @ModifyExpressionValue(method = "removePlayer", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/objects/ObjectSet;isEmpty()Z"))
    private boolean joinedthegame$onlyBotsLeft(boolean empty, SectionPos pos, ServerPlayer player) {
        if (empty) return true;
        ObjectSet<ServerPlayer> left = this.playersPerChunk.get(pos.chunk().pack());
        for (ServerPlayer p : left) if (!(p instanceof BotPlayer)) return false;
        return true;
    }

    // ...but the bots still there are still there: keep them on the chunk's list, and keep
    // counting them for mob spawning.

    @WrapWithCondition(method = "removePlayer", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;remove(J)Ljava/lang/Object;"))
    private boolean joinedthegame$keepBotsListed(Long2ObjectMap<?> map, long key) {
        ObjectSet<ServerPlayer> left = this.playersPerChunk.get(key);
        return left == null || left.isEmpty();
    }

    @WrapWithCondition(method = "removePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/DistanceManager$FixedPlayerDistanceChunkTracker;update(JIZ)V"))
    private boolean joinedthegame$keepBotsSpawning(@Coerce Object tracker, long chunk, int level, boolean add) {
        ObjectSet<ServerPlayer> left = this.playersPerChunk.get(chunk);
        return left == null || left.isEmpty();
    }

    @Inject(method = "removePlayer", at = @At("TAIL"))
    private void joinedthegame$dropBotTicket(SectionPos pos, ServerPlayer player, CallbackInfo ci) {
        if (!(player instanceof BotPlayer)) return;
        long key = pos.chunk().pack();
        ObjectSet<ServerPlayer> left = this.playersPerChunk.get(key);
        if (left != null) for (ServerPlayer p : left) if (p instanceof BotPlayer) return; // another bot still holds it
        if (!this.joinedthegame$botTickets.containsKey(key)) return;
        int level = this.joinedthegame$botTickets.remove(key);
        this.ticketStorage.removeTicket(new Ticket(JtgTickets.BOT.get(), level), pos.chunk());
    }
}
