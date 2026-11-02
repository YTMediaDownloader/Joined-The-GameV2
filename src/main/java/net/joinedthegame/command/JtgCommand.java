package net.joinedthegame.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.function.Predicate;
import net.joinedthegame.GuideBook;
import net.joinedthegame.JtgConfig;
import net.joinedthegame.ai.BotBrain;
import net.joinedthegame.bot.BotManager;
import net.joinedthegame.bot.BotPlayer;
import net.joinedthegame.bot.BotRecord;
import net.joinedthegame.network.JtgNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * /jtg list | invite &lt;name&gt; | kick &lt;name&gt; | ban &lt;name&gt; | unban &lt;name&gt; |
 * tp &lt;name&gt; | inventory &lt;name&gt; | guide
 */
public final class JtgCommand {
    private JtgCommand() {}

    private static final Predicate<CommandSourceStack> CAN_MANAGE = src -> {
        ServerPlayer player = src.getPlayer();
        if (player != null) return JtgNetwork.canManage(player);
        return src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || !JtgConfig.OPS_ONLY.get();
    };

    private static SuggestionProvider<CommandSourceStack> names(Predicate<BotRecord> filter) {
        return (c, builder) -> SharedSuggestionProvider.suggest(
                BotManager.get(c.getSource().getServer()).roster().all().stream().filter(filter).map(BotRecord::name), builder);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("jtg")
                .then(Commands.literal("guide").executes(c -> {
                    GuideBook.give(c.getSource().getPlayerOrException());
                    return 1;
                }))
                .then(Commands.literal("list").requires(CAN_MANAGE).executes(JtgCommand::list))
                .then(Commands.literal("invite").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.AVAILABLE))
                                .executes(c -> report(c, BotManager.get(c.getSource().getServer())
                                        .invite(name(c), c.getSource().getPlayer())))))
                .then(Commands.literal("kick").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .executes(c -> report(c, BotManager.get(c.getSource().getServer()).kick(name(c))))))
                .then(Commands.literal("ban").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() != BotRecord.State.BANNED))
                                .executes(c -> report(c, BotManager.get(c.getSource().getServer()).ban(name(c))))))
                .then(Commands.literal("unban").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.BANNED))
                                .executes(c -> report(c, BotManager.get(c.getSource().getServer()).unban(name(c))))))
                .then(Commands.literal("tp").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .executes(c -> report(c, BotManager.get(c.getSource().getServer())
                                        .teleport(name(c), c.getSource().getPlayerOrException())))))
                .then(Commands.literal("focus").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .then(Commands.argument("focus", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("house", "diamonds", "wander", "project", "nether", "gear", "leisure", "social", "none"), b))
                                        .executes(JtgCommand::focus))))
                .then(Commands.literal("project").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .then(Commands.argument("project", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("farm", "wolf", "decorate", "bow", "raid", "village_raid", "enchant", "trial_chamber", "netherite", "pen", "trees", "cane", "garden", "paths", "house"), b))
                                        .executes(JtgCommand::project))))
                .then(Commands.literal("build").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .then(Commands.argument("house", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("cottage", "stone_house", "longhouse", "farmhouse", "bungalow", "two_storey", "lodge", "cabin"), b))
                                        .executes(JtgCommand::build))))
                .then(Commands.literal("memory").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .executes(JtgCommand::memory)))
                .then(Commands.literal("relationships").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .executes(JtgCommand::relationships)))
                .then(Commands.literal("inventory").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .executes(c -> report(c, BotManager.get(c.getSource().getServer())
                                        .openInventory(name(c), c.getSource().getPlayerOrException())))));
        // Testing jar (and dev runs) only: /jtg debug <bot>.
        if (net.joinedthegame.DebugLog.on()) {
            root.then(Commands.literal("debug").requires(CAN_MANAGE)
                    .then(Commands.argument("name", StringArgumentType.word())
                            .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                            .executes(JtgCommand::debug)
                            .then(Commands.literal("path")
                                    .then(Commands.argument("from", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                            .then(Commands.argument("to", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                                    .executes(JtgCommand::debugPath))))
                            .then(Commands.literal("home")
                                    .then(Commands.argument("at", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                            .executes(JtgCommand::debugHome)))
                            .then(Commands.literal("stash")
                                    .then(Commands.argument("at", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                            .executes(c -> {
                                                BotManager m = BotManager.get(c.getSource().getServer());
                                                BotRecord r = m.roster().get(name(c));
                                                BotPlayer b = r == null ? null : m.online(r);
                                                if (b == null) return 0;
                                                var at = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "at");
                                                // (Testing: this chest is their secret stash.)
                                                m.setHouse(r, r.house(), net.minecraft.core.GlobalPos.of(b.level().dimension(), at));
                                                c.getSource().sendSuccess(() -> Component.literal(r.name() + "'s stash is now " + at.toShortString()), false);
                                                return 1;
                                            })))
                            .then(Commands.literal("partner")
                                    .then(Commands.argument("other", StringArgumentType.word()).executes(c -> {
                                        BotManager m = BotManager.get(c.getSource().getServer());
                                        BotRecord a = m.roster().get(name(c));
                                        BotRecord b = m.roster().get(StringArgumentType.getString(c, "other"));
                                        BotPlayer pa = a == null ? null : m.online(a);
                                        BotPlayer pb = b == null ? null : m.online(b);
                                        var ba = pa == null ? null : m.brain(pa.getUUID());
                                        var bb = pb == null ? null : m.brain(pb.getUUID());
                                        if (ba == null || bb == null) return 0;
                                        long now = pa.level().getGameTime();
                                        var ra = ba.relation(pb.getUUID(), b.name(), false);
                                        var rb = bb.relation(pa.getUUID(), a.name(), false);
                                        ra.partnerSince = now - 13000; // (testing: together half a day already)
                                        rb.partnerSince = now - 13000;
                                        ra.exSince = 0;
                                        rb.exSince = 0;
                                        c.getSource().sendSuccess(() -> Component.literal(a.name() + " and " + b.name() + " are together (testing)"), false);
                                        return 1;
                                    })))
                            .then(Commands.literal("crouchedby")
                                    .then(Commands.argument("other", StringArgumentType.word()).executes(c -> {
                                        BotManager m = BotManager.get(c.getSource().getServer());
                                        BotRecord a = m.roster().get(name(c));
                                        BotPlayer pa = a == null ? null : m.online(a);
                                        var other = c.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(c, "other"));
                                        var ba = pa == null ? null : m.brain(pa.getUUID());
                                        if (ba == null || other == null) return 0;
                                        ba.onPlayerCrouched(pa, other, true);
                                        c.getSource().sendSuccess(() -> Component.literal(other.getGameProfile().name() + " crouched at " + a.name() + " (testing)"), false);
                                        return 1;
                                    })))
                            .then(Commands.literal("punchedby")
                                    .then(Commands.argument("other", StringArgumentType.word()).executes(c -> {
                                        BotManager m = BotManager.get(c.getSource().getServer());
                                        BotRecord a = m.roster().get(name(c));
                                        BotPlayer pa = a == null ? null : m.online(a);
                                        var other = c.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(c, "other"));
                                        var ba = pa == null ? null : m.brain(pa.getUUID());
                                        if (ba == null || other == null) return 0;
                                        ba.onPunched(pa, other); // (the reaction only: no damage)
                                        c.getSource().sendSuccess(() -> Component.literal(other.getGameProfile().name() + " tapped " + a.name() + " (testing)"), false);
                                        return 1;
                                    })))
                            .then(Commands.literal("spite").executes(c -> {
                                BotManager m = BotManager.get(c.getSource().getServer());
                                BotRecord rec = m.roster().get(name(c));
                                BotPlayer b = rec == null ? null : m.online(rec);
                                var br = b == null ? null : m.brain(b.getUUID());
                                if (br == null) return 0;
                                br.forceSpite();
                                c.getSource().sendSuccess(() -> Component.literal(rec.name() + " will go for the nearest pet"), false);
                                return 1;
                            }))
                            .then(Commands.literal("feel")
                                    .then(Commands.argument("other", StringArgumentType.word())
                                            .then(Commands.argument("liking", com.mojang.brigadier.arguments.IntegerArgumentType.integer(-100, 100))
                                                    .then(Commands.argument("familiarity", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                                                            .executes(JtgCommand::debugFeel)
                                                            .then(Commands.argument("attraction", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 100))
                                                                    .executes(JtgCommand::debugFeel))))))
                            .then(Commands.literal("tree")
                                    .then(Commands.argument("at", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                            .executes(JtgCommand::debugTree)))));
        }
        dispatcher.register(root);
        dispatcher.register(Commands.literal("joinedthegame").redirect(dispatcher.getRoot().getChild("jtg")));
    }

    private static String name(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "name");
    }

    private static int report(CommandContext<CommandSourceStack> c, BotManager.Result result) throws CommandSyntaxException {
        if (result.message().getString().isEmpty()) return result.ok() ? 1 : 0;
        if (result.ok()) c.getSource().sendSuccess(result::message, true);
        else c.getSource().sendFailure(result.message());
        return result.ok() ? 1 : 0;
    }

    /** What a bot remembers: its plans, the places it knows, and its latest diary entries. */
    /** Testing: set how a bot feels about someone (online), to try out what follows from it. */
    private static int debugFeel(CommandContext<CommandSourceStack> c) {
        BotManager manager = BotManager.get(c.getSource().getServer());
        BotRecord record = manager.roster().get(name(c));
        BotPlayer bot = record == null ? null : manager.online(record);
        var other = c.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(c, "other"));
        var brain = bot == null ? null : manager.brain(bot.getUUID());
        if (bot == null || brain == null || other == null) {
            c.getSource().sendFailure(Component.literal("Both need to be online"));
            return 0;
        }
        var r = brain.relation(other.getUUID(), other.getGameProfile().name(), !(other instanceof BotPlayer));
        r.liking = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "liking");
        r.familiarity = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "familiarity");
        r.truceUntil = 0;
        r.rivalSince = 0;
        r.lasting = r.liking * 0.3; // a plausible history to go with it (not every old test's)
        try {
            r.attraction = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "attraction");
        } catch (IllegalArgumentException ignored) {
            // not given
        }
        r.trust = Math.clamp(50 + r.liking * 0.4, 5, 100);
        if (r.liking <= -60) brain.becomeRivals(r);
        brain.forgetEncounters();
        String text = record.name() + " now feels " + r.status() + " about " + r.name;
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** Testing: would bots chop that log (a tree), or is it part of something built, and why. */
    private static int debugTree(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var at = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "at");
        String text = at.toShortString() + ": " + net.joinedthegame.ai.Trees.explain(c.getSource().getLevel(), at);
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** Testing: make that spot this bot's home (as if its bed were there). */
    private static int debugHome(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        BotManager manager = BotManager.get(c.getSource().getServer());
        BotRecord record = manager.roster().get(name(c));
        BotPlayer bot = record == null ? null : manager.online(record);
        if (bot == null) {
            c.getSource().sendFailure(Component.translatable("joinedthegame.msg.not_here", name(c)));
            return 0;
        }
        var at = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "at");
        manager.setHome(record, net.minecraft.core.GlobalPos.of(bot.level().dimension(), at));
        // (Testing: and the house round it is theirs, a proper one.)
        manager.setHouse(record, net.minecraft.core.GlobalPos.of(bot.level().dimension(), at), null);
        bot.getData(net.joinedthegame.JtgAttachments.MEMORY).homeType = "HOUSE";
        c.getSource().sendSuccess(() -> Component.literal(record.name() + "'s home is now " + at.toShortString()), false);
        return 1;
    }

    /**
     * The route this bot would plan from one block to another, every step of it (testing the
     * pathfinder: what it digs, places and why it goes the way it does).
     */
    private static int debugPath(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        BotManager manager = BotManager.get(c.getSource().getServer());
        BotRecord record = manager.roster().get(name(c));
        BotPlayer bot = record == null ? null : manager.online(record);
        if (bot == null) {
            c.getSource().sendFailure(Component.translatable("joinedthegame.msg.not_here", name(c)));
            return 0;
        }
        var from = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "from");
        var to = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(c, "to");
        var finder = new net.joinedthegame.ai.nav.Pathfinder(bot, net.joinedthegame.ai.nav.Pathfinder.Options.normal(bot));
        var path = finder.find(from, net.minecraft.world.phys.Vec3.atBottomCenterOf(to), to::equals);
        StringBuilder out = new StringBuilder(path == null ? "no path" : (path.complete() ? "path" : "partial path") + " (" + path.nodes().size() + "):");
        if (path != null) {
            for (var n : path.nodes()) {
                out.append("\n ").append(n.move()).append(' ').append(n.pos().toShortString());
                if (!n.breaks().isEmpty()) out.append(" break ").append(n.breaks().size());
                if (n.place() != null) out.append(" place ").append(n.place().toShortString());
            }
        }
        String text = out.toString();
        c.getSource().sendSuccess(() -> Component.literal(text), false);
        net.joinedthegame.DebugLog.write("[path] " + record.name() + " " + from.toShortString() + " -> " + to.toShortString() + ": " + text.replace("\n", " |"));
        return 1;
    }

    /** What a bot's thinking right now: its goal and every goal's score, big goal, path, health. */
    private static int debug(CommandContext<CommandSourceStack> c) {
        BotManager manager = BotManager.get(c.getSource().getServer());
        BotRecord record = manager.roster().get(name(c));
        BotPlayer bot = record == null ? null : manager.online(record);
        BotBrain brain = record == null ? null : manager.brain(record.uuid());
        if (bot == null || brain == null) {
            c.getSource().sendFailure(Component.translatable("joinedthegame.msg.not_here", name(c)));
            return 0;
        }
        var mem = brain.memory();
        MutableComponent out = Component.literal(record.name() + " (debug)").withStyle(ChatFormatting.GOLD);
        var goal = brain.currentGoal();
        out.append(Component.literal("\n Doing: " + brain.activity() + "  [" + (goal == null ? "no goal" : goal.getClass().getSimpleName().replace("Goal", ""))
                + " " + brain.currentPriority() + "]").withStyle(ChatFormatting.WHITE));
        out.append(Component.literal("\n Big goal: " + brain.focusNow().name().toLowerCase(java.util.Locale.ROOT)
                + (mem.project.isEmpty() ? "" : " (project " + mem.project + ")")
                + (mem.unfinished.isEmpty() ? "" : "  unfinished: " + String.join(", ", mem.unfinished))).withStyle(ChatFormatting.WHITE));
        StringBuilder scores = new StringBuilder();
        brain.lastScores().entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> scores.append(e.getKey()).append(' ').append(e.getValue()).append("  "));
        out.append(Component.literal("\n Scores: " + (scores.isEmpty() ? "(all 0)" : scores.toString().trim())).withStyle(ChatFormatting.GRAY));
        out.append(Component.literal(String.format("\n %s at %s | hp %.0f food %d%s",
                bot.level().dimension().identifier().getPath(), bot.blockPosition().toShortString(), bot.getHealth(),
                bot.getFoodData().getFoodLevel(), bot.isUnderWater() ? " air " + bot.getAirSupply() : "")).withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n Path: " + brain.navigator().debugString()).withStyle(ChatFormatting.DARK_GRAY));
        out.append(Component.literal("\n Needs: " + brain.needs().describe(brain.personality())).withStyle(ChatFormatting.GRAY));
        var partnerId = brain.partner();
        var partnerRel = partnerId == null ? null : brain.knownRelation(partnerId);
        out.append(Component.literal("\n Who: " + brain.identity().describe() + (partnerRel == null ? "" : " | with " + partnerRel.name))
                .withStyle(ChatFormatting.GRAY));
        out.append(Component.literal("\n Personality: " + brain.personality()).withStyle(ChatFormatting.DARK_GRAY));
        out.append(Component.literal("\n Debug log: logs/joinedthegame-debug.log").withStyle(ChatFormatting.DARK_GRAY));
        c.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    /** How a bot feels about everyone it knows (bots and players), the best known first. */
    private static int relationships(CommandContext<CommandSourceStack> c) {
        BotManager manager = BotManager.get(c.getSource().getServer());
        BotRecord record = manager.roster().get(name(c));
        BotPlayer bot = record == null ? null : manager.online(record);
        if (bot == null) {
            c.getSource().sendFailure(Component.translatable("joinedthegame.msg.not_here", name(c)));
            return 0;
        }
        var mem = bot.getData(net.joinedthegame.JtgAttachments.MEMORY);
        var all = new java.util.ArrayList<>(mem.relations.values());
        all.sort((a, b) -> Double.compare(b.familiarity + Math.abs(b.liking), a.familiarity + Math.abs(a.liking)));
        MutableComponent out = Component.literal(record.name() + " knows " + all.size() + (all.size() == 1 ? " person" : " people"))
                .withStyle(ChatFormatting.GOLD);
        for (int i = 0; i < Math.min(20, all.size()); i++) {
            var r = all.get(i);
            String knows = r.familiarity < 8 ? "barely" : r.familiarity < 25 ? "a little" : r.familiarity < 60 ? "fairly well" : "well";
            ChatFormatting colour = r.liking >= 30 ? ChatFormatting.GREEN : r.liking >= 10 ? ChatFormatting.DARK_GREEN
                    : r.liking > -10 ? ChatFormatting.GRAY : r.liking > -70 ? ChatFormatting.RED : ChatFormatting.DARK_RED;
            out.append(Component.literal("\n " + r.name + (r.player ? " (player)" : "")).withStyle(colour))
                    .append(Component.literal(String.format(java.util.Locale.ROOT, " - %s | liking %+.0f (lasting %+.0f), trust %.0f, knows them %s",
                            r.status(), r.liking, r.lasting, r.trust, knows)).withStyle(ChatFormatting.GRAY));
        }
        c.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    private static int memory(CommandContext<CommandSourceStack> c) {
        BotManager manager = BotManager.get(c.getSource().getServer());
        BotRecord record = manager.roster().get(name(c));
        BotPlayer bot = record == null ? null : manager.online(record);
        if (bot == null) {
            c.getSource().sendFailure(Component.translatable("joinedthegame.msg.not_here", name(c)));
            return 0;
        }
        var mem = bot.getData(net.joinedthegame.JtgAttachments.MEMORY);
        MutableComponent out = Component.literal(record.name() + "'s memory").withStyle(ChatFormatting.GOLD);
        out.append(Component.literal("\n Focus: " + mem.focus.toLowerCase(java.util.Locale.ROOT)
                + (mem.project.isEmpty() ? "" : " (" + mem.project + ")")
                + (mem.homeType.isEmpty() ? "" : " | lives in a " + mem.homeType.toLowerCase(java.util.Locale.ROOT)))
                .withStyle(ChatFormatting.GRAY));
        if (mem.housePlan != null) {
            out.append(Component.literal("\n Working on: " + mem.housePlan.type().toLowerCase(java.util.Locale.ROOT)
                    + " at " + mem.housePlan.room().toShortString() + " (" + mem.housePlan.stage().toLowerCase(java.util.Locale.ROOT) + ")")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (mem.deathSpot != null) {
            out.append(Component.literal("\n Stuff dropped at: " + mem.deathSpot.pos().toShortString()).withStyle(ChatFormatting.GRAY));
        }
        if (!mem.stored.isEmpty()) {
            // The most plentiful things in the chests at home, as the bot remembers them.
            StringBuilder stored = new StringBuilder();
            mem.stored.entrySet().stream()
                    .sorted((x, y) -> Integer.compare(y.getValue(), x.getValue()))
                    .limit(8)
                    .forEach(e -> {
                        if (!stored.isEmpty()) stored.append(", ");
                        stored.append(e.getValue()).append(" ").append(e.getKey().replace("minecraft:", "").replace('_', ' '));
                    });
            if (mem.stored.size() > 8) stored.append(", ...");
            out.append(Component.literal("\n Stored at home: " + stored).withStyle(ChatFormatting.GRAY));
        }
        if (!mem.places().isEmpty()) {
            StringBuilder places = new StringBuilder();
            mem.places().forEach((kind, list) -> {
                if (!places.isEmpty()) places.append(", ");
                places.append(kind.replace("ore:", "").replace('_', ' ')).append(" x").append(list.size());
                if (net.joinedthegame.ai.BotMemory.isSticky(kind)) places.append("*");
            });
            out.append(Component.literal("\n Knows: " + places).withStyle(ChatFormatting.GRAY));
        }
        var events = mem.events();
        out.append(Component.literal("\n Diary (" + events.size() + "):").withStyle(ChatFormatting.YELLOW));
        for (int i = Math.max(0, events.size() - 10); i < events.size(); i++) {
            out.append(Component.literal("\n  - " + events.get(i).describe()).withStyle(ChatFormatting.WHITE));
        }
        c.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    /** Have a bot build a particular kind of house next (near its home), and move in. */
    private static int build(CommandContext<CommandSourceStack> c) {
        BotManager manager = BotManager.get(c.getSource().getServer());
        BotRecord record = manager.roster().get(name(c));
        BotPlayer bot = record == null ? null : manager.online(record);
        BotBrain brain = record == null ? null : manager.brain(record.uuid());
        if (bot == null || brain == null) {
            c.getSource().sendFailure(Component.translatable("joinedthegame.msg.not_here", name(c)));
            return 0;
        }
        String which = StringArgumentType.getString(c, "house").toLowerCase(java.util.Locale.ROOT);
        if (!which.equals("cabin") && net.joinedthegame.ai.Blueprints.byName(which) == null) {
            c.getSource().sendFailure(Component.literal("Use cottage, stone_house, longhouse, two_storey or cabin"));
            return 0;
        }
        brain.buildHouse(bot, which);
        c.getSource().sendSuccess(() -> Component.literal(record.name() + " is going to build: " + which), true);
        return 1;
    }

    /** Start a personal project right away: farm, wolf, decorate, or (re)build a house. */
    private static int project(CommandContext<CommandSourceStack> c) {
        BotManager manager = BotManager.get(c.getSource().getServer());
        BotRecord record = manager.roster().get(name(c));
        BotPlayer bot = record == null ? null : manager.online(record);
        BotBrain brain = record == null ? null : manager.brain(record.uuid());
        if (bot == null || brain == null) {
            c.getSource().sendFailure(Component.translatable("joinedthegame.msg.not_here", name(c)));
            return 0;
        }
        String which = StringArgumentType.getString(c, "project").toLowerCase(java.util.Locale.ROOT);
        long now = bot.level().getGameTime();
        if (which.equals("house")) {
            brain.setFocus(BotBrain.Focus.HOUSE, now);
        } else {
            net.joinedthegame.ai.goal.ProjectGoal.Kind kind;
            try {
                kind = net.joinedthegame.ai.goal.ProjectGoal.Kind.valueOf(which.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                c.getSource().sendFailure(Component.literal("Use farm, wolf, decorate, bow, raid, enchant, trial_chamber, netherite, pen, trees, cane, garden, paths, brew or house"));
                return 0;
            }
            brain.projects().force(kind, now);
        }
        c.getSource().sendSuccess(() -> Component.literal(record.name() + " is starting a project: " + which), true);
        return 1;
    }

    /** Nudge a bot's big-picture goal: settle down, go for diamonds, wander, or let it decide. */
    private static int focus(CommandContext<CommandSourceStack> c) {
        BotManager manager = BotManager.get(c.getSource().getServer());
        BotRecord record = manager.roster().get(name(c));
        BotPlayer bot = record == null ? null : manager.online(record);
        BotBrain brain = record == null ? null : manager.brain(record.uuid());
        if (bot == null || brain == null) {
            c.getSource().sendFailure(Component.translatable("joinedthegame.msg.not_here", name(c)));
            return 0;
        }
        BotBrain.Focus focus;
        try {
            focus = BotBrain.Focus.valueOf(StringArgumentType.getString(c, "focus").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal("Use house, diamonds, wander, project, nether or none"));
            return 0;
        }
        brain.setFocus(focus, bot.level().getGameTime());
        c.getSource().sendSuccess(() -> Component.literal(record.name() + " is now focused on: " + focus.name().toLowerCase(java.util.Locale.ROOT)), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        BotManager manager = BotManager.get(c.getSource().getServer());
        MutableComponent out = Component.translatable("joinedthegame.msg.list_header", manager.onlineCount());
        for (BotRecord record : manager.roster().all()) {
            BotPlayer bot = manager.online(record);
            if (bot != null) {
                BotBrain brain = manager.brain(record.uuid());
                out.append(Component.literal("\n " + record.name()).withStyle(ChatFormatting.GREEN))
                        .append(Component.literal(" - " + (brain == null ? "Idle" : brain.activity())
                                + String.format(" (%.0f hp)", bot.getHealth())).withStyle(ChatFormatting.GRAY));
            } else if (record.state() == BotRecord.State.BANNED) {
                out.append(Component.literal("\n " + record.name() + " (banned)").withStyle(ChatFormatting.DARK_RED));
            }
        }
        c.getSource().sendSuccess(() -> out, false);
        return manager.onlineCount();
    }
}
