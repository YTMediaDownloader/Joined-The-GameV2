package net.joinedthegame;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.joinedthegame.activity.PlayerActivity;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Recipes;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.BotRecord;
import net.joinedthegame.bot.Homes;
import net.joinedthegame.command.JtgCommand;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = JoinedTheGame.MODID)
public final class JtgEvents {
    private JtgEvents() {}

    /** Real players who were crouching last tick, to spot the moment they crouch. */
    private static final Set<UUID> CROUCHING = new HashSet<>();

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        BotManager.get(event.getServer()).tick();
        if (event.getServer().getTickCount() % 20 == 0) {
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) PlayerActivity.sample(player);
        }
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof BotPlayer) return;
        // Spectators crouch to fly down: that's not saying hi to anyone.
        boolean crouching = player.isShiftKeyDown() && !player.isSpectator();
        boolean was = CROUCHING.contains(player.getUUID());
        if (crouching == was) return;
        if (crouching) {
            CROUCHING.add(player.getUUID());
            onCrouch(player);
        } else {
            CROUCHING.remove(player.getUUID());
        }
    }

    /** Crouching at a bot: it crouches back; if you were looking right at it, it follows you. */
    private static void onCrouch(ServerPlayer player) {
        BotManager manager = BotManager.get(player.level().getServer());
        List<BotPlayer> bots = player.level().getEntitiesOfClass(BotPlayer.class, player.getBoundingBox().inflate(8), b -> b.isAlive());
        Vec3 look = player.getViewVector(1.0f);
        BotPlayer lookedAt = null;
        double best = 0.97;
        for (BotPlayer bot : bots) {
            Vec3 toBot = bot.getEyePosition().subtract(player.getEyePosition()).normalize();
            double dot = look.dot(toBot);
            if (dot > best && player.hasLineOfSight(bot)) {
                best = dot;
                lookedAt = bot;
            }
        }
        for (BotPlayer bot : bots) {
            BotBrain brain = manager.brain(bot.getUUID());
            if (brain == null) continue;
            boolean isTarget = bot == lookedAt;
            if (isTarget || bot.distanceToSqr(player) < 4 * 4) brain.onPlayerCrouched(bot, player, isTarget);
        }
    }

    @SubscribeEvent
    static void onAttack(AttackEntityEvent event) {
        if (!(event.getTarget() instanceof BotPlayer bot) || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (player instanceof BotPlayer) return;
        // The hit lands like any other (damage, knockback, hurt flash), and the bot stops
        // whatever it was doing and turns to look at whoever hit it.
        BotBrain brain = BotManager.get(player.level().getServer()).brain(bot.getUUID());
        if (brain != null) brain.onPunched(bot, player);
    }

    @SubscribeEvent
    static void onHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof BotPlayer bot)) return;
        BotBrain brain = BotManager.get(bot.level().getServer()).brain(bot.getUUID());
        if (brain != null) brain.onHurt(bot, event.getSource().getEntity(), event.getAmount());
    }

    /** A bot killed something: worth remembering. */
    @SubscribeEvent
    static void onKill(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.animal.wolf.Wolf wolf && wolf.isTame()
                && wolf.getOwner() instanceof BotPlayer owner) {
            BotBrain ownerBrain = BotManager.get(owner.level().getServer()).brain(owner.getUUID());
            if (ownerBrain != null) ownerBrain.remember(net.joinedthegame.ai.BotMemory.EventType.PET_DIED, "wolf",
                    net.minecraft.core.GlobalPos.of(wolf.level().dimension(), wolf.blockPosition()), 1f);
        }
        if (!(event.getSource().getEntity() instanceof BotPlayer bot) || event.getEntity() instanceof BotPlayer) return;
        BotBrain brain = BotManager.get(bot.level().getServer()).brain(bot.getUUID());
        if (brain == null || !(event.getEntity() instanceof net.minecraft.world.entity.monster.Enemy)) return;
        brain.remember(net.joinedthegame.ai.BotMemory.EventType.KILLED,
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).toString());
    }

    @SubscribeEvent
    static void onBreak(BreakBlockEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) PlayerActivity.onBlockBroken(player, event.getPos(), event.getState());
    }

    /** "This bed belongs to ...": players can't sleep in a bed a bot has claimed. */
    @SubscribeEvent
    static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof BotPlayer) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.getBlockState(event.getPos()).is(BlockTags.BEDS) || player.isSecondaryUseActive()) return;
        BotRecord owner = BotManager.get(level.getServer()).bedOwner(level, Homes.headOf(level, event.getPos()));
        if (owner == null) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        player.sendOverlayMessage(Component.translatable("joinedthegame.msg.bed_owned", owner.name()));
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof BotPlayer) return;
        BotManager.get(player.level().getServer()).onRealPlayerJoined();
        GuideBook.giveOnFirstJoin(player);
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof BotPlayer bot) {
            BotManager.get(bot.level().getServer()).onBotLoggedOut(bot);
        } else {
            PlayerActivity.forget(event.getEntity().getUUID());
            CROUCHING.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        JtgCommand.register(event.getDispatcher());
    }

    /** Dedicated servers that keep bots running without players: bring them back right away. */
    @SubscribeEvent
    static void onStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (!JtgConfig.PAUSE_WITHOUT_PLAYERS.get()) BotManager.get(event.getServer()).onRealPlayerJoined();
    }

    @SubscribeEvent
    static void onStopped(ServerStoppedEvent event) {
        BotManager.reset();
        PlayerActivity.clear();
        Recipes.clear();
        CROUCHING.clear();
    }
}
