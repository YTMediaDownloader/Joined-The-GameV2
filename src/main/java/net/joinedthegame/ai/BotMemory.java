package net.joinedthegame.ai;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import org.jspecify.annotations.Nullable;

/**
 * Everything a bot remembers between sessions. Stored on the bot's own player data (a
 * NeoForge attachment), so it survives restarts, quitting the world and dying.
 *
 * <p>Built to grow: relationships, personal goals and grudges will hang off this later.
 * <ul>
 *   <li><b>Plans:</b> its big-picture focus, the house it's halfway through building, where
 *       it last died.</li>
 *   <li><b>Places:</b> crafting tables, furnaces, villages, hay and so on it has seen or used,
 *       so it can go back instead of rediscovering everything.</li>
 *   <li><b>Diary:</b> notable things that happened, newest last.</li>
 * </ul>
 *
 * <p>The rule is <i>remember usefulness, not everything</i>. Everyday places (a crafting table,
 * a furnace, an ore vein) fade if they go unused for a few days, and a vein is dropped once
 * it's mined out. Places that matter (villages, the stash, death hotspots) are sticky and
 * never fade. Old diary entries that were never important get forgotten too.
 */
public final class BotMemory {
    public static final int MAX_PLACES_PER_KIND = 16;
    /** Everyday places are forgotten after this long without being seen or used (5 game days). */
    public static final long PLACE_FADE = 24000L * 5;
    /** Minor diary entries (one-offs, low value) fade after this long (10 game days). */
    public static final long EVENT_FADE = 24000L * 10;

    /** Places that matter too much to fade: you don't forget where the village or your stash is. */
    private static final java.util.Set<String> STICKY = java.util.Set.of("village", "stash", "death_hotspot", "home", "project", "farm", "portal");

    public static boolean isSticky(String kind) {
        return STICKY.contains(kind) || kind.startsWith("structure:");
    }

    /** A remembered place and when the bot last saw or used it. */
    public record Place(GlobalPos pos, long seen) {
        public static final Codec<Place> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("pos").forGetter(Place::pos),
                Codec.LONG.optionalFieldOf("seen", 0L).forGetter(Place::seen)
        ).apply(i, Place::new));
    }

    /** A house in progress. {@code stage} is SettleGoal's stage name. */
    /**
     * What a bot remembers of a Trial Chamber that the world doesn't keep for it: which trial
     * spawners it has beaten (normal and ominous: a spawner goes back to waiting after its
     * cooldown, so its state alone can't say "done"), the ways in it has used, how many times it
     * has died in there, when it backed off (and so not to rush straight back), and how far it got.
     */
    public static final class Chamber {
        public static final Codec<Chamber> CODEC = RecordCodecBuilder.create(i -> i.group(
                GlobalPos.CODEC.fieldOf("center").forGetter(c -> c.center),
                Codec.LONG.listOf().optionalFieldOf("cleared", List.of()).forGetter(c -> List.copyOf(c.cleared)),
                Codec.LONG.listOf().optionalFieldOf("ominous_cleared", List.of()).forGetter(c -> List.copyOf(c.ominousCleared)),
                Codec.LONG.listOf().optionalFieldOf("entrances", List.of()).forGetter(c -> List.copyOf(c.entrances)),
                Codec.INT.optionalFieldOf("deaths", 0).forGetter(c -> c.deaths),
                Codec.LONG.optionalFieldOf("rest_until", 0L).forGetter(c -> c.restUntil),
                Codec.STRING.optionalFieldOf("status", "discovered").forGetter(c -> c.status),
                Codec.STRING.optionalFieldOf("left_because", "").forGetter(c -> c.leftBecause)
        ).apply(i, Chamber::new));

        public final GlobalPos center;
        public final java.util.Set<Long> cleared = new java.util.LinkedHashSet<>();
        public final java.util.Set<Long> ominousCleared = new java.util.LinkedHashSet<>();
        public final List<Long> entrances = new ArrayList<>();
        public int deaths;
        public long restUntil;
        /** discovered, entered, partly cleared, trials cleared, vaults left, ominous, raided. */
        public String status;
        public String leftBecause;

        public Chamber(GlobalPos center) {
            this(center, List.of(), List.of(), List.of(), 0, 0L, "discovered", "");
        }

        private Chamber(GlobalPos center, List<Long> cleared, List<Long> ominousCleared, List<Long> entrances, int deaths, long restUntil,
                        String status, String leftBecause) {
            this.center = center;
            this.cleared.addAll(cleared);
            this.ominousCleared.addAll(ominousCleared);
            this.entrances.addAll(entrances);
            this.deaths = deaths;
            this.restUntil = restUntil;
            this.status = status;
            this.leftBecause = leftBecause;
        }

        public void addEntrance(BlockPos pos) {
            long l = pos.asLong();
            for (long e : this.entrances) if (BlockPos.of(e).distSqr(pos) < 8 * 8) return;
            this.entrances.add(l);
            if (this.entrances.size() > 8) this.entrances.removeFirst();
        }
    }

    public record HousePlan(String type, BlockPos room, Direction forward, String stage, int failures) {
        public static final Codec<HousePlan> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("type").forGetter(HousePlan::type),
                BlockPos.CODEC.fieldOf("room").forGetter(HousePlan::room),
                Direction.CODEC.fieldOf("forward").forGetter(HousePlan::forward),
                Codec.STRING.fieldOf("stage").forGetter(HousePlan::stage),
                Codec.INT.optionalFieldOf("failures", 0).forGetter(HousePlan::failures)
        ).apply(i, HousePlan::new));
    }

    /**
     * Something that happened, stored as data (not text) so later systems can reason from it:
     * relationships ("helped by X, 5 times"), exploration ("died twice down there"), personal
     * goals. The diary just renders these as sentences.
     *
     * @param type    what happened (see {@link EventType})
     * @param subject who or what: a mob type id, a player name, an item id
     * @param where   where it happened, if that matters
     * @param value   how much it mattered, 0..1 (damage taken, how valuable, ...)
     * @param time    game time of the first occurrence
     * @param last    game time of the latest occurrence
     * @param count   how many times (repeats of the same thing are merged)
     */
    public record Event(EventType type, String subject, Optional<GlobalPos> where, float value, long time, long last, int count) {
        public static final Codec<Event> CODEC = RecordCodecBuilder.create(i -> i.group(
                EventType.CODEC.fieldOf("type").forGetter(Event::type),
                Codec.STRING.optionalFieldOf("subject", "").forGetter(Event::subject),
                GlobalPos.CODEC.optionalFieldOf("where").forGetter(Event::where),
                Codec.FLOAT.optionalFieldOf("value", 0f).forGetter(Event::value),
                Codec.LONG.fieldOf("time").forGetter(Event::time),
                Codec.LONG.optionalFieldOf("last", 0L).forGetter(Event::last),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Event::count)
        ).apply(i, Event::new));

        /** Readable version for the diary. */
        public String describe() {
            String what = this.subject.replace("minecraft:", "").replace('_', ' ');
            String times = this.count > 1 ? " (x" + this.count + ")" : "";
            return switch (this.type) {
                case DIED -> "Died: " + what + times;
                case ATTACKED_BY -> "Attacked by a " + what + times;
                case KILLED -> "Killed a " + what + times;
                case MET -> "Met " + this.subject;
                case FOUND -> "Found " + what + times;
                case MADE -> "Made my first " + what;
                case SETTLED -> "Settled into a " + what;
                case FOCUS -> "Decided to go for " + what;
                case RECOVERED_ITEMS -> "Got my stuff back after dying" + times;
                case LOST_ITEMS -> "Lost my stuff for good" + times;
                case HIT_BY_PLAYER -> "Got punched by " + this.subject + times;
                case TAMED -> "Tamed a " + what;
                case BUILT -> "Built " + what;
                case PLANTED -> "Planted " + what;
                case PET_DIED -> "Lost my " + what + " :(";
                case LOOTED -> "Finished raiding a " + what + times;
                case VISITED -> "Went to " + what + times;
            };
        }
    }

    /** Kinds of memories. Add new ones at the end (they're saved by name). */
    public enum EventType implements net.minecraft.util.StringRepresentable {
        DIED, ATTACKED_BY, KILLED, MET, FOUND, MADE, SETTLED, FOCUS, RECOVERED_ITEMS, LOST_ITEMS, HIT_BY_PLAYER,
        TAMED, BUILT, PLANTED, PET_DIED, LOOTED, VISITED;

        public static final Codec<EventType> CODEC = net.minecraft.util.StringRepresentable.fromEnum(EventType::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final MapCodec<BotMemory> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.optionalFieldOf("focus", "NONE").forGetter(m -> m.focus),
            Codec.LONG.optionalFieldOf("focus_until", 0L).forGetter(m -> m.focusUntil),
            Codec.LONG.optionalFieldOf("next_roll", 0L).forGetter(m -> m.nextRollAt),
            GlobalPos.CODEC.optionalFieldOf("death_spot").forGetter(m -> Optional.ofNullable(m.deathSpot)),
            Codec.LONG.optionalFieldOf("death_time", 0L).forGetter(m -> m.deathTime),
            Codec.INT.optionalFieldOf("deaths_in_a_row", 0).forGetter(m -> m.deathsInARow),
            HousePlan.CODEC.optionalFieldOf("house_plan").forGetter(m -> Optional.ofNullable(m.housePlan)),
            Codec.unboundedMap(Codec.STRING, Place.CODEC.listOf()).optionalFieldOf("places", Map.of()).forGetter(m -> m.places),
            Event.CODEC.listOf().optionalFieldOf("events", List.of()).forGetter(m -> m.events),
            Codec.STRING.optionalFieldOf("project", "").forGetter(m -> m.project),
            Codec.STRING.optionalFieldOf("home_type", "").forGetter(m -> m.homeType),
            GlobalPos.CODEC.listOf().optionalFieldOf("looted_containers", List.of()).forGetter(m -> m.lootedContainers),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("stored", Map.of()).forGetter(m -> m.stored),
            Chamber.CODEC.listOf().optionalFieldOf("chambers", List.of()).forGetter(m -> List.copyOf(m.chambers.values()))
    ).apply(i, BotMemory::new));

    // Plans
    public String focus = "NONE";
    public long focusUntil;
    public long nextRollAt;
    public @Nullable GlobalPos deathSpot;
    public long deathTime;
    public int deathsInARow;
    public @Nullable HousePlan housePlan;
    /** The personal project under way ("farm", "wolf", ...), or empty. */
    public String project = "";
    /** What kind of home it lives in (HousePlans type name), or empty. */
    public String homeType = "";
    // Places and diary
    private final Map<String, List<Place>> places = new HashMap<>();
    private final List<Event> events = new ArrayList<>();
    /** Chests, barrels, minecart chests and vaults we've already emptied (oldest dropped past the cap). */
    private final List<GlobalPos> lootedContainers = new ArrayList<>();
    public static final int MAX_LOOTED = 1024;
    /** What's in the chests at home (item id to count), as of the last look. See {@link Storage}. */
    public final Map<String, Integer> stored = new HashMap<>();
    /** Trial Chambers we've been to (keyed by the chamber's centre). */
    private final Map<Long, Chamber> chambers = new HashMap<>();

    /** Our notes on the chamber with this centre (made if new). */
    public Chamber chamber(GlobalPos center) {
        return this.chambers.computeIfAbsent(center.pos().asLong(), k -> new Chamber(center));
    }

    public java.util.Collection<Chamber> chambers() {
        return this.chambers.values();
    }

    public BotMemory() {}

    private BotMemory(String focus, long focusUntil, long nextRollAt, Optional<GlobalPos> deathSpot, long deathTime,
                      int deathsInARow, Optional<HousePlan> housePlan, Map<String, List<Place>> places, List<Event> events,
                      String project, String homeType, List<GlobalPos> lootedContainers, Map<String, Integer> stored, List<Chamber> chambers) {
        this.stored.putAll(stored);
        for (Chamber c : chambers) this.chambers.put(c.center.pos().asLong(), c);
        this.lootedContainers.addAll(lootedContainers);
        this.project = project;
        this.homeType = homeType;
        this.focus = focus;
        this.focusUntil = focusUntil;
        this.nextRollAt = nextRollAt;
        this.deathSpot = deathSpot.orElse(null);
        this.deathTime = deathTime;
        this.deathsInARow = deathsInARow;
        this.housePlan = housePlan.orElse(null);
        places.forEach((k, v) -> this.places.put(k, new ArrayList<>(v)));
        this.events.addAll(events);
    }

    // --- places ---------------------------------------------------------------------

    /**
     * Remember (or refresh) a place of some kind ("crafting_table", "furnace", "village", ...).
     * Using a place again keeps it fresh.
     */
    public void remember(String kind, GlobalPos pos, long now) {
        List<Place> list = this.places.computeIfAbsent(kind, k -> new ArrayList<>());
        list.removeIf(p -> p.pos().equals(pos));
        list.addFirst(new Place(pos, now));
        while (list.size() > MAX_PLACES_PER_KIND) list.removeLast();
    }

    public void forget(String kind, GlobalPos pos) {
        List<Place> list = this.places.get(kind);
        if (list != null) list.removeIf(p -> p.pos().equals(pos));
    }

    /** Forget every place of this kind within {@code radius} of {@code pos} (a mined-out vein). */
    public void forgetAround(String kind, GlobalPos pos, int radius) {
        List<Place> list = this.places.get(kind);
        if (list == null) return;
        list.removeIf(p -> p.pos().dimension() == pos.dimension() && p.pos().pos().distSqr(pos.pos()) <= radius * radius);
    }

    /** The closest remembered place of this kind in the same dimension, within range. */
    public @Nullable GlobalPos nearest(String kind, GlobalPos from, double range) {
        List<Place> list = this.places.get(kind);
        if (list == null) return null;
        GlobalPos best = null;
        double bestDist = range * range;
        for (Place p : list) {
            if (p.pos().dimension() != from.dimension()) continue;
            double d = p.pos().pos().distSqr(from.pos());
            if (d < bestDist) {
                bestDist = d;
                best = p.pos();
            }
        }
        return best;
    }

    public Map<String, List<Place>> places() {
        return this.places;
    }

    /**
     * Let unimportant things fade: everyday places nobody's used in days, and old one-off
     * diary entries that never mattered much. Sticky places and big memories stay.
     */
    public void tidy(long now) {
        for (var entry : this.places.entrySet()) {
            if (isSticky(entry.getKey())) continue;
            entry.getValue().removeIf(p -> now - p.seen() > PLACE_FADE);
        }
        this.places.values().removeIf(List::isEmpty);
        this.events.removeIf(e -> !isLasting(e) && e.count() == 1 && e.value() < 0.5f && now - e.last() > EVENT_FADE);
    }

    // --- looted containers ---------------------------------------------------------------

    public void markLooted(GlobalPos pos) {
        if (this.lootedContainers.contains(pos)) return;
        this.lootedContainers.add(pos);
        while (this.lootedContainers.size() > MAX_LOOTED) this.lootedContainers.removeFirst();
    }

    public boolean isLooted(GlobalPos pos) {
        return this.lootedContainers.contains(pos);
    }

    // --- events ------------------------------------------------------------------------

    /** Same thing, same subject, near the same place, within this long: merged into one. */
    private static final long MERGE_WINDOW = 24000L; // one in-game day
    public static final int MAX_EVENTS = 200;

    /**
     * Remember something. Repeats (same type and subject, same area, same day) are merged
     * into one event with a count, so a long fight is one memory, not forty.
     */
    public void record(EventType type, String subject, @Nullable GlobalPos where, float value, long now) {
        for (int i = this.events.size() - 1; i >= 0; i--) {
            Event e = this.events.get(i);
            if (e.type() != type || !e.subject().equals(subject)) continue;
            if (now - e.last() > MERGE_WINDOW) break;
            boolean samePlace = where == null || e.where().isEmpty()
                    || e.where().get().dimension() == where.dimension() && e.where().get().pos().distSqr(where.pos()) < 32 * 32;
            if (!samePlace) continue;
            this.events.set(i, new Event(type, subject, e.where(), Math.max(e.value(), value), e.time(), now, e.count() + 1));
            return;
        }
        this.events.add(new Event(type, subject, Optional.ofNullable(where), value, now, now, 1));
        trim();
    }

    /**
     * Memories that never fade or get trimmed: people met, homes made, first-time
     * milestones, and anywhere it died more than once.
     */
    private static boolean isLasting(Event e) {
        return switch (e.type()) {
            case MET, SETTLED, MADE, TAMED, BUILT, PLANTED, PET_DIED, VISITED -> true;
            case DIED -> e.count() >= 2;
            default -> false;
        };
    }

    /** Deaths remembered within {@code radius} of a spot (any cause). */
    public int deathsNear(GlobalPos pos, int radius) {
        int n = 0;
        for (Event e : this.events) {
            if (e.type() != EventType.DIED || e.where().isEmpty()) continue;
            GlobalPos w = e.where().get();
            if (w.dimension() == pos.dimension() && w.pos().distSqr(pos.pos()) <= radius * radius) n += e.count();
        }
        return n;
    }

    /** Over the cap: forget the least important old stuff first (never the lasting ones). */
    private void trim() {
        while (this.events.size() > MAX_EVENTS) {
            int drop = -1;
            float worst = Float.MAX_VALUE;
            int limit = this.events.size() / 2; // only consider the older half
            for (int i = 0; i < limit; i++) {
                Event e = this.events.get(i);
                if (isLasting(e)) continue;
                float score = e.value() + e.count() * 0.05f;
                if (score < worst) {
                    worst = score;
                    drop = i;
                }
            }
            if (drop < 0) drop = 0; // everything old is precious; the oldest goes anyway
            this.events.remove(drop);
        }
    }

    public List<Event> events() {
        return this.events;
    }

    /** Has this ever happened (any time)? */
    public boolean ever(EventType type, String subject) {
        for (Event e : this.events) if (e.type() == type && e.subject().equals(subject)) return true;
        return false;
    }

    /** How many times this has happened, across all remembered events. */
    public int times(EventType type, String subject) {
        int n = 0;
        for (Event e : this.events) if (e.type() == type && e.subject().equals(subject)) n += e.count();
        return n;
    }
}
