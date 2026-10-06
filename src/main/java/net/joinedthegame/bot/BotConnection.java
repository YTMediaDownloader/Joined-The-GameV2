package net.joinedthegame.bot;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;

/**
 * A network connection with nobody on the other end.
 *
 * <p>The server talks to every player through a {@link Connection}. A bot has no client,
 * so this one is backed by an in-memory Netty channel (the same trick vanilla's game tests
 * use) and silently drops everything sent to it.
 */
public class BotConnection extends Connection {
    public BotConnection() {
        super(PacketFlow.SERVERBOUND);
        // Registers this connection as the channel's handler, which fires channelActive and
        // gives the connection a live (but local) channel.
        new EmbeddedChannel(this);
    }

    @Override
    public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
        // Nobody is listening.
    }

    @Override
    public void flushChannel() {}

    @Override
    public void tick() {}
}
