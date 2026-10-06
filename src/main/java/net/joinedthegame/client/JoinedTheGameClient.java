package net.joinedthegame.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.joinedthegame.JoinedTheGame;
import net.joinedthegame.network.JtgNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = JoinedTheGame.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = JoinedTheGame.MODID, value = Dist.CLIENT)
public class JoinedTheGameClient {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(JoinedTheGame.id("main"));
    public static final KeyMapping OPEN_LIST = new KeyMapping("key.joinedthegame.open", InputConstants.KEY_K, CATEGORY);

    public JoinedTheGameClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        JtgNetwork.ClientHooks.handler = BotListScreen::onRoster;
        NeoForge.EVENT_BUS.addListener(JoinedTheGameClient::onClientTick);
    }

    @SubscribeEvent
    static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN_LIST);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_LIST.consumeClick()) {
            if (mc.gui.screen() == null && mc.player != null) mc.gui.setScreen(new BotListScreen());
        }
    }
}
