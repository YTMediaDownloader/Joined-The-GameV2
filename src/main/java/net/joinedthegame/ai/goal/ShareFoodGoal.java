package net.joinedthegame.ai.goal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.social.Gifts;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A friend's hungry and we've food to spare: go over and throw them some. Friends only (it likes
 * them well: see FRIEND), bots or real players, and only food it can spare (it keeps enough for
 * itself). Once per friend every few minutes. A bot friend who catches it is grateful (a gift).
 */
public final class ShareFoodGoal extends Goal {
    /** Liking it takes to share with someone. */
    private static final double FRIEND = 30;

    private @Nullable UUID with;
    private String withName = "";
    private int ticks;
    private final Map<UUID, Long> sharedAt = new HashMap<>();

    public ShareFoodGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (bot.getFoodData().getFoodLevel() < 14 || spareFood(bot) < 0) return 0;
        ServerPlayer friend = hungryFriend(bot);
        if (friend == null) return 0;
        this.with = friend.getUUID();
        this.withName = friend.getGameProfile().name();
        // A friend going hungry beats the chores (building, mining); one who's starving cuts
        // straight through. Fights and staying alive still come first.
        return friend.getFoodData().getFoodLevel() <= 4 ? 60 : 56;
    }

    /** A friend close by, in sight, hungry, and not fed by us lately. */
    private @Nullable ServerPlayer hungryFriend(BotPlayer bot) {
        for (var p : bot.level().players()) {
            if (p == bot || !(p instanceof ServerPlayer sp) || sp.isSpectator() || !sp.isAlive() || p.distanceToSqr(bot) > 14 * 14) continue;
            var r = this.brain.knownRelation(p.getUUID());
            if (r == null || r.liking < FRIEND && r.partnerSince == 0) continue;
            int hungry = r.partnerSince > 0 ? 12 : p instanceof BotPlayer ? 8 : 12; // partners look out for each other sooner
            if (p.getFoodData().getFoodLevel() > hungry) continue;
            Long last = this.sharedAt.get(p.getUUID());
            if (last != null && this.brain.now() - last < 20 * 60 * 3) continue;
            if (!bot.hasLineOfSight(p)) continue;
            return sp;
        }
        return null;
    }

    /** The slot of the food we have most of, if we've more than we need (-1 if not). */
    private static int spareFood(BotPlayer bot) {
        int best = -1, total = 0, most = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack s = bot.getInventory().getItem(i);
            if (s.isEmpty() || !s.has(DataComponents.FOOD) || isRisky(s)) continue;
            total += s.getCount();
            if (s.getCount() > most) {
                most = s.getCount();
                best = i;
            }
        }
        return total >= 6 ? best : -1; // keeps a few for itself
    }

    /** Nobody gives a friend rotten flesh or a spider eye. */
    private static boolean isRisky(ItemStack s) {
        return s.is(net.minecraft.world.item.Items.ROTTEN_FLESH) || s.is(net.minecraft.world.item.Items.SPIDER_EYE)
                || s.is(net.minecraft.world.item.Items.POISONOUS_POTATO) || s.is(net.minecraft.world.item.Items.PUFFERFISH)
                || s.is(net.minecraft.world.item.Items.CHICKEN);
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        UUID id = this.with;
        var server = bot.level().getServer();
        ServerPlayer who = id == null || server == null ? null : server.getPlayerList().getPlayer(id);
        if (who == null || !who.isAlive() || who.level() != bot.level() || ++this.ticks > 20 * 30) return finish();
        Navigator nav = this.brain.navigator();
        if (bot.distanceTo(who) > 3.2) {
            if (!nav.isMoving() || this.ticks % 30 == 0) nav.moveNear(bot, who.position(), 2.2);
            return true;
        }
        nav.stop(bot);
        this.brain.controls().lookAt(bot, who.getEyePosition());
        if (this.ticks % 10 != 0 || !this.brain.controls().isLookingAtTarget(bot, 15f)) return true;
        int slot = spareFood(bot);
        if (slot < 0) return finish();
        ItemStack stack = bot.getInventory().getItem(slot);
        ItemStack give = stack.split(Math.min(stack.getCount(), who instanceof BotPlayer ? 3 : 4));
        var thrown = bot.drop(give, true, Prediction.SERVER_ONLY);
        if (thrown != null) Gifts.given(thrown, bot.getUUID(), who.getUUID(), this.brain.now());
        this.sharedAt.put(who.getUUID(), this.brain.now());
        this.brain.log("gave " + this.withName + " some " + give.getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + " (they were hungry)");
        return finish();
    }

    private boolean finish() {
        this.with = null;
        return false;
    }

    @Override
    public String activity() {
        return "Bringing " + this.withName + " some food";
    }
}
