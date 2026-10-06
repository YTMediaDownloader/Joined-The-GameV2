package net.joinedthegame.ai.goal;

import net.joinedthegame.activity.PlayerActivity;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.task.CollectItems;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Monkey see, monkey do: when a player nearby is mining, the bot comes over and starts
 * mining around them too, ores first.
 */
public final class CopyMiningGoal extends Goal {
    private @Nullable BlockPos spot;
    private @Nullable BlockPos block;
    private @Nullable CollectItems collect;
    private int ticks;

    public CopyMiningGoal(BotBrain brain) {
        super(brain);
    }

    private static boolean mineable(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.ORES) || state.is(Blocks.COBBLESTONE);
    }

    @Override
    public int priority(BotPlayer bot) {
        if (Inv.pickaxeTier(bot) == 0) return 0;
        PlayerActivity.Mining mining = PlayerActivity.recentMiningNear(bot, 32, 20 * 15);
        if (mining == null) return 0;
        this.spot = mining.pos();
        return (int) (30 + this.brain.personality().social() * 25 + this.brain.personality().miner() * 10);
    }

    @Override
    public void start(BotPlayer bot) {
        this.block = null;
        this.collect = null;
        this.ticks = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (++this.ticks > 20 * 60 || this.spot == null) return false;
        if (this.collect != null) {
            if (this.collect.tick(bot, this.brain) == CollectItems.Status.RUNNING) return true;
            this.collect = null;
        }
        if (this.block == null) {
            BlockPos center = this.spot;
            BlockPos ore = Senses.findBlock(bot, s -> s.is(BlockTags.ORES), 8, 4, true,
                    p -> p.distSqr(center) > 100 || this.brain.isBlacklisted(p));
            this.block = ore != null ? ore : Senses.findBlock(bot, CopyMiningGoal::mineable, 8, 3, true,
                    p -> p.distSqr(center) > 64 || this.brain.isBlacklisted(p));
            if (this.block == null) {
                this.brain.navigator().moveNear(bot, net.minecraft.world.phys.Vec3.atBottomCenterOf(center), 4);
                return this.brain.navigator().status() != Navigator.Status.FAILED;
            }
        }
        BlockPos pos = this.block;
        if (!mineable(bot.level().getBlockState(pos))) {
            this.brain.breaker().cancel(bot);
            this.collect = new CollectItems(pos, 4, 40);
            this.block = null;
            return true;
        }
        if (BlockBreaker.inReach(bot, pos)) {
            this.brain.navigator().stop(bot);
            this.brain.breaker().start(pos);
            if (this.brain.breaker().tick(bot, this.brain.controls()) == BlockBreaker.Result.FAILED) {
                this.brain.blacklist(pos);
                this.block = null;
            }
        } else {
            Navigator nav = this.brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToReach(bot, pos);
                if (nav.status() == Navigator.Status.FAILED) {
                    this.brain.blacklist(pos);
                    this.block = null;
                    nav.stop(bot);
                }
            }
        }
        return true;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        this.brain.breaker().cancel(bot);
    }

    @Override
    public String activity() {
        return "Mining with you";
    }
}
