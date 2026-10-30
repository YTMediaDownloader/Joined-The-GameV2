package net.joinedthegame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.BotRecord;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A live picture of every bot, rewritten every five seconds while the testing jar is in:
 * {@code logs/joinedthegame-bots.json}, next to the debug log. Where each one is, its health
 * and food, what it's doing and why, its whole inventory (enchantments, wear), its home, and
 * how it feels about everyone it knows. Plain JSON, so anyone (a person, a script, another AI)
 * can open it while the game's running and see the state of things now, without digging back
 * through the log. The real players in the world are in it too (where they are), for context.
 */
public final class Snapshot {
    private Snapshot() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static boolean failed;

    /** Every server tick; writes once every five seconds. */
    public static void tick(MinecraftServer server) {
        if (failed || !DebugLog.on() || server.getTickCount() % 100 != 0) return;
        try {
            write(server);
        } catch (Exception e) {
            failed = true; // never let a debug aid trouble the game
            JoinedTheGame.LOGGER.warn("Couldn't write the bot snapshot: {}", e.toString());
        }
    }

    private static void write(MinecraftServer server) throws IOException {
        BotManager mgr = BotManager.get(server);
        JsonObject root = new JsonObject();
        root.addProperty("written", LocalTime.now().format(TIME));
        root.addProperty("gameTime", server.overworld().getGameTime());
        root.addProperty("day", server.overworld().getOverworldClockTime() / 24000L);
        root.addProperty("timeOfDay", server.overworld().getOverworldClockTime() % 24000L);
        root.addProperty("night", server.overworld().isDarkOutside());

        JsonArray players = new JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof BotPlayer) continue;
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getGameProfile().name());
            o.add("pos", pos(p));
            o.addProperty("health", Math.round(p.getHealth()));
            o.addProperty("gameMode", p.gameMode.getGameModeForPlayer().getName());
            players.add(o);
        }
        root.add("players", players);

        JsonArray bots = new JsonArray();
        for (BotRecord record : mgr.roster().all()) {
            BotPlayer bot = mgr.online(record);
            if (bot == null) continue;
            BotBrain brain = mgr.brain(bot.getUUID());
            bots.add(bot(bot, record, brain));
        }
        root.add("bots", bots);

        Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs");
        Files.createDirectories(dir);
        Path tmp = dir.resolve("joinedthegame-bots.json.tmp");
        Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
        // Swapped in whole, so whoever's reading never gets half a file.
        try {
            Files.move(tmp, dir.resolve("joinedthegame-bots.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicNotSupported) {
            Files.move(tmp, dir.resolve("joinedthegame-bots.json"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonObject bot(BotPlayer bot, BotRecord record, @Nullable BotBrain brain) {
        JsonObject o = new JsonObject();
        o.addProperty("name", record.name());
        o.add("pos", pos(bot));
        o.addProperty("dead", bot.isDeadOrDying());
        o.addProperty("health", Math.round(bot.getHealth()));
        o.addProperty("food", bot.getFoodData().getFoodLevel());
        o.addProperty("inWater", bot.isInWater());
        if (brain != null) {
            o.addProperty("doing", brain.activity());
            var goal = brain.currentGoal();
            o.addProperty("goal", goal == null ? "none" : goal.getClass().getSimpleName().replace("Goal", "") + " (" + brain.currentPriority() + ")");
            o.addProperty("focus", String.valueOf(brain.focusNow()));
            o.addProperty("identity", brain.identity().describe());
            o.addProperty("needs", brain.needs().describe(brain.personality()));
            o.add("home", where(record.home()));
            o.add("house", where(record.house()));
            o.addProperty("homeType", brain.memory().homeType);
            o.add("deathSpot", where(brain.deathSpot()));
            JsonArray feelings = new JsonArray();
            for (var r : brain.relations()) {
                JsonObject f = new JsonObject();
                f.addProperty("who", r.name);
                f.addProperty("player", r.player);
                f.addProperty("status", r.status());
                f.addProperty("liking", Math.round(r.liking));
                f.addProperty("familiarity", Math.round(r.familiarity));
                f.addProperty("trust", Math.round(r.trust));
                feelings.add(f);
            }
            o.add("feelings", feelings);
        }
        JsonObject gear = new JsonObject();
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
            ItemStack s = bot.getItemBySlot(slot);
            if (!s.isEmpty()) gear.add(slot.getName(), item(s));
        }
        o.add("equipped", gear);
        JsonArray inv = new JsonArray();
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack s = bot.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            JsonObject it = item(s);
            it.addProperty("slot", i);
            inv.add(it);
        }
        o.add("inventory", inv);
        return o;
    }

    private static JsonObject item(ItemStack s) {
        JsonObject it = new JsonObject();
        it.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        it.addProperty("count", s.getCount());
        if (s.isDamageableItem()) it.addProperty("durability", (s.getMaxDamage() - s.getDamageValue()) + "/" + s.getMaxDamage());
        var ench = s.getEnchantments();
        if (!ench.isEmpty()) {
            JsonObject e = new JsonObject();
            for (var entry : ench.entrySet()) {
                e.addProperty(entry.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"), entry.getIntValue());
            }
            it.add("enchantments", e);
        }
        return it;
    }

    private static JsonObject pos(net.minecraft.world.entity.Entity e) {
        JsonObject p = new JsonObject();
        p.addProperty("dim", e.level().dimension().identifier().toString());
        p.addProperty("x", e.getBlockX());
        p.addProperty("y", e.getBlockY());
        p.addProperty("z", e.getBlockZ());
        return p;
    }

    private static com.google.gson.JsonElement where(@Nullable GlobalPos gp) {
        if (gp == null) return com.google.gson.JsonNull.INSTANCE;
        JsonObject p = new JsonObject();
        p.addProperty("dim", gp.dimension().identifier().toString());
        p.addProperty("x", gp.pos().getX());
        p.addProperty("y", gp.pos().getY());
        p.addProperty("z", gp.pos().getZ());
        return p;
    }
}
