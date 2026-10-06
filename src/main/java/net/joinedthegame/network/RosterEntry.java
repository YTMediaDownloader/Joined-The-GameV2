package net.joinedthegame.network;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** One row of the AI player list, as sent to the client. */
public record RosterEntry(String name, UUID uuid, int state, String activity, float health, int food) {
    public static final int AVAILABLE = 0;
    public static final int ONLINE = 1;
    public static final int BANNED = 2;

    public static final StreamCodec<ByteBuf, RosterEntry> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RosterEntry::name,
            UUIDUtil.STREAM_CODEC, RosterEntry::uuid,
            ByteBufCodecs.VAR_INT, RosterEntry::state,
            ByteBufCodecs.STRING_UTF8, RosterEntry::activity,
            ByteBufCodecs.FLOAT, RosterEntry::health,
            ByteBufCodecs.VAR_INT, RosterEntry::food,
            RosterEntry::new);
}
