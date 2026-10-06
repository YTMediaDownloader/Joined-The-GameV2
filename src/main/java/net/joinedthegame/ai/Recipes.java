package net.joinedthegame.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;

/**
 * Everything the bots know about crafting and smelting, read from the game's real recipe
 * list. Because nothing is hard-coded, datapack and modded recipes work too.
 */
public final class Recipes {
    private Recipes() {}

    /** A crafting recipe flattened into a grid. Empty slots are {@code Optional.empty()}. */
    public record CraftOption(RecipeHolder<CraftingRecipe> holder, int width, int height,
                              List<Optional<Ingredient>> grid, ItemStack result, boolean needsTable) {}

    public record SmeltOption(Ingredient input, ItemStack result) {}

    private static Map<Item, List<CraftOption>> crafting = Map.of();
    private static Map<Item, List<SmeltOption>> smelting = Map.of();
    private static RecipeManager cachedManager;
    private static int cachedSize = -1;

    public static void clear() {
        crafting = Map.of();
        smelting = Map.of();
        cachedManager = null;
        cachedSize = -1;
    }

    private static void ensure(MinecraftServer server) {
        RecipeManager manager = server.getRecipeManager();
        int size = manager.getRecipes().size();
        if (manager == cachedManager && size == cachedSize) return;
        cachedManager = manager;
        cachedSize = size;

        Map<Item, List<CraftOption>> craftMap = new HashMap<>();
        for (RecipeHolder<CraftingRecipe> holder : manager.recipeMap().byType(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            try {
                if (recipe instanceof ShapedRecipe shaped) {
                    ItemStack result = shaped.assemble(CraftingInput.EMPTY);
                    if (result.isEmpty()) continue;
                    int w = shaped.getWidth();
                    int h = shaped.getHeight();
                    craftMap.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                            .add(new CraftOption(holder, w, h, shaped.pattern.ingredients(), result, w > 2 || h > 2));
                } else if (recipe instanceof ShapelessRecipe shapeless) {
                    ItemStack result = shapeless.assemble(CraftingInput.EMPTY);
                    if (result.isEmpty()) continue;
                    List<Optional<Ingredient>> grid = new ArrayList<>();
                    for (Ingredient ing : shapeless.placementInfo().ingredients()) grid.add(Optional.of(ing));
                    if (grid.isEmpty() || grid.size() > 9) continue;
                    int w = Math.min(3, grid.size());
                    int h = (grid.size() + w - 1) / w;
                    craftMap.computeIfAbsent(result.getItem(), k -> new ArrayList<>())
                            .add(new CraftOption(holder, w, h, grid, result, grid.size() > 4));
                }
            } catch (RuntimeException ignored) {
                // Some modded recipes can't be assembled without real inputs; skip them.
            }
        }

        Map<Item, List<SmeltOption>> smeltMap = new HashMap<>();
        for (RecipeHolder<?> holder : manager.recipeMap().byType(RecipeType.SMELTING)) {
            if (!(holder.value() instanceof AbstractCookingRecipe cooking)) continue;
            Ingredient input = cooking.input();
            Optional<ItemStack> sample = input.items().findFirst().map(h -> new ItemStack(h));
            if (sample.isEmpty()) continue;
            try {
                ItemStack result = cooking.assemble(new SingleRecipeInput(sample.get()));
                if (!result.isEmpty()) smeltMap.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(new SmeltOption(input, result));
            } catch (RuntimeException ignored) {
            }
        }
        crafting = craftMap;
        smelting = smeltMap;
    }

    public static List<CraftOption> craftingFor(MinecraftServer server, Item item) {
        ensure(server);
        return crafting.getOrDefault(item, List.of());
    }

    public static List<SmeltOption> smeltingFor(MinecraftServer server, Item item) {
        ensure(server);
        return smelting.getOrDefault(item, List.of());
    }

    /** What a furnace would turn this stack into, or empty. */
    public static ItemStack smeltResult(MinecraftServer server, ItemStack input) {
        ensure(server);
        for (List<SmeltOption> options : smelting.values()) {
            for (SmeltOption option : options) {
                if (option.input().test(input)) return option.result();
            }
        }
        return ItemStack.EMPTY;
    }
}
