package net.joinedthegame.ai.social;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Peace calls waiting for an answer: who called it, to whom, when. A bot answers its own (in its
 * fight); a player answers by typing in chat (see JtgEvents), and saying nothing for a while is a
 * no. Server-wide, in memory only.
 */
public final class Peace {
    public record Offer(UUID from, String fromName, UUID to, long at) {}

    /** Answers: who answered whom, yes or no, when (read by the one who called). */
    public record Answer(UUID from, UUID to, boolean yes, long at) {}

    private static final Map<UUID, Offer> OFFERS = new ConcurrentHashMap<>();   // keyed by who it's to
    private static final Map<UUID, Answer> ANSWERS = new ConcurrentHashMap<>(); // keyed by who called

    /** How long a call waits for an answer (silence is a no). */
    public static final int WAIT = 20 * 10;

    private Peace() {}

    public static void offer(UUID from, String fromName, UUID to, long now) {
        OFFERS.put(to, new Offer(from, fromName, to, now));
        ANSWERS.remove(from);
    }

    /** A call to {@code to} still waiting for an answer. */
    public static @Nullable Offer offerTo(UUID to, long now) {
        Offer o = OFFERS.get(to);
        if (o != null && now - o.at() > WAIT) {
            OFFERS.remove(to);
            return null;
        }
        return o;
    }

    public static void answer(Offer o, boolean yes, long now) {
        OFFERS.remove(o.to());
        ANSWERS.put(o.from(), new Answer(o.to(), o.from(), yes, now));
    }

    /** The answer to our call, once (null while waiting). */
    public static @Nullable Answer answerFor(UUID caller) {
        return ANSWERS.remove(caller);
    }

    /** A call we made that's still out there. */
    public static boolean waitingOn(UUID caller, long now) {
        for (Offer o : OFFERS.values()) if (o.from().equals(caller) && now - o.at() <= WAIT) return true;
        return false;
    }
}
