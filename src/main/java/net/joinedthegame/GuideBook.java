package net.joinedthegame;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/** The "how this works" book every player gets the first time they join. */
public final class GuideBook {
    private GuideBook() {}

    private static final String GOT_BOOK = "joinedthegame_got_guide";

    private static final String[][] PAGES = {
            {"Joined the Game", "Your world just got less lonely.\n\nAI players can join, play survival on their own and get into all sorts of trouble.\n\nNo internet needed."},
            {"Inviting players", "Press K to open the AI player list (change the key in Controls). Click Invite next to a name and they join like anyone else.\n\nOr type /jtg invite <name>."},
            {"What they do", "They chop trees, craft tools, mine, smelt, hunt, fight monsters and slowly gear up from wood to diamond.\n\nEach one has its own habits. Some are better at it than others."},
            {"Playing together", "Mine near them and they'll start mining too. Head off exploring and some will tag along.\n\nCrouch while looking at one and it'll follow you."},
            {"Making them stop", "Bot up to no good? Punch it.\n\nIt stops whatever it was doing and turns to look at you.\n\nIt does take the hit, so go easy."},
            {"Homes", "Every bot wants a bed. It grabs any free one nearby, villagers' beds included (sorry, villagers).\n\nNo bed around? It carries or crafts one and puts it down. Only with no bed at all does it dig a hole and cover itself up for the night.\n\nBots open wooden doors, so village houses are fair game."},
            {"Memory", "Bots remember things between sessions: where they died, who they've met, villages, their stash, the house they were halfway through.\n\nEveryday spots like crafting tables fade if unused. Ops can peek with /jtg memory <name>."},
            {"Storage", "A bot sleeps and respawns at its bed.\n\nPut chests within a few blocks of its bed and it'll store its stuff in them.\n\nYou can't sleep in a bed a bot has claimed."},
            {"Settling down", "Gear comes first: iron tools, iron armour and a shield. Then a bot mixes diamond trips with a home, its own projects, or wandering off. Personality decides the odds.\n\nHomes: a village house, a hillside room, a scrappy hut or a proper cabin, with its name on a sign out front."},
            {"Nether & structures", "Bots know structures when they see them, and raid the chests: villages, mineshafts, temples, outposts, trial chambers.\n\nWith a diamond pickaxe they build a portal near home, go to the nether for quartz and blaze rods, and come back through it."},
            {"Projects", "Geared-up bots find things to do besides mining:\n\n- Build a proper cabin and move in\n- Plant a fenced wheat farm (and keep it harvested)\n- Tame a wolf with bones\n- Plant flowers and put torches round the house"},
            {"Secret stash", "Settled bots bury a chest under their floor for diamonds, emeralds and gold.\n\nThe chests by the bed get everything else they have too much of. They remember what's in them, come home to unload after a long trip, and fetch from them before going out for more."},
            {"Enchanting", "With a diamond pickaxe, bots make an enchanting table (diamonds, obsidian, a book) and set up a room for it with space for 15 bookshelves.\n\nThey enchant with 30 levels. Each has favourite enchantments; a roll it doesn't want goes through the grindstone."},
            {"Fighting", "Bots crit, step back between hits and weave toward skeletons.\n\nAfter their first iron they craft a shield and block arrows and creeper blasts.\n\nLosing? They run, or pillar up if cornered."},
            {"Dying", "A bot that dies runs back to grab its stuff, if it can make it in 5 minutes.\n\nThree deaths in a row and it gives up and starts fresh."},
            {"Admin tools", "From the list you can:\n- Teleport a bot to you\n- Open its inventory to give or take gear\n- Kick it (invite it back any time)\n- Ban it\n\nOn servers this needs operator."},
    };

    public static ItemStack create() {
        List<Filterable<Component>> pages = new ArrayList<>();
        for (String[] page : PAGES) {
            Component text = Component.literal(page[0]).withStyle(ChatFormatting.BOLD)
                    .append(Component.literal("\n\n" + page[1]).withStyle(s -> s.withBold(false)));
            pages.add(Filterable.passThrough(text));
        }
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,
                new WrittenBookContent(Filterable.passThrough("AI Players Guide"), "Joined the Game", 0, pages, true));
        return book;
    }

    public static void give(ServerPlayer player) {
        ItemStack book = create();
        if (!player.getInventory().add(book)) player.drop(book, false, net.minecraft.util.Prediction.SERVER_ONLY);
    }

    /** First join only. */
    public static void giveOnFirstJoin(ServerPlayer player) {
        if (!JtgConfig.GIVE_GUIDE_BOOK.get()) return;
        // The "persisted" sub-tag survives death, so the book isn't handed out again on respawn.
        var root = player.getPersistentData();
        var persisted = root.getCompoundOrEmpty(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG);
        if (persisted.getBooleanOr(GOT_BOOK, false)) return;
        persisted.putBoolean(GOT_BOOK, true);
        root.put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG, persisted);
        give(player);
    }
}
