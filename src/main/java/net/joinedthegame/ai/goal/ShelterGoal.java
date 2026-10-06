package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.ai.task.HideTask;
import net.joinedthegame.ai.task.Task;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

/**
 * Night falls, no bed to get to, and the bot isn't geared for it? Do what every new player
 * does: dig a hole, cover it up and wait for morning. Bots in iron with a shield are tough
 * enough to keep working through the night.
 */
public final class ShelterGoal extends Goal {
    private HideTask hide = new HideTask();

    public ShelterGoal(BotBrain brain) {
        super(brain);
    }

    /** Full iron (or better) and a shield: can handle a night out. */
    public static boolean nightReady(BotPlayer bot) {
        int armor = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (Inv.armorTier(bot, slot) >= 4) armor++;
        }
        return armor >= 4 && bot.getOffhandItem().is(Items.SHIELD) && bot.getHealth() >= 14;
    }

    @Override
    public int priority(BotPlayer bot) {
        if (this.brain.inTrialChamber(bot)) return 0; // underground on a chamber run: no need to dig in
        if (!bot.level().isDarkOutside()) return 0;
        if (nightReady(bot)) return 0;
        // Underground already (caves are dark anyway); only the surface is a death trap at night.
        if (!bot.level().canSeeSky(bot.blockPosition()) && !HideTask.isHidden(bot)) return 0;
        // The reckless ones push their luck.
        if (this.brain.personality().reckless() > 0.7 && bot.getHealth() > 16) return 0;
        // Safely dug in with a pickaxe: night is mining time, like any player. Drop below
        // gearing up so it carries on digging down from the hole (it won't go back outside
        // while it's dark); if there's nothing to mine, sit tight.
        if (HideTask.isHidden(bot) && Inv.pickaxeTier(bot) >= 1) return 25;
        return 68; // well below going to bed (75), so a bot in a hole still gets up for a bed
    }

    @Override
    public void start(BotPlayer bot) {
        this.hide = new HideTask();
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (!bot.level().isDarkOutside()) return false; // morning: back to work
        Task.Status status = this.hide.tick(bot, this.brain);
        if (status == Task.Status.FAILED) {
            cooldown(this.brain.now(), 20 * 30);
            return false;
        }
        return true;
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        this.hide.stop(bot, this.brain);
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return true; // dug in for the night: waiting for morning is the point
    }

    @Override
    public String activity() {
        return this.hide.activity() + " for the night";
    }
}
