package net.joinedthegame.ai.goal;

import net.joinedthegame.activity.PlayerActivity;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** A player is heading off exploring? The sociable bots come along. */
public final class TagAlongGoal extends Goal {
    private @Nullable ServerPlayer leader;
    private int ticks;

    public TagAlongGoal(BotBrain brain) {
        super(brain);
    }

    @Override
    public int priority(BotPlayer bot) {
        ServerPlayer explorer = PlayerActivity.exploringPlayerNear(bot, 40);
        if (explorer == null) return 0;
        // Not everyone comes. Roll per bot, stable for a while.
        double social = this.brain.personality().social();
        if (social < 0.35) return 0;
        this.leader = explorer;
        return (int) (25 + social * 25);
    }

    @Override
    public void start(BotPlayer bot) {
        this.ticks = 0;
    }

    @Override
    public boolean tick(BotPlayer bot) {
        ServerPlayer leader = this.leader;
        if (leader == null || !leader.isAlive() || leader.level() != bot.level()) return false;
        if (++this.ticks > 20 * 45 && !PlayerActivity.isExploring(leader)) return false;
        if (this.ticks > 20 * 120) return false;
        return FollowGoal.followStep(bot, this.brain, leader, 6.0, this);
    }

    @Override
    public boolean waitingOnPurpose(BotPlayer bot) {
        return true;
    }

    @Override
    public String activity() {
        return "Exploring with you";
    }
}
