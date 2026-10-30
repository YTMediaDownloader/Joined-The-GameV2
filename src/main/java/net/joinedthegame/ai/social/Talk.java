package net.joinedthegame.ai.social;

import java.util.List;
import net.joinedthegame.ai.Personality;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.network.chat.Component;

/**
 * The only time bots say anything: calling peace in a fight, and answering a call. A handful of
 * lines each, picked to fit the bot (a proud one's call is grudging, a gentle one's quick), shown in
 * chat like anyone's message.
 */
public final class Talk {
    private Talk() {}

    private static final List<String> CALL_GENTLE = List.of("ok ok, truce?", "stop, stop. peace?", "can we not? truce?", "alright, enough. peace?");
    private static final List<String> CALL_PROUD = List.of("enough. peace?", "fine. truce. for now.", "this is pointless. truce?", "...peace?");
    private static final List<String> ACCEPT_GENTLE = List.of("ok. peace.", "fine by me.", "alright, truce.", "ok, ok.");
    private static final List<String> ACCEPT_PROUD = List.of("fine.", "...fine. stay away from me.", "fine. don't push it.", "whatever. fine.");
    private static final List<String> REFUSE = List.of("no.", "not a chance.", "nope.", "too late for that.", "no way.");
    private static final List<String> REFUSE_PROUD = List.of("no.", "not a chance.", "you started this.", "absolutely not.");

    /** Proud: brave, reckless, not one to give in (the lines get gruffer). */
    public static boolean proud(Personality p) {
        return p.brave() * 0.4 + p.reckless() * 0.3 + (1 - Feelings.forgiving(p)) * 0.5 > 0.55;
    }

    public static void callPeace(BotPlayer bot, Personality p) {
        say(bot, pick(bot, proud(p) ? CALL_PROUD : CALL_GENTLE));
    }

    public static void accept(BotPlayer bot, Personality p) {
        say(bot, pick(bot, proud(p) ? ACCEPT_PROUD : ACCEPT_GENTLE));
    }

    public static void refuse(BotPlayer bot, Personality p) {
        say(bot, pick(bot, proud(p) ? REFUSE_PROUD : REFUSE));
    }

    private static String pick(BotPlayer bot, List<String> lines) {
        return lines.get(bot.getRandom().nextInt(lines.size()));
    }

    /** Said out loud: everyone sees it in chat, like a player's message. */
    private static void say(BotPlayer bot, String line) {
        var server = bot.level().getServer();
        if (server == null) return;
        server.getPlayerList().broadcastSystemMessage(Component.literal("<" + bot.getScoreboardName() + "> " + line), false);
        net.joinedthegame.DebugLog.write("[chat] " + bot.getScoreboardName() + ": " + line);
    }
}
