package net.joinedthegame.ai.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * How one bot feels about one person (another bot, or a real player). One-sided: what A thinks of
 * B is only in A's memory. Saved with the bot, so it lasts across restarts.
 * <ul>
 *   <li><b>liking</b> -100..100: do I like them?</li>
 *   <li><b>trust</b> 0..100: will they do right by me? (starts at 50: no reason not to)</li>
 *   <li><b>familiarity</b> 0..100: how well do I know them?</li>
 *   <li><b>attraction</b> 0..100: romance only (part 3, step 4); unused until then.</li>
 * </ul>
 * The rest is kept ready for later steps (rivals, truces, John Wick), saved now so old worlds
 * don't need converting when those arrive.
 */
public final class Relation {
    public final UUID id;
    public String name;
    public boolean player;
    public double liking;
    /**
     * The lasting impression: the part of everything that's happened that never fades. Liking
     * drifts back towards this, not towards neutral. They always remember what you did.
     */
    public double lasting;
    public double trust = 50;
    public double familiarity;
    public double attraction;
    /** When we last saw them (game time), and when feelings last faded. */
    public long lastSeen;
    public long lastFaded;
    /** Sworn enemies since (0 = not rivals). Step 3. */
    public long rivalSince;
    /** A truce holds until (game time). Step 3. */
    public long truceUntil;
    /** They killed our pet on purpose and we went John Wick. Step 3. */
    public boolean wick;
    /** Together since (0 = not partners). Step 4. */
    public long partnerSince;
    /** Broke up at (0 = never together). */
    public long exSince;
    /** When we last asked them out (or they turned us down): not again for a while. */
    public long askedAt;

    public Relation(UUID id, String name, boolean player) {
        this.id = id;
        this.name = name;
        this.player = player;
    }

    private record Saved(UUID id, String name, boolean player, double liking, double lasting, double trust, double familiarity, double attraction,
                         long lastSeen, long lastFaded, long rivalSince, long truceUntil, boolean wick,
                         long partnerSince, long exSince, long askedAt) {}

    private static final Codec<Saved> SAVED = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Saved::id),
            Codec.STRING.optionalFieldOf("name", "").forGetter(Saved::name),
            Codec.BOOL.optionalFieldOf("player", false).forGetter(Saved::player),
            Codec.DOUBLE.optionalFieldOf("liking", 0.0).forGetter(Saved::liking),
            Codec.DOUBLE.optionalFieldOf("lasting", 0.0).forGetter(Saved::lasting),
            Codec.DOUBLE.optionalFieldOf("trust", 50.0).forGetter(Saved::trust),
            Codec.DOUBLE.optionalFieldOf("familiarity", 0.0).forGetter(Saved::familiarity),
            Codec.DOUBLE.optionalFieldOf("attraction", 0.0).forGetter(Saved::attraction),
            Codec.LONG.optionalFieldOf("last_seen", 0L).forGetter(Saved::lastSeen),
            Codec.LONG.optionalFieldOf("last_faded", 0L).forGetter(Saved::lastFaded),
            Codec.LONG.optionalFieldOf("rival_since", 0L).forGetter(Saved::rivalSince),
            Codec.LONG.optionalFieldOf("truce_until", 0L).forGetter(Saved::truceUntil),
            Codec.BOOL.optionalFieldOf("wick", false).forGetter(Saved::wick),
            Codec.LONG.optionalFieldOf("partner_since", 0L).forGetter(Saved::partnerSince),
            Codec.LONG.optionalFieldOf("ex_since", 0L).forGetter(Saved::exSince),
            Codec.LONG.optionalFieldOf("asked_at", 0L).forGetter(Saved::askedAt)
    ).apply(i, Saved::new));

    public static final Codec<Relation> CODEC = SAVED.xmap(s -> {
        Relation r = new Relation(s.id(), s.name(), s.player());
        r.liking = s.liking();
        r.lasting = s.lasting();
        r.trust = s.trust();
        r.familiarity = s.familiarity();
        r.attraction = s.attraction();
        r.lastSeen = s.lastSeen();
        r.lastFaded = s.lastFaded();
        r.rivalSince = s.rivalSince();
        r.truceUntil = s.truceUntil();
        r.wick = s.wick();
        r.partnerSince = s.partnerSince();
        r.exSince = s.exSince();
        r.askedAt = s.askedAt();
        return r;
    }, r -> new Saved(r.id, r.name, r.player, r.liking, r.lasting, r.trust, r.familiarity, r.attraction, r.lastSeen, r.lastFaded,
            r.rivalSince, r.truceUntil, r.wick, r.partnerSince, r.exSince, r.askedAt));

    /** A word for how it is between us, for the command and the log. */
    public String status() {
        if (this.partnerSince > 0) return this.liking < 20 ? "partner (rocky)" : "partner";
        if (this.wick) return "wants them dead";
        if (this.rivalSince > 0) return "rival";
        if (this.familiarity < 8) {
            // Hardly knows them, or has half forgotten them: but not what they did.
            if (Math.abs(this.liking) < 10) return "barely knows them";
            return (this.liking > 0 ? "barely knows them, but liked them" : "barely knows them, but remembers what they did");
        }
        if (this.exSince > 0) return this.liking < 0 ? "ex (bitter)" : this.liking >= 60 ? "ex (still close)" : "ex";
        if (this.attraction >= 50 && this.liking >= 40) return "has a crush on them";
        if (this.liking >= 60) return "close friend";
        if (this.liking >= 30) return "friend";
        if (this.liking >= 10) return "likes them";
        if (this.liking > -10) return "neutral";
        if (this.liking > -30) return "not keen";
        if (this.liking > -70) return "dislikes them";
        return "hates them";
    }
}
