package net.joinedthegame.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.jspecify.annotations.Nullable;

/**
 * What the bots know about structures: which one they're looking at, whether it's worth
 * raiding, and what to watch out for. Read from the game's real structure data, but only for
 * chunks around the bot, so a bot "knows" a structure once it's actually near it.
 */
public final class Structures {
    private Structures() {}

    /** Kinds of structure, and how a player treats each. */
    public enum Kind {
        VILLAGE(true, 0.2, "village"),
        MINESHAFT(true, 0.5, "mineshaft"),
        DESERT_TEMPLE(true, 0.3, "desert temple"),
        JUNGLE_TEMPLE(true, 0.3, "jungle temple"),
        IGLOO(true, 0.1, "igloo"),
        SHIPWRECK(true, 0.2, "shipwreck"),
        RUINED_PORTAL(true, 0.1, "ruined portal"),
        OCEAN_RUIN(true, 0.4, "ocean ruin"),
        PILLAGER_OUTPOST(true, 0.7, "pillager outpost"),
        MANSION(true, 0.9, "woodland mansion"),
        STRONGHOLD(true, 0.7, "stronghold"),
        TRIAL_CHAMBERS(true, 0.7, "trial chambers"),
        ANCIENT_CITY(true, 1.0, "ancient city"),
        TRAIL_RUINS(false, 0.1, "trail ruins"),
        FORTRESS(true, 0.8, "nether fortress"),
        BASTION(true, 0.9, "bastion"),
        OTHER(false, 0.3, "structure");

        /** Has chests (or other loot) worth going in for. */
        public final boolean loot;
        /** Rough danger, 0..1: an igloo is nothing, an ancient city is the deep dark. */
        public final double danger;
        public final String label;

        Kind(boolean loot, double danger, String label) {
            this.loot = loot;
            this.danger = danger;
            this.label = label;
        }

        /** The memory place key for this kind ("structure:fortress"). */
        public String placeKey() {
            return "structure:" + name().toLowerCase(java.util.Locale.ROOT);
        }

        static Kind of(Identifier id) {
            String p = id.getPath();
            if (p.startsWith("village")) return VILLAGE;
            if (p.startsWith("mineshaft")) return MINESHAFT;
            if (p.equals("desert_pyramid")) return DESERT_TEMPLE;
            if (p.equals("jungle_pyramid")) return JUNGLE_TEMPLE;
            if (p.equals("igloo")) return IGLOO;
            if (p.startsWith("shipwreck")) return SHIPWRECK;
            if (p.startsWith("ruined_portal")) return RUINED_PORTAL;
            if (p.startsWith("ocean_ruin")) return OCEAN_RUIN;
            if (p.equals("pillager_outpost")) return PILLAGER_OUTPOST;
            if (p.equals("mansion")) return MANSION;
            if (p.equals("stronghold")) return STRONGHOLD;
            if (p.equals("trial_chambers")) return TRIAL_CHAMBERS;
            if (p.equals("ancient_city")) return ANCIENT_CITY;
            if (p.equals("trail_ruins")) return TRAIL_RUINS;
            if (p.equals("fortress")) return FORTRESS;
            if (p.equals("bastion_remnant")) return BASTION;
            return OTHER;
        }
    }

    /**
     * A structure the bot knows about. {@code box} covers the whole thing. {@code variant} is
     * which sort it is where that matters (a bastion: "treasure", "bridge", "units" or
     * "hoglin stable"), otherwise empty.
     */
    public record Known(Kind kind, Identifier id, BoundingBox box, String variant) {
        public String describe() {
            return this.variant.isEmpty() ? this.kind.label : this.kind.label + " (" + this.variant + ")";
        }

        public BlockPos center() {
            return box.getCenter();
        }

        public boolean contains(BlockPos pos, int margin) {
            return pos.getX() >= box.minX() - margin && pos.getX() <= box.maxX() + margin
                    && pos.getY() >= box.minY() - margin && pos.getY() <= box.maxY() + margin
                    && pos.getZ() >= box.minZ() - margin && pos.getZ() <= box.maxZ() + margin;
        }
    }

    /** Structures whose pieces reach into the loaded chunks within {@code chunkRadius} of the bot. */
    /**
     * Inside somewhere no one sensible lives: a trial chamber, a mansion, an outpost, a fortress, an
     * ancient city (anything rated half-dangerous or worse; villages are fine). Its beds aren't a
     * home and its chests aren't storage. Redlantern moved into a trial chamber's bedroom, between
     * the spawners, and filled its loot chest with his things.
     */
    public static boolean dangerousAt(BotPlayer bot, net.minecraft.core.BlockPos pos) {
        for (Known k : near(bot, 3)) {
            if (k.kind().danger >= 0.5 && k.box().isInside(pos)) return true;
        }
        return false;
    }

    /** Inside a trial chamber's bounds (whatever else overlaps the spot). */
    public static boolean inTrialChamber(BotPlayer bot, net.minecraft.core.BlockPos pos) {
        for (Known k : near(bot, 3)) {
            if (k.kind() == Kind.TRIAL_CHAMBERS && k.box().isInside(pos)) return true;
        }
        return false;
    }

    public static List<Known> near(BotPlayer bot, int chunkRadius) {
        ServerLevel level = bot.level();
        ChunkPos center = bot.chunkPosition();
        List<Known> out = new ArrayList<>();
        Set<StructureStart> seen = new HashSet<>();
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(center.x() + dx, center.z() + dz);
                if (chunk == null) continue;
                for (var ref : chunk.getAllReferences().entrySet()) {
                    Structure structure = ref.getKey();
                    for (long startChunk : ref.getValue()) {
                        LevelChunk startAt = level.getChunkSource().getChunkNow(ChunkPos.getX(startChunk), ChunkPos.getZ(startChunk));
                        if (startAt == null) continue;
                        StructureStart start = startAt.getStartForStructure(structure);
                        if (start == null || !start.isValid() || !seen.add(start)) continue;
                        Identifier id = registry.getKey(structure);
                        if (id == null) continue;
                        Kind kind = Kind.of(id);
                        out.add(new Known(kind, id, start.getBoundingBox(), kind == Kind.BASTION ? bastionVariant(start) : ""));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Which of the four bastions this is, from the pieces it was built from (26.3 BastionPieces:
     * bastion/treasure, bastion/bridge, bastion/units, bastion/hoglin_stable). The treasure one
     * always has a netherite upgrade template in its chest; the others only sometimes.
     */
    private static String bastionVariant(StructureStart start) {
        for (var piece : start.getPieces()) {
            if (!(piece instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece p)) continue;
            String e = p.getElement().toString();
            if (e.contains("bastion/treasure")) return "treasure";
            if (e.contains("bastion/hoglin_stable")) return "hoglin stable";
            if (e.contains("bastion/bridge")) return "bridge";
            if (e.contains("bastion/units")) return "units";
        }
        return "";
    }

    /** Memory key for treasure bastions (the sure place for a netherite upgrade template). */
    public static final String TREASURE_BASTION = "structure:bastion_treasure";

    /** The structure (of any kind) the bot is standing in, or null. */
    public static @Nullable Known inside(BotPlayer bot) {
        for (Known k : near(bot, 1)) if (k.contains(bot.blockPosition(), 2)) return k;
        return null;
    }
}
