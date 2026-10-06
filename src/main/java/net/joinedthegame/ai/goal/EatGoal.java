package net.joinedthegame.ai.goal;

import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.ai.Inv;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.InteractionHand;

/** Eat when hungry, the normal way: hold the food and keep right-click held down. */
public final class EatGoal extends Goal {
    private int ticks;

    public EatGoal(BotBrain brain) {
        super(brain);
    }

    private boolean desperate(BotPlayer bot) {
        return bot.getFoodData().getFoodLevel() <= 6;
    }

    @Override
    public int priority(BotPlayer bot) {
        int food = bot.getFoodData().getFoodLevel();
        if (food >= 20) return 0;
        // Health only regenerates at 18+ food, so a hurt bot tops up rather than waiting to
        // get hungry. Otherwise it walks into the next fight still hurt from the last one.
        boolean hungry = food <= 14 || (bot.getHealth() < bot.getMaxHealth() && food < 18)
                || (bot.getHealth() < bot.getMaxHealth() * 0.6f && food < 20);
        if (!hungry) return 0;
        return Inv.bestFood(bot, desperate(bot)) >= 0 ? 85 : 0;
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
        this.brain.navigator().stop(bot);
    }

    @Override
    public boolean tick(BotPlayer bot) {
        if (++this.ticks > 80) return false;
        if (bot.isUsingItem()) return true;
        if (this.ticks > 5 && bot.getFoodData().getFoodLevel() >= 20) return false;
        int slot = Inv.bestFood(bot, desperate(bot));
        if (slot < 0) return false;
        Inv.hold(bot, slot);
        bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
        return bot.isUsingItem();
    }

    @Override
    public void stop(BotPlayer bot) {
        super.stop(bot);
        if (bot.isUsingItem()) bot.stopUsingItem();
    }

    @Override
    public String activity() {
        return "Eating";
    }
}
