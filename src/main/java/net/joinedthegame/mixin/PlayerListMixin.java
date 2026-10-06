package net.joinedthegame.mixin;

import com.mojang.authlib.GameProfile;
import net.joinedthegame.bot.BotPacketListener;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Two places where vanilla hard-codes "make a normal player object":
 * <ul>
 *   <li>Logging in creates the player's game connection. Bots get a {@link BotPacketListener}.</li>
 *   <li>Respawning throws the dead player object away and builds a fresh one. Bots must come
 *       back as {@link BotPlayer}s, or they'd respawn as brainless statues.</li>
 * </ul>
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Redirect(
            method = "placeNewPlayer",
            at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"))
    private ServerGamePacketListenerImpl joinedthegame$botListener(MinecraftServer server, Connection connection, ServerPlayer player, CommonListenerCookie cookie) {
        return player instanceof BotPlayer
                ? new BotPacketListener(server, connection, player, cookie)
                : new ServerGamePacketListenerImpl(server, connection, player, cookie);
    }

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private java.util.List<ServerPlayer> players;

    /**
     * Shutdown kicks everyone by index. Bots leave instantly (no client to wait for), which
     * shrinks the list mid-loop and skips the next player, so loop over a copy instead.
     */
    @org.spongepowered.asm.mixin.injection.Inject(method = "removeAll", at = @At("HEAD"), cancellable = true)
    private void joinedthegame$removeAllSafely(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        for (ServerPlayer player : java.util.List.copyOf(this.players)) {
            player.connection.disconnect(net.minecraft.network.chat.Component.translatable("multiplayer.disconnect.server_shutdown"));
        }
        ci.cancel();
    }

    @Redirect(
            method = "respawn",
            at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/level/ServerLevel;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/level/ClientInformation;)Lnet/minecraft/server/level/ServerPlayer;"))
    private ServerPlayer joinedthegame$botRespawn(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info,
                                                  ServerPlayer old, boolean keepAllPlayerData, Entity.RemovalReason reason) {
        return old instanceof BotPlayer
                ? new BotPlayer(server, level, profile, info)
                : new ServerPlayer(server, level, profile, info);
    }
}
