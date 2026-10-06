package net.joinedthegame.network;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.GuideBook;
import net.joinedthegame.JoinedTheGame;
import net.joinedthegame.JtgConfig;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.BotRecord;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Packets between the AI player screen and the server. */
public final class JtgNetwork {
    private JtgNetwork() {}

    /** Client → server: "send me the list". */
    public record RequestRoster() implements CustomPacketPayload {
        public static final Type<RequestRoster> TYPE = new Type<>(JoinedTheGame.id("request_roster"));
        public static final StreamCodec<ByteBuf, RequestRoster> CODEC = StreamCodec.unit(new RequestRoster());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client → server: a button was pressed. */
    public record BotAction(String action, String name) implements CustomPacketPayload {
        public static final Type<BotAction> TYPE = new Type<>(JoinedTheGame.id("bot_action"));
        public static final StreamCodec<ByteBuf, BotAction> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, BotAction::action,
                ByteBufCodecs.stringUtf8(32), BotAction::name,
                BotAction::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server → client: the list, plus whether this player is allowed to manage it. */
    public record Roster(boolean canManage, List<RosterEntry> entries) implements CustomPacketPayload {
        public static final Type<Roster> TYPE = new Type<>(JoinedTheGame.id("roster"));
        public static final StreamCodec<ByteBuf, Roster> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Roster::canManage,
                RosterEntry.STREAM_CODEC.apply(ByteBufCodecs.list(512)), Roster::entries,
                Roster::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(RequestRoster.TYPE, RequestRoster.CODEC, (payload, ctx) -> sendRoster(player(ctx)));
        registrar.playToServer(BotAction.TYPE, BotAction.CODEC, JtgNetwork::handleAction);
        registrar.playToClient(Roster.TYPE, Roster.CODEC, (payload, ctx) -> ClientHooks.onRoster(payload));
    }

    private static ServerPlayer player(IPayloadContext ctx) {
        return (ServerPlayer) ctx.player();
    }

    public static boolean canManage(ServerPlayer player) {
        if (player.level().getServer().isSingleplayerOwner(new NameAndId(player.getGameProfile()))) return true;
        if (!JtgConfig.OPS_ONLY.get()) return true;
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static void handleAction(BotAction action, IPayloadContext ctx) {
        ServerPlayer player = player(ctx);
        if (action.action().equals("guide")) {
            GuideBook.give(player);
            return;
        }
        if (!canManage(player)) return;
        BotManager manager = BotManager.get(player.level().getServer());
        BotManager.Result result = switch (action.action()) {
            case "invite" -> manager.invite(action.name(), player);
            case "kick" -> manager.kick(action.name());
            case "ban" -> manager.ban(action.name());
            case "unban" -> manager.unban(action.name());
            case "tp" -> manager.teleport(action.name(), player);
            case "inventory" -> manager.openInventory(action.name(), player);
            default -> null;
        };
        if (result != null && !result.message().getString().isEmpty()) player.sendSystemMessage(result.message());
        if (!action.action().equals("inventory")) sendRoster(player);
    }

    public static void sendRoster(ServerPlayer player) {
        BotManager manager = BotManager.get(player.level().getServer());
        List<RosterEntry> entries = new ArrayList<>();
        for (BotRecord record : manager.roster().all()) {
            BotPlayer bot = manager.online(record);
            if (bot != null) {
                BotBrain brain = manager.brain(record.uuid());
                String activity = bot.isDeadOrDying() ? "Respawning" : brain == null ? "Idle" : brain.activity();
                entries.add(new RosterEntry(record.name(), record.uuid(), RosterEntry.ONLINE, activity, bot.getHealth(), bot.getFoodData().getFoodLevel()));
            } else {
                int state = record.state() == BotRecord.State.BANNED ? RosterEntry.BANNED : RosterEntry.AVAILABLE;
                entries.add(new RosterEntry(record.name(), record.uuid(), state, "", 0, 0));
            }
        }
        // Online first, then available, then banned; alphabetical within each.
        entries.sort((a, b) -> {
            int order = Integer.compare(rank(a.state()), rank(b.state()));
            return order != 0 ? order : a.name().compareToIgnoreCase(b.name());
        });
        PacketDistributor.sendToPlayer(player, new Roster(canManage(player), entries));
    }

    private static int rank(int state) {
        return switch (state) {
            case RosterEntry.ONLINE -> 0;
            case RosterEntry.AVAILABLE -> 1;
            default -> 2;
        };
    }

    /**
     * Indirection so the dedicated server never loads client classes: the client mod class
     * fills this in at startup.
     */
    public static final class ClientHooks {
        public static java.util.function.Consumer<Roster> handler = r -> {};

        static void onRoster(Roster roster) {
            handler.accept(roster);
        }
    }
}
