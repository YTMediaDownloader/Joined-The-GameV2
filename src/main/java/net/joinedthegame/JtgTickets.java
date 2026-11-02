package net.joinedthegame;

import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.TicketType;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The chunk ticket a bot holds where it stands (see {@code DistanceManagerMixin}). */
public final class JtgTickets {
    private JtgTickets() {}

    public static final DeferredRegister<TicketType> TYPES = DeferredRegister.create(Registries.TICKET_TYPE, JoinedTheGame.MODID);

    /** Loads and ticks the chunks around a bot, like a player's own tickets, just not as far out. */
    public static final Supplier<TicketType> BOT = TYPES.register("bot",
            () -> new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    /** How far (in chunks) around a bot the world stays loaded and running. */
    public static final int BOT_RADIUS = 10;
}
