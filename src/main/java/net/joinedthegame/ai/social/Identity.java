package net.joinedthegame.ai.social;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.RandomSource;
import org.jspecify.annotations.Nullable;

/**
 * Who a bot is attracted to: its gender and orientation. Romance only (part 3, step 4).
 * <p>
 * Gender follows the roster (the celebrity and creator bots as listed; the user's own picks for
 * a few of the originals). Orientation is <b>rolled once per world</b> and saved with the bot, not
 * written down per real person: the bots are fictional versions, and this is part of the game's
 * fiction, not a claim about anyone. A few originals are fixed by the user's choice. Saved on the
 * bot's roster record as "f:straight", "m:bi" and so on.
 */
public final class Identity {
    public enum Gender { F, M }

    public enum Orientation { STRAIGHT, GAY, LESBIAN, BI }

    public record Who(Gender gender, Orientation orientation) {
        public String save() {
            return gender.name().toLowerCase(Locale.ROOT) + ":" + orientation.name().toLowerCase(Locale.ROOT);
        }

        /** Could this one be attracted to someone of that gender? */
        public boolean into(Gender other) {
            return switch (this.orientation) {
                case STRAIGHT -> other != this.gender;
                case GAY, LESBIAN -> other == this.gender;
                case BI -> true;
            };
        }

        public String describe() {
            return (gender == Gender.F ? "female" : "male") + ", " + orientation.name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Set<String> FEMALE = Set.of(
            "madisonbeer", "milliebobbybrown", "sadiesink", "jennaortega", "mayahawke", "nataliadyer", "emmamyers",
            "sabrinacarpenter", "oliviarodrigo", "billieeilish", "arianagrande", "dualipa", "selenagomez", "tatemcrae",
            "haileesteinfeld", "zendaya", "florencepugh",
            "ldshadowlady", "geminitay", "pearlescentmoon", "falsesymmetry", "shubble", "ihascupquake", "aphmau", "katherineeliza");
    private static final Set<String> MALE = Set.of(
            "stephencurry", "lebronjames", "finnwolfhard", "joekeery", "jackblack", "grian", "mumbo", "smallishbeans",
            "captainsparklez", "markiplier", "dantdm", "stampylongnose",
            "redlantern8808", "duckatyt", "cozmoiscool130");
    /** Fixed by the user, the same in every world. */
    private static final Map<String, Orientation> FIXED = Map.of(
            "redlantern8808", Orientation.STRAIGHT,
            "duckatyt", Orientation.BI,
            "cozmoiscool130", Orientation.STRAIGHT);

    private Identity() {}

    public static @Nullable Who load(String saved) {
        int c = saved.indexOf(':');
        if (c < 0) return null;
        try {
            return new Who(Gender.valueOf(saved.substring(0, c).toUpperCase(Locale.ROOT)),
                    Orientation.valueOf(saved.substring(c + 1).toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A new bot's, for this world: gender from the roster (or rolled), orientation rolled (or fixed). */
    public static Who roll(String name, RandomSource random) {
        String key = name.toLowerCase(Locale.ROOT);
        Gender g = FEMALE.contains(key) ? Gender.F : MALE.contains(key) ? Gender.M : random.nextBoolean() ? Gender.F : Gender.M;
        Orientation fixed = FIXED.get(key);
        if (fixed != null) return new Who(g, fixed);
        double r = random.nextDouble();
        Orientation o = r < 0.70 ? Orientation.STRAIGHT : r < 0.85 ? Orientation.BI : g == Gender.F ? Orientation.LESBIAN : Orientation.GAY;
        return new Who(g, o);
    }

    /**
     * This bot's, for this world: what's saved, or a new roll. The roster's genders and the user's
     * fixed picks always win (over anything saved before they were set).
     */
    public static Who resolve(String name, String saved, RandomSource random) {
        String key = name.toLowerCase(Locale.ROOT);
        Who w = load(saved);
        if (w == null) w = roll(name, random);
        Gender g = FEMALE.contains(key) ? Gender.F : MALE.contains(key) ? Gender.M : w.gender();
        Orientation o = FIXED.getOrDefault(key, w.orientation());
        if (o == Orientation.GAY && g == Gender.F) o = Orientation.LESBIAN;
        if (o == Orientation.LESBIAN && g == Gender.M) o = Orientation.GAY;
        return new Who(g, o);
    }

    /** Both ways: could a be into b and b into a? */
    public static boolean compatible(Who a, Who b) {
        return a.into(b.gender()) && b.into(a.gender());
    }
}
