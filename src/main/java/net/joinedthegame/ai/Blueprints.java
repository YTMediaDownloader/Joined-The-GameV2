package net.joinedthegame.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.joinedthegame.ai.HousePlans.Plan;
import net.joinedthegame.ai.HousePlans.Type;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.ai.task.BuildTask.Step;
import net.joinedthegame.ai.task.Tasks;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * House blueprints, drawn as floor plans: one picture per layer (y 0, 1, 2 of the walls),
 * rows running from the front wall (z 0) to the back, as seen from outside looking in. The
 * door is the {@code D} in the front row; everything else is measured from it.
 * <pre>
 *   L log post     # wall          g window (glass, or wall if there's none)
 *   D door         d door's top half (leave it)   . inside (floor, open)
 *   B bed foot (head goes one further in)         T crafting table   F furnace
 *   C chest (two side by side make a double chest; it faces whichever open cell it's next to)
 *   S the secret stash, buried under this bit of floor            t torch
 *   = upstairs floor (planks)     H ladder (on the wall beside it)   R barrel
 * </pre>
 * Furniture can go on any floor. A plan can have as many layers as it likes: a two-storey
 * house is walls, a layer of floor, then more walls.
 * The floor is planks over a stone foundation, and a flat stone roof sits on top, sticking out
 * one block all round. Every one has a storage room: the chests live together, not dotted
 * about the place.
 */
public final class Blueprints {
    private Blueprints() {}

    public enum Walls { PLANKS, STONE }

    /**
     * FLAT: the old flat stone roof with an overhang. GABLE: a proper pitched roof of stairs (in
     * whatever wood grows round there), ridge running front to back, gable ends filled in.
     */
    public enum Roof { FLAT, GABLE }

    public record Blueprint(String name, String label, Walls walls, Roof roof, String[][] layers) {
        public Blueprint(String name, String label, Walls walls, String[][] layers) {
            this(name, label, walls, Roof.FLAT, layers);
        }

        /** How many layers the roof itself adds above the walls. */
        int roofLayers() {
            return this.roof == Roof.GABLE ? (width() + 1) / 2 : 1;
        }

        int width() {
            return this.layers[0][0].length();
        }

        int depth() {
            return this.layers[0].length;
        }

        int height() {
            return this.layers.length;
        }

        /** Column of the door in the front row: x 0. */
        int doorColumn() {
            return this.layers[0][0].indexOf('D');
        }

        /** The character at room coordinates (x, y, z), or ' ' outside the plan. */
        char at(int x, int y, int z) {
            int col = x + doorColumn();
            if (y < 0 || y >= height() || z < 0 || z >= depth() || col < 0 || col >= width()) return ' ';
            return this.layers[y][z].charAt(col);
        }

        int minX() {
            return -doorColumn();
        }

        int maxX() {
            return width() - 1 - doorColumn();
        }
    }

    /** A cosy timber cottage: living room at the front, storage room at the back. */
    public static final Blueprint COTTAGE = new Blueprint("cottage", "a cottage", Walls.PLANKS, Roof.GABLE, new String[][]{
            {
                    "L###D###L",
                    "#T......#",
                    "#F.....B#",
                    "#.......#",
                    "#...S...#",
                    "#.......#",
                    "####.####",
                    "#.......#",
                    "#CC...CC#",
                    "L#######L"},
            {
                    "L#g#d#g#L",
                    "#.......#",
                    "g.......g",
                    "#.......#",
                    "g.......g",
                    "#t.....t#",
                    "####.####",
                    "#t.....t#",
                    "#.......#",
                    "L##g#g##L"},
            {
                    "L#######L",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#########",
                    "#.......#",
                    "#.......#",
                    "L#######L"}});

    /** The same idea in stone, the other way round: for the bot that lives underground anyway. */
    public static final Blueprint STONE_HOUSE = new Blueprint("stone_house", "a stone house", Walls.STONE, new String[][]{
            {
                    "L###D###L",
                    "#......T#",
                    "#B.....F#",
                    "#.......#",
                    "#...S...#",
                    "#.......#",
                    "####.####",
                    "#.......#",
                    "#CC...CC#",
                    "L#######L"},
            {
                    "L#g#d#g#L",
                    "#.......#",
                    "g.......g",
                    "#.......#",
                    "g.......g",
                    "#t.....t#",
                    "####.####",
                    "#t.....t#",
                    "#.......#",
                    "L##g#g##L"},
            {
                    "L#######L",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#########",
                    "#.......#",
                    "#.......#",
                    "L#######L"}});

    /** A long hall with a storage wing down one side: fits a narrow spot. */
    public static final Blueprint LONGHOUSE = new Blueprint("longhouse", "a longhouse", Walls.PLANKS, Roof.GABLE, new String[][]{
            {
                    "L####D####L",
                    "#C.#.....T#",
                    "#C.#.....F#",
                    "#....S....#",
                    "#C.#...B..#",
                    "#C.#......#",
                    "L#########L"},
            {
                    "L#g##d##g#L",
                    "#..#......#",
                    "g..#......g",
                    "#.t.......#",
                    "g..#......g",
                    "#..#.....t#",
                    "L###g#g###L"},
            {
                    "L#########L",
                    "#..#......#",
                    "#..#......#",
                    "#..#......#",
                    "#..#......#",
                    "#..#......#",
                    "L#########L"}});

    /**
     * Two storeys: a workshop and storage room downstairs, a bedroom and more storage upstairs,
     * a ladder in the corner. The house a bot builds once a one-storey house is full.
     */
    public static final Blueprint TWO_STOREY = new Blueprint("two_storey", "a two-storey house", Walls.PLANKS, Roof.GABLE, new String[][]{
            {   // ground floor
                    "L###D###L",
                    "#T.....H#",
                    "#F......#",
                    "#F......#",
                    "#...S...#",
                    "####.####",
                    "#.......#",
                    "#CCR.RCC#",
                    "L#######L"},
            {
                    "L#g#d#g#L",
                    "#......H#",
                    "g.......g",
                    "#.......#",
                    "#t.....t#",
                    "####.####",
                    "#t.....t#",
                    "#.......#",
                    "L##g#g##L"},
            {
                    "L#######L",
                    "#......H#",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#########",
                    "#.......#",
                    "#.......#",
                    "L#######L"},
            {   // the upstairs floor
                    "L#######L",
                    "#======H#",
                    "#=======#",
                    "#=======#",
                    "#=======#",
                    "#=======#",
                    "#=======#",
                    "#=======#",
                    "L#######L"},
            {   // upstairs
                    "L#######L",
                    "#......H#",
                    "#B......#",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#CCR.RCC#",
                    "L#######L"},
            {
                    "L#g###g#L",
                    "#.......#",
                    "g.......g",
                    "#t.....t#",
                    "g.......g",
                    "#.......#",
                    "#t.....t#",
                    "#.......#",
                    "L##g#g##L"},
            {
                    "L#######L",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "#.......#",
                    "L#######L"}});

    /** A roomy farmhouse: a big kitchen-living room at the front, the store room behind. */
    public static final Blueprint FARMHOUSE = new Blueprint("farmhouse", "a farmhouse", Walls.PLANKS, Roof.GABLE, new String[][]{
            {
                    "L####D####L",
                    "#TF......B#",
                    "#.........#",
                    "#....S....#",
                    "#.........#",
                    "####.#.####",
                    "#CC.....RR#",
                    "#CC.....RR#",
                    "L#########L"},
            {
                    "L#g#gdg#g#L",
                    "#.........#",
                    "g.........g",
                    "#t.......t#",
                    "g.........g",
                    "####.#.####",
                    "#t.......t#",
                    "#.........#",
                    "L###g#g###L"},
            {
                    "L#########L",
                    "#.........#",
                    "#.........#",
                    "#.........#",
                    "#.........#",
                    "###########",
                    "#.........#",
                    "#.........#",
                    "L#########L"}});

    /** A small, wide bungalow: one big room and a store room, windows all along the front. */
    public static final Blueprint BUNGALOW = new Blueprint("bungalow", "a bungalow", Walls.PLANKS, Roof.GABLE, new String[][]{
            {
                    "L##D##L",
                    "#T...B#",
                    "#F....#",
                    "#..S..#",
                    "##.####",
                    "#CC..C#",
                    "L#####L"},
            {
                    "Lg#d#gL",
                    "#.....#",
                    "g.....g",
                    "#t...t#",
                    "##.####",
                    "#t....#",
                    "L##g##L"},
            {
                    "L#####L",
                    "#.....#",
                    "#.....#",
                    "#.....#",
                    "#######",
                    "#.....#",
                    "L#####L"}});

    /** A big timber lodge: a long hall with the hearth at one end, a workshop and a store room. */
    public static final Blueprint LODGE = new Blueprint("lodge", "a lodge", Walls.PLANKS, Roof.GABLE, new String[][]{
            {
                    "L#####D#####L",
                    "#T.........B#",
                    "#F..........#",
                    "#F....S.....#",
                    "#...........#",
                    "#...........#",
                    "L###.#####.#L",
                    "#RR.....#...#",
                    "#RR.....#...#",
                    "#.......#CCC#",
                    "L###########L"},
            {
                    "L#g#g#d#g#g#L",
                    "#...........#",
                    "g...........g",
                    "#t.........t#",
                    "g...........g",
                    "#...........#",
                    "L###.#####.#L",
                    "#t......#..t#",
                    "g.......#...g",
                    "#.......#...#",
                    "L###g###g###L"},
            {
                    "L###########L",
                    "#...........#",
                    "#...........#",
                    "#...........#",
                    "#...........#",
                    "#...........#",
                    "L###########L",
                    "#.......#...#",
                    "#.......#...#",
                    "#.......#...#",
                    "L###########L"}});

    /** One-storey houses, the tier above a cabin. */
    public static final List<Blueprint> HOUSES = List.of(COTTAGE, STONE_HOUSE, LONGHOUSE, FARMHOUSE, BUNGALOW);
    /** The tier above that. */
    public static final List<Blueprint> LARGE = List.of(TWO_STOREY, LODGE);

    public static @Nullable Blueprint byName(String name) {
        for (Blueprint b : HOUSES) if (b.name().equals(name)) return b;
        for (Blueprint b : LARGE) if (b.name().equals(name)) return b;
        return null;
    }

    /** A large house (the top tier)? */
    public static boolean isLarge(String name) {
        for (Blueprint b : LARGE) if (b.name().equals(name)) return true;
        return false;
    }

    /**
     * Which house this bot would like: miners go for stone, explorers for the long hall, and the
     * rest a cottage. A bot's taste stays the same (it's from its personality).
     */
    public static List<Blueprint> preferred(Personality p) {
        List<Blueprint> order = new ArrayList<>(HOUSES);
        order.sort((a, b) -> Double.compare(taste(b, p), taste(a, p)));
        return order;
    }

    private static double taste(Blueprint b, Personality p) {
        return switch (b.name()) {
            case "stone_house" -> 0.3 + p.miner() * 0.8;
            case "longhouse" -> 0.3 + p.explorer() * 0.6 + p.hoarder() * 0.3;
            case "farmhouse" -> 0.3 + p.homebody() * 0.5 + p.animalLover() * 0.5 + p.diligent() * 0.2;
            case "bungalow" -> 0.3 + p.social() * 0.4 + (1 - p.builder()) * 0.4 + p.nomad() * 0.3;
            default -> 0.5 + p.homebody() * 0.5 + p.social() * 0.3;
        };
    }

    // --- siting -----------------------------------------------------------------------

    /** The nearest flat spot round {@code near} that this house fits, or null. */
    public static @Nullable Plan find(BotPlayer bot, BlockPos near, int radius, Blueprint bp) {
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
                if (dist >= bestDist || dist < 10 * 10) continue;
                for (Direction forward : Direction.Plane.HORIZONTAL) {
                    if (!fits(w, door, forward, bp)) continue;
                    best = plan(door, forward, bp);
                    bestDist = dist;
                    break;
                }
            }
        }
        return best;
    }

    private static boolean fits(WorldView w, BlockPos room, Direction f, Blueprint bp) {
        int gaps = 0;
        int clutter = 0;
        int roofY = bp.height();
        int top = roofY + bp.roofLayers() - 1;
        for (int x = bp.minX() - 1; x <= bp.maxX() + 1; x++) {
            for (int z = -1; z <= bp.depth(); z++) {
                boolean footprint = bp.at(x, 0, z) != ' ';
                if (footprint) {
                    BlockPos under = HousePlans.at(room, f, x, -1, z);
                    if (!w.fluid(under).isEmpty()) return false;
                    if (!w.solidFloor(under)) {
                        if (!w.solidFloor(under.below())) return false; // a dip we can fill, not a cliff
                        gaps++;
                    }
                }
                for (int y = 0; y <= top; y++) {
                    if (!footprint && y < roofY) continue; // only the roof's overhang sticks out
                    BlockPos c = HousePlans.at(room, f, x, y, z);
                    BlockState s = w.state(c);
                    if (!s.getFluidState().isEmpty()) return false;
                    if (s.canBeReplaced()) continue;
                    if (s.hasBlockEntity() || s.is(BlockTags.BEDS) || !w.breakable(c)) return false;
                    // Trees and bushes are quick to clear; hills and rock aren't.
                    if (!HousePlans.isSoftClutter(s)) clutter++;
                }
            }
        }
        if (gaps > 20 || clutter > 16) return false;
        return w.standable(HousePlans.at(room, f, 0, 0, -1)) || w.standable(HousePlans.at(room, f, 0, 0, -2));
    }

    // --- the plan ---------------------------------------------------------------------

    /** Room (x, z) to the world direction it points along. */
    private static Direction toWorld(Direction f, int dx, int dz) {
        if (dx > 0) return f.getClockWise();
        if (dx < 0) return f.getCounterClockWise();
        return dz > 0 ? f : f.getOpposite();
    }

    /** The building steps and furniture for a blueprint at this spot. */
    public static Plan plan(BlockPos room, Direction f, Blueprint bp) {
        Predicate<ItemStack> wall = bp.walls() == Walls.STONE ? HousePlans::isRoofBlock : s -> s.is(ItemTags.PLANKS);
        int roofY = bp.height();
        int minX = bp.minX(), maxX = bp.maxX(), depth = bp.depth();
        List<Step> build = new ArrayList<>();
        int top = roofY + bp.roofLayers() - 1;
        // Clear the site, top down.
        for (int y = top; y >= 0; y--) {
            for (int x = minX - 1; x <= maxX + 1; x++) {
                for (int z = -1; z <= depth; z++) {
                    boolean footprint = bp.at(x, 0, z) != ' ';
                    if (!footprint && y < roofY) continue;
                    build.add(Step.dig(HousePlans.at(room, f, x, y, z)));
                }
            }
        }
        // Foundation under the walls, then a plank floor inside, back to front so we're never
        // standing on the next bit.
        int foundation = 0, floor = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = 0; z < depth; z++) {
                if (isWall(bp.at(x, 0, z)) || bp.at(x, 0, z) == 'D') {
                    build.add(Step.place(HousePlans.at(room, f, x, -1, z), HousePlans::isRoofBlock));
                    foundation++;
                }
            }
        }
        for (int z = depth - 1; z >= 0; z--) {
            for (int x = minX; x <= maxX; x++) {
                char c = bp.at(x, 0, z);
                if (isWall(c) || c == 'D' || c == ' ') continue;
                build.add(Step.replace(HousePlans.at(room, f, x, -1, z), s -> s.is(ItemTags.PLANKS)));
                floor++;
            }
        }
        // Then a layer at a time, bottom up: posts, walls, the upstairs floor (from the walls
        // inwards, so every plank has something to stick to), ladders on the walls.
        int logs = 0, walls = 0, glass = 0, ladders = 0;
        for (int y = 0; y < roofY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = 0; z < depth; z++) {
                    if (bp.at(x, y, z) == 'L') {
                        build.add(Step.place(HousePlans.at(room, f, x, y, z), s -> s.is(ItemTags.LOGS)));
                        logs++;
                    }
                }
            }
            for (int x = minX; x <= maxX; x++) {
                for (int z = 0; z < depth; z++) {
                    char c = bp.at(x, y, z);
                    BlockPos p = HousePlans.at(room, f, x, y, z);
                    if (c == '#') {
                        build.add(Step.place(p, wall));
                        walls++;
                    } else if (c == 'g') {
                        build.add(Step.place(p, s -> s.is(Items.GLASS)));
                        glass++;
                    }
                }
            }
            int maxRing = Math.max(maxX - minX, depth);
            for (int ring = 0; ring <= maxRing; ring++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = 0; z < depth; z++) {
                        if (bp.at(x, y, z) != '=') continue;
                        int d = Math.min(Math.min(x - minX, maxX - x), Math.min(z, depth - 1 - z));
                        if (d != ring) continue;
                        build.add(Step.place(HousePlans.at(room, f, x, y, z), s -> s.is(ItemTags.PLANKS)));
                        floor++;
                    }
                }
            }
            for (int x = minX; x <= maxX; x++) {
                for (int z = 0; z < depth; z++) {
                    if (bp.at(x, y, z) != 'H') continue;
                    // On the wall it's against: we face the wall to put it up.
                    Direction toWall = f;
                    for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        if (isWall(bp.at(x + n[0], y, z + n[1]))) {
                            toWall = toWorld(f, n[0], n[1]);
                            break;
                        }
                    }
                    build.add(Step.placeFacing(HousePlans.at(room, f, x, y, z), s -> s.is(Items.LADDER), toWall));
                    ladders++;
                }
            }
        }
        // Windows we couldn't glaze get walled up rather than left open.
        for (int y = 0; y < roofY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = 0; z < depth; z++) {
                    if (bp.at(x, y, z) == 'g') build.add(Step.place(HousePlans.at(room, f, x, y, z), wall));
                }
            }
        }
        int roof = 0, stairs = 0;
        if (bp.roof() == Roof.GABLE) {
            // A pitched roof, a layer at a time from the eaves up. Each layer: the gable ends first
            // (the front and back walls carrying on up), then a row of stairs down each side,
            // starting against the gable end and running along it, every stair with something to
            // go against; the overhang at each end last. Odd widths finish with a ridge.
            Predicate<ItemStack> stair = s -> s.is(ItemTags.WOODEN_STAIRS);
            Direction upLeft = toWorld(f, 1, 0), upRight = toWorld(f, -1, 0);
            for (int k = 0; ; k++) {
                int left = minX + k, right = maxX - k, y = roofY + k;
                if (left > right) break;
                for (int z : new int[]{0, depth - 1}) {
                    for (int x = left + 1; x <= right - 1; x++) {
                        build.add(Step.place(HousePlans.at(room, f, x, y, z), wall));
                        walls++;
                    }
                }
                if (left == right) {
                    // The ridge: along the top, then the overhang at each end (with the ridge
                    // block next to it to go against).
                    for (int z = 0; z < depth; z++) build.add(Step.place(HousePlans.at(room, f, left, y, z), wall));
                    build.add(Step.place(HousePlans.at(room, f, left, y, -1), wall));
                    build.add(Step.place(HousePlans.at(room, f, left, y, depth), wall));
                    walls += depth + 2;
                    break;
                }
                for (int side : new int[]{left, right}) {
                    Direction face = side == left ? upLeft : upRight;
                    for (int z = 0; z < depth; z++) {
                        build.add(Step.placeFacing(HousePlans.at(room, f, side, y, z), stair, face));
                        stairs++;
                    }
                    build.add(Step.placeFacing(HousePlans.at(room, f, side, y, -1), stair, face));
                    build.add(Step.placeFacing(HousePlans.at(room, f, side, y, depth), stair, face));
                    stairs += 2;
                }
            }
        } else {
            // Flat: over the walls first, then inwards ring by ring, then the overhang.
            int maxRing = Math.max(maxX - minX, depth);
            for (int ring = 0; ring <= maxRing; ring++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = 0; z < depth; z++) {
                        if (bp.at(x, 0, z) == ' ') continue;
                        int d = Math.min(Math.min(x - minX, maxX - x), Math.min(z, depth - 1 - z));
                        if (d != ring) continue;
                        build.add(Step.place(HousePlans.at(room, f, x, roofY, z), HousePlans::isRoofBlock));
                        roof++;
                    }
                }
            }
            for (int x = minX - 1; x <= maxX + 1; x++) {
                for (int z = -1; z <= depth; z++) {
                    if (x >= minX && x <= maxX && z >= 0 && z < depth) continue;
                    build.add(Step.place(HousePlans.at(room, f, x, roofY, z), HousePlans::isRoofBlock));
                    roof++;
                }
            }
        }
        // The door last, so walking in and out while building is easy.
        build.add(Step.placeFacing(HousePlans.at(room, f, 0, 0, 0), s -> s.is(ItemTags.WOODEN_DOORS), f));

        // Furniture, on any floor.
        List<Step> furnish = new ArrayList<>();
        BlockPos bedFoot = null;
        BlockPos stash = null;
        int chests = 0, torches = 0, furnaces = 0, barrels = 0;
        boolean firstChest = true;
        for (int y = 0; y < roofY; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = minX; x <= maxX; x++) {
                    char c = bp.at(x, y, z);
                    BlockPos p = HousePlans.at(room, f, x, y, z);
                    switch (c) {
                        case 'B' -> {
                            bedFoot = p;
                            furnish.addFirst(Step.placeEssential(p, s -> s.is(ItemTags.BEDS), f));
                        }
                        case 'T' -> furnish.add(Step.place(p, s -> s.is(Items.CRAFTING_TABLE)));
                        case 'F' -> {
                            furnish.add(Step.place(p, s -> s.is(Items.FURNACE)));
                            furnaces++;
                        }
                        case 'C', 'R' -> {
                            // Face the open floor in front of it; facing the same way, chests side
                            // by side join into a double chest. (We face the other way to place it.)
                            Direction face = f;
                            for (int[] n : new int[][]{{0, -1}, {0, 1}, {-1, 0}, {1, 0}}) {
                                char nc = bp.at(x + n[0], y, z + n[1]);
                                if (nc == '.' || nc == 'S') {
                                    face = toWorld(f, -n[0], -n[1]);
                                    break;
                                }
                            }
                            if (c == 'R') {
                                furnish.add(Step.placeFacing(p, s -> s.is(Items.BARREL), face));
                                barrels++;
                            } else {
                                Predicate<ItemStack> chest = s -> s.is(Items.CHEST);
                                furnish.add(firstChest ? new Step(net.joinedthegame.ai.task.BuildTask.Kind.PLACE, p, chest, face, true, null)
                                        : Step.placeFacing(p, chest, face));
                                firstChest = false;
                                chests++;
                            }
                        }
                        case 'S' -> {
                            if (y == 0) stash = HousePlans.at(room, f, x, -2, z);
                        }
                        case 't' -> {
                            furnish.add(Step.place(p, s -> s.is(Items.TORCH)));
                            torches++;
                        }
                        default -> {
                        }
                    }
                }
            }
        }
        if (stash != null) {
            furnish.add(Step.dig(stash.above()));
            furnish.add(Step.dig(stash));
            furnish.add(Step.place(stash, s -> s.is(Items.CHEST))); // (nice to have: a house without a stash is still a house)
            furnish.add(Step.place(stash.above(), s -> s.is(ItemTags.PLANKS)));
            chests++;
        }
        furnish.add(Step.place(HousePlans.signSpot(room, f), s -> s.is(ItemTags.SIGNS)));
        if (bedFoot == null) throw new IllegalStateException("blueprint " + bp.name() + " has no bed");

        int plankWalls = bp.walls() == Walls.PLANKS ? walls + glass : 0;
        int stoneWalls = bp.walls() == Walls.STONE ? walls + glass : 0;
        List<Tasks.Need> needs = new ArrayList<>(List.of(
                Tasks.Need.of("planks", s -> s.is(ItemTags.PLANKS), floor + plankWalls + 2, bot -> Wood.preferred(bot, "planks")),
                Tasks.Need.of("logs", s -> s.is(ItemTags.LOGS), logs, bot -> Wood.preferred(bot, "log")),
                Tasks.Need.of("stone", HousePlans::isRoofBlock, foundation + roof + stoneWalls, bot -> Items.COBBLESTONE),
                Tasks.Need.of("a door", s -> s.is(ItemTags.WOODEN_DOORS), 1, bot -> Wood.preferred(bot, "door")),
                Tasks.Need.of("chests", s -> s.is(Items.CHEST), chests, bot -> Items.CHEST),
                Tasks.Need.of("a crafting table", s -> s.is(Items.CRAFTING_TABLE), 1, bot -> Items.CRAFTING_TABLE),
                Tasks.Need.of("furnaces", s -> s.is(Items.FURNACE), Math.max(1, furnaces), bot -> Items.FURNACE),
                Tasks.Need.nice("glass", s -> s.is(Items.GLASS), glass, bot -> Items.GLASS),
                Tasks.Need.nice("torches", s -> s.is(Items.TORCH), torches, bot -> Items.TORCH)));
        if (ladders > 0) needs.add(Tasks.Need.of("ladders", s -> s.is(Items.LADDER), ladders, bot -> Items.LADDER));
        if (stairs > 0) needs.add(Tasks.Need.of("stairs", s -> s.is(ItemTags.WOODEN_STAIRS), stairs, bot -> Wood.preferred(bot, "stairs")));
        if (barrels > 0) needs.add(Tasks.Need.nice("barrels", s -> s.is(Items.BARREL), barrels, bot -> Items.BARREL));
        return new Plan(Type.HOUSE, room, f, HousePlans.keepWhatsBuilt(build, furnish), furnish, bedFoot, stash, 0, List.copyOf(needs), bp.name());
    }

    private static boolean isWall(char c) {
        return c == '#' || c == 'L' || c == 'g';
    }
}
