package net.joinedthegame.ai.skill;

import net.joinedthegame.ai.Inv;
import net.joinedthegame.bot.BotPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Working a brewing stand: bottles in the three bottom slots, the ingredient on top, blaze
 * powder for fuel (one does twenty brews), and twenty seconds a step. Like the furnace, the
 * stand's slots are filled the way the screen would fill them; the game does the brewing.
 */
public final class Brewer {
    private Brewer() {}

    public static final int INGREDIENT = 3;
    public static final int FUEL = 4;

    public static @Nullable BrewingStandBlockEntity at(BotPlayer bot, @Nullable BlockPos pos) {
        if (pos == null || !bot.level().isLoaded(pos) || !bot.level().getBlockState(pos).is(Blocks.BREWING_STAND)) return null;
        return bot.level().getBlockEntity(pos) instanceof BrewingStandBlockEntity be ? be : null;
    }

    public static boolean isWater(ItemStack s) {
        if (!s.is(Items.POTION)) return false;
        PotionContents c = s.get(DataComponents.POTION_CONTENTS);
        return c != null && c.is(Potions.WATER);
    }

    /** Would this ingredient do anything to what's in the stand? (The game's own brewing rules.) */
    public static boolean works(BotPlayer bot, BrewingStandBlockEntity be, Item ingredient) {
        ItemStack in = new ItemStack(ingredient);
        var level = (net.minecraft.server.level.ServerLevel) bot.level();
        for (int i = 0; i < 3; i++) {
            ItemStack bottle = be.getItem(i);
            if (bottle.isEmpty()) continue;
            // 26.3: brewing is recipes (bottle + ingredient), like crafting.
            if (level.recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.BREWING,
                    new net.minecraft.world.item.crafting.BrewingInput(bottle, in), level).isPresent()) return true;
        }
        return false;
    }

    /** Ticks left on the brew under way (0: nothing brewing). */
    public static int brewTime(BrewingStandBlockEntity be) {
        return ((net.joinedthegame.mixin.BrewingStandAccessor) be).joinedthegame$brewTime();
    }

    /** Brews left in the fuel already burned into the stand (the fuel slot can be empty). */
    public static int fuelInside(BrewingStandBlockEntity be) {
        return ((net.joinedthegame.mixin.BrewingStandAccessor) be).joinedthegame$fuel();
    }

    /** Bottles in the stand. */
    public static int bottles(BrewingStandBlockEntity be) {
        int n = 0;
        for (int i = 0; i < 3; i++) if (!be.getItem(i).isEmpty()) n++;
        return n;
    }

    /** Up to three water bottles from the bag into the empty bottle slots. */
    public static int loadWater(BotPlayer bot, BrewingStandBlockEntity be) {
        int loaded = 0;
        for (int slot = 0; slot < 3; slot++) {
            if (!be.getItem(slot).isEmpty()) continue;
            int from = Inv.find(bot, Brewer::isWater);
            if (from < 0) break;
            be.setItem(slot, bot.getInventory().getItem(from).split(1));
            loaded++;
        }
        bot.getInventory().setChanged();
        be.setChanged();
        return loaded;
    }

    /**
     * Fuel for the next brew: what's burned into the stand already (a powder is twenty brews),
     * or powder waiting in the slot, or one from the bag. False only if all three are empty.
     */
    public static boolean fuel(BotPlayer bot, BrewingStandBlockEntity be) {
        if (fuelInside(be) > 0 || !be.getItem(FUEL).isEmpty()) return true;
        int from = Inv.find(bot, s -> s.is(Items.BLAZE_POWDER));
        if (from < 0) return false;
        be.setItem(FUEL, bot.getInventory().getItem(from).split(1));
        bot.getInventory().setChanged();
        be.setChanged();
        return true;
    }

    /** One of {@code ingredient} on top: brewing starts. */
    public static boolean addIngredient(BotPlayer bot, BrewingStandBlockEntity be, Item ingredient) {
        if (!be.getItem(INGREDIENT).isEmpty()) return false;
        int from = Inv.find(bot, s -> s.is(ingredient));
        if (from < 0) return false;
        be.setItem(INGREDIENT, bot.getInventory().getItem(from).split(1));
        bot.getInventory().setChanged();
        be.setChanged();
        return true;
    }

    /** What the stand's doing. */
    public enum State { IDLE, BREWING, STUCK }

    /**
     * Really brewing (the clock's running), done with that step (nothing on top), or stuck:
     * something on top that isn't brewing in (out of fuel, or it doesn't go with these bottles).
     * Stuck isn't waited on for ever.
     */
    public static State state(BrewingStandBlockEntity be) {
        if (brewTime(be) > 0) return State.BREWING;
        return be.getItem(INGREDIENT).isEmpty() ? State.IDLE : State.STUCK;
    }

    /** Anything on top, brewing or not. */
    public static boolean brewing(BrewingStandBlockEntity be) {
        return state(be) != State.IDLE;
    }

    /** Everything finished out of the bottle slots (and anything left on top) into the bag. */
    public static int takeAll(BotPlayer bot, BrewingStandBlockEntity be) {
        int taken = 0;
        for (int slot = 0; slot <= INGREDIENT; slot++) {
            ItemStack s = be.getItem(slot);
            if (s.isEmpty()) continue;
            ItemStack copy = s.copy();
            if (!bot.getInventory().add(copy)) net.joinedthegame.ai.task.CraftTask.give(bot, copy);
            be.setItem(slot, ItemStack.EMPTY);
            taken++;
        }
        bot.getInventory().setChanged();
        be.setChanged();
        return taken;
    }

    /** What's in the first bottle slot (to say what was made). */
    public static ItemStack first(BrewingStandBlockEntity be) {
        for (int i = 0; i < 3; i++) if (!be.getItem(i).isEmpty()) return be.getItem(i);
        return ItemStack.EMPTY;
    }

    /**
     * Fill one glass bottle from the water at {@code water} (in reach): look right at it and use
     * the bottle, the way a player does. True if one got filled.
     */
    public static boolean fillBottle(BotPlayer bot, BlockPos water) {
        if (!Inv.hold(bot, s -> s.is(Items.GLASS_BOTTLE))) return false;
        int before = Inv.count(bot, Brewer::isWater);
        Placer.lookExactly(bot, Vec3.atCenterOf(water).add(0, 0.35, 0));
        bot.gameMode.useItem(bot, bot.level(), bot.getMainHandItem(), InteractionHand.MAIN_HAND);
        return Inv.count(bot, Brewer::isWater) > before;
    }
}
