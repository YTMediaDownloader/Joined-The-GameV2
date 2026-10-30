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
        items.removeIf(e -> this.skipped.contains(e.getId()));
        if (items.isEmpty()) return this.ticks > 15 ? Status.DONE : Status.RUNNING;
        ItemEntity nearest = items.getFirst();
        Navigator nav = brain.navigator();
        // Knocking it loose: the leaves it's caught in, coming down.
        if (this.shaking != null) {
            var breaker = brain.breaker();
            if (bot.level().getBlockState(this.shaking).isAir() || breaker.tick(bot, brain.controls()) == net.joinedthegame.ai.skill.BlockBreaker.Result.FAILED) {
                this.shaking = null;
                this.onIt = 0;
            }
            return Status.RUNNING;
        }
        if (nearest.getId() != this.target) {
            this.target = nearest.getId();
            this.onIt = 0;
        }
        // A few seconds on this one and it's still lying there: out of reach (up in a tree, down a
        // crack). Caught in leaves we can reach: knock them out and it drops. Otherwise leave that
        // one and get the rest. (LeBron stood by a pile for the whole time, picking up nothing,
        // because the first thing he went for was caught up in a dark oak.)
        if (++this.onIt > 60) {
            BlockPos under = BlockPos.containing(nearest.getX(), nearest.getY() - 0.05, nearest.getZ());
            for (BlockPos holding : new BlockPos[]{BlockPos.containing(nearest.position()), under}) {
                if (bot.level().getBlockState(holding).is(net.minecraft.tags.BlockTags.LEAVES)
                        && net.joinedthegame.ai.skill.BlockBreaker.inReach(bot, holding)) {
                    nav.stop(bot);
                    this.shaking = holding.immutable();
                    brain.breaker().start(this.shaking);
                    return Status.RUNNING;
                }
            }
            this.skipped.add(nearest.getId());
            return Status.RUNNING;
        }
        if (nearest.distanceToSqr(bot) < 0.8) return Status.RUNNING;
        if (!nav.isMoving() || this.onIt % 20 == 1) nav.moveNear(bot, nearest.position(), 0.7);
        if (nav.status() == Navigator.Status.FAILED) {
            nav.stop(bot);
            this.skipped.add(nearest.getId()); // can't get to that one: the others, then
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.shaking != null) brain.breaker().cancel(bot);
        this.shaking = null;
    }

    private final java.util.Set<Integer> skipped = new java.util.HashSet<>();
    private int target = -1;
    private int onIt;
    private @org.jspecify.annotations.Nullable BlockPos shaking;

    @Override
    public String activity() {
        return "Picking up items";
    }
}
