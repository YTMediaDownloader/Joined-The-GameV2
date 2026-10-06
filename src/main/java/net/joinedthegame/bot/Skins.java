package net.joinedthegame.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.google.common.collect.ImmutableMultimap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real skins for the bots named after real players (see {@link BotNames#REAL_SKINS}). The
 * skin is the player's signed "textures" property, looked up from Mojang the same way a server
 * looks up a joining player.
 * <ul>
 *   <li>The lookup runs in the background; the bot's join waits for it (a few seconds at most).</li>
 *   <li>If it fails or times out, the bot joins anyway with the default skin.</li>
 *   <li>Found skins are cached on disk (by lowercase name), so restarts don't look them up again
 *       (refreshed once a day); failed lookups aren't retried for a while.</li>
 * </ul>
 */
public final class Skins {
    private Skins() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("JoinedTheGame/Skins");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** How long a found skin is trusted before looking it up again. */
    private static final long REFRESH_MS = 24L * 60 * 60 * 1000;
    /** After a failed lookup, how long before trying again. */
    private static final long RETRY_MS = 30L * 60 * 1000;
    /** How long a bot's join waits for its skin. */
    public static final long WAIT_MS = 10_000;

    private record Cached(String value, @Nullable String signature, long fetchedAt) {}

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Void>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static @Nullable Path file;

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean wantsRealSkin(String name) {
        return BotNames.REAL_SKINS.contains(key(name));
    }

    /** Load the saved skins (server start). */
    public static void load(MinecraftServer server) {
        file = server.getServerDirectory().resolve("config").resolve("joinedthegame-skins.json");
        if (!Files.exists(file)) return;
        try {
            Map<String, Cached> saved = GSON.fromJson(Files.readString(file), new TypeToken<HashMap<String, Cached>>() {}.getType());
            if (saved != null) CACHE.putAll(saved);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read saved skins: {}", e.toString());
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(new HashMap<>(CACHE)));
        } catch (IOException e) {
            LOGGER.warn("Couldn't save skins: {}", e.toString());
        }
    }

    /** True if a lookup for this name is worth starting now (not cached fresh, not failed recently, not running). */
    public static boolean needsFetch(String name) {
        if (!wantsRealSkin(name)) return false;
        String k = key(name);
        Cached c = CACHE.get(k);
        long now = System.currentTimeMillis();
        if (c != null && now - c.fetchedAt() < REFRESH_MS) return false;
        Long failed = FAILED.get(k);
        if (failed != null && now - failed < RETRY_MS) return false;
        return !IN_FLIGHT.containsKey(k);
    }

    /** Is a lookup for this name still running? */
    public static boolean fetching(String name) {
        return IN_FLIGHT.containsKey(key(name));
    }

    /** Start looking the skin up in the background (does nothing if not needed). */
    public static void fetch(MinecraftServer server, String name) {
        if (!needsFetch(name)) return;
        String k = key(name);
        CompletableFuture<Void> job = CompletableFuture.runAsync(() -> {
            try {
                Optional<GameProfile> found = server.services().profileResolver().fetchByName(name);
                Property textures = found.flatMap(p -> p.properties().get("textures").stream().findFirst()).orElse(null);
                if (textures == null) {
                    FAILED.put(k, System.currentTimeMillis());
                    LOGGER.info("No skin found for {} (using the default)", name);
                    return;
                }
                CACHE.put(k, new Cached(textures.value(), textures.signature(), System.currentTimeMillis()));
                FAILED.remove(k);
                save();
                LOGGER.info("Got {}'s skin", name);
            } catch (RuntimeException e) {
                FAILED.put(k, System.currentTimeMillis());
                LOGGER.info("Couldn't look up {}'s skin ({}): using the default", name, e.toString());
            }
        });
        IN_FLIGHT.put(k, job);
        job.whenComplete((v, t) -> IN_FLIGHT.remove(k));
    }

    /** The profile a bot joins with: its offline id and name, plus the real skin if we have it. */
    public static GameProfile profile(UUID id, String name) {
        Cached c = wantsRealSkin(name) ? CACHE.get(key(name)) : null;
        if (c == null) return new GameProfile(id, name);
        Property textures = c.signature() == null ? new Property("textures", c.value()) : new Property("textures", c.value(), c.signature());
        return new GameProfile(id, name, new PropertyMap(ImmutableMultimap.of("textures", textures)));
    }
}
