package net.joinedthegame.ai.goal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Junk;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Grab useful stuff lying around: drops from a tree it just chopped, ore that fell down a
 * hole, a stack someone left on the floor. Skips junk it threw away on purpose.
 * <p>
 * Like a player, it won't build or dig its way to a bit of cobblestone or dirt on a ledge. It
 * used to: mining dropped a block up on a ledge, this pillared up to it, the mining dug back
 * down through the pillar (dropping more), round and round every couple of seconds.
 */
public final class PickupGoal extends Goal {
    private @Nullable ItemEntity target;
    private int ticks;
    /** Items we couldn't get to, so we don't keep trying. */
    private final Map<UUID, Long> ignored = new HashMap<>();

    public PickupGoal(BotBrain brain) {
        super(brain);
    }

    private boolean wanted(BotPlayer bot, ItemEntity item) {
        if (!item.isAlive() || item.getItem().isEmpty()) return false;
        if (item.getOwner() == bot) return false; // tossed it ourselves
        if (!Junk.isKeeper(item.getItem()) && !Junk.isValuable(item.getItem())) return false;
        Long until = this.ignored.get(item.getUUID());
        if (until != null && until > this.brain.now()) return false;
        // What another bot just chopped or dug is theirs: most leave it; some help themselves.
        var theirs = net.joinedthegame.ai.Claims.whoseDrop(bot, item);
        if (theirs != null && !takeTheirs(bot, theirs)) return false;
        // Cobble up on a ledge over our head: not even worth working out a route to.
        if (item.getY() - bot.getY() > 1.3 && !worthTheWork(item.getItem())) return false;
        return Math.abs(item.getY() - bot.getY()) <= 6;
    }

    /** What we decided about each bot's drops (for a few minutes). */
    private final Map<UUID, Boolean> takeFrom = new HashMap<>();
    private final Map<UUID, Long> takeFromAt = new HashMap<>();
    /** The drop we're going for is someone else's: whose. */
    private net.joinedthegame.ai.Claims.@Nullable Break stealing;

    /**
     * Someone else's drops lying there. Taking them is plainly taking what's theirs (unlike going
     * for the same tree), so it's much rarer: a selfish bot might, more so with bad blood between
     * them. Decided once per bot every few minutes.
     */
    private boolean takeTheirs(BotPlayer bot, net.joinedthegame.ai.Claims.Break b) {
        // Chose to wait our turn for their tree, or leave it to them: their logs are theirs too.
        if (this.brain.friendly(b.owner())) return true; // friends share
        String tree = this.brain.recentDecision("tree:" + b.owner());
        if ("WAIT".equals(tree) || "LEAVE".equals(tree)) return false;
        Long at = this.takeFromAt.get(b.owner());
        if (at != null && this.brain.now() - at < 20 * 60 * 3) return this.takeFrom.get(b.owner());
        double chance = Math.clamp(0.01 + this.brain.selfish() * 0.35 + this.brain.grudgeAgainst(b.owner()) * 1.2, 0.01, 0.6);
        boolean take = bot.getRandom().nextDouble() < chance;
        this.takeFrom.put(b.owner(), take);
        this.takeFromAt.put(b.owner(), this.brain.now());
        this.brain.log(take ? "helping myself to what " + b.name() + " chopped" : "leaving " + b.name() + "'s drops alone");
        return take;
    }

    /**
     * Worth putting blocks down or digging to get at: anything but a plain building block
     * (cobblestone, dirt, gravel...). Logs count: a chopped tree's drops are the point of it.
     */
    private static boolean worthTheWork(ItemStack stack) {
        if (Junk.isValuable(stack) || stack.is(ItemTags.LOGS)) return true;
        return !(stack.getItem() instanceof BlockItem);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (Inv.usedSlots(bot) >= Inv.MAIN_SLOTS - 1) return 0;
        // Stick with the item we're already going for (switching mid-way just wastes the trip).
        ItemEntity current = this.target;
        if (current != null && wanted(bot, current) && current.distanceToSqr(bot) <= 10 * 10) return 48;
        List<ItemEntity> items = Senses.entitiesNear(bot, ItemEntity.class, 8, e -> wanted(bot, e));
        if (items.isEmpty()) {
            this.target = null;
            return 0;
        }
        this.target = items.getFirst();
        this.stealing = net.joinedthegame.ai.Claims.whoseDrop(bot, this.target);
        return 48;
    }

    /** Going for something valuable (worth stopping a mining job for). */
    public boolean forSomethingValuable() {
        ItemEntity item = this.target;
        return item != null && item.isAlive() && Junk.isValuable(item.getItem());
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        ItemEntity item = this.target;
        if (item != null && !item.isAlive() && this.stealing != null && item.distanceToSqr(bot) < 3 * 3) stole(bot, this.stealing);
        if (item == null || !item.isAlive()) return false;
        if (++this.ticks > 100) return giveUp(item);
        Navigator nav = this.brain.navigator();
        if (!nav.isMoving() || this.ticks % 20 == 0) {
            nav.moveNear(bot, item.position(), 0.6);
            if (nav.status() == Navigator.Status.FAILED) return giveUp(item);
            // A route that has to build or dig: only for something worth it.
            if (nav.isMoving() && nav.plannedWork() > 0 && !worthTheWork(item.getItem())) {
                nav.stop(bot);
                return giveUp(item);
            }
        }
        return true;
    }

    /** Picked up someone else's drop on purpose. They'll know if they saw. */
    private void stole(BotPlayer bot, net.joinedthegame.ai.Claims.Break b) {
        this.stealing = null;
        var srv = bot.level().getServer();
        var owners = srv == null ? null : net.joinedthegame.bot.BotManager.get(srv).brain(b.owner());
        if (this.brain.friendly(b.owner()) && owners != null && owners.friendly(bot.getUUID())) {
            this.brain.log("picked up what " + b.name() + " chopped (we're friends: we share)");
            return;
        }
        this.brain.log("took what " + b.name() + " chopped");
        this.brain.remember(net.joinedthegame.ai.BotMemory.EventType.STOLE_FROM, b.name());
        var server = bot.level().getServer();
        if (server == null) return;
        var them = server.getPlayerList().getPlayer(b.owner());
        var theirs = net.joinedthegame.bot.BotManager.get(server).brain(b.owner());
        if (them != null && theirs != null && BotBrain.sees(them, bot) && theirs.friendly(bot.getUUID())) {
            theirs.log("saw " + bot.getScoreboardName() + " take what I chopped, but they're a friend: letting it go");
        } else if (them != null && theirs != null && BotBrain.sees(them, bot)) {
            theirs.log("saw " + bot.getScoreboardName() + " take what I chopped");
            theirs.remember(net.joinedthegame.ai.BotMemory.EventType.STOLEN_FROM, bot.getScoreboardName());
            theirs.feel(bot, net.joinedthegame.ai.social.Feelings.Act.STOLE_FROM_ME, 1);
        }
    }

    private boolean giveUp(ItemEntity item) {
        this.ignored.put(item.getUUID(), this.brain.now() + 20 * 60);
        // The rest of the same stuff lying with it (a pile of cobble down a pit) is no easier to
        // get: leave it too, rather than starting and giving up on each one in turn.
        for (ItemEntity other : item.level().getEntitiesOfClass(ItemEntity.class, item.getBoundingBox().inflate(4),
                e -> e.getItem().is(item.getItem().getItem()))) {
            this.ignored.put(other.getUUID(), this.brain.now() + 20 * 60);
        }
        this.target = null;
        return false;
    }

    @Override
    public String activity() {
        return "Picking up items";
    }
}
