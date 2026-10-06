package net.joinedthegame.ai;

import java.util.ArrayList;
import java.util.List;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.task.BuildTask.Step;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Where a bot's house goes and what goes in it. Three kinds, laziest first:
 * <ul>
 *   <li><b>Village</b>: someone else's house, with a free bed indoors.</li>
 *   <li><b>Cave</b>: a 3x3 room dug into a hillside (free roof, no building).</li>
 *   <li><b>Hut</b>: a 5x5 box of whatever blocks it's carrying, flat roof, doorway.</li>
 *   <li><b>Cabin</b>: a proper house it builds itself: log corners, plank walls and floor,
 *       glass windows, a door and an overhanging roof (see {@link #cabin}).</li>
 * </ul>
 * All of them share one layout, in "room" coordinates: x is left/right of the entrance, z is
 * how far in. The room is x -1..1, z 1..3; the doorway is at x 0, z 0.
 * <pre>
 *   z=3   B  .  C        B = bed (head)   C = chest
 *   z=2   B  .  s        s = secret chest under the floor
 *   z=1   .  .  t        t = torch on the wall
 *   z=0   #  door #      sign outside, left of the door
 * </pre>
 */
public final class HousePlans {
    private HousePlans() {}

    public enum Type { EXISTING, CAVE, HUT, CABIN, HOUSE }

    /**
     * @param room     room origin: the doorway cell, at floor level
     * @param forward  pointing into the house
     */
    public record Plan(Type type, BlockPos room, Direction forward, List<Step> build, List<Step> furnish,
                       BlockPos bedFoot, @Nullable BlockPos stash, int blocksNeeded, List<Tasks.Need> needs, String variant) {
        Plan(Type type, BlockPos room, Direction forward, List<Step> build, List<Step> furnish, BlockPos bedFoot,
             @Nullable BlockPos stash, int blocksNeeded, List<Tasks.Need> needs) {
            this(type, room, forward, build, furnish, bedFoot, stash, blocksNeeded, needs, "");
        }

        Plan(Type type, BlockPos room, Direction forward, List<Step> build, List<Step> furnish, BlockPos bedFoot,
             @Nullable BlockPos stash, int blocksNeeded) {
            this(type, room, forward, build, furnish, bedFoot, stash, blocksNeeded, List.of(), "");
        }

        /** How it's saved: the type, plus which blueprint for the bigger houses ("HOUSE:cottage"). */
        public String key() {
            return this.variant.isEmpty() ? this.type.name() : this.type.name() + ":" + this.variant;
        }
    }

    /** Rebuild a saved plan (after a restart) from its type, room and facing. */
    public static Plan rebuild(BotPlayer bot, String type, BlockPos room, Direction forward) {
        WorldView w = new WorldView(bot.level());
        String variant = type.contains(":") ? type.substring(type.indexOf(':') + 1) : "";
        if (type.contains(":")) type = type.substring(0, type.indexOf(':'));
        return switch (Type.valueOf(type)) {
            case HUT -> hut(w, room, forward);
            case CAVE -> cave(w, room, forward);
            case CABIN -> cabin(room, forward);
            case HOUSE -> {
                Blueprints.Blueprint bp = Blueprints.byName(variant);
                if (bp == null) throw new IllegalArgumentException("unknown house " + variant);
                yield Blueprints.plan(room, forward, bp);
            }
            case EXISTING -> throw new IllegalArgumentException("village houses aren't saved");
        };
    }

    /** Room coordinates to world coordinates. */
    public static BlockPos at(BlockPos room, Direction forward, int x, int y, int z) {
        Direction right = forward.getClockWise();
        return room.relative(right, x).relative(forward, z).above(y);
    }

    // --- hut ------------------------------------------------------------------------

    /** A flat, open 5x5 patch near the bot for a hut, or null. */
    public static @Nullable Plan findHut(BotPlayer bot) {
        WorldView w = new WorldView(bot.level());
        BlockPos origin = bot.blockPosition();
        Plan best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos door = new BlockPos(x, y, z);
                double dist = door.distSqr(origin);
                if (dist >= bestDist) continue;
                for (Direction forward : Direction.Plane.HORIZONTAL) {
                    if (!hutFits(w, door, forward)) continue;
                    best = hut(w, door, forward);
                    bestDist = dist;
                    break;
                }
            }
        }
        return best;
    }

    /**
     * A first home on open ground, for a bot carrying the blocks for it: the hut's walls and
     * roof, then the bed. (Better than a pit in the ground; the proper house comes later.)
     */
    public static @Nullable Plan starterHut(BotPlayer bot) {
        Plan hut = findHut(bot);
        if (hut == null || Inv.scaffoldCount(bot) < hut.blocksNeeded() + 4) return null;
        return hut;
    }

    private static boolean hutFits(WorldView w, BlockPos room, Direction f) {
        for (int x = -2; x <= 2; x++) {
            for (int z = 0; z <= 4; z++) {
                if (!w.solidFloor(at(room, f, x, -1, z))) return false;
                for (int y = 0; y <= 2; y++) {
                    BlockState s = w.state(at(room, f, x, y, z));
                    if (!s.canBeReplaced() || !s.getFluidState().isEmpty()) return false;
                }
            }
        }
        // Room to walk up to the door.
        BlockPos front = at(room, f, 0, 0, -1);
        return w.standable(front);
    }

    private static Plan hut(WorldView w, BlockPos room, Direction f) {
        List<Step> build = new ArrayList<>();
        int needed = 0;
        // Walls, two high, with a gap for the door.
        for (int y = 0; y <= 1; y++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = 0; z <= 4; z++) {
                    boolean edge = x == -2 || x == 2 || z == 0 || z == 4;
                    if (!edge || (x == 0 && z == 0)) continue;
                    build.add(Step.place(at(room, f, x, y, z), Inv::isPlaceableBlock));
                    needed++;
                }
            }
        }
        // Flat roof, outside ring first so every block has something to stick to.
        for (int ring = 2; ring >= 0; ring--) {
            for (int x = -2; x <= 2; x++) {
                for (int z = 0; z <= 4; z++) {
                    int r = Math.max(Math.abs(x), Math.abs(z - 2));
                    if (r != ring) continue;
                    build.add(Step.place(at(room, f, x, 2, z), Inv::isPlaceableBlock));
                    needed++;
                }
            }
        }
        return withFurnishing(Type.HUT, w, room, f, build, needed);
    }

    // --- cabin ------------------------------------------------------------------------

    /**
     * Clearing the site mustn't knock down what's already built. A build that's picked up again
     * (after a restart, or a hiccup moving in) starts from the top of its list, and the first
     * steps dig the site out: they used to dig out the finished walls. So a clearing step counts
     * as done if the block there is already what the plan puts there.
     */
    public static List<Step> keepWhatsBuilt(List<Step> build, List<Step> furnish) {
        java.util.Map<BlockPos, List<java.util.function.Predicate<net.minecraft.world.item.ItemStack>>> planned = new java.util.HashMap<>();
        for (List<Step> list : List.of(build, furnish)) {
            for (Step s : list) {
                if ((s.kind() == net.joinedthegame.ai.task.BuildTask.Kind.PLACE || s.kind() == net.joinedthegame.ai.task.BuildTask.Kind.REPLACE) && s.item() != null) {
                    planned.computeIfAbsent(s.pos(), k -> new ArrayList<>()).add(s.item());
                }
            }
        }
        List<Step> out = new ArrayList<>(build.size());
        for (Step s : build) {
            var wanted = s.kind() == net.joinedthegame.ai.task.BuildTask.Kind.DIG && s.done() == null ? planned.get(s.pos()) : null;
            if (wanted == null) {
                out.add(s);
                continue;
            }
            out.add(new Step(net.joinedthegame.ai.task.BuildTask.Kind.DIG, s.pos(), null, null, false, (w, p) -> {
                if (w.passable(p) && !w.isWater(p) || w.state(p).isAir()) return true;
                var item = new net.minecraft.world.item.ItemStack(w.state(p).getBlock().asItem());
                for (var want : wanted) if (want.test(item)) return true; // already built: leave it
                return false;
            }));
        }
        return out;
    }

    /** In the way, but quick to clear: trees, leaves, bushes, flowers. A site in a wood is fine. */
    public static boolean isSoftClutter(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.FLOWERS)
                || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.BAMBOO) || s.is(Blocks.MUSHROOM_STEM)
                || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.VINE);
    }

    /** Anything a roof or foundation can be made of: the stone every miner has stacks of. */
    public static boolean isRoofBlock(net.minecraft.world.item.ItemStack s) {
        return s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.STONE) || s.is(Items.ANDESITE)
                || s.is(Items.DIORITE) || s.is(Items.GRANITE) || s.is(Items.TUFF) || s.is(Items.STONE_BRICKS);
    }

    /**
     * Somewhere flat near {@code near} for a 7x7 cabin (9x9 with the roof overhang), or null.
     * Small stuff in the way (grass, flowers, a sapling, a little tree) gets cleared; hills,
     * water and other people's things rule a spot out.
     */
    public static @Nullable Plan findCabin(BotPlayer bot, BlockPos near, int radius) {
        WorldView w = new WorldView(bot.level());
        Plan best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx += 2) {
            for (int dz = -radius; dz <= radius; dz += 2) {
                int x = near.getX() + dx;
                int z = near.getZ() + dz;
                if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos door = new BlockPos(x, y, z);
                double dist = door.distSqr(near);
                // Not right on top of the old place: leave a gap.
                if (dist >= bestDist || dist < 8 * 8) continue;
                for (Direction forward : Direction.Plane.HORIZONTAL) {
                    if (!cabinFits(w, door, forward)) continue;
                    best = cabin(door, forward);
                    bestDist = dist;
                    break;
                }
            }
        }
        return best;
    }

    private static boolean cabinFits(WorldView w, BlockPos room, Direction f) {
        int gaps = 0;
        int clutter = 0;
        for (int x = -4; x <= 4; x++) {
            for (int z = -1; z <= 7; z++) {
                boolean footprint = Math.abs(x) <= 3 && z >= 0 && z <= 6;
                if (footprint) {
                    BlockPos under = at(room, f, x, -1, z);
                    if (!w.fluid(under).isEmpty()) return false;
                    if (!w.solidFloor(under)) {
                        // A dip we can fill, but not a cliff.
                        if (!w.solidFloor(under.below())) return false;
                        gaps++;
                    }
                }
                for (int y = 0; y <= 3; y++) {
                    if (!footprint && y < 3) continue; // only the overhang sticks out
                    BlockPos c = at(room, f, x, y, z);
                    BlockState s = w.state(c);
                    if (!s.getFluidState().isEmpty()) return false;
                    if (s.canBeReplaced()) continue;
                    if (s.hasBlockEntity() || s.is(BlockTags.BEDS) || !w.breakable(c)) return false;
                    if (!isSoftClutter(s)) clutter++;
                }
            }
        }
        if (gaps > 12 || clutter > 12) return false;
        return w.standable(at(room, f, 0, 0, -1)) || w.standable(at(room, f, 0, 0, -2));
    }

    /**
     * A 7x7 log-and-plank cabin. Room coordinates, door at x 0, z 0, looking in along +z:
     * <pre>
     *   z=6   L # g # g # L      L = log corner post    # = plank wall
     *   z=5   # B . . . C #      g = glass window       D = door
     *   z=4   g B . . . C g      B = bed  C = chests  T = table  F = furnace
     *   z=3   # . . s . T #      s = secret chest under the floor
     *   z=2   g . . . . F g
     *   z=1   # . . . . . #
     *   z=0   L # g D g # L
     * </pre>
     * Walls are three high, the floor is planks, and a flat stone roof sticks out one block all
     * round. Steps run in an order where every block has something to stick to.
     */
    public static Plan cabin(BlockPos room, Direction f) {
        List<Step> build = new ArrayList<>();
        // Clear the site, top down.
        for (int y = 3; y >= 0; y--) {
            for (int x = -4; x <= 4; x++) {
                for (int z = -1; z <= 7; z++) {
                    boolean footprint = Math.abs(x) <= 3 && z >= 0 && z <= 6;
                    if (!footprint && y < 3) continue;
                    build.add(Step.dig(at(room, f, x, y, z)));
                }
            }
        }
        // Foundation under the walls (fills any dips), then a plank floor inside, back to front
        // so the bot isn't standing on the next bit.
        for (int x = -3; x <= 3; x++) {
            for (int z = 0; z <= 6; z++) {
                if (Math.abs(x) == 3 || z == 0 || z == 6) build.add(Step.place(at(room, f, x, -1, z), HousePlans::isRoofBlock));
            }
        }
        for (int z = 5; z >= 1; z--) {
            for (int x = -2; x <= 2; x++) build.add(Step.replace(at(room, f, x, -1, z), s -> s.is(ItemTags.PLANKS)));
        }
        // Corner posts, bottom up.
        for (int y = 0; y <= 2; y++) {
            for (int[] c : new int[][]{{-3, 0}, {3, 0}, {-3, 6}, {3, 6}}) {
                build.add(Step.place(at(room, f, c[0], y, c[1]), s -> s.is(ItemTags.LOGS)));
            }
        }
        // Walls, a layer at a time, with windows in the middle layer and a gap for the door.
        for (int y = 0; y <= 2; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = 0; z <= 6; z++) {
                    boolean edge = Math.abs(x) == 3 || z == 0 || z == 6;
                    boolean corner = Math.abs(x) == 3 && (z == 0 || z == 6);
                    if (!edge || corner) continue;
                    if (x == 0 && z == 0 && y <= 1) continue; // doorway
                    BlockPos c = at(room, f, x, y, z);
                    if (y == 1 && isWindow(x, z)) build.add(Step.place(c, s -> s.is(Items.GLASS)));
                    else build.add(Step.place(c, s -> s.is(ItemTags.PLANKS)));
                }
            }
        }
        // Windows the bot couldn't glaze get planked over rather than left open.
        for (int x = -3; x <= 3; x++) {
            for (int z = 0; z <= 6; z++) {
                if (isWindow(x, z)) build.add(Step.place(at(room, f, x, 1, z), s -> s.is(ItemTags.PLANKS)));
            }
        }
        // Roof: over the walls first, then inwards, then the overhang outwards.
        for (int ring = 3; ring >= 0; ring--) addRoofRing(build, room, f, ring);
        addRoofRing(build, room, f, 4);
        // The door goes in last, so walking in and out while building is easy.
        build.add(Step.placeFacing(at(room, f, 0, 0, 0), s -> s.is(ItemTags.WOODEN_DOORS), f));

        // Furniture.
        List<Step> furnish = new ArrayList<>();
        BlockPos bedFoot = at(room, f, -2, 0, 4);
        furnish.add(Step.placeEssential(bedFoot, s -> s.is(ItemTags.BEDS), f));
        // Two chests side by side against the right wall, both facing into the room: a double chest.
        furnish.add(Step.placeEssential(at(room, f, 2, 0, 5), s -> s.is(Items.CHEST), f.getClockWise()));
        furnish.add(Step.placeFacing(at(room, f, 2, 0, 4), s -> s.is(Items.CHEST), f.getClockWise()));
        furnish.add(Step.place(at(room, f, 2, 0, 3), s -> s.is(Items.CRAFTING_TABLE)));
        furnish.add(Step.place(at(room, f, 2, 0, 2), s -> s.is(Items.FURNACE)));
        furnish.add(Step.place(at(room, f, -2, 1, 2), s -> s.is(Items.TORCH)));
        furnish.add(Step.place(at(room, f, 0, 1, 5), s -> s.is(Items.TORCH)));
        BlockPos stash = at(room, f, 0, -2, 3);
        furnish.add(Step.dig(stash.above()));
        furnish.add(Step.dig(stash));
        furnish.add(Step.place(stash, s -> s.is(Items.CHEST))); // (nice to have: a house without a stash is still a house)
        furnish.add(Step.place(stash.above(), s -> s.is(ItemTags.PLANKS)));
        furnish.add(Step.place(signSpot(room, f), s -> s.is(ItemTags.SIGNS)));

        List<Tasks.Need> needs = List.of(
                Tasks.Need.of("planks", s -> s.is(ItemTags.PLANKS), 82, bot -> net.joinedthegame.ai.Wood.preferred(bot, "planks")),
                Tasks.Need.of("logs", s -> s.is(ItemTags.LOGS), 12, bot -> net.joinedthegame.ai.Wood.preferred(bot, "log")),
                Tasks.Need.of("stone", HousePlans::isRoofBlock, 81 + 24, bot -> Items.COBBLESTONE),
                Tasks.Need.of("a door", s -> s.is(ItemTags.WOODEN_DOORS), 1, bot -> net.joinedthegame.ai.Wood.preferred(bot, "door")),
                Tasks.Need.of("a crafting table", s -> s.is(Items.CRAFTING_TABLE), 1, bot -> Items.CRAFTING_TABLE),
                Tasks.Need.of("a furnace", s -> s.is(Items.FURNACE), 1, bot -> Items.FURNACE),
                Tasks.Need.nice("glass", s -> s.is(Items.GLASS), 8, bot -> Items.GLASS),
                Tasks.Need.nice("torches", s -> s.is(Items.TORCH), 2, bot -> Items.TORCH));
        return new Plan(Type.CABIN, room, f, keepWhatsBuilt(build, furnish), furnish, bedFoot, stash, 0, needs);
    }

    private static boolean isWindow(int x, int z) {
        if (Math.abs(x) == 3) return z == 2 || z == 4;
        if (z == 6) return x == -1 || x == 1;
        if (z == 0) return x == -1 || x == 1;
        return false;
    }

    /** One ring of the roof at y 3: ring 3 sits on the walls, 4 is the overhang, 0 the middle. */
    private static void addRoofRing(List<Step> build, BlockPos room, Direction f, int ring) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -1; z <= 7; z++) {
                if (Math.max(Math.abs(x), Math.abs(z - 3)) != ring) continue;
                build.add(Step.place(at(room, f, x, 3, z), HousePlans::isRoofBlock));
            }
        }
    }

    // --- cave -------------------------------------------------------------------------

    /** A hillside right next to the bot that a 3x3 room can be dug into, or null. */
    public static @Nullable Plan findCave(BotPlayer bot) {
        WorldView w = new WorldView(bot.level());
        BlockPos feet = Navigator.feet(bot);
        for (Direction f : Direction.Plane.HORIZONTAL) {
            BlockPos room = feet.relative(f);
            if (caveFits(w, feet, room, f)) return cave(w, room, f);
        }
        return null;
    }

    /** Search the area for a hillside, standing spots within {@code radius}. */
    public static @Nullable Plan findCaveNear(BotPlayer bot, int radius) {
        Plan here = findCave(bot);
        if (here != null) return here;
        WorldView w = new WorldView(bot.level());
        BlockPos origin = bot.blockPosition();
        for (int r = 2; r <= radius; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) {
                for (int dz = -r; dz <= r; dz += 2) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int x = origin.getX() + dx;
                    int z = origin.getZ() + dz;
                    if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                    int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos feet = new BlockPos(x, y, z);
                    if (!w.standable(feet)) continue;
                    for (Direction f : Direction.Plane.HORIZONTAL) {
                        BlockPos room = feet.relative(f);
                        if (caveFits(w, feet, room, f)) return cave(w, room, f);
                    }
                }
            }
        }
        return null;
    }

    private static boolean caveFits(WorldView w, BlockPos standing, BlockPos room, Direction f) {
        // The hill starts right in front of us.
        if (w.passable(room) || w.passable(room.above())) return false;
        int solid = 0;
        for (int x = -1; x <= 1; x++) {
            for (int z = 0; z <= 3; z++) {
                if (z == 0 && x != 0) continue;
                for (int y = 0; y <= 1; y++) {
                    BlockPos c = at(room, f, x, y, z);
                    if (!w.clearOrBreakable(c)) return false;
                    if (!w.passable(c)) solid++;
                }
                // A real roof (no sand or gravel to come down on us) and a real floor.
                BlockState roof = w.state(at(room, f, x, 2, z));
                if (w.passable(at(room, f, x, 2, z)) || roof.is(Blocks.SAND) || roof.is(Blocks.GRAVEL) || roof.is(Blocks.RED_SAND)) return false;
                if (!w.solidFloor(at(room, f, x, -1, z))) return false;
            }
        }
        // Not a home with lava next door (behind the wall, under the floor).
        if (w.lavaNear(at(room, f, -1, -1, 0), at(room, f, 1, 2, 3), 3)) return false;
        return solid >= 16; // actually inside a hill, not a little bump
    }

    private static Plan cave(WorldView w, BlockPos room, Direction f) {
        List<Step> build = new ArrayList<>();
        for (int y = 1; y >= 0; y--) build.add(Step.dig(at(room, f, 0, y, 0)));
        for (int z = 1; z <= 3; z++) {
            for (int x = -1; x <= 1; x++) {
                for (int y = 1; y >= 0; y--) build.add(Step.dig(at(room, f, x, y, z)));
            }
        }
        return withFurnishing(Type.CAVE, w, room, f, build, 0);
    }

    // --- village ------------------------------------------------------------------------

    /** Is this bed actually indoors (a roof overhead and walls around)? */
    public static boolean isIndoors(WorldView w, BlockPos bed) {
        boolean roof = false;
        for (int y = 1; y <= 5 && !roof; y++) roof = !w.passable(bed.above(y));
        if (!roof) return false;
        int walls = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int i = 1; i <= 5; i++) {
                if (!w.passable(bed.relative(d, i).above())) {
                    walls++;
                    break;
                }
            }
        }
        if (walls < 3) return false;
        // Room to actually live in, not just a bed-sized nook.
        int floor = 0;
        for (BlockPos c : BlockPos.betweenClosed(bed.offset(-2, 0, -2), bed.offset(2, 0, 2))) {
            if (!w.state(c).is(BlockTags.BEDS) && w.standable(c)) floor++;
        }
        return floor >= 4;
    }

    /** Moving into someone's house: just bring a chest and dig a secret stash. */
    public static Plan village(BotPlayer bot, BlockPos bedHead) {
        WorldView w = new WorldView(bot.level());
        List<Step> furnish = new ArrayList<>();
        BlockPos chest = null;
        BlockPos stash = null;
        // Two free spots side by side with floor to stand on in front: a double chest.
        outer:
        for (BlockPos c : BlockPos.betweenClosed(bedHead.offset(-2, 0, -2), bedHead.offset(2, 0, 2))) {
            BlockPos cell = c.immutable();
            if (!freeSpot(w, cell)) continue;
            for (Direction along : Direction.Plane.HORIZONTAL) {
                BlockPos other = cell.relative(along);
                if (!freeSpot(w, other)) continue;
                for (Direction front : new Direction[]{along.getClockWise(), along.getCounterClockWise()}) {
                    if (!w.standable(cell.relative(front)) || !w.standable(other.relative(front))) continue;
                    // Both facing the open floor (we face the other way to place them).
                    furnish.add(Step.placeEssential(cell, s -> s.is(Items.CHEST), front.getOpposite()));
                    furnish.add(Step.placeFacing(other, s -> s.is(Items.CHEST), front.getOpposite()));
                    chest = cell;
                    break outer;
                }
            }
        }
        for (BlockPos c : BlockPos.betweenClosed(bedHead.offset(-2, 0, -2), bedHead.offset(2, 0, 2))) {
            BlockPos cell = c.immutable();
            if (w.state(cell).is(BlockTags.BEDS) || !w.state(cell).canBeReplaced() || !w.solidFloor(cell.below())) continue;
            if (chest == null) {
                chest = cell;
                furnish.add(Step.placeEssential(chest, s -> s.is(Items.CHEST), null));
            } else if (stash == null && w.breakable(cell.below()) && w.state(cell.below(2)).canBeReplaced() || stash == null && w.breakable(cell.below()) && w.breakable(cell.below(2))) {
                stash = cell.below(2);
            }
        }
        if (stash != null) addStash(furnish, stash);
        return new Plan(Type.EXISTING, bedHead, Direction.NORTH, List.of(), furnish, bedHead, stash, 0);
    }

    /** Free floor for a chest: empty, floored, not a bed or a doorway. */
    private static boolean freeSpot(WorldView w, BlockPos p) {
        if (w.state(p).is(BlockTags.BEDS) || !w.state(p).canBeReplaced() || !w.solidFloor(p.below())) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (w.state(p.relative(d)).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) return false;
        }
        return true;
    }

    // --- furnishing -----------------------------------------------------------------------

    private static Plan withFurnishing(Type type, WorldView w, BlockPos room, Direction f, List<Step> build, int needed) {
        List<Step> furnish = new ArrayList<>();
        BlockPos bedFoot = at(room, f, -1, 0, 2);
        furnish.add(Step.placeEssential(bedFoot, s -> s.is(ItemTags.BEDS), f));
        // A double chest along the right wall, facing into the room.
        furnish.add(Step.placeEssential(at(room, f, 1, 0, 3), s -> s.is(Items.CHEST), f.getClockWise()));
        furnish.add(Step.placeFacing(at(room, f, 1, 0, 2), s -> s.is(Items.CHEST), f.getClockWise()));
        furnish.add(Step.place(at(room, f, 1, 1, 1), s -> s.is(Items.TORCH)));
        BlockPos stash = at(room, f, 0, -2, 2);
        addStash(furnish, stash);
        furnish.add(Step.place(signSpot(room, f), s -> s.is(ItemTags.SIGNS)));
        return new Plan(type, room, f, build, furnish, bedFoot, stash, needed);
    }

    /** Dig through the floor, bury a chest, put the floor back. */
    private static void addStash(List<Step> steps, BlockPos stash) {
        steps.add(Step.dig(stash.above()));
        steps.add(Step.dig(stash));
        steps.add(Step.place(stash, s -> s.is(Items.CHEST))); // (nice to have: a house without a stash is still a house)
        steps.add(Step.place(stash.above(), Inv::isPlaceableBlock));
    }

    /** Outside, on the wall just left of the door, at eye height. */
    public static BlockPos signSpot(BlockPos room, Direction f) {
        return at(room, f, -1, 1, -1);
    }

    /** Places a sign could go, best first: either side of the door outside, then just inside. */
    public static List<BlockPos> signSpots(BlockPos room, Direction f) {
        return List.of(at(room, f, -1, 1, -1), at(room, f, 1, 1, -1), at(room, f, -1, 0, -1), at(room, f, 1, 0, -1),
                at(room, f, 0, 1, 1), at(room, f, -1, 1, 1));
    }
}
