package net.joinedthegame.ai.task;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Senses;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.Placer;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Going fishing, the way a player does: find some open water, stand on the bank, cast, watch the
 * bobber, and reel in the moment it ducks under. A few catches (or a few quiet minutes) and done.
 * Fish are food; now and then something better comes up on the line.
 */
public final class FishTask implements Task {
    private enum Stage { FIND, GO, CAST, WAIT }

    private Stage stage = Stage.FIND;
    private @Nullable BlockPos water;
    private int ticks;
    private int stageTicks;
    private int catches;
    private final int wanted;
    private int badCasts;

    public FishTask(int wanted) {
        this.wanted = wanted;
    }

    @Override
    public String activity() {
        return "Fishing";
    }

    /** Line in the water, waiting for a bite: standing still on purpose. */
    public boolean waitingForABite() {
        return this.stage == Stage.WAIT || this.stage == Stage.CAST;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        ++this.ticks;
        ++this.stageTicks;
        if (this.catches >= this.wanted || this.ticks > 20 * 60 * 4) return reelIn(bot, Status.DONE);
        if (Inv.count(bot, Items.FISHING_ROD) == 0) return reelIn(bot, this.catches > 0 ? Status.DONE : Status.FAILED);
        Navigator nav = brain.navigator();
        switch (this.stage) {
            case FIND -> {
                // Open water: a source block with sky over it and more water round it (not a puddle).
                this.water = Senses.findBlock(bot, s -> s.getFluidState().isSourceOfType(Fluids.WATER), 24, 8, true,
                        p -> !bot.level().canSeeSky(p.above()) || !bot.level().getBlockState(p.above()).isAir() || !openWater(bot, p));
                if (this.water == null) return Status.FAILED;
                this.stage = Stage.GO;
                this.stageTicks = 0;
            }
            case GO -> {
                BlockPos w = this.water;
                if (w == null) return Status.FAILED;
                double dist = bot.position().distanceTo(Vec3.atCenterOf(w));
                if (dist <= 5 && bot.onGround() && !bot.isInWater()) {
                    nav.stop(bot);
                    this.stage = Stage.CAST;
                    this.stageTicks = 0;
                } else if (!nav.isMoving()) {
                    nav.moveNear(bot, Vec3.atCenterOf(w), 4);
                    if (nav.status() == Navigator.Status.FAILED || this.stageTicks > 20 * 40) return Status.FAILED;
                }
            }
            case CAST -> {
                BlockPos w = this.water;
                if (w == null) return Status.FAILED;
                Inv.hold(bot, s -> s.is(Items.FISHING_ROD));
                Placer.lookExactly(bot, Vec3.atCenterOf(w).add(0, 0.6, 0));
                if (this.stageTicks < 8) return Status.RUNNING; // a moment to aim, like anyone
                if (bot.fishing == null) use(bot);
                this.stage = Stage.WAIT;
                this.stageTicks = 0;
            }
            case WAIT -> {
                FishingHook hook = bot.fishing;
                if (hook == null) {
                    this.stage = Stage.CAST;
                    this.stageTicks = 0;
                    return Status.RUNNING;
                }
                brain.controls().lookAt(bot, hook.position());
                // Landed on the bank, or caught on something: reel in and try again (elsewhere after a few).
                if (this.stageTicks > 40 && !hook.isInWater()) {
                    use(bot);
                    if (++this.badCasts > 3) {
                        this.badCasts = 0;
                        this.stage = Stage.FIND;
                    } else {
                        this.stage = Stage.CAST;
                    }
                    this.stageTicks = 0;
                    return Status.RUNNING;
                }
                if (((net.joinedthegame.mixin.FishingHookAccessor) hook).joinedthegame$biting()) {
                    use(bot); // reel it in
                    this.catches++;
                    brain.log("caught something (" + this.catches + ")");
                    this.stage = Stage.CAST;
                    this.stageTicks = -10;
                    return Status.RUNNING;
                }
                if (this.stageTicks > 20 * 50) {
                    use(bot); // nothing biting here: recast
                    this.stage = Stage.CAST;
                    this.stageTicks = 0;
                }
            }
        }
        return Status.RUNNING;
    }

    /** Water on at least five sides of the 3x3 round it: a pond or a river, not a puddle. */
    private static boolean openWater(BotPlayer bot, BlockPos p) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-1, 0, -1), p.offset(1, 0, 1))) {
            if (bot.level().getFluidState(q).is(net.minecraft.tags.FluidTags.WATER)) n++;
        }
        return n >= 6;
    }

    private static void use(BotPlayer bot) {
        if (!bot.getMainHandItem().is(Items.FISHING_ROD)) Inv.hold(bot, s -> s.is(Items.FISHING_ROD));
        bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
    }

    private static Status reelIn(BotPlayer bot, Status then) {
        if (bot.fishing != null) use(bot);
        return then;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (bot.fishing != null) use(bot);
        brain.navigator().stop(bot);
    }
}
