package net.joinedthegame.ai.task;

import java.util.function.Predicate;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.nav.Navigator;
import net.joinedthegame.ai.skill.BlockBreaker;
import net.joinedthegame.ai.skill.Containers;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Walk to a chest or barrel (somewhere it can really be reached and seen from), open it, take
 * what's wanted, close it. Marked looted either way, so it isn't gone back to. Fails if it
 * can't be got to.
 */
public final class LootChest implements Task {
    private final BlockPos pos;
    private final Predicate<ItemStack> wanted;
    private final String label;
    private int ticks;
    private int open;

    public LootChest(BlockPos pos, Predicate<ItemStack> wanted, String label) {
        this.pos = pos;
        this.wanted = wanted;
        this.label = label;
    }

    @Override
    public String activity() {
        return this.label;
    }

    @Override
    public Status tick(BotPlayer bot, BotBrain brain) {
        if (++this.ticks > 20 * 40) {
            brain.blacklist(this.pos);
            return Status.FAILED;
        }
        if (!BlockBreaker.canUse(bot, this.pos)) {
            Navigator nav = brain.navigator();
            if (!nav.isMoving()) {
                nav.moveToUse(bot, this.pos);
                if (nav.status() == Navigator.Status.FAILED) {
                    brain.blacklist(this.pos);
                    return Status.FAILED;
                }
            }
            return Status.RUNNING;
        }
        brain.navigator().stop(bot);
        brain.controls().lookAt(bot, Vec3.atCenterOf(this.pos));
        this.open++;
        if (this.open == 5 && Containers.open(bot, this.pos) == null) {
            brain.markLooted(this.pos);
            return Status.DONE;
        }
        if (this.open == 20 && bot.containerMenu instanceof net.minecraft.world.inventory.ChestMenu menu) {
            Containers.take(bot, menu.getContainer(), this.wanted);
        }
        if (this.open >= 30) {
            Containers.close(bot);
            brain.markLooted(this.pos);
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(BotPlayer bot, BotBrain brain) {
        Containers.close(bot);
    }
}
