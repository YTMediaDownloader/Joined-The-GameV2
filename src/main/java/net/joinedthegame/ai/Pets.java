package net.joinedthegame.ai;

import java.util.List;
import net.joinedthegame.ai.nav.WorldView;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeping animals out from under our feet. A dog likes to stand exactly where the next block
 * goes, or right in the tunnel we're digging; a player would shove it out of the way. Our own
 * pets hop a few blocks off (like wolves do anyway to keep up with their owner); anyone else's
 * animal gets a nudge.
 */
public final class Pets {
    private Pets() {}

    private static boolean ours(BotPlayer bot, Entity e) {
        return e instanceof TamableAnimal t && t.isOwnedBy(bot);
    }

    /** Sit down (like an owner right-clicking it). */
    public static void sit(TamableAnimal pet) {
        pet.setOrderedToSit(true);
        pet.setInSittingPose(true);
        pet.getNavigation().stop();
        pet.setJumping(false);
        pet.setTarget(null);
    }

    /** Move a pet to {@code spot} and leave it sitting there. */
    private static void moveAndSit(Entity e, BlockPos spot) {
        e.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        if (e instanceof TamableAnimal t) sit(t);
    }

    /**
     * Where a dog sits at home: out in the yard, 7 to 14 blocks from the bed, under the open
     * sky (so not in the house), away from the enchanting room, and not on top of another dog.
     * Out of the way of anything being built.
     */
    public static @Nullable BlockPos yardSpot(BotPlayer bot, BotBrain brain) {
        var home = brain.record().home();
        if (home == null || home.dimension() != bot.level().dimension() || !bot.level().isLoaded(home.pos())) return null;
        BlockPos bed = home.pos();
        WorldView w = new WorldView(bot.level());
        var table = brain.memory().nearest("enchanting_table", home, 32);
        List<Entity> sitting = bot.level().getEntities(bot, new AABB(bed).inflate(20),
                e -> e instanceof TamableAnimal t && t.isOwnedBy(bot) && t.isOrderedToSit());
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(bed.offset(-14, -4, -14), bed.offset(14, 4, 14))) {
            double d = Math.sqrt(p.distSqr(bed));
            if (d < 7 || d > 14 || !w.standable(p) || !bot.level().canSeeSky(p) || w.isWater(p)) continue;
            if (table != null && p.distSqr(table.pos()) < 6 * 6) continue;
            boolean crowded = false;
            for (Entity e : sitting) if (e.blockPosition().distSqr(p) < 3 * 3) crowded = true;
            if (crowded) continue;
            // A little further out is better (clear of the house and its yard), level with home.
            double score = Math.abs(d - 10) + Math.abs(p.getY() - bed.getY()) * 2;
            if (score < bestScore) {
                bestScore = score;
                best = p.immutable();
            }
        }
        return best;
    }

    /** Take a pet home and sit it in the yard. False if there's nowhere (no home loaded). */
    public static boolean sitAtHome(BotPlayer bot, BotBrain brain, TamableAnimal pet) {
        BlockPos spot = yardSpot(bot, brain);
        if (spot == null) return false;
        moveAndSit(pet, spot);
        return true;
    }

    /** Moved house: bring every pet we can reach over to the new yard. */
    public static void moveHouse(BotPlayer bot, BotBrain brain) {
        for (Entity e : bot.level().getEntities(bot, bot.getBoundingBox().inflate(64), e -> e.isAlive() && ours(bot, e))) {
            sitAtHome(bot, brain, (TamableAnimal) e);
        }
    }

    /** Clear animals out of this cell so a block can go in. Returns true if anything moved. */
    public static boolean shooFrom(BotPlayer bot, BlockPos cell) {
        BotBrain brain = bot.brain();
        List<Entity> inTheWay = bot.level().getEntities(bot, new AABB(cell), e -> e.isAlive() && e instanceof Animal);
        boolean moved = false;
        for (Entity e : inTheWay) {
            if (ours(bot, e)) {
                moved = outOfTheWay(bot, brain, e, cell);
            } else {
                // Someone else's: a shove away from the spot, the way a player pushes past.
                Vec3 away = e.position().subtract(Vec3.atCenterOf(cell)).multiply(1, 0, 1);
                if (away.lengthSqr() < 1e-3) away = new Vec3(bot.getRandom().nextDouble() - 0.5, 0, bot.getRandom().nextDouble() - 0.5);
                e.push(away.normalize().scale(0.6).add(0, 0.2, 0));
                moved = true;
            }
        }
        return moved;
    }

    /**
     * Our dogs crowding us while we dig or build (in a tunnel especially, where they plug the
     * way): send them a few blocks back.
     */
    public static void giveUsRoom(BotPlayer bot) {
        BotBrain brain = bot.brain();
        for (Entity e : bot.level().getEntities(bot, bot.getBoundingBox().inflate(1.2), e -> e.isAlive() && ours(bot, e))) {
            outOfTheWay(bot, brain, e, bot.blockPosition());
        }
    }

    /**
     * Our pet's in the way: home to the yard if home's about (that's where it lives now);
     * otherwise just a few blocks aside, still following, so it isn't left sat down a mine.
     */
    private static boolean outOfTheWay(BotPlayer bot, @Nullable BotBrain brain, Entity e, BlockPos from) {
        if (brain != null && e instanceof TamableAnimal t && sitAtHome(bot, brain, t)) return true;
        BlockPos spot = freeSpotAway(bot, from);
        if (spot == null) return false;
        e.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        return true;
    }

    /** Somewhere 2-4 blocks from {@code from}, behind us, that an animal can stand. */
    private static @Nullable BlockPos freeSpotAway(BotPlayer bot, BlockPos from) {
        WorldView w = new WorldView(bot.level());
        Vec3 look = bot.getLookAngle().multiply(1, 0, 1);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(from.offset(-4, -2, -4), from.offset(4, 2, 4))) {
            double d = Math.sqrt(p.distSqr(from));
            if (d < 2 || d > 4.5 || !w.standable(p)) continue;
            // Behind us is best (out of the way of where we're working).
            Vec3 to = Vec3.atCenterOf(p).subtract(bot.position()).multiply(1, 0, 1);
            double ahead = look.lengthSqr() < 1e-3 || to.lengthSqr() < 1e-3 ? 0 : look.normalize().dot(to.normalize());
            double score = ahead * 3 + Math.abs(p.getY() - bot.getBlockY());
            if (score < bestScore) {
                bestScore = score;
                best = p.immutable();
            }
        }
        return best;
    }
}
