package net.joinedthegame.mixin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.joinedthegame.JoinedTheGame;
import net.joinedthegame.bot.BotNames;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The bots whose skin comes with the mod (see {@link BotNames#BUNDLED_SKINS}) wear it: in the
 * world, the tab list and the bot list. Only the bot itself: it has the offline id for its name,
 * so a real account that happens to share the name keeps its own skin.
 */
@Mixin(PlayerInfo.class)
public class PlayerInfoMixin {
    private static final Map<String, PlayerSkin> joinedthegame$skins = new HashMap<>();

    @Shadow @Final private com.mojang.authlib.GameProfile profile;

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void joinedthegame$bundledSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        String name = this.profile.name();
        String key = name.toLowerCase(Locale.ROOT);
        if (!BotNames.BUNDLED_SKINS.contains(key) || !this.profile.id().equals(UUIDUtil.createOfflinePlayerUUID(name))) return;
        cir.setReturnValue(joinedthegame$skins.computeIfAbsent(key, PlayerInfoMixin::joinedthegame$load));
    }

    /**
     * The skin for a bundled-skin bot: {@code config/joinedthegame/skins/<name>.png} if someone's put
     * one there (their own pick), otherwise the one drawn for the mod.
     */
    private static PlayerSkin joinedthegame$load(String key) {
        PlayerModelType model = BotNames.WIDE_SKINS.contains(key) ? PlayerModelType.WIDE : PlayerModelType.SLIM;
        java.nio.file.Path custom = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("joinedthegame").resolve("skins").resolve(key + ".png");
        if (java.nio.file.Files.isRegularFile(custom)) {
            try (var in = java.nio.file.Files.newInputStream(custom)) {
                var image = com.mojang.blaze3d.platform.NativeImage.read(in);
                if (image.getWidth() == 64 && (image.getHeight() == 64 || image.getHeight() == 32)) {
                    var id = JoinedTheGame.id("skins/custom/" + key);
                    net.minecraft.client.Minecraft.getInstance().getTextureManager().register(id,
                            new net.minecraft.client.renderer.texture.DynamicTexture(() -> "joinedthegame custom skin " + key, image));
                    return PlayerSkin.insecure(new ClientAsset.ResourceTexture(id, id), null, null, model);
                }
                image.close();
                JoinedTheGame.LOGGER.warn("Skin {} isn't 64x64: using the bundled one", custom);
            } catch (Exception e) {
                JoinedTheGame.LOGGER.warn("Couldn't load skin {}: {}", custom, e.toString());
            }
        }
        return PlayerSkin.insecure(new ClientAsset.ResourceTexture(JoinedTheGame.id("entity/player/" + key)), null, null, model);
    }
}
