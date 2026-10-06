package net.joinedthegame.ai.nav;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * One step of a path: where the bot's feet end up, how it gets there, and any blocks it
 * has to dig out or place first.
 */
public record PathNode(BlockPos pos, Move move, List<BlockPos> breaks, @Nullable BlockPos place) {
    public enum Move {
        /** Walk onto a neighbouring block at the same height. */
        WALK,
        /** Jump up one block. */
        JUMP_UP,
        /** Walk off an edge and fall. */
        DROP,
        /** Swim or climb straight up. */
        UP,
        /** Place a block under the next cell, then walk onto it. */
        BRIDGE,
        /** Jump and place a block underneath. */
        PILLAR,
        /** Dig the block underfoot and drop into the hole. */
        DIG_DOWN,
        /** The starting position. */
        START
    }

    public static PathNode start(BlockPos pos) {
        return new PathNode(pos, Move.START, List.of(), null);
    }
}
