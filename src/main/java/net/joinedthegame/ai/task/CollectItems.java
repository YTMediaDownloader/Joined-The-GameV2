package net.joinedthegame.ai.task;

import java.util.List;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;

/** Walk over the drops near a spot so they get picked up. */
public final class CollectItems implements Task {
    private final BlockPos center;
    private final double radius;
    private int ticks;
    private final int maxTicks;

    public CollectItems(BlockPos center, double radius, int maxTicks) {
        this.center = center;
        this.radius = radius;
        this.maxTicks = maxTicks;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (++this.ticks > this.maxTicks) return Status.DONE;
        // Items need ~10 ticks before they can be picked up; give them a moment to land.
        List<ItemEntity> items = Senses.itemsNear(bot, this.center, this.radius);
        // Drops can fall a fair way (logs off a tree chopped from a pillar).
        items.removeIf(e -> e.getY() < bot.getY() - 8 || e.getY() > bot.getY() + 3);
        if (items.isEmpty()) return this.ticks > 15 ? Status.DONE : Status.RUNNING;
        ItemEntity nearest = items.getFirst();
        if (nearest.distanceToSqr(bot) < 0.8) return Status.RUNNING;
        Navigator nav = brain.navigator();
        nav.moveNear(bot, nearest.position(), 0.7);
        if (nav.status() == Navigator.Status.FAILED) {
            nav.stop(bot);
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    @Override
    public String activity() {
        return "Picking up items";
    }
}
