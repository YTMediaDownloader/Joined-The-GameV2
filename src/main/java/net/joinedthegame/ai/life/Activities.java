package net.joinedthegame.ai.life;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.joinedthegame.ai.Personality;
import net.joinedthegame.ai.goal.ProjectGoal;
import net.joinedthegame.ai.life.Needs.Need;

/**
 * How appealing each kind of thing to do is, from the needs it meets. The brain picks among the
 * ones that are possible right now, with odds by appeal (see {@code BotBrain#updateFocus}), so a
 * bot that badly wants a home builds one, and two bots in the same spot still do different things.
 */
public final class Activities {
    private Activities() {}

    /** The big things a bot can set out to do (the brain's "focus"). */
    public enum Activity { GEAR_UP, DIAMONDS, HOUSE, PROJECT, NETHER, WANDER, LEISURE, SOCIAL }

    public static double appeal(Activity a, Needs n, Personality p) {
        return switch (a) {
            case GEAR_UP -> n.pull(Need.GEAR, p) * 1.2 + n.pull(Need.WEALTH, p) * 0.2;
            case DIAMONDS -> n.pull(Need.WEALTH, p) + n.pull(Need.GEAR, p) * 0.5;
            case HOUSE -> n.pull(Need.HOME, p) + n.pull(Need.CREATIVITY, p) * 0.5;
            case NETHER -> n.pull(Need.ADVENTURE, p) * 0.8 + n.pull(Need.KNOWLEDGE, p) * 0.3;
            case WANDER -> n.pull(Need.CURIOSITY, p) + n.pull(Need.ADVENTURE, p) * 0.3;
            case LEISURE -> n.pull(Need.REST, p) + n.pull(Need.FOOD, p) * 0.2;
            case SOCIAL -> n.pull(Need.SOCIAL, p) + n.pull(Need.REST, p) * 0.2;
            case PROJECT -> 0; // per project, below
        };
    }

    /** How appealing a particular personal project is. */
    public static double appeal(ProjectGoal.Kind k, Needs n, Personality p) {
        return switch (k) {
            case FARM -> n.pull(Need.FOOD, p) * 0.6 + n.pull(Need.COMFORT, p) * 0.8;
            case WOLF -> n.pull(Need.COMFORT, p) * 0.9 + n.pull(Need.ADVENTURE, p) * 0.2;
            case DECORATE -> n.pull(Need.CREATIVITY, p) + n.pull(Need.HOME, p) * 0.3;
            case BOW -> n.pull(Need.GEAR, p) * 0.5 + n.pull(Need.ADVENTURE, p) * 0.2;
            case RAID -> n.pull(Need.ADVENTURE, p) * 0.9 + n.pull(Need.WEALTH, p) * 0.4;
            case VILLAGE_RAID -> n.pull(Need.ADVENTURE, p) * 0.8 + n.pull(Need.WEALTH, p) * 0.3;
            case ENCHANT -> n.pull(Need.KNOWLEDGE, p) + n.pull(Need.GEAR, p) * 0.3;
            case TRIAL_CHAMBER -> n.pull(Need.ADVENTURE, p) + n.pull(Need.GEAR, p) * 0.2 + n.pull(Need.WEALTH, p) * 0.3;
            case NETHERITE -> n.pull(Need.GEAR, p) * 0.6 + n.pull(Need.WEALTH, p) * 0.6;
            case PEN -> n.pull(Need.FOOD, p) * 0.5 + n.pull(Need.COMFORT, p) * 0.9;
            case TREES -> n.pull(Need.COMFORT, p) * 0.4 + n.pull(Need.CREATIVITY, p) * 0.4 + n.pull(Need.HOME, p) * 0.2;
            case CANE -> n.pull(Need.KNOWLEDGE, p) * 0.5 + n.pull(Need.COMFORT, p) * 0.3 + n.pull(Need.CREATIVITY, p) * 0.2;
            case GARDEN -> n.pull(Need.CREATIVITY, p) * 0.8 + n.pull(Need.COMFORT, p) * 0.4 + n.pull(Need.HOME, p) * 0.2;
            case PATHS -> n.pull(Need.CREATIVITY, p) * 0.6 + n.pull(Need.HOME, p) * 0.3 + n.pull(Need.COMFORT, p) * 0.2;
            case BREW -> n.pull(Need.KNOWLEDGE, p) * 0.7 + n.pull(Need.GEAR, p) * 0.6 + n.pull(Need.ADVENTURE, p) * 0.2;
        };
    }

    /**
     * Pick one, odds by appeal squared (strong pulls win most of the time; weaker ones still come
     * up now and then). Null if nothing appeals at all.
     */
    public static <T> T pick(Map<T, Double> appeal, double random01) {
        double total = 0;
        for (double v : appeal.values()) total += v * v;
        if (total <= 0) return null;
        double roll = random01 * total;
        T last = null;
        for (Map.Entry<T, Double> e : appeal.entrySet()) {
            last = e.getKey();
            if ((roll -= e.getValue() * e.getValue()) < 0) return e.getKey();
        }
        return last;
    }

    /** For the log: "house 0.92, gear_up 0.81, ...". */
    public static String describe(Map<?, Double> appeal) {
        StringBuilder out = new StringBuilder();
        appeal.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.append(e.getKey().toString().toLowerCase(Locale.ROOT)).append(' ')
                        .append(String.format(Locale.ROOT, "%.2f", e.getValue())).append(", "));
        return out.length() > 2 ? out.substring(0, out.length() - 2) : "";
    }

    public static <T extends Enum<T>> Map<T, Double> newMap(Class<T> type) {
        return new EnumMap<>(type);
    }
}
