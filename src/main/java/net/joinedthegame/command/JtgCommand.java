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
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("house", "diamonds", "wander", "project", "nether", "none"), b))
                                        .executes(JtgCommand::focus))))
                .then(Commands.literal("project").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .then(Commands.argument("project", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("farm", "wolf", "decorate", "bow", "raid", "village_raid", "house"), b))
                                        .executes(JtgCommand::project))))
                .then(Commands.literal("build").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .then(Commands.argument("house", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(java.util.List.of("cottage", "stone_house", "longhouse", "two_storey", "cabin"), b))
                                        .executes(JtgCommand::build))))
                .then(Commands.literal("memory").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .executes(JtgCommand::memory)))
                .then(Commands.literal("inventory").requires(CAN_MANAGE)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(names(r -> r.state() == BotRecord.State.ACTIVE))
                                .executes(c -> report(c, BotManager.get(c.getSource().getServer())
                                        .openInventory(name(c), c.getSource().getPlayerOrException())))));
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
                c.getSource().sendFailure(Component.literal("Use farm, wolf, decorate, bow, raid or house"));
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
