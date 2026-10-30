package net.joinedthegame.ai.social;

import net.joinedthegame.ai.Personality;

/**
 * What things people do to a bot are worth to it, and how feelings fade. Every number in one place,
 * so the whole of "how much does this matter" can be read and tuned here.
 * <p>
 * Personality scales it all. Bad things hit a vengeful bot harder and a forgiving one softer; good
 * things mean more to a sociable bot. Bad feelings fade slower than good ones, and slower still
 * for the vengeful (a forgiving bot lets go in a few days, a vengeful one carries it for weeks).
 */
public final class Feelings {
    private Feelings() {}

    /** Things someone did, and what each is worth: liking, trust (before personality). */
    public enum Act {
        /** First time we met. Nothing either way, just knowing they exist. */
        MET(0, 0, "met"),
        /** Time spent near them (every few seconds): familiarity only. */
        NEAR(0, 0, "around"),
        /** Came over and said hi (a stranger, or someone we barely know). */
        GREETED(2, 1, "said hi"),
        /** Waited for us to finish with the only tree about, instead of piling in. */
        WAITED_FOR_ME(2, 3, "waited their turn"),
        /** Piled in on the tree (or vein) we were working. */
        CROWDED_ME(-4, -3, "crowded my tree"),
        /** Took what we'd just chopped or dug. */
        STOLE_FROM_ME(-10, -12, "took what I chopped"),
        /** Hurt us (per hit; scaled by how hard). */
        HIT_ME(-4, -4, "hit me"),
        /** Killed us. */
        KILLED_ME(-40, -30, "killed me"),
        /** Gave us something (scaled by what it was worth to us). */
        GAVE_ME(1, 1, "gave me something"),
        /** Killed our pet on purpose. */
        KILLED_MY_PET(-60, -50, "killed my pet"),
        /** Killed our pet while it was going for them (it was defending us). */
        KILLED_MY_PET_DEFENDING(-20, -15, "killed my pet (it was going for them)"),
        /** Our pet died with them involved, but not on purpose (a sweep, a stray arrow). */
        PET_ACCIDENT(-5, -4, "got my pet killed"),
        /** Went after our partner (asked them out, or a lot of time with them). */
        JEALOUS(-20, -15, "went after my partner"),
        /** We called a truce in a fight. */
        MADE_PEACE(5, 6, "made peace with me"),
        /** Jumped into a fight against us, on someone else's side. */
        JUMPED_ME(-10, -8, "jumped into a fight against me"),
        /** Took our stuff after killing us. */
        LOOTED_ME(-12, -10, "took my stuff"),
        /** Broke a truce we'd made. */
        BROKE_TRUCE(-25, -40, "broke our truce"),
        /** Killed us after we'd called peace and they'd said no. */
        KILLED_AFTER_PEACE(-30, -30, "killed me after I called peace"),
        /** We saw them kill someone who'd called peace. */
        CRUEL(-8, -20, "killed someone who'd called peace"),
        /** Spent a while together (company). */
        SPENT_TIME(3, 2, "spent time with me"),
        /** Killed something that was attacking us. */
        HELPED_ME(8, 6, "helped me in a fight"),
        /** Caught us with a sword sweep going for something else: clumsy, not meant. */
        SWEPT_ME(-1, -1, "caught me with a sweep"),
        /** Kept punching us for fun (bare-handed taps, over and over). */
        ANNOYED_ME(-3, -2, "kept punching me");

        public final double liking;
        public final double trust;
        public final String why;

        Act(double liking, double trust, String why) {
            this.liking = liking;
            this.trust = trust;
            this.why = why;
        }
    }

    /**
     * Forgiving (1) to vengeful (0), from who the bot is: sociable, steady, homely bots let things
     * go; brave, reckless ones don't.
     */
    public static double forgiving(Personality p) {
        return Math.clamp(p.social() * 0.35 + p.diligent() * 0.15 + p.homebody() * 0.2 + (1 - p.brave()) * 0.15
                + (1 - p.reckless()) * 0.15, 0.0, 1.0);
    }

    /** Apply what they did. {@code amount} scales it (1 for most things). Returns the change in liking. */
    public static double apply(Relation r, Act act, double amount, Personality p, long now) {
        double before = r.liking;
        double bad = 1.4 - forgiving(p) * 0.8;   // 0.6 (forgiving) .. 1.4 (vengeful)
        double good = 0.7 + p.social() * 0.6;    // 0.7 (loner) .. 1.3 (sociable)
        double dl = act.liking * amount, dt = act.trust * amount;
        dl *= dl < 0 ? bad : good;
        dt *= dt < 0 ? bad : good;
        r.liking = Math.clamp(r.liking + dl, -100, 100);
        // Part of it sticks for good: more of the bad than the good, half of being killed.
        double sticks = act == Act.KILLED_ME || act == Act.KILLED_AFTER_PEACE || act == Act.BROKE_TRUCE || act == Act.KILLED_MY_PET
                ? 0.5 : dl < 0 ? 0.3 : 0.2;
        r.lasting = Math.clamp(r.lasting + dl * sticks, -100, 100);
        r.trust = Math.clamp(r.trust + dt, 0, 100);
        switch (act) {
            case MET -> r.familiarity = Math.max(r.familiarity, 3);
            case GREETED -> r.familiarity = Math.min(100, Math.max(r.familiarity, 3) + 6);
            case NEAR -> {
                r.familiarity = Math.min(100, r.familiarity + 0.12 * amount);
                // Being around someone you don't mind: you slowly warm to them (not past friendly).
                if (r.liking > -5 && r.liking < 25) r.liking = Math.min(25, r.liking + 0.08 * amount * good);
            }
            // Anything that actually happens between you: you know them a bit better for it.
            default -> r.familiarity = Math.min(100, r.familiarity + 1.5);
        }
        r.lastSeen = Math.max(r.lastSeen, now);
        return r.liking - before;
    }

    /**
     * A day's fading (call with how many in-game days have passed). Liking drifts back toward the
     * lasting impression (never all the way to neutral), good feelings faster than bad ones; trust
     * drifts back toward 50; someone not seen for days gets less familiar (half forgotten, but not
     * what they did). John Wick never fades.
     */
    public static void fade(Relation r, double days, Personality p, long now) {
        if (days <= 0 || r.wick) return; // John Wick doesn't fade
        double f = forgiving(p);
        double goodKeep = Math.pow(0.96, days);
        double badKeep = Math.pow(0.99 - f * 0.03, days); // 1%/day (vengeful) .. 4%/day (forgiving)
        // Back towards the lasting impression, never past it: they always remember what you did.
        double over = r.liking - r.lasting;
        r.liking = r.lasting + over * (over > 0 ? goodKeep : badKeep);
        r.trust = 50 + (r.trust - 50) * Math.pow(0.98, days);
        long unseenDays = (now - r.lastSeen) / 24000L;
        if (unseenDays >= 3) r.familiarity *= Math.pow(0.99, days);
        // Attraction keeps while you see each other; apart, it fades (and a partner left alone
        // too long drifts away: see BotBrain.fadeFeelings).
        if (unseenDays >= 2) r.attraction *= Math.pow(0.97, days);
    }
}
