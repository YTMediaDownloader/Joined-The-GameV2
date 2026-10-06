package net.joinedthegame.ai.project;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.BotMemory;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.Structures;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.ai.task.TravelTask;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Personal project: start a village raid on purpose, for the totems. Players do it like this:
 * drink an ominous bottle (outpost captains drop them), walk into a village, and the raid comes
 * to you. Then hold the village through the waves; evokers drop totems of undying. The fighting
 * is CombatGoal's job (it stands its ground during a raid); this keeps the bot in the village.
 */
public final class VillageRaidProject implements Task {
    private enum Stage { TRAVEL, DRINK, WAIT, DEFEND, DONE }

    private Stage stage = Stage.TRAVEL;
    private @Nullable GlobalPos village;
    private @Nullable Task sub;
    private int ticks;
    private int stageTicks;
    private boolean sawRaid;

    public static boolean ready(BotPlayer bot, BotBrain brain) {
        return Inv.count(bot, Items.OMINOUS_BOTTLE) > 0 && Inv.fullIron(bot)
                && Inv.count(bot, s -> Inv.isFood(s) && !Inv.isBadFood(s)) >= 6 && knownVillage(bot, brain) != null;
    }

    private static @Nullable GlobalPos knownVillage(BotPlayer bot, BotBrain brain) {
        return brain.memory().nearest(Structures.Kind.VILLAGE.placeKey(), GlobalPos.of(bot.level().dimension(), bot.blockPosition()), 400);
    }

    @Override
    public String activity() {
        if (this.sub != null) return this.sub.activity();
        return switch (this.stage) {
            case TRAVEL -> "Heading to a village";
            case DRINK -> "Drinking an ominous bottle";
            case WAIT -> "Waiting for the raid";
            case DEFEND -> "Defending the village";
            case DONE -> "Raid over";
        };
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (++this.ticks > 20 * 60 * 20) return Status.DONE;
        this.stageTicks++;
        if (this.sub != null) {
            Status s = this.sub.tick(bot, brain);
            if (s == Status.RUNNING) return Status.RUNNING;
            this.sub.stop(bot, brain);
            this.sub = null;
            if (s == Status.FAILED) return Status.FAILED;
        }
        if (this.village == null) {
            this.village = knownVillage(bot, brain);
            if (this.village == null) return Status.FAILED;
        }
        BlockPos center = this.village.pos();
        Raid raid = bot.level().getRaidAt(bot.blockPosition());
        switch (this.stage) {
            case TRAVEL -> {
                if (center.distSqr(bot.blockPosition()) > 20 * 20) {
                    this.sub = new TravelTask(center, 10, "Heading to a village");
                } else {
                    next(Stage.DRINK);
                }
            }
            case DRINK -> {
                // Drink it right in the village: Raid Omen turns into a raid here.
                if (!bot.isUsingItem() && Inv.count(bot, Items.OMINOUS_BOTTLE) > 0) {
                    Inv.hold(bot, s -> s.is(Items.OMINOUS_BOTTLE));
                    bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
                }
                if (Inv.count(bot, Items.OMINOUS_BOTTLE) == 0 && !bot.isUsingItem() || this.stageTicks > 20 * 10) {
                    brain.log("drank an ominous bottle in the village; here they come");
                    next(Stage.WAIT);
                }
            }
            case WAIT -> {
                // Raid Omen counts down, then the raid starts. Stay in the village.
                stayNear(bot, brain, center);
                if (raid != null) next(Stage.DEFEND);
                else if (this.stageTicks > 20 * 90) return Status.FAILED; // never came
            }
            case DEFEND -> {
                stayNear(bot, brain, center);
                if (raid != null) this.sawRaid = true;
                if (raid == null || raid.isOver()) {
                    if (this.sawRaid) {
                        brain.remember(BotMemory.EventType.VISITED, "a village raid (and won)", this.village, 1f);
                        brain.log("raid's over");
                    }
                    next(Stage.DONE);
                }
            }
            case DONE -> {
                return Status.DONE;
            }
        }
        return Status.RUNNING;
    }

    private void next(Stage stage) {
        this.stage = stage;
        this.stageTicks = 0;
    }

    /** Don't wander off: stay within shouting distance of the village centre. */
    private static void stayNear(BotPlayer bot, BotBrain brain, BlockPos center) {
        if (center.distSqr(bot.blockPosition()) < 16 * 16) return;
        Navigator nav = brain.navigator();
        if (!nav.isMoving()) {
            nav.moveNear(bot, Vec3.atCenterOf(center), 8);
            if (nav.status() == Navigator.Status.FAILED) nav.stop(bot);
        }
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        if (this.sub != null) this.sub.stop(bot, brain);
        this.sub = null;
        brain.navigator().stop(bot);
    }
}
