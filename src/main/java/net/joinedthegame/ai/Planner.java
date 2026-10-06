package net.joinedthegame.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * Works backwards from "I want X" to the next concrete thing to do.
 *
 * <p>Example: want an iron pickaxe → need 3 iron ingots and 2 sticks → ingots come from
 * smelting raw iron → raw iron needs a stone pickaxe and a trip underground → ... The
 * answer is just the first step in that chain. The bot does it, then asks again, so the
 * plan adapts to whatever it actually found along the way.
 */
public final class Planner {
    public sealed interface Step permits Mine, Craft, Smelt, Fetch, Fail {
        String label();
    }

    public record Mine(Sources source, Item wanted, int count, String label) implements Step {}

    /** {@code grid} holds the item chosen for each recipe slot (null for empty slots). */
    public record Craft(Recipes.CraftOption option, List<@Nullable Item> grid, int times, String label) implements Step {}

    public record Smelt(Item input, int count, Item output, String label) implements Step {}

    public record Fail(String label) implements Step {}

    /** It's in the chests at home: go and get it rather than make more. */
    public record Fetch(Item item, int count, String label) implements Step {}

    private final BotPlayer bot;
    private final MinecraftServer server;
    private final boolean tableNearby;
    private final boolean furnaceNearby;

    public Planner(BotPlayer bot) {
        this(bot, true);
    }

    /** @param scan look around for tables and furnaces (skip for quick inventory-only checks) */
    public Planner(BotPlayer bot, boolean scan) {
        this.bot = bot;
        this.server = bot.level().getServer();
        this.tableNearby = scan && Senses.findBlock(bot, s -> s.is(Blocks.CRAFTING_TABLE), 24, 8, false, p -> false) != null;
        this.furnaceNearby = scan && Senses.findBlock(bot, s -> s.is(Blocks.FURNACE), 24, 8, false, p -> false) != null;
        if (scan && bot.brain() != null) Storage.refreshIfHome(bot, bot.brain());
    }

    /** Next step toward having {@code count} of {@code item}, or null if we already do. */
    public @Nullable Step next(Item item, int count) {
        return resolve(item, count, 0, new HashSet<>());
    }

    private int have(Item item) {
        return Inv.count(this.bot, item);
    }

    private @Nullable Step resolve(Item item, int count, int depth, Set<Item> visiting) {
        int have = have(item);
        if (have >= count) return null;
        if (depth > 10 || !visiting.add(item)) return new Fail("can't work out how to get " + name(item));
        int missing = count - have;

        // Got some put away at home? Use that first.
        BotBrain brain = this.bot.brain();
        if (brain != null) {
            int stored = Storage.stored(brain, item);
            if (stored > 0 && Storage.worthFetching(this.bot, brain, item)) {
                return new Fetch(item, Math.min(missing, stored), "Getting " + name(item) + " from my chests");
            }
        }

        Sources source = Sources.forItem(item);
        if (source != null) {
            if (Inv.pickaxeTier(this.bot) < source.pickTier()) {
                Step pick = resolve(source.pickaxeNeeded(), 1, depth + 1, visiting);
                if (pick != null) return pick;
            }
            return new Mine(source, item, missing, verb(source));
        }

        // Craft right away if everything is in the bag.
        List<Recipes.CraftOption> options = Recipes.craftingFor(this.server, item);
        CraftChoice bestCraft = null;
        for (Recipes.CraftOption option : options) {
            CraftChoice choice = choose(option, missing);
            if (choice == null) continue;
            if (bestCraft == null || choice.missingScore < bestCraft.missingScore) bestCraft = choice;
        }
        if (bestCraft != null && bestCraft.missingScore == 0) return craftStep(bestCraft, item, depth, visiting);

        // Smelt it if that's how it's normally made (ingots, cooked food, glass...).
        List<Recipes.SmeltOption> smelts = Recipes.smeltingFor(this.server, item);
        if (!smelts.isEmpty()) {
            // Several furnace recipes can make the same thing (raw copper or copper ore both
            // give ingots). Use whichever input we have or can get most easily.
            Item input = null;
            int inputScore = 0;
            for (Recipes.SmeltOption smelt : smelts) {
                Item candidate = pickCandidate(smelt.input(), missing);
                int score = candidate == null ? 0 : candidateScore(candidate, missing);
                if (score > inputScore) {
                    inputScore = score;
                    input = candidate;
                }
            }
            if (input != null) {
                Step inputStep = resolve(input, missing, depth + 1, visiting);
                if (inputStep != null) return inputStep;
                if (fuelValue() < missing) {
                    Step fuel = resolveFuel(missing, depth, visiting);
                    if (fuel != null) return fuel;
                }
                if (!this.furnaceNearby && have(Items.FURNACE) == 0) {
                    Step furnace = resolve(Items.FURNACE, 1, depth + 1, visiting);
                    if (furnace != null) return furnace;
                }
                return new Smelt(input, missing, item, "Smelting " + name(item));
            }
        }

        if (bestCraft != null) {
            for (Map.Entry<Item, Integer> need : bestCraft.needs.entrySet()) {
                if (have(need.getKey()) < need.getValue()) {
                    Step sub = resolve(need.getKey(), need.getValue(), depth + 1, visiting);
                    if (sub != null) return sub;
                }
            }
            return craftStep(bestCraft, item, depth, visiting);
        }
        return new Fail("can't work out how to get " + name(item));
    }

    private Step craftStep(CraftChoice choice, Item item, int depth, Set<Item> visiting) {
        if (choice.option.needsTable() && !this.tableNearby && have(Items.CRAFTING_TABLE) == 0) {
            Step table = resolve(Items.CRAFTING_TABLE, 1, depth + 1, visiting);
            if (table != null) return table;
        }
        return new Craft(choice.option, choice.grid, choice.times, "Crafting " + article(item));
    }

    private @Nullable Step resolveFuel(int itemsToSmelt, int depth, Set<Item> visiting) {
        if (have(Items.COAL) > 0 || have(Items.CHARCOAL) > 0) return null;
        int logs = (int) Math.ceil(itemsToSmelt / 1.5);
        return new Mine(Sources.LOGS, Items.OAK_LOG, Math.max(1, logs - Inv.count(this.bot, s -> s.is(ItemTags.LOGS))), "Chopping wood");
    }

    /** How many items the fuel in the inventory can smelt. */
    public int fuelValue() {
        double total = 0;
        for (int i = 0; i < Inv.MAIN_SLOTS; i++) {
            ItemStack stack = this.bot.getInventory().getItem(i);
            total += fuelPerItem(stack) * stack.getCount();
        }
        return (int) total;
    }

    public static double fuelPerItem(ItemStack stack) {
        if (stack.isEmpty() || !stack.has(DataComponents.COOKING_FUEL)) return 0;
        if (stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) return 8;
        if (stack.is(Items.COAL_BLOCK)) return 80;
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS)) return 1.5;
        if (stack.is(Items.STICK)) return 0.5;
        // Don't burn tools or anything else valuable.
        return 0;
    }

    // --- ingredient choice ---------------------------------------------------

    private record CraftChoice(Recipes.CraftOption option, List<@Nullable Item> grid, Map<Item, Integer> needs, int times, int missingScore) {}

    private @Nullable CraftChoice choose(Recipes.CraftOption option, int wanted) {
        int perCraft = Math.max(1, option.result().getCount());
        int times = (wanted + perCraft - 1) / perCraft;
        List<@Nullable Item> grid = new ArrayList<>();
        Map<Item, Integer> needs = new LinkedHashMap<>();
        for (Optional<Ingredient> slot : option.grid()) {
            if (slot.isEmpty()) {
                grid.add(null);
                continue;
            }
            Item pick = pickCandidate(slot.get(), times);
            if (pick == null) return null;
            grid.add(pick);
            needs.merge(pick, times, Integer::sum);
        }
        int score = 0;
        for (Map.Entry<Item, Integer> need : needs.entrySet()) {
            int short_ = need.getValue() - have(need.getKey());
            if (short_ <= 0) continue;
            score += Sources.forItem(need.getKey()) != null ? 2 : (!Recipes.craftingFor(this.server, need.getKey()).isEmpty() ? 3 : 6);
        }
        // Never "craft" iron ingots out of iron blocks we don't have, etc.
        if (score > 0 && isStorageDecompress(option)) return null;
        return new CraftChoice(option, grid, needs, times, score);
    }

    private static boolean isStorageDecompress(Recipes.CraftOption option) {
        String result = BuiltInRegistries.ITEM.getKey(option.result().getItem()).getPath();
        for (Optional<Ingredient> slot : option.grid()) {
            if (slot.isEmpty()) continue;
            for (Holder<Item> h : slot.get().items().limit(4).toList()) {
                String path = BuiltInRegistries.ITEM.getKey(h.value()).getPath();
                if (path.endsWith("_block") || path.endsWith("_nugget")) return true;
            }
        }
        return result.endsWith("_nugget");
    }

    /** Pick which item to use for an ingredient that accepts several (e.g. any planks). */
    private @Nullable Item pickCandidate(Ingredient ingredient, int needed) {
        Item best = null;
        int bestScore = -1;
        for (Holder<Item> holder : ingredient.items().limit(64).toList()) {
            Item item = holder.value();
            int score = candidateScore(item, needed);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return bestScore > 0 ? best : null;
    }

    /** How easy an item is to come by: in the bag beats craftable beats mineable. 0 = no idea. */
    private int candidateScore(Item item, int needed) {
        int have = have(item);
        int score;
        if (have >= needed) score = 1000 + have;
        else if (have > 0) score = 500 + have;
        else if (craftableFromBag(item)) score = 400;
        else if (Sources.forItem(item) != null) score = 300;
        else if (!Recipes.craftingFor(this.server, item).isEmpty()) score = 100;
        else return 0;
        if (item == Items.OAK_PLANKS || item == Items.OAK_LOG || item == Items.COBBLESTONE) score += 5;
        return score;
    }

    /** Can this be made in one craft from what's in the inventory right now? */
    private boolean craftableFromBag(Item item) {
        for (Recipes.CraftOption option : Recipes.craftingFor(this.server, item)) {
            boolean ok = true;
            for (Optional<Ingredient> slot : option.grid()) {
                if (slot.isEmpty()) continue;
                if (Inv.count(this.bot, slot.get()) == 0) {
                    ok = false;
                    break;
                }
            }
            if (ok) return true;
        }
        return false;
    }

    // --- labels ------------------------------------------------------------

    private static String verb(Sources source) {
        if (source == Sources.LOGS) return "Chopping wood";
        return "Mining " + source.label();
    }

    public static String name(Item item) {
        return new ItemStack(item).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
    }

    private static String article(Item item) {
        String n = name(item);
        if (n.endsWith("s")) return n;
        return ("aeiou".indexOf(n.charAt(0)) >= 0 ? "an " : "a ") + n;
    }

    /** True if there's a crafting table close enough to bother walking to. */
    public static @Nullable BlockPos nearestTable(BotPlayer bot) {
        return Senses.findBlock(bot, s -> s.is(Blocks.CRAFTING_TABLE), 24, 8, false, p -> false);
    }
}
