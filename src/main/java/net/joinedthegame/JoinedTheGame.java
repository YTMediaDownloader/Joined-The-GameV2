package net.joinedthegame;

import com.mojang.logging.LogUtils;
import net.joinedthegame.network.JtgNetwork;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Joined the Game: AI players for your world. Hand-written game AI, no language models.
 */
@Mod(JoinedTheGame.MODID)
public class JoinedTheGame {
    public static final String MODID = "joinedthegame";
    public static final Logger LOGGER = LogUtils.getLogger();

    public JoinedTheGame(IEventBus modBus, ModContainer container) {
        modBus.addListener(JtgNetwork::register);
        JtgAttachments.TYPES.register(modBus);
        container.registerConfig(ModConfig.Type.SYNCED, JtgConfig.SPEC);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
