package net.joinedthegame.bot;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.joinedthegame.JtgConfig;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Personality;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Runs the AI players for a server: inviting, kicking, banning, rejoining after a restart,
 * and holding each bot's brain.
 */
public final class BotManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static @Nullable BotManager instance;

    public record Result(boolean ok, Component message) {
        static Result ok(String key, Object... args) {
            return new Result(true, Component.translatable(key, args));
        }

        static Result fail(String key, Object... args) {
            return new Result(false, Component.translatable(key, args));
        }
    }

    /** A bot waiting to join: when, and who invited it (it appears next to them), if anyone. */
    private record PendingJoin(String name, long at, @Nullable UUID near) {
        PendingJoin(String name, long at) {
            this(name, at, null);
        }
    }

    private final MinecraftServer server;
    private final Map<UUID, BotBrain> brains = new HashMap<>();
    private final List<PendingJoin> pending = new ArrayList<>();
    private boolean rejoinScheduled;

    private BotManager(MinecraftServer server) {
        this.server = server;
        Skins.load(server);
    }

    public static BotManager get(MinecraftServer server) {
        BotManager current = instance;
        if (current == null || current.server != server) {
            current = new BotManager(server);
            instance = current;
        }
        return current;
    }

    public static void reset() {
        instance = null;
    }

    // --- queries -------------------------------------------------------------

    public BotRoster roster() {
        return BotRoster.get(this.server);
    }

    public @Nullable BotBrain brain(UUID id) {
        return this.brains.get(id);
    }

    public static boolean isBot(Player player) {
        return player instanceof BotPlayer;
    }

    public static boolean anyRealPlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (!(player instanceof BotPlayer)) return true;
        return false;
    }

    public static @Nullable ServerPlayer nearestRealPlayer(BotPlayer bot) {
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer player : bot.level().players()) {
            if (player instanceof BotPlayer || player.isSpectator()) continue;
            double d = player.distanceToSqr(bot);
            if (d < bestDist) {
                bestDist = d;
                best = player;
            }
        }
        return best;
    }

    public @Nullable BotPlayer online(BotRecord record) {
        return this.server.getPlayerList().getPlayer(record.uuid()) instanceof BotPlayer bot ? bot : null;
    }

    public int onlineCount() {
        int n = 0;
        for (ServerPlayer player : this.server.getPlayerList().getPlayers()) if (player instanceof BotPlayer) n++;
        return n;
    }

    public @Nullable BotRecord bedOwner(ServerLevel level, BlockPos head) {
        for (BotRecord record : roster().all()) {
            GlobalPos home = record.home();
            if (home != null && home.dimension() == level.dimension() && home.pos().equals(head)) return record;
        }
        return null;
    }

    public boolean isBotStorage(ServerLevel level, BlockPos pos) {
        int r = Homes.STORAGE_RADIUS + 1;
        for (BotRecord record : roster().all()) {
            GlobalPos home = record.home();
            if (home != null && home.dimension() == level.dimension() && home.pos().distSqr(pos) <= r * r) return true;
        }
        return false;
    }

    public void setHome(BotRecord record, @Nullable GlobalPos home) {
        record.setHome(home);
        roster().markDirty();
    }

    public void setHouse(BotRecord record, @Nullable GlobalPos house, @Nullable GlobalPos stash) {
        record.setHouse(house);
        record.setStash(stash);
        roster().markDirty();
    }

    /** Is this the secret valuables chest of any bot? (Kept out of normal storage and looting.) */
    public boolean isStash(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        for (BotRecord record : roster().all()) {
            GlobalPos stash = record.stash();
            if (stash != null && stash.dimension() == level.dimension() && stash.pos().equals(pos)) return true;
        }
        return false;
    }

    // --- management actions -------------------------------------------------------

    public Result invite(String name, @Nullable ServerPlayer near) {
        BotRecord record = roster().getOrCreate(name);
        if (record == null) return Result.fail("joinedthegame.msg.bad_name", name);
        if (record.state() == BotRecord.State.BANNED) return Result.fail("joinedthegame.msg.banned", record.name());
        if (online(record) != null) return Result.fail("joinedthegame.msg.already_here", record.name());
        if (this.server.getPlayerList().getPlayer(record.uuid()) != null) return Result.fail("joinedthegame.msg.already_here", record.name());
        if (onlineCount() >= JtgConfig.MAX_BOTS.get()) return Result.fail("joinedthegame.msg.full", JtgConfig.MAX_BOTS.get());
        // A real player's name: get their skin first (in the background), then join.
        if (Skins.needsFetch(record.name())) {
            Skins.fetch(this.server, record.name());
            this.pending.removeIf(p -> p.name().equalsIgnoreCase(record.name()));
            this.pending.add(new PendingJoin(record.name(), this.server.overworld().getGameTime() + 5, near == null ? null : near.getUUID()));
            record.setState(BotRecord.State.ACTIVE);
            roster().markDirty();
            return Result.ok("joinedthegame.msg.invited", record.name());
        }
        try {
            spawn(record, near);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to spawn AI player {}", record.name(), e);
            return Result.fail("joinedthegame.msg.error", record.name());
        }
        record.setState(BotRecord.State.ACTIVE);
        roster().markDirty();
        return Result.ok("joinedthegame.msg.invited", record.name());
    }

    public Result kick(String name) {
        BotRecord record = roster().get(name);
        if (record == null) return Result.fail("joinedthegame.msg.unknown", name);
        BotPlayer bot = online(record);
        this.pending.removeIf(p -> p.name().equalsIgnoreCase(record.name()));
        if (bot == null) return Result.fail("joinedthegame.msg.not_here", record.name());
        record.setState(BotRecord.State.AVAILABLE);
        roster().markDirty();
        bot.connection.disconnect(Component.translatable("multiplayer.disconnect.kicked"));
        return Result.ok("joinedthegame.msg.kicked", record.name());
    }

    public Result ban(String name) {
        BotRecord record = roster().getOrCreate(name);
        if (record == null) return Result.fail("joinedthegame.msg.unknown", name);
        BotPlayer bot = online(record);
        this.pending.removeIf(p -> p.name().equalsIgnoreCase(record.name()));
        record.setState(BotRecord.State.BANNED);
        roster().markDirty();
        if (bot != null) bot.connection.disconnect(Component.translatable("multiplayer.disconnect.banned"));
        return Result.ok("joinedthegame.msg.bans", record.name());
    }

    public Result unban(String name) {
        BotRecord record = roster().get(name);
        if (record == null) return Result.fail("joinedthegame.msg.unknown", name);
        if (record.state() != BotRecord.State.BANNED) return Result.fail("joinedthegame.msg.not_banned", record.name());
        record.setState(BotRecord.State.AVAILABLE);
        roster().markDirty();
        return Result.ok("joinedthegame.msg.unbanned", record.name());
    }

    public Result teleport(String name, ServerPlayer to) {
        BotRecord record = roster().get(name);
        BotPlayer bot = record == null ? null : online(record);
        if (bot == null) return Result.fail("joinedthegame.msg.not_here", name);
        if (bot.isSleeping()) bot.stopSleepInBed(true, true);
        BotBrain brain = brain(bot.getUUID());
        if (brain != null) brain.navigator().stop(bot);
        bot.teleportTo(to.level(), to.getX(), to.getY(), to.getZ(), Set.of(), to.getYRot(), 0, true);
        return Result.ok("joinedthegame.msg.teleported", bot.getName());
    }

    public Result openInventory(String name, ServerPlayer viewer) {
        BotRecord record = roster().get(name);
        BotPlayer bot = record == null ? null : online(record);
        if (bot == null) return Result.fail("joinedthegame.msg.not_here", name);
        viewer.openMenu(BotInventoryMenu.provider(bot));
        return new Result(true, Component.empty());
    }

    // --- spawning ------------------------------------------------------------

    private BotPlayer spawn(BotRecord record, @Nullable ServerPlayer near) {
        GameProfile profile = Skins.profile(record.uuid(), record.name());
        Personality personality = Personality.roll(record.name());
        HumanoidArm arm = personality.explorer() > 0.9 ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
        // 0x7F shows every skin layer (hat, jacket, sleeves...), like a real client does.
        ClientInformation info = new ClientInformation("en_us", 2, ChatVisiblity.FULL, true, 0x7F, arm, false, true, ParticleStatus.MINIMAL);

        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            Optional<ValueInput> input = this.server.getPlayerList()
                    .loadPlayerData(new NameAndId(profile))
                    .map(tag -> TagValueInput.create(reporter, this.server.registryAccess(), tag));
            ServerPlayer.SavedPosition saved = input.flatMap(tag -> tag.read(ServerPlayer.SavedPosition.MAP_CODEC))
                    .orElse(ServerPlayer.SavedPosition.EMPTY);

            ServerLevel level;
            Vec3 pos;
            float yaw;
            if (near != null) {
                level = near.level();
                pos = spotNear(level, near.position(), near.getRandom());
                yaw = near.getRandom().nextFloat() * 360f;
            } else if (saved.position().isPresent()) {
                level = saved.dimension().map(this.server::getLevel).orElse(this.server.overworld());
                pos = saved.position().get();
                yaw = saved.rotation().map(r -> r.x).orElse(0f);
            } else {
                level = this.server.overworld();
                BlockPos spawn = level.getRespawnData().pos();
                pos = spotNear(level, Vec3.atBottomCenterOf(spawn), level.getRandom());
                yaw = 0;
            }
            // Make sure the ground exists before dropping a player onto it.
            level.getChunk(Mth.floor(pos.x) >> 4, Mth.floor(pos.z) >> 4);

            BotPlayer bot = new BotPlayer(this.server, level, profile, info);
            input.ifPresent(bot::load);
            net.neoforged.neoforge.event.EventHooks.firePlayerLoadingEvent(bot, this.server.getPlayerList(), bot.getStringUUID());
            bot.snapTo(pos.x, pos.y, pos.z, yaw, 0);
            this.brains.put(record.uuid(), new BotBrain(record));
            this.server.getPlayerList().placeNewPlayer(new BotConnection(), bot,
                    new CommonListenerCookie(profile, 0, info, false));
            bot.connection.markClientLoaded();
            if (bot.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) bot.setGameMode(GameType.SURVIVAL);
            return bot;
        }
    }

    /** A safe surface spot a few blocks from {@code center}, so bots walk up rather than spawning inside you. */
    private static Vec3 spotNear(ServerLevel level, Vec3 center, net.minecraft.util.RandomSource random) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 5 + random.nextDouble() * 6;
            int x = Mth.floor(center.x + Math.cos(angle) * dist);
            int z = Mth.floor(center.z + Math.sin(angle) * dist);
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos ground = new BlockPos(x, y - 1, z);
            if (Math.abs(y - center.y) > 6 && level.dimension() == Level.OVERWORLD && center.y < y - 6) continue;
            if (!level.getFluidState(ground).isEmpty() || !level.getFluidState(ground.above()).isEmpty()) continue;
            if (level.getBlockState(ground).isAir()) continue;
            return new Vec3(x + 0.5, y, z + 0.5);
        }
        return center;
    }

    // --- lifecycle ---------------------------------------------------------------

    public void tick() {
        tickDeadBots();
        if (this.pending.isEmpty()) return;
        long now = this.server.overworld().getGameTime();
        Iterator<PendingJoin> it = this.pending.iterator();
        while (it.hasNext()) {
            PendingJoin join = it.next();
            if (join.at() > now) continue;
            // Still getting its skin: wait (10 seconds at most, then it joins with the default).
            if (Skins.fetching(join.name()) && now - join.at() < Skins.WAIT_MS / 50) continue;
            it.remove();
            BotRecord record = roster().get(join.name());
            if (record == null || record.state() != BotRecord.State.ACTIVE || online(record) != null) continue;
            if (onlineCount() >= JtgConfig.MAX_BOTS.get()) continue;
            try {
                ServerPlayer near = join.near() == null ? null : this.server.getPlayerList().getPlayer(join.near());
                spawn(record, near);
            } catch (RuntimeException e) {
                LOGGER.error("Failed to rejoin AI player {}", record.name(), e);
            }
        }
    }

    /** Dead bots sit on the "You Died" screen for a couple of seconds, then respawn. */
    private void tickDeadBots() {
        for (ServerPlayer player : List.copyOf(this.server.getPlayerList().getPlayers())) {
            if (!(player instanceof BotPlayer bot)) continue;
            if (bot.getHealth() > 0) {
                bot.deadTicks = 0;
                bot.respawnAfter = -1;
                continue;
            }
            if (bot.respawnAfter < 0) bot.respawnAfter = 40 + bot.getRandom().nextInt(60);
            if (++bot.deadTicks >= bot.respawnAfter) {
                bot.deadTicks = 0;
                bot.respawnAfter = -1;
                bot.respawn();
            }
        }
    }

    /** First real player is in: bots that were playing last time trickle back in. */
    public void onRealPlayerJoined() {
        if (this.rejoinScheduled) return;
        this.rejoinScheduled = true;
        long now = this.server.overworld().getGameTime();
        long at = now + 40;
        for (BotRecord record : roster().all()) {
            if (record.state() != BotRecord.State.ACTIVE || online(record) != null) continue;
            at += 20 + this.server.overworld().getRandom().nextInt(140);
            Skins.fetch(this.server, record.name());
            this.pending.add(new PendingJoin(record.name(), at));
        }
    }

    public void onBotLoggedOut(BotPlayer bot) {
        this.brains.remove(bot.getUUID());
        // Deliberately leave the roster state alone. Only our own kick/ban mark a bot as
        // gone. Any other way out (quitting a singleplayer world removes everyone else
        // before shutdown even starts, a server stopping, vanilla /kick) leaves it ACTIVE,
        // so it rejoins next time.
    }
}
