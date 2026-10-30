package net.joinedthegame.ai;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;

/**
 * Sword sweeps. A full swing with a sword also catches whatever's stood right by what it was
 * aimed at, so two people going for the same sheep can catch each other. That's clumsy, not an
 * attack, and nobody should start a fight to the death over it (PenTester killed Redlantern that
 * way: a sweep at a sheep, a swing back in "self defence", a fight neither of them picked).
 * <ul>
 *   <li>Bots don't sweep with somebody (a player, a pet, a villager, a golem) stood by what
 *       they're hitting: just the one target, the way a careful player would.</li>
 *   <li>Every sweep (anyone's, real players' too) is noted as it starts, so a bot caught by one
 *       can tell an accident (they were swinging at something else) from being hit on purpose.</li>
 * </ul>
 */
public final class Sweeps {
    private record Sweep(int targetId, long at) {}

    private static final Map<UUID, Sweep> LAST = new ConcurrentHashMap<>();

    private Sweeps() {}

    /** Someone we wouldn't want to catch is in the sweep around {@code target}. */
    public static boolean bystanders(Player attacker, Entity target) {
        var box = target.getBoundingBox().inflate(1.0, 0.25, 1.0);
        return !attacker.level().getEntities(attacker, box, e -> e != target && e.isAlive() && !e.isSpectator()
                && (e instanceof Player || e instanceof OwnableEntity o && o.getOwnerReference() != null
                        || e instanceof AbstractVillager || e instanceof IronGolem)).isEmpty();
    }

    public static void note(Player attacker, Entity target) {
        LAST.put(attacker.getUUID(), new Sweep(target.getId(), attacker.level().getGameTime()));
    }

    /** Caught by {@code attacker}'s sweep at something else, this very tick: an accident. */
    public static boolean accident(Player attacker, Entity victim) {
        Sweep s = LAST.get(attacker.getUUID());
        return s != null && s.at() == attacker.level().getGameTime() && s.targetId() != victim.getId();
    }
}
