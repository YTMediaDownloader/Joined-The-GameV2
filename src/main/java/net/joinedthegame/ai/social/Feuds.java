package net.joinedthegame.ai.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Who's fighting whom right now (bots only: what a player's up to is their business). Other bots
 * can see a fight: a friend can jump in, and a bot can tell when it's outnumbered. Kept fresh every
 * tick of the fight, gone a few seconds after it stops. Server-wide, in memory only.
 */
public final class Feuds {
    public enum Why { RIVAL, SELF_DEFENCE, HELPING_FRIEND, VENGEANCE }

    public record Fight(UUID fighter, UUID opponent, Why why, long at) {}

    private static final Map<UUID, Fight> FIGHTS = new ConcurrentHashMap<>();

    private Feuds() {}

    public static void fighting(UUID fighter, UUID opponent, Why why, long now) {
        FIGHTS.put(fighter, new Fight(fighter, opponent, why, now));
    }

    public static void stopped(UUID fighter) {
        FIGHTS.remove(fighter);
    }

    public static @Nullable Fight of(UUID fighter, long now) {
        Fight f = FIGHTS.get(fighter);
        return f != null && now - f.at() <= 60 ? f : null;
    }

    /** Everyone fighting {@code who} right now. */
    public static List<UUID> against(UUID who, long now) {
        List<UUID> out = new ArrayList<>();
        for (Fight f : FIGHTS.values()) if (f.opponent().equals(who) && now - f.at() <= 60) out.add(f.fighter());
        return out;
    }
}
