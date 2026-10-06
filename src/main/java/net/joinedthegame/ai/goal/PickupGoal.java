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
import net.minecraft.world.entity.item.ItemEntity;
import org.jspecify.annotations.Nullable;

/**
 * Grab useful stuff lying around: drops from a tree it just chopped, ore that fell down a
 * hole, a stack someone left on the floor. Skips junk it threw away on purpose.
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
        return Math.abs(item.getY() - bot.getY()) <= 6;
    }

    @Override
    public int priority(BotPlayer bot) {
        if (Inv.usedSlots(bot) >= Inv.MAIN_SLOTS - 1) return 0;
        List<ItemEntity> items = Senses.entitiesNear(bot, ItemEntity.class, 8, e -> wanted(bot, e));
        if (items.isEmpty()) return 0;
        this.target = items.getFirst();
        return 48;
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        ItemEntity item = this.target;
        if (item == null || !item.isAlive()) return false;
        if (++this.ticks > 100) {
            this.ignored.put(item.getUUID(), this.brain.now() + 20 * 60);
            return false;
        }
        Navigator nav = this.brain.navigator();
        if (!nav.isMoving() || this.ticks % 20 == 0) {
            nav.moveNear(bot, item.position(), 0.6);
            if (nav.status() == Navigator.Status.FAILED) {
                this.ignored.put(item.getUUID(), this.brain.now() + 20 * 60);
                return false;
            }
        }
        return true;
    }

    @Override
    public String activity() {
        return "Picking up items";
    }
}
