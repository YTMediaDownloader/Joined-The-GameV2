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
    /** Datapacks reloaded: block tags may have changed under remembered answers. */
    @SubscribeEvent
    public static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        net.joinedthegame.ai.nav.WorldView.forgetDangers();
    }

    private JtgEvents() {}

    /** Real players who were crouching last tick, to spot the moment they crouch. */
    private static final Set<UUID> CROUCHING = new HashSet<>();

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        BotManager.get(event.getServer()).tick();
        Snapshot.tick(event.getServer()); // logs/joinedthegame-bots.json (testing jar only)
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

    /** A blow aimed at someone's pet (a sweep doesn't fire this: only the one you aimed at). */
    @SubscribeEvent
    static void onAttackPet(AttackEntityEvent event) {
        if (event.getTarget() instanceof net.minecraft.world.entity.TamableAnimal pet && pet.isTame()) {
            net.joinedthegame.ai.social.PetKills.hit(pet, event.getEntity());
        }
    }

    /** Something someone shot at a pet. */
    @SubscribeEvent
    static void onPetHurt(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.TamableAnimal pet && pet.isTame()
                && event.getSource().getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile
                && event.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player shooter) {
            net.joinedthegame.ai.social.PetKills.hit(pet, shooter);
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

    /**
     * A sword sweep starting (see Sweeps). Bots mostly don't sweep with someone stood by what
     * they're hitting, but the careless and the reckless don't always look first. Every sweep
     * that goes ahead is noted, so whoever it catches can tell it wasn't aimed at them.
     */
    @SubscribeEvent
    static void onSweep(net.neoforged.neoforge.event.entity.player.SweepAttackEvent event) {
        var attacker = event.getEntity();
        if (!event.isSweeping() || attacker.level().isClientSide()) return;
        if (attacker instanceof BotPlayer bot && net.joinedthegame.ai.Sweeps.bystanders(bot, event.getTarget())) {
            BotBrain brain = BotManager.get(bot.level().getServer()).brain(bot.getUUID());
            double careless = brain == null ? 0.05 : 0.02 + brain.personality().reckless() * 0.12 + (1 - brain.personality().diligent()) * 0.06;
            if (bot.getRandom().nextDouble() >= careless) {
                event.setSweeping(false);
                return;
            }
        }
        net.joinedthegame.ai.Sweeps.note(attacker, event.getTarget());
    }

    @SubscribeEvent
    static void onHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof BotPlayer bot)) return;
        BotBrain brain = BotManager.get(bot.level().getServer()).brain(bot.getUUID());
        if (brain != null) brain.onHurt(bot, event.getSource().getEntity(), event.getAmount());
    }

    /**
     * A bot picked up something a real player threw: a gift. What it's worth to the bot depends on
     * what it is (the good stuff, food when it's hungry, anything useful; junk is nothing).
     */
    @SubscribeEvent
    static void onPickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post event) {
        if (!(event.getPlayer() instanceof BotPlayer bot)) return;
        if (!(event.getItemEntity().getOwner() instanceof ServerPlayer giver) || giver == bot) return;
        // A bot's throw is a gift only if it was meant for us (bots also throw junk away).
        boolean meant = giver instanceof BotPlayer && giver.getUUID().equals(net.joinedthegame.ai.social.Gifts.pickedUp(event.getItemEntity(), bot.getUUID()));
        if (giver instanceof BotPlayer && !meant) return;
        BotBrain brain = BotManager.get(bot.level().getServer()).brain(bot.getUUID());
        if (brain == null) return;
        var stack = event.getOriginalStack();
        double worth;
        if (net.joinedthegame.ai.Junk.isValuable(stack)) worth = 6;
        else if (stack.has(net.minecraft.core.component.DataComponents.FOOD) && bot.getFoodData().getFoodLevel() < 16) worth = 4;
        else if (net.joinedthegame.ai.Junk.isKeeper(stack)) worth = 2;
        else if (meant && (stack.is(net.minecraft.tags.BlockItemTags.FLOWERS.item()) || stack.is(net.minecraft.tags.BlockItemTags.SMALL_FLOWERS.item()))) worth = 3;
        else return; // junk: no thanks, and no hard feelings either
        worth *= Math.min(2.0, 1.0 + stack.getCount() / 16.0);
        brain.feel(giver, net.joinedthegame.ai.social.Feelings.Act.GAVE_ME, worth);
    }

    /**
     * Which players each mob has gone for lately (mob, then who and when). A mob turns on whoever
     * hits it, so at the moment a defender kills it, it's after the defender, not the one it was
     * attacking: this is how the one who was saved still knows it.
     */
    private static final java.util.Map<UUID, java.util.Map<UUID, Long>> WENT_FOR = new java.util.concurrent.ConcurrentHashMap<>();

    @SubscribeEvent
    static void onTarget(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.TamableAnimal pet && pet.isTame()
                && event.getNewAboutToBeSetTarget() instanceof net.minecraft.world.entity.player.Player who) {
            net.joinedthegame.ai.social.PetKills.wentFor(pet, who);
        }
        if (!(event.getNewAboutToBeSetTarget() instanceof BotPlayer target)) return;
        if (!(event.getEntity() instanceof net.minecraft.world.entity.monster.Enemy)) return;
        WENT_FOR.computeIfAbsent(event.getEntity().getUUID(), k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(target.getUUID(), event.getEntity().level().getGameTime());
    }

    /** A bot killed something: worth remembering. */
    @SubscribeEvent
    static void onKill(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        // Someone killed a mob that was going for a bot (now, or in the last fifteen seconds): the
        // bot's grateful. Not for killing what was only after the killer itself.
        var went = WENT_FOR.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof net.minecraft.world.entity.Mob mob
                && event.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player helper) {
            var victims = new java.util.HashSet<UUID>();
            long now = mob.level().getGameTime();
            if (went != null) went.forEach((id, at) -> { if (now - at < 20 * 15) victims.add(id); });
            if (mob.getTarget() instanceof BotPlayer t) victims.add(t.getUUID());
            victims.remove(helper.getUUID());
            for (UUID id : victims) {
                BotBrain victimBrain = BotManager.get(mob.level().getServer()).brain(id);
                if (victimBrain != null) victimBrain.feel(helper, net.joinedthegame.ai.social.Feelings.Act.HELPED_ME, 1);
            }
        }
        if (WENT_FOR.size() > 500) WENT_FOR.clear(); // mobs that went for someone and wandered off
        // Someone's pet killed by a person: on purpose or not, the owner (if a bot) reacts.
        if (event.getEntity() instanceof net.minecraft.world.entity.TamableAnimal pet && pet.isTame()
                && event.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player petKiller) {
            net.joinedthegame.ai.social.PetKills.died(pet, petKiller);
        }
        // A bot killed a person (bot or player): it knows (it won, and where their stuff is).
        // (Knocked off a ledge, into lava, into the water by them counts too: the game's own kill
        // credit, "doomed to fall by LeBronJames". Only counting the killing blow, a bot that won
        // that way didn't know it had, and didn't go for the loot.)
        if (event.getEntity() instanceof net.minecraft.world.entity.player.Player victim
                && (event.getSource().getEntity() != null ? event.getSource().getEntity() : victim.getKillCredit()) instanceof BotPlayer killer
                && killer != victim) {
            BotBrain kb = BotManager.get(killer.level().getServer()).brain(killer.getUUID());
            if (kb != null) kb.onKilledPerson(victim.getUUID(), victim.blockPosition());
        }
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

    /**
     * Peace by chat. A bot called peace on you: "peace", "truce", "ok", "fine", "sure", "yes" to
     * accept, "no" to refuse (saying nothing is a no, after a while). Or you call it: say "peace"
     * or "truce" near a bot you're fighting, and it answers.
     */
    @SubscribeEvent
    static void onChat(net.neoforged.neoforge.event.ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player instanceof BotPlayer) return;
        String said = event.getRawText().toLowerCase(java.util.Locale.ROOT).trim();
        long now = player.level().getGameTime();
        var offer = net.joinedthegame.ai.social.Peace.offerTo(player.getUUID(), now);
        if (offer != null) {
            boolean yes = said.matches(".*\\b(peace|truce|ok|okay|fine|sure|yes|deal)\\b.*");
            boolean no = said.matches(".*\\b(no|nope|never|nah)\\b.*");
            if (yes || no) net.joinedthegame.ai.social.Peace.answer(offer, yes && !no, now);
            return;
        }
        if (!said.matches(".*\\b(peace|truce)\\b.*")) return;
        // Calling it ourselves: to the bots fighting us, and to any close by with a quarrel to make
        // up (a rival, one that can't stand us, one we've been hitting and running from). It used to
        // go only to bots registered mid-fight, so a call between blows reached nobody, silently.
        java.util.Set<UUID> to = new java.util.LinkedHashSet<>(net.joinedthegame.ai.social.Feuds.against(player.getUUID(), now));
        BotManager mgr = BotManager.get(player.level().getServer());
        for (var other : player.level().players()) {
            if (!(other instanceof BotPlayer bot) || bot.distanceToSqr(player) > 24 * 24) continue;
            BotBrain b = mgr.brain(bot.getUUID());
            if (b == null) continue;
            var r = b.knownRelation(player.getUUID());
            if (player.getUUID().equals(b.feudOpponent()) || b.unsettledRounds(player.getUUID()) > 0
                    || r != null && (r.rivalSince > 0 || r.liking <= -30)) to.add(bot.getUUID());
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (UUID id : to) {
            if (player.level().getPlayerByUUID(id) instanceof BotPlayer bot && bot.distanceToSqr(player) < 32 * 32) {
                net.joinedthegame.ai.social.Peace.offer(player.getUUID(), player.getGameProfile().name(), id, now);
                names.add(bot.getScoreboardName());
            }
        }
        if (net.joinedthegame.DebugLog.on()) net.joinedthegame.DebugLog.write("[peace] " + player.getGameProfile().name() + " called peace: "
                + (names.isEmpty() ? "nobody nearby to make peace with" : "offered to " + String.join(", ", names)));
        if (names.isEmpty()) player.sendOverlayMessage(Component.literal("No one nearby has a quarrel with you to make peace over."));
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
        DebugLog.close();
        PlayerActivity.clear();
        Recipes.clear();
        CROUCHING.clear();
    }
}
