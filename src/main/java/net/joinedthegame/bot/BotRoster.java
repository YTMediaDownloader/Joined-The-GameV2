package net.joinedthegame.bot;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.joinedthegame.JoinedTheGame;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/** The list of AI players for this world. Saved with the world. */
public final class BotRoster extends SavedData {
    public static final Codec<BotRoster> CODEC = BotRecord.CODEC.listOf().xmap(BotRoster::new, r -> new ArrayList<>(r.records.values()));
    public static final SavedDataType<BotRoster> TYPE = new SavedDataType<>(JoinedTheGame.id("roster"), BotRoster::new, CODEC);

    private final Map<String, BotRecord> records = new LinkedHashMap<>();

    /** A fresh world starts with the default roster. */
    public BotRoster() {
        for (String name : BotNames.DEFAULT) add(BotRecord.create(name));
    }

    private BotRoster(List<BotRecord> list) {
        for (BotRecord record : list) add(record);
        // Names added in later versions (the real players): on the list, ready to invite.
        for (String name : BotNames.DEFAULT) {
            if (BotNames.REAL_SKINS.contains(name.toLowerCase(Locale.ROOT)) && get(name) == null) add(BotRecord.create(name));
        }
    }

    public static BotRoster get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    private void add(BotRecord record) {
        this.records.put(record.name().toLowerCase(Locale.ROOT), record);
    }

    public @Nullable BotRecord get(String name) {
        return this.records.get(name.toLowerCase(Locale.ROOT));
    }

    /** Find a bot by name, adding a new one to the roster if the name is new and valid. */
    public @Nullable BotRecord getOrCreate(String name) {
        BotRecord existing = get(name);
        if (existing != null) return existing;
        if (!BotNames.isValid(name)) return null;
        BotRecord record = BotRecord.create(name);
        add(record);
        setDirty();
        return record;
    }

    public Collection<BotRecord> all() {
        return this.records.values();
    }

    public void markDirty() {
        setDirty();
    }
}
