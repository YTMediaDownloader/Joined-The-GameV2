package net.joinedthegame.mixin;

import java.util.List;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Bots count toward "players sleeping" like anyone else, as long as they have a bed to get
 * to (a home in this dimension, not miles away). Homeless bots are left out, so a bot with
 * nowhere to sleep can never stop you skipping the night.
 */
@Mixin(SleepStatus.class)
public class SleepStatusMixin {
    @ModifyVariable(method = "update", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> joinedthegame$ignoreBots(List<ServerPlayer> players) {
        return withoutBots(players);
    }

    @ModifyVariable(method = "areEnoughDeepSleeping", at = @At("HEAD"), argsOnly = true)
    private List<ServerPlayer> joinedthegame$ignoreBotsDeep(List<ServerPlayer> players) {
        return withoutBots(players);
    }

    private static List<ServerPlayer> withoutBots(List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            if (player instanceof BotPlayer) return players.stream().filter(p -> !(p instanceof BotPlayer bot) || canSleep(bot)).toList();
        }
        return players;
    }

    /** Has a bed it can actually get to tonight. */
    private static boolean canSleep(BotPlayer bot) {
        if (bot.isSleeping()) return true;
        var brain = bot.brain();
        if (brain == null) return false;
        var home = brain.record().home();
        return home != null && home.dimension() == bot.level().dimension()
                && home.pos().distSqr(bot.blockPosition()) < net.joinedthegame.ai.goal.SleepGoal.HOME_RANGE * net.joinedthegame.ai.goal.SleepGoal.HOME_RANGE;
    }
}
