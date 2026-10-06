package net.joinedthegame.ai;

import net.joinedthegame.ai.task.CraftTask;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.world.item.Items;

/** Quick crafts a player does on the spot from the inventory grid. */
public final class Crafting {
    private Crafting() {}

    /** Keep a stack of torches going while there's coal around. */
    public static void craftTorchesIfUseful(BotPlayer bot) {
        int torches = Inv.count(bot, Items.TORCH);
        if (torches >= 16) return;
        if (Inv.count(bot, Items.COAL) + Inv.count(bot, Items.CHARCOAL) == 0) return;
        for (int i = 0; i < 3; i++) {
            Planner.Step step = new Planner(bot, false).next(Items.TORCH, torches + 4);
            if (!(step instanceof Planner.Craft craft) || craft.option().needsTable()) return;
            if (!CraftTask.craftNow(bot, craft)) return;
        }
    }
}
