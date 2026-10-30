package net.joinedthegame.ai.social;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.item.ItemEntity;
import org.jspecify.annotations.Nullable;

/**
 * Things a bot threw to someone on purpose (food for a hungry friend). Bots also throw things away
 * (junk), and picking that up is no gift, so a bot's gift is marked when it's thrown: who it was
 * for. Only that one picking it up counts. Server-wide, in memory only; a gift nobody picks up is
 * forgotten after a few minutes.
 */
public final class Gifts {
    private record Gift(UUID from, UUID to, long at) {}

    private static final Map<UUID, Gift> GIFTS = new ConcurrentHashMap<>();

    private Gifts() {}

    public static void given(ItemEntity item, UUID from, UUID to, long now) {
        GIFTS.values().removeIf(g -> now - g.at() > 20 * 60 * 5);
        GIFTS.put(item.getUUID(), new Gift(from, to, now));
    }

    /** Picked up by who it was meant for: who it was from. Otherwise null (and it's forgotten). */
    public static @Nullable UUID pickedUp(ItemEntity item, UUID by) {
        Gift g = GIFTS.remove(item.getUUID());
        return g != null && g.to().equals(by) ? g.from() : null;
    }
}
