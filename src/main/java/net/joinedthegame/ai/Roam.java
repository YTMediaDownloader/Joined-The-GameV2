package net.joinedthegame.ai;

import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Wandering off to look for something: head up to daylight first, then roam the surface. */
public final class Roam {
    private Roam() {}

    /** Start (or continue) a roaming leg. Call each tick while searching. */
    public static void tick(BotPlayer bot, Navigator nav) {
        if (nav.isMoving()) return;
        // Underground: find the way up first. Roaming "4 blocks over, but at the surface"
        // from a cave just fails every time, and the bot stands there doing nothing.
        if (!bot.level().canSeeSky(bot.blockPosition())) {
            int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bot.getBlockX(), bot.getBlockZ());
            nav.moveTo(bot, new Vec3(bot.getX(), y, bot.getZ()), p -> bot.level().canSeeSky(p));
            if (nav.isMoving()) return;
            nav.stop(bot);
        }
        // Try a few directions; skip any that run into unloaded terrain or out over water.
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = bot.getRandom().nextDouble() * Math.PI * 2;
            double dist = 20 + bot.getRandom().nextInt(16);
            int x = Mth.floor(bot.getX() + Math.cos(angle) * dist);
            int z = Mth.floor(bot.getZ() + Math.sin(angle) * dist);
            if (!bot.level().getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
            int y = bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos dest = new BlockPos(x, y, z);
            // Over a lake or the sea the "surface" is the water: not somewhere to go looking.
            if (!bot.level().getFluidState(dest.below()).isEmpty()) continue;
            nav.moveTo(bot, new Vec3(x + 0.5, y, z + 0.5), p -> p.getY() >= y - 2 && p.distSqr(dest) < 8 * 8);
            if (nav.isMoving()) return;
        }
        // Nowhere good found this time: at least take a few steps somewhere, then look again.
        double angle = bot.getRandom().nextDouble() * Math.PI * 2;
        nav.moveNear(bot, bot.position().add(Math.cos(angle) * 8, 0, Math.sin(angle) * 8), 3);
        if (!nav.isMoving()) nav.stop(bot);
    }
}
