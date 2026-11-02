package net.joinedthegame.ai;

import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Blocks a bot put down just to get somewhere or out of trouble: pillars up to a tree or a ledge,
 * bridges over a gap, the walls it boxed itself in with, the block thrown down at a creeper. Not
 * houses, farms or torches. Remembered here so the bot can mine them back once it's done with them
 * ({@link net.joinedthegame.ai.goal.CleanupGoal}), instead of leaving dirt pillars all over the map.
 */
public final class Scaffold {
    private Scaffold() {}

    /** One of them: where, what it was (if it's changed since, it's not ours to take), and when. */
    public record Placed(GlobalPos at, Block block, long when) {}

    /** A temporary block just went down at {@code pos}: remember it. */
    public static void placed(BotPlayer bot, BlockPos pos) {
        BotBrain brain = bot.brain();
        if (brain == null) return;
        BlockState s = bot.level().getBlockState(pos);
        if (s.isAir() || s.hasBlockEntity()) return;
        BlockPos at = pos.immutable();
        brain.scaffold.removeIf(p -> p.at().pos().equals(at));
        brain.scaffold.add(new Placed(GlobalPos.of(bot.level().dimension(), at), s.getBlock(), brain.now()));
        if (brain.scaffold.size() > 200) brain.scaffold.removeFirst();
    }
}
