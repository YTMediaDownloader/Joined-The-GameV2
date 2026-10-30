package net.joinedthegame.ai.life;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Personality;
import net.joinedthegame.ai.task.FetchTask;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/**
 * What a bot needs right now, the way a person would feel it: each need a level from 0 (fine)
 * to 1 (badly), worked out from its situation, and how much each one matters to this
 * particular bot (its personality). Activities are picked by the needs they meet (see
 * {@code Activities}), instead of everyone climbing the same ladder.
 * <p>
 * Survival isn't in here: starving, drowning, a fight, the night are emergencies, and the brain
 * deals with those first, whatever the bot's in the mood for.
 */
public final class Needs {
    public enum Need { GEAR, FOOD, HOME, WEALTH, CURIOSITY, CREATIVITY, COMFORT, ADVENTURE, KNOWLEDGE, SOCIAL, REST }

    private final Map<Need, Double> level = new EnumMap<>(Need.class);
    private long updatedAt = -1_000_000L;

    /** How badly, 0..1 (as of the last update). */
    public double level(Need need) {
        return this.level.getOrDefault(need, 0.0);
    }

    /** How much this need matters to this bot, roughly 0.2..2. */
    public static double weight(Need need, Personality p) {
        return switch (need) {
            case GEAR -> 0.8 + p.diligent() * 0.4 + p.brave() * 0.2;
            case FOOD -> 1.0;
            case HOME -> 0.4 + p.homebody() * 0.8 + p.builder() * 0.6 + p.social() * 0.2 - p.nomad() * 0.5;
            case WEALTH -> 0.4 + p.miner() * 0.8 + p.diligent() * 0.2;
            case CURIOSITY -> 0.3 + p.explorer() * 1.2 + p.nomad() * 0.6;
            case CREATIVITY -> 0.2 + p.builder() * 1.2 + p.homebody() * 0.3;
            case COMFORT -> 0.3 + p.homebody() * 0.6 + p.animalLover() * 0.8;
            case ADVENTURE -> 0.2 + p.brave() * 0.8 + p.reckless() * 0.4 + p.explorer() * 0.2;
            case KNOWLEDGE -> 0.3 + p.scholar() * 1.2;
            case SOCIAL -> 0.2 + p.social() * 1.0;
            case REST -> 0.3 + p.homebody() * 0.4 + p.social() * 0.2 + (1 - p.diligent()) * 0.4;
        };
    }

    /** Level times weight: how much it's pulling at the bot right now. */
    public double pull(Need need, Personality p) {
        return level(need) * Math.max(0.1, weight(need, p));
    }

    /** Recompute (every few seconds is plenty). */
    public void update(BotPlayer bot, BotBrain brain) {
        long now = brain.now();
        if (now - this.updatedAt < 100) return;
        this.updatedAt = now;
        BotMemory m = brain.memory();
        this.level.put(Need.GEAR, gear(bot));
        this.level.put(Need.FOOD, food(bot, brain));
        this.level.put(Need.HOME, home(brain));
        this.level.put(Need.WEALTH, wealth(bot, brain));
        // The "it's been a while" needs: they creep up over time and are reset by doing the thing.
        this.level.put(Need.CURIOSITY, creep(now, m, 20 * 60 * 20, BotMemory.EventType.FOUND, BotMemory.EventType.VISITED));
        this.level.put(Need.CREATIVITY, creep(now, m, 20 * 60 * 30, BotMemory.EventType.BUILT, BotMemory.EventType.SETTLED, BotMemory.EventType.PLANTED));
        this.level.put(Need.COMFORT, comfort(brain));
        // Adventure waits till it's got something to adventure in.
        double adventure = creep(now, m, 20 * 60 * 40, BotMemory.EventType.LOOTED, BotMemory.EventType.VISITED);
        this.level.put(Need.ADVENTURE, level(Need.GEAR) > 0.6 ? adventure * 0.3 : adventure);
        this.level.put(Need.KNOWLEDGE, knowledge(bot, m));
        // Company: creeps up without it (half an hour), reset by spending time with someone.
        this.level.put(Need.SOCIAL, creep(now, m, 20 * 60 * (brain.partner() != null ? 18 : 30), BotMemory.EventType.SOCIALISED));
        // A break: everybody needs one now and then (less while there's no pickaxe to its name).
        double rest = creep(now, m, 20 * 60 * 30, BotMemory.EventType.RELAXED);
        this.level.put(Need.REST, level(Need.GEAR) > 0.7 ? rest * 0.3 : rest);
    }

    /** Tools, weapon, armour and a shield: 1 with nothing, about 0.3 in full iron, near 0 in diamond. */
    private static double gear(BotPlayer bot) {
        double have = Math.min(5, Inv.pickaxeTier(bot)) / 5.0 * 0.3
                + Math.min(5, Math.max(Inv.swordTier(bot), Inv.axeTier(bot))) / 5.0 * 0.2
                + (bot.getOffhandItem().is(Items.SHIELD) || Inv.count(bot, Items.SHIELD) > 0 ? 0.1 : 0);
        double armour = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            armour += Math.min(5, Inv.armorTier(bot, slot)) / 5.0;
        }
        have += armour / 4 * 0.4;
        return clamp(1 - have);
    }

    /** Meals to hand (bag and chests): about ten keeps a bot happy. */
    private static double food(BotPlayer bot, BotBrain brain) {
        int meals = Inv.count(bot, s -> Inv.isFood(s) && !Inv.isBadFood(s) && !Inv.isRawMeat(s))
                + FetchTask.stored(bot, brain, s -> Inv.isFood(s) && !Inv.isBadFood(s));
        return clamp(1 - meals / 12.0);
    }

    /** No home at all, a bed somewhere, a hut or cave, a cabin, a proper house. */
    private static double home(BotBrain brain) {
        if (brain.record().home() == null) return 1;
        String type = brain.memory().homeType.toUpperCase(Locale.ROOT);
        return switch (type) {
            case "LARGE" -> 0.05;
            case "HOUSE" -> 0.15;
            case "CABIN" -> 0.35;
            case "" -> 0.6;
            default -> 0.5; // a hut, a cave, a bed in a village
        };
    }

    /** Diamonds and iron put by. */
    private static double wealth(BotPlayer bot, BotBrain brain) {
        int diamonds = Inv.count(bot, Items.DIAMOND) + FetchTask.stored(bot, brain, s -> s.is(Items.DIAMOND));
        int iron = Inv.count(bot, Items.IRON_INGOT) + Inv.count(bot, Items.RAW_IRON)
                + FetchTask.stored(bot, brain, s -> s.is(Items.IRON_INGOT) || s.is(Items.RAW_IRON));
        double rich = Math.min(1, (diamonds * 4 + iron) / 64.0);
        // Already in diamond: wealth for its own sake matters less.
        return clamp((1 - rich) * (Inv.fullDiamond(bot) ? 0.4 : 1));
    }

    /** A farm and a pet. */
    private static double comfort(BotBrain brain) {
        double n = 0;
        var home = brain.record().home();
        if (home != null && brain.memory().nearest("farm", home, 64) == null) n += 0.5;
        if (!net.joinedthegame.ai.project.WolfProject.hasPet(brain)) n += 0.4;
        return clamp(n);
    }

    /** Levels piling up, and no enchanting table yet. */
    private static double knowledge(BotPlayer bot, BotMemory m) {
        double n = Math.min(1, bot.experienceLevel / 30.0) * 0.7;
        if (!m.ever(BotMemory.EventType.BUILT, "an enchanting table")) n += 0.3;
        return clamp(n);
    }

    /** 0 just after the last of these, rising to 1 after {@code full} ticks without. */
    private static double creep(long now, BotMemory m, long full, BotMemory.EventType... types) {
        long last = Long.MIN_VALUE;
        for (BotMemory.Event e : m.events()) {
            for (BotMemory.EventType t : types) {
                if (e.type() == t) last = Math.max(last, Math.max(e.time(), e.last()));
            }
        }
        if (last == Long.MIN_VALUE) return 0.7; // never yet: interested, not desperate
        return clamp((now - last) / (double) full);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** For /jtg debug: "gear 0.82 food 0.10 ...", the strongest first. */
    public String describe(Personality p) {
        StringBuilder out = new StringBuilder();
        this.level.entrySet().stream()
                .sorted((a, b) -> Double.compare(pull(b.getKey(), p), pull(a.getKey(), p)))
                .forEach(e -> out.append(e.getKey().name().toLowerCase(Locale.ROOT)).append(' ')
                        .append(String.format(Locale.ROOT, "%.2f", e.getValue())).append("  "));
        return out.toString().trim();
    }
}
