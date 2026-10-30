package net.joinedthegame.ai.goal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.joinedthegame.JtgConfig;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Personality;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.skill.Duelist;
import net.joinedthegame.ai.social.Relation;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Killing someone's pet on purpose. Most bots never would. The ones that might:
 * <ul>
 *   <li><b>Spite:</b> it hates the owner deeply (a rival, well past the point of no return) and goes
 *       for what they love.</li>
 *   <li><b>Pranksters:</b> high mischief (see {@link Personality#mischief}), and no love for the
 *       owner: a cruel "joke", now and then.</li>
 * </ul>
 * Bots' pets and players' pets alike. Never its own, never a friend's. Decided once per pet every
 * so often (not every second it's in sight), and only a small chance even then.
 */
public final class SpiteGoal extends Goal {
    private @Nullable TamableAnimal target;
    private String ownerName = "";
    private int ticks;
    private final Duelist duel;
    private final Map<UUID, Long> rolledAt = new HashMap<>();
    /** Testing: go for the nearest pet (not ours, not a friend's) now, no dice. */
    private boolean forced;

    public void force() {
        this.forced = true;
        this.rolledAt.clear();
    }

    public SpiteGoal(BotBrain brain) {
        super(brain);
        this.duel = new Duelist(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (this.target != null && this.target.isAlive()) return 60; // decided: a few seconds, cuts through chores
        if (!JtgConfig.RIVALRIES.get() || this.brain.now() % 10 != 0) return 0;
        if (bot.getHealth() < bot.getMaxHealth() * 0.6f) return 0;
        double mischief = Personality.mischief(bot.getScoreboardName());
        for (TamableAnimal pet : Senses.entitiesNear(bot, TamableAnimal.class, 12, p -> p.isAlive() && p.isTame() && p.getOwner() != null)) {
            if (!(pet.getOwner() instanceof Player owner) || owner == bot) continue;
            if (!(owner instanceof BotPlayer) && !JtgConfig.PLAYER_RIVALRIES.get()) continue;
            Relation r = this.brain.knownRelation(owner.getUUID());
            double liking = r == null ? 0 : r.liking;
            if (liking >= 10 || !bot.hasLineOfSight(pet)) continue; // never a friend's
            Long at = this.rolledAt.get(pet.getUUID());
            if (at != null && this.brain.now() - at < 20 * 60 * 10) continue;
            this.rolledAt.put(pet.getUUID(), this.brain.now());
            boolean spite = liking <= -85;
            double chance = this.forced ? 1 : spite ? 0.2 : mischief >= 0.6 && liking <= 5 ? 0.05 * mischief : 0;
            this.forced = false;
            if (chance <= 0 || bot.getRandom().nextDouble() >= chance) continue;
            this.target = pet;
            this.ownerName = owner.getGameProfile().name();
            this.ticks = 0;
            this.brain.log("going for " + this.ownerName + "'s " + kind(pet) + (spite ? " (spite)" : " (for a laugh)"));
            return 60;
        }
        return 0;
    }

    private static String kind(TamableAnimal pet) {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).getPath();
    }

    @Override
    public boolean tick(BotPlayer bot) {
        TamableAnimal pet = this.target;
        if (pet == null || !pet.isAlive() || ++this.ticks > 20 * 30 || pet.distanceToSqr(bot) > 32 * 32) {
            if (pet != null && !pet.isAlive()) this.brain.log("killed " + this.ownerName + "'s " + kind(pet));
            this.target = null;
            this.duel.stop(bot);
            return false;
        }
        return this.duel.fight(bot, pet) || stopNow(bot);
    }

    private boolean stopNow(BotPlayer bot) {
        this.target = null;
        this.duel.stop(bot);
        return false;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        this.duel.stop(bot);
    }

    @Override
    public String activity() {
        return "Going after " + this.ownerName + "'s pet";
    }
}
