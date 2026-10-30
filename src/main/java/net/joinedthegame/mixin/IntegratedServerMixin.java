package net.joinedthegame.mixin;

import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In singleplayer, vanilla decides who owns the world by name alone. So a bot sharing the
 * player's name (Redlantern8808's bot, in Redlantern8808's own world) counted as the owner:
 * it loaded the player's saved inventory when it joined (the guide book, whatever they were
 * carrying), got their operator powers, and if it ever disconnected the game would have shut the
 * world down as if the player had quit. The owner is the one with the owner's id; a bot has its
 * own (the offline id for its name), so same name, different id: not the owner.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    @Inject(method = "isSingleplayerOwner", at = @At("HEAD"), cancellable = true)
    private void joinedthegame$ownerById(NameAndId who, CallbackInfoReturnable<Boolean> cir) {
        var owner = ((IntegratedServer) (Object) this).getSingleplayerProfile();
        if (owner != null && who.id() != null && owner.id() != null && !who.id().equals(owner.id())) cir.setReturnValue(false);
    }
}
