package net.joinedthegame.bot;

import io.netty.channel.ChannelFutureListener;
import java.util.Set;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import org.jspecify.annotations.Nullable;

/**
 * The server-side "game connection" for a bot.
 *
 * <p>Swapped in for the vanilla one by {@code PlayerListMixin} so that nothing (vanilla,
 * NeoForge or another mod) ever tries to push a packet to a client that doesn't exist.
 * Without this, a mod that syncs data on login would crash with "payload may not be sent".
 */
public class BotPacketListener extends ServerGamePacketListenerImpl {
    private boolean gone;

    public BotPacketListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener) {
        // Dropped: bots have no client.
    }

    @Override
    public void teleport(PositionMoveRotation destination, Set<Relative> relatives) {
        super.teleport(destination, relatives);
        // A real client would confirm the teleport; ours can't, so settle the position now.
        if (this.player.level().getPlayerByUUID(this.player.getUUID()) != null) {
            this.resetPosition();
            this.player.level().getChunkSource().move(this.player);
        }
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        // Vanilla waits for the client to acknowledge the kick. Bots leave immediately,
        // which still runs the normal logout path ("X left the game", save, remove).
        if (this.gone) return;
        this.gone = true;
        this.onDisconnect(details);
        this.connection.disconnect(details);
    }
}
