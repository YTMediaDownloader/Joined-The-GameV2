package net.joinedthegame.ai.nav;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Read-only view of the world for pathfinding. Never loads or generates chunks: anything
 * in an unloaded chunk is treated as a solid, unbreakable wall.
 */
public final class WorldView {
    private static final BlockState UNLOADED = Blocks.BEDROCK.defaultBlockState();

    private final ServerLevel level;
    private final Long2ObjectOpenHashMap<LevelChunk> chunks = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    public WorldView(ServerLevel level) {
        this.level = level;
    }

    public ServerLevel level() {
        return this.level;
    }

    /**
     * Remember each block looked at, for the life of this view. Only for a view that lives a
     * moment, like one path search (the world doesn't change under it): a search asks about the
     * same blocks over and over (passable? breakable? floor?), and each ask unpacks the chunk's
     * palette. Six bots planning at sunrise spent their tick doing exactly that.
     */
    public WorldView cached() {
        this.cache = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(); // grows as the search does
        return this;
    }

    private it.unimi.dsi.fastutil.longs.@org.jspecify.annotations.Nullable Long2ObjectOpenHashMap<BlockState> cache;

    public BlockState state(BlockPos pos) {
        if (this.cache != null) {
            long k = pos.asLong();
            BlockState s = this.cache.get(k);
            if (s == null) {
                s = lookup(pos);
                this.cache.put(k, s);
            }
            return s;
        }
        return lookup(pos);
    }

    private BlockState lookup(BlockPos pos) {
        if (pos.getY() < this.level.getMinY() || pos.getY() > this.level.getMaxY()) {
            return pos.getY() < this.level.getMinY() ? UNLOADED : Blocks.AIR.defaultBlockState();
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long key = ChunkPos.pack(cx, cz);
        LevelChunk chunk = this.chunks.get(key);
        if (chunk == null) {
            chunk = this.level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) return UNLOADED;
            this.chunks.put(key, chunk);
        }
        return chunk.getBlockState(pos);
    }

    public boolean isLoaded(BlockPos pos) {
        return this.level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    public FluidState fluid(BlockPos pos) {
        return state(pos).getFluidState();
    }

    /**
     * Any water here at all: open water, or water inside a waterlogged block. Right for "will I
     * be swimming / is the floor wet"; for "is this a block or just water" use
     * {@link #isPureWater} / {@link #isWaterloggedSolid}.
     */
    public boolean isWater(BlockPos pos) {
        return fluid(pos).is(FluidTags.WATER);
    }

    /** Just water (swim through it, can't dig it): no block shape in it. */
    public boolean isPureWater(BlockPos pos) {
        BlockState state = state(pos);
        return state != UNLOADED && state.getFluidState().is(FluidTags.WATER) && collision(state, pos).isEmpty();
    }

    /**
     * A real block with water in it: a waterlogged stair, slab, fence, chest (shipwrecks are full
     * of them). Solid, breakable like any block; but breaking it leaves a water source behind.
     */
    public boolean isWaterloggedSolid(BlockPos pos) {
        BlockState state = state(pos);
        return state != UNLOADED && state.getFluidState().is(FluidTags.WATER) && !collision(state, pos).isEmpty();
    }

    /** Falling or flowing water: the current shoves you around, and you can't climb it. */
    public boolean strongCurrent(BlockPos pos) {
        FluidState fluid = fluid(pos);
        if (!fluid.is(FluidTags.WATER) || fluid.isSource()) return false;
        return fluid.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING) || fluid.getAmount() < 7;
    }

    public boolean isLava(BlockPos pos) {
        return fluid(pos).is(FluidTags.LAVA);
    }

    /** Any lava within {@code r} blocks of anywhere in the box from {@code a} to {@code b}? */
    public boolean lavaNear(BlockPos a, BlockPos b, int r) {
        int x0 = Math.min(a.getX(), b.getX()) - r, x1 = Math.max(a.getX(), b.getX()) + r;
        int y0 = Math.min(a.getY(), b.getY()) - r, y1 = Math.max(a.getY(), b.getY()) + r;
        int z0 = Math.min(a.getZ(), b.getZ()) - r, z1 = Math.max(a.getZ(), b.getZ()) + r;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (isLava(p.set(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    /** Things a sensible player won't walk into or stand on. */
    /**
     * Set just while planning a path that's meant to end in a nether portal (we're going
     * through on purpose). Any other time, portals are somewhere not to wander into.
     */
    public static boolean portalsOk;

    public static boolean isDangerous(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.WITHER_ROSE) || state.is(BlockTags.CAMPFIRES) || state.is(Blocks.POINTED_DRIPSTONE)
                || state.is(Blocks.NETHER_PORTAL) && !portalsOk || state.is(Blocks.END_PORTAL) || state.is(Blocks.COBWEB)
                || state.getFluidState().is(FluidTags.LAVA)
                // Traps and alarms: the desert temple's TNT plate, jungle temple tripwires, and
                // sculk that calls the warden.
                || state.is(BlockTags.PRESSURE_PLATES) || state.is(Blocks.TRIPWIRE)
                || state.is(Blocks.SCULK_SENSOR) || state.is(Blocks.CALIBRATED_SCULK_SENSOR) || state.is(Blocks.SCULK_SHRIEKER);
    }

    private VoxelShape collision(BlockState state, BlockPos pos) {
        return state.getCollisionShape(this.level, pos);
    }

    /** Can a player's body occupy this cell? */
    public boolean passable(BlockPos pos) {
        BlockState state = state(pos);
        if (state == UNLOADED) return false;
        if (isDangerous(state)) return false;
        // Wooden doors and gates open with a click, so they're as good as a gap (the navigator
        // opens them, and shuts them again behind it).
        if (isOpenable(state)) return true;
        VoxelShape shape = collision(state, pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.15;
    }

    /**
     * Real cover to hide behind for the night: a full solid block. Not water or lava (a drowned
     * swims straight in, and "not passable" isn't the same as a wall), not leaves (something over
     * your head that a spider walks across and a skeleton sees through), not a door.
     */
    public boolean cover(BlockPos pos) {
        BlockState state = state(pos);
        if (state == UNLOADED || state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (state.is(net.minecraft.tags.BlockTags.LEAVES) || isOpenable(state) || isDangerous(state)) return false;
        return net.minecraft.world.level.block.Block.isShapeFullBlock(collision(state, pos));
    }

    /** A door or gate you can open by hand (iron doors need redstone). */
    public static boolean isOpenable(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && state.is(BlockTags.WOODEN_DOORS)
                || state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock;
    }

    /** A shut door or gate that needs opening before we can walk through. */
    public boolean isClosedDoor(BlockPos pos) {
        BlockState state = state(pos);
        return isOpenable(state) && !state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
    }

    /** An open door or gate (to shut behind us). */
    public boolean isOpenDoor(BlockPos pos) {
        BlockState state = state(pos);
        return isOpenable(state) && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
    }

    /** Is the block at {@code pos} something you can stand on top of? */
    /**
     * Wearing Frost Walker boots (and up on our feet, not swimming): still water freezes under us
     * as we go, so the top of a pond or the sea is ground to walk on, not water to swim through.
     * Only for planning a route (set by the Pathfinder).
     */
    public boolean frostWalk;

    /** Still water with open air over it: what Frost Walker turns to ice underfoot. */
    private boolean freezable(BlockPos pos, BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.WATER) && state.getFluidState().isSource()
                && state(pos.above()).isAir();
    }

    public boolean solidFloor(BlockPos pos) {
        BlockState state = state(pos);
        if (state == UNLOADED) return false;
        if (this.frostWalk && freezable(pos, state)) return true;
        if (isDangerous(state)) return false;
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)) return false;
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) return false;
        VoxelShape shape = collision(state, pos);
        if (shape.isEmpty()) return false;
        double top = shape.max(Direction.Axis.Y);
        return top >= 0.5 && top <= 1.0;
    }

    /**
     * The actual height a player standing in this cell has their feet at. Usually just the
     * cell's Y, but on a bed, slab or chest you stand lower than the block above it, which
     * matters for what you can jump onto (a full block next to a bed is a 1.5-block jump).
     */
    public double floorTop(BlockPos feet) {
        BlockState here = state(feet);
        VoxelShape inCell = collision(here, feet);
        if (!inCell.isEmpty()) return feet.getY() + inCell.max(Direction.Axis.Y);
        BlockPos below = feet.below();
        VoxelShape shape = collision(state(below), below);
        if (shape.isEmpty()) return feet.getY();
        return below.getY() + shape.max(Direction.Axis.Y);
    }

    public boolean climbable(BlockPos pos) {
        BlockState state = state(pos);
        return state.is(BlockTags.CLIMBABLE) && !state.is(Blocks.SCAFFOLDING);
    }

    public boolean hasFloor(BlockPos feet) {
        return solidFloor(feet.below());
    }

    /** A cell a player can stand in: body fits, and there's ground, water or a ladder. */
    public boolean standable(BlockPos feet) {
        return passable(feet) && passable(feet.above())
                && (hasFloor(feet) || isWater(feet) || climbable(feet));
    }

    /**
     * Can a bot reasonably dig this block out? Refuses unbreakable and very hard blocks,
     * containers (someone's chest), and anything next to lava or flowing liquid that would
     * pour in.
     */
    public boolean breakable(BlockPos pos) {
        BlockState state = state(pos);
        if (state == UNLOADED || state.isAir()) return false;
        // Pure water can't be dug, and a waterlogged solid (isWaterloggedSolid) is left alone
        // while tunnelling on purpose: breaking it leaves a water source in the tunnel.
        if (!state.getFluidState().isEmpty()) return false;
        float hardness = state.getDestroySpeed(this.level, pos);
        if (hardness < 0 || hardness > 20) return false;
        if (state.hasBlockEntity()) return false;
        if (state.is(Blocks.CRAFTING_TABLE) || state.is(BlockTags.BEDS) || state.is(BlockTags.DOORS)) return false;
        // Digging out from under sand or gravel drops it on your head (suffocation).
        if (state(pos.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock
                && !(state.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) return false;
        for (Direction dir : Direction.values()) {
            this.scratch.setWithOffset(pos, dir);
            FluidState fluid = state(this.scratch).getFluidState();
            if (fluid.is(FluidTags.LAVA)) return false;
            if (dir != Direction.DOWN && !fluid.isEmpty()) return false;
        }
        // Falling sand/gravel just gets dug again by the navigator, like a player would.
        return true;
    }

    /**
     * Fine to break to get at a chest or the like we can't otherwise see: what {@link #breakable}
     * allows, plus blocks with water beside them (a sunken shipwreck's planks: the water coming
     * in is no danger, and it's the only way in). Never next to lava, never a container.
     */
    public boolean breakableToReach(BlockPos pos) {
        if (breakable(pos)) return true;
        BlockState state = state(pos);
        if (state == UNLOADED || state.isAir() || state.hasBlockEntity()) return false;
        // Water itself can't be dug; a waterlogged stair, slab or fence (a shipwreck's hull is
        // full of them) is a solid block like any other: it breaks, the water stays.
        if (isPureWater(pos) || !state.getFluidState().isEmpty() && !isWaterloggedSolid(pos)) return false;
        float hardness = state.getDestroySpeed(this.level, pos);
        if (hardness < 0 || hardness > 20) return false;
        if (state.is(BlockTags.BEDS) || state.is(BlockTags.DOORS)) return false;
        for (Direction dir : Direction.values()) {
            if (state(pos.relative(dir)).getFluidState().is(FluidTags.LAVA)) return false;
        }
        return true;
    }

    /**
     * Can the bot break this on purpose (its own bed, a block in its house plan)? Looser than
     * {@link #breakable}, which is about digging through things while walking around.
     */
    public boolean breakableOnPurpose(BlockPos pos) {
        BlockState state = state(pos);
        if (state == UNLOADED || state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(this.level, pos).isEmpty()) return false;
        float hardness = state.getDestroySpeed(this.level, pos);
        return hardness >= 0 && hardness <= 50;
    }

    /** Clear (or clearable) for the body, used when planning digs. */
    public boolean clearOrBreakable(BlockPos pos) {
        return passable(pos) || breakable(pos);
    }

    /** Rough number of ticks to break this block with the bot's best tool. */
    public float breakTicks(BotPlayer bot, BlockPos pos) {
        BlockState state = state(pos);
        if (passable(pos)) return 0;
        // In a search (cached view), once per kind of block: it's the same tools all search long,
        // and this went through the whole bag for every block looked at.
        if (this.cache != null) {
            float known = this.breakCache.getOrDefault(state, -1f);
            if (known >= 0) return known;
            float ticks = breakTicksUncached(bot, pos, state);
            this.breakCache.put(state, ticks);
            return ticks;
        }
        return breakTicksUncached(bot, pos, state);
    }

    private final it.unimi.dsi.fastutil.objects.Reference2FloatOpenHashMap<BlockState> breakCache = new it.unimi.dsi.fastutil.objects.Reference2FloatOpenHashMap<>();

    private float breakTicksUncached(BotPlayer bot, BlockPos pos, BlockState state) {
        float best = Math.max(state.getDestroyProgress(bot, this.level, pos), 0.0001f);
        // Estimate with the best tool in the inventory, not whatever is in hand.
        float hardness = state.getDestroySpeed(this.level, pos);
        if (hardness > 0) {
            for (int i = 0; i < 36; i++) {
                var stack = bot.getInventory().getItem(i);
                if (stack.isEmpty()) continue;
                float speed = stack.getDestroySpeed(state);
                if (speed <= 1) continue;
                boolean correct = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
                float progress = speed / hardness / (correct ? 30f : 100f);
                best = Math.max(best, progress);
            }
        }
        return Math.min(1f / best, 600f);
    }
}
