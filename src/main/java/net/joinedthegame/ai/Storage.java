package net.joinedthegame.ai;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.Homes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * What a bot has put away in the chests at home (not the secret stash), remembered so it can
 * be used from anywhere: "I've got 20 iron at home" beats going mining for more. The list is
 * refreshed whenever the bot is home and whenever it puts things in or takes them out.
 */
public final class Storage {
    private Storage() {}

    /** The home chests, if home is loaded and in this dimension (else empty). */
    public static List<BlockPos> chests(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension() || !bot.level().isLoaded(home.pos())) return List.of();
        List<BlockPos> out = new ArrayList<>(Homes.storageNear(bot.level(), home.pos()));
        BotManager manager = BotManager.get(bot.level().getServer());
        out.removeIf(p -> manager.isStash(bot.level(), p) || brain.isBlacklisted(p));
        return out;
    }

    /** Count up what's in the home chests now (only when home is loaded). */
    public static void refresh(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension() || !bot.level().isLoaded(home.pos())) return;
        var stored = brain.memory().stored;
        int before = stored.values().stream().mapToInt(Integer::intValue).sum();
        stored.clear();
        List<BlockPos> chests = chests(bot, brain);
        for (BlockPos pos : chests) {
            if (!(bot.level().getBlockEntity(pos) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                stored.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
            }
        }
        int after = stored.values().stream().mapToInt(Integer::intValue).sum();
        if (after != before) brain.log("home storage: " + chests.size() + " chests, " + after + " items");
    }

    private static final java.util.Map<java.util.UUID, Long> LAST_LOOK = new java.util.HashMap<>();

    /** Near home: take a fresh look (at most every few seconds), so plans use what's really there. */
    public static void refreshIfHome(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension() || home.pos().distSqr(bot.blockPosition()) > 64 * 64) return;
        long now = bot.level().getGameTime();
        Long last = LAST_LOOK.get(bot.getUUID());
        if (last != null && now - last < 100) return;
        LAST_LOOK.put(bot.getUUID(), now);
        refresh(bot, brain);
    }

    /** How many of this we remember having at home. */
    public static int stored(BotBrain brain, Item item) {
        return brain.memory().stored.getOrDefault(BuiltInRegistries.ITEM.getKey(item).toString(), 0);
    }

    /**
     * Worth going home for this rather than getting more? Anything when home's close by; from
     * further off, only things that take real work to get (ore, ingots, diamonds, obsidian...).
     */
    public static boolean worthFetching(BotPlayer bot, BotBrain brain, Item item) {
        GlobalPos home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension()) return false;
        double dist = Math.sqrt(home.pos().distSqr(bot.blockPosition()));
        if (dist <= 64) return true;
        if (dist > 400) return false;
        String path = BuiltInRegistries.ITEM.getKey(item).getPath();
        Sources source = Sources.forItem(item);
        return path.endsWith("_ingot") || path.startsWith("raw_") || path.equals("diamond") || path.equals("emerald")
                || path.equals("obsidian") || path.equals("lapis_lazuli") || path.equals("leather") || path.equals("book")
                || path.equals("string") || path.equals("blaze_rod")
                || source != null && source.isUnderground() && source != Sources.STONE && source != Sources.FLINT;
    }

    /** Where home is, if it's in this dimension. */
    public static @Nullable BlockPos home(BotPlayer bot, BotBrain brain) {
        GlobalPos home = brain.record().home();
        return home == null || home.dimension() != bot.level().dimension() ? null : home.pos();
    }
}
