package net.joinedthegame.bot;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.Nullable;

/** A bot on the roster: its name, whether it's in the world, and where it lives. */
public final class BotRecord {
    public enum State implements StringRepresentable {
        /** On the roster, not in the world. */
        AVAILABLE,
        /** Playing right now (or will rejoin when the world loads). */
        ACTIVE,
        /** Banned: can't be invited until unbanned. */
        BANNED;

        public static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final Codec<BotRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(BotRecord::name),
            UUIDUtil.CODEC.fieldOf("uuid").forGetter(BotRecord::uuid),
            State.CODEC.fieldOf("state").forGetter(BotRecord::state),
            GlobalPos.CODEC.optionalFieldOf("home").forGetter(r -> Optional.ofNullable(r.home)),
            GlobalPos.CODEC.optionalFieldOf("house").forGetter(r -> Optional.ofNullable(r.house)),
            GlobalPos.CODEC.optionalFieldOf("stash").forGetter(r -> Optional.ofNullable(r.stash))
    ).apply(i, (name, uuid, state, home, house, stash) -> {
        BotRecord record = new BotRecord(name, uuid, state, home.orElse(null));
        record.house = house.orElse(null);
        record.stash = stash.orElse(null);
        return record;
    }));

    private final String name;
    private final UUID uuid;
    private State state;
    private @Nullable GlobalPos home;
    /** A proper house (built, dug into a hill, or taken from villagers). Null until it settles down. */
    private @Nullable GlobalPos house;
    /** The secret chest buried under the floor, for valuables. */
    private @Nullable GlobalPos stash;

    public BotRecord(String name, UUID uuid, State state, @Nullable GlobalPos home) {
        this.name = name;
        this.uuid = uuid;
        this.state = state;
        this.home = home;
    }

    /** Offline-mode UUID: the same name always gets the same UUID, and so the same skin. */
    public static BotRecord create(String name) {
        return new BotRecord(name, UUIDUtil.createOfflinePlayerUUID(name), State.AVAILABLE, null);
    }

    public String name() { return this.name; }
    public UUID uuid() { return this.uuid; }
    public State state() { return this.state; }
    public @Nullable GlobalPos home() { return this.home; }
    public @Nullable GlobalPos house() { return this.house; }
    public @Nullable GlobalPos stash() { return this.stash; }

    void setState(State state) { this.state = state; }
    void setHome(@Nullable GlobalPos home) { this.home = home; }
    void setHouse(@Nullable GlobalPos house) { this.house = house; }
    void setStash(@Nullable GlobalPos stash) { this.stash = stash; }
}
