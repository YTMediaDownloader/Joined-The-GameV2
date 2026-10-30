package net.joinedthegame.ai.social;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Personality;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;

/**
 * Someone's pet killed: on purpose or not, and what the owner does about it.
 * <p>
 * On purpose means the killer hit the pet itself in the last few seconds: a blow aimed at it, or
 * something they shot at it. A sword sweep that caught it, an explosion, fire, lava or a mob don't
 * count. Killing a pet that was going for you (defending its owner) is self-defence: it hurts, but
 * it's not the same thing.
 * <p>
 * The owner's reaction is who it is: a peaceful, forgiving bot grieves, thinks much less of them
 * and finds a new pet; a middling one makes them a rival; a vengeful, brave, reckless one goes John
 * Wick (certain for the most extreme). See BotBrain.onPetKilled.
 */
public final class PetKills {
    private PetKills() {}

    private record Hit(UUID by, long at) {}

    private static final Map<UUID, Hit> LAST_HIT = new ConcurrentHashMap<>();
    /** When someone first hit this pet, and when it first went for someone: who started it. */
    private static final Map<String, Long> FIRST_HIT = new ConcurrentHashMap<>();
    private static final Map<String, Long> WENT_FOR = new ConcurrentHashMap<>();

    private static String key(TamableAnimal pet, UUID who) {
        return pet.getUUID() + "/" + who;
    }

    /** Someone hit this pet directly (aimed at it, or shot it). */
    public static void hit(TamableAnimal pet, Player by) {
        if (LAST_HIT.size() > 200) LAST_HIT.clear();
        if (FIRST_HIT.size() > 400) FIRST_HIT.clear();
        long now = pet.level().getGameTime();
        LAST_HIT.put(pet.getUUID(), new Hit(by.getUUID(), now));
        FIRST_HIT.putIfAbsent(key(pet, by.getUUID()), now);
    }

    /** The pet went for someone (defending its owner, or told to). */
    public static void wentFor(TamableAnimal pet, Player who) {
        if (WENT_FOR.size() > 400) WENT_FOR.clear();
        WENT_FOR.putIfAbsent(key(pet, who.getUUID()), pet.level().getGameTime());
    }

    /** It died, killed by {@code killer}: tell its owner, if the owner's a bot. */
    public static void died(TamableAnimal pet, Player killer) {
        Hit h = LAST_HIT.remove(pet.getUUID());
        if (!(pet.getOwner() instanceof BotPlayer owner) || owner == killer) return;
        boolean onPurpose = h != null && h.by().equals(killer.getUUID()) && pet.level().getGameTime() - h.at() <= 20 * 3;
        // Self-defence: the pet went for them first, before they ever hit it. (A pet always turns on
        // whoever hits it, so "it was going for them when it died" proves nothing.)
        Long went = WENT_FOR.remove(key(pet, killer.getUUID()));
        Long first = FIRST_HIT.remove(key(pet, killer.getUUID()));
        boolean selfDefence = went != null && (first == null || went < first);
        var server = owner.level().getServer();
        BotBrain brain = server == null ? null : BotManager.get(server).brain(owner.getUUID());
        String what = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).getPath();
        if (brain != null) brain.onPetKilled(killer, what, onPurpose, selfDefence);
    }

    /**
     * How vengeful the owner is about it, 0..1: vengeful (not forgiving), brave, reckless, and how
     * much it loves animals. At 0.7 and up it is certain John Wick; below 0.45 it never is.
     */
    public static double vengeance(Personality p) {
        return Math.clamp((1 - Feelings.forgiving(p)) * 0.6 + p.brave() * 0.2 + p.reckless() * 0.2 + p.animalLover() * 0.15, 0.0, 1.0);
    }
}
